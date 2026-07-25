package org.castello.game.domain;

import org.castello.game.Action;
import org.castello.game.CenterBoard;
import org.castello.game.Game;
import org.castello.game.GameStatus;
import org.castello.game.Infra;
import org.castello.game.Location;
import org.castello.game.Phase;
import org.castello.game.RaidEffects;
import org.castello.game.RoundFight;
import org.castello.game.StatMod;
import org.castello.game.WeatherStatus;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.game.support.RaidFlow;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Résolutions des cartes action VAMPIRE (jets et choix de cible) :
 * Cataclysme, Clones d'ombre, Voile de brume, Image miroir,
 * Marque ténébreuse, Affaiblissement occulte, Passage secret.
 */
@Service
public class VampireActionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VampireActionService.class);

    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final WeatherService weather;
    private final CorruptionService corruption;
    private final LocationEffectService locationEffects;
    private final ActionCardService actionCards;
    private final HarvestService harvest;
    private final LiveEvents live;
    private final RaidFlow flow;

    public VampireActionService(GameStore store, Dice dice, DeckService decks,
            WeatherService weather, CorruptionService corruption,
            LocationEffectService locationEffects, ActionCardService actionCards,
            HarvestService harvest, LiveEvents live, @Lazy RaidFlow flow) {
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.weather = weather;
        this.corruption = corruption;
        this.locationEffects = locationEffects;
        this.actionCards = actionCards;
        this.harvest = harvest;
        this.live = live;
        this.flow = flow;
    }

    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        live.message(g, msg);
    }

    @Transactional
    public void resolveCataclysme(String gameId,
            String playerId,
            WeatherStatus first,
            WeatherStatus second) {

        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        Player p = g.findPlayer(playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cataclysme ne peut être résolu qu'en PHASE2.");
        }

        Game.Action ca = g.getCurrentAction();
        if (ca == null || !"CATACLYSME".equals(ca.getMode()) || !playerId.equals(ca.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Cataclysme en cours pour ce joueur.");
        }

        if (first == null || second == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choix météo invalide");
        }
        if (first == second) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "les deux statuts météo doivent être différents.");
        }

        // évite d'empiler plusieurs Cataclysmes (une météo secondaire = déjà un Cataclysme)
        if (g.getSecondaryWeatherStatus() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cataclysme impossible : des statuts météo supplémentaires sont déjà actifs.");
        }

        WeatherStatus oldBase = g.getWeatherStatus();

        // WIND était-il DÉJÀ actif via l'ancienne base ? (ses réparations ont alors
        // déjà eu lieu en PHASE0 ; on ne les rejoue pas.)
        boolean windAlreadyApplied = (oldBase == WeatherStatus.WIND);

        // Les 2 météos choisies REMPLACENT la météo en cours : 2 statuts MAX
        // (base + secondaire). Le 1er choix devient la nouvelle base, le 2e la
        // secondaire. Comme AUCUN statut secondaire n'existe hors Cataclysme,
        // « secondaire != null » signale désormais un Cataclysme actif (utilisé par
        // l'immunité et les gardes météo).
        g.setWeatherStatus(first);
        g.setWeatherStatusNameFr(weather.weatherNameFr(first));
        g.setWeatherDescriptionFr(weather.weatherDescFr(first));

        g.setSecondaryWeatherStatus(second);
        g.setSecondaryWeatherStatusNameFr(weather.weatherNameFr(second));
        g.setSecondaryWeatherDescriptionFr(weather.weatherDescFr(second));

        // résumé lisible = les 2 météos du Cataclysme (l'ancienne est remplacée)
        String summaryName = weather.weatherNameFr(first)
                + " + " + weather.weatherNameFr(second);

        String hist = g.nameOf(playerId)
                + " déclenche un Cataclysme: "
                + weather.weatherNameFr(first) + " + " + weather.weatherNameFr(second)
                + " (remplace la météo en cours).";
        g.addHistory(hist);

        // mods météo (base = 1er choix, secondaire = 2e choix) + immunité vampire
        weather.rebuildWeatherMods(g);

        // effet WIND si une des 2 météos choisies est WIND (et que WIND n'était pas
        // déjà actif via l'ancienne base). drainDomain=false : le vampire lanceur est
        // immunisé — seul le village des chasseurs subit le cyclone, pas son domaine.
        if (!windAlreadyApplied && (first == WeatherStatus.WIND || second == WeatherStatus.WIND)) {
            weather.applyWindRepairs(g, false);
        }

        ca.setResolvedAtMillis(System.currentTimeMillis());
        ca.setTargetId(first.name() + "," + second.name());

        // Libère currentAction pour permettre de jouer une autre carte
        g.setCurrentAction(null);

        var msgs = new ArrayList<String>();
        msgs.add("Météo — " + summaryName);
        msgs.add("Le vampire déchire le ciel.");
        g.setMessages(msgs);

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, "CATACLYSME", playerId, null);
            live.raidModsUpdated(gAfter);
            live.phaseChanged(gAfter);
        });
    }

    @Transactional
    public Game rollShadowClones(String gameId, String playerId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE2");
        }

        Player p = g.findPlayer(playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"CLONES_OMBRE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Clones des ombres en cours pour ce joueur.");
        }

        if (a.getRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dé déjà lancé.");
        }

        int roll = dice.roll(4); // D4
        a.setRoll(roll);

        a.getBreakdownLines().add(
                g.nameOf(playerId) + " invoque " + roll + " clone"
                        + (roll > 1 ? "s" : "") + " des ombres.");

        g.addHistory("Clones des ombres — " + g.nameOf(playerId)
                + " contrôle " + roll + " clone" + (roll > 1 ? "s" : "") + ".");

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            // juste pour forcer un GET propre + affichage du résultat
            live.actionResolved(gAfter, "CLONES_OMBRE", playerId, null);
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveShadowClones(String gameId, String playerId, java.util.List<String> locations,
            java.util.List<Boolean> biteEnabled) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE2");
        }

        Player p = g.findPlayer(playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"CLONES_OMBRE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Clones des ombres en cours pour ce joueur.");
        }

        if (a.getResolvedAtMillis() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Les clones des ombres ont déjà été résolus pour ce raid.");
        }

        if (a.getRoll() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "le dé n'a pas encore été lancé.");
        }

        int expected = a.getRoll();
        if (locations == null || locations.size() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tu dois choisir exactement " + expected + " lieux.");
        }

        // Validation biteEnabled
        if (biteEnabled != null && biteEnabled.size() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La liste des capacités doit correspondre au nombre de clones.");
        }

        // Calcul coût et paiement
        int cost = 0;
        if (biteEnabled != null) {
            for (Boolean b : biteEnabled) {
                if (Boolean.TRUE.equals(b)) {
                    cost += 10;
                }
            }
        }

        if (cost > 0) {
            if (p.getSouls() < cost) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pas assez d'âmes déchues pour les dons de capacités");
            }
            p.setSouls(p.getSouls() - cost);
            g.addHistory(g.nameOf(playerId) + " paie " + cost + " âmes pour activer la morsure sur ses clones.");
        }

        for (String loc : locations) {
            if (g.getGarlicBlockedLocations() != null
                    && g.getGarlicBlockedLocations().contains(loc)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Les clones ne peuvent pas attaquer un lieu protégé par une fumigation d'ail.");
            }
        }

        // CUMULER avec les clones déjà présents sur ce raid
        java.util.List<String> all = g.getClonesLocations();
        if (all == null) {
            all = new java.util.ArrayList<>();
        }
        all.addAll(locations); // on ajoute les nouveaux clones
        g.setClonesLocations(all);

        // CUMULER les flags de capacité morsure
        java.util.List<Boolean> allCaps = g.getClonesBiteCapabilities();
        if (allCaps == null) {
            allCaps = new java.util.ArrayList<>();
        }
        if (biteEnabled != null) {
            allCaps.addAll(biteEnabled);
        } else {
            // par défaut false
            for (int i = 0; i < locations.size(); i++)
                allCaps.add(false);
        }
        g.setClonesBiteCapabilities(allCaps);

        a.setResolvedAtMillis(System.currentTimeMillis());
        a.setTargetId(String.join(",", locations));

        String niceList = locations.stream()
                .map(Location::labelFrOf)
                .reduce((aa, bb) -> aa + ", " + bb)
                .orElse("");

        String hist = "Clones des ombres — " + g.nameOf(playerId)
                + " envoie ses clones attaquer : " + niceList + ".";
        g.addHistory(hist);

        g.setCurrentAction(null);

        flow.refreshPrephaseRevealMessages(g);

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            // Notifie le front que l’action est terminée
            live.actionResolved(gAfter, "CLONES_OMBRE", playerId, null);
            // Et heartbeat classique
            live.phaseChanged(gAfter);
        });

        return g;
    }

    /**
     * Invocation de monstre par carte-action (Portail) : place un monstre
     * (Revenant ou Chauve-souris selon la carte jouée) sur le lieu choisi,
     * en PHASE2. Aucun coût en âmes — la carte est le coût. Le monstre est
     * un gardien PERSISTANT (comme ceux du Laboratoire) : il défend le lieu
     * raid après raid jusqu'à être tué en combat.
     */
    @Transactional
    public Game resolvePortalInvocation(String gameId, String playerId, String location) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE2");
        }

        Player p = g.findPlayer(playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        String mode = (a != null) ? a.getMode() : null;
        if (a == null || mode == null || !mode.startsWith("PORTAL_INVOCATION")
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune invocation de monstre en cours pour ce joueur.");
        }
        if (a.getResolvedAtMillis() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "L'invocation a déjà été résolue pour ce raid.");
        }
        if (location == null || location.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "location required");
        }
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(location)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible d'invoquer un monstre sur un lieu protégé par une fumigation d'ail.");
        }

        // Type + stats (miroir de l'Expérimentation du Labo, mais SANS coût d'âmes)
        Game.MonsterType type = mode.endsWith("BAT")
                ? Game.MonsterType.BAT
                : Game.MonsterType.REVENANT;
        int hp;
        String atk;
        String def;
        if (type == Game.MonsterType.BAT) {
            hp = 5;
            atk = "D4";
            def = "D6";
        } else { // REVENANT
            hp = 5;
            atk = "D6";
            def = "D4";
        }

        if (g.getMonsters() == null) {
            g.setMonsters(new java.util.ArrayList<>());
        }
        Game.Monster m = new Game.Monster();
        m.id = java.util.UUID.randomUUID().toString();
        m.type = type;
        m.location = location;
        m.hp = hp;
        m.attackDice = atk;
        m.defenseDice = def;
        g.getMonsters().add(m);

        a.setLocation(location);
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        String monsterFr = (type == Game.MonsterType.BAT) ? "une chauve-souris" : "un revenant";
        g.addHistory("Portail — " + g.nameOf(playerId) + " invoque " + monsterFr
                + " pour défendre " + Location.labelFrOf(location) + ".");

        store.save(g);

        Game gAfter = g;
        final String fMode = mode;
        final String fLoc = location;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, fMode, playerId, fLoc);
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveVoileDeBrume(String gameId, String userId, String location) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");
        }

        Player p = g.findPlayer(userId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"VOILE_DE_BRUME".equals(a.getMode()) || !userId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Voile de brume en cours pour ce joueur.");
        }

        if (a.getResolvedAtMillis() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le voile de brume a déjà été résolu.");
        }

        if (location == null || location.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lieu invalide");
        }

        a.setLocation(location);
        a.setResolvedAtMillis(System.currentTimeMillis());

        // Stocker le lieu affecté
        g.setFogAffectedLocation(location);

        // Appliquer -1 DEF via stat mod engine pour tous les chasseurs sur ce lieu
        var huntersOnLoc = g.getPlayers().stream()
                .filter(pl -> "HUNTER".equals(pl.getRole()))
                .filter(pl -> location.equals(g.locationOf(pl.getId())))
                .toList();

        for (var hunter : huntersOnLoc) {
            g.addRaidMod(hunter.getId(), "DEFENSE", -1, "ACTION:VOILE_DE_BRUME");
        }

        String locLabel = Location.labelFrOf(location);
        String line = "Voile de brume — " + g.nameOf(userId)
                + " enveloppe " + locLabel + " d'un brouillard mystique (-1 DEF, récolte /2).";
        g.addHistory(line);
        a.getBreakdownLines().add(line);

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, "VOILE_DE_BRUME", userId, null);
            live.phaseChanged(gAfter);
        });

        return g;
    }

    /**
     * Annule toutes les préparations d'actions PREPHASE3 des chasseurs
     * pour le raid courant :
     */
    @Transactional
    public Game resolveImageMiroirSetup(String gameId, String playerId, String loc) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PHASE2 && g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE2 or PREPHASE3");
        }

        Player p = g.findPlayer(playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"IMAGE_MIROIR_SETUP".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Image miroir en cours pour ce joueur.");
        }

        if (loc == null || loc.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "loc required");
        }

        if (p.getHand() == null || !p.getHand().contains(loc)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lieu non présent dans la main");
        }

        // Fumigation : on ne peut pas projeter l'image miroir sur un lieu fumigé
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(loc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce lieu est protégé par une fumigation d'ail.");
        }

        // Cumul des lieux alternatifs
        g.setMirrorOwnerId(playerId);
        java.util.List<String> alts = g.getMirrorAltLocations();
        if (alts == null) {
            alts = new java.util.ArrayList<>();
        }
        if (!alts.contains(loc)) {
            alts.add(loc);
        }
        g.setMirrorAltLocations(alts);

        // Tant qu’aucun choix final n’a été fait
        g.setMirrorChosenLocation(null);

        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // on prépare la résolution finale pour la fin de préphase
        g.setPendingVampireEscape("IMAGE_MIROIR_RESOLVE");

        Game gAfter = g;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, "IMAGE_MIROIR_SETUP", playerId, loc);
            live.phaseChanged(gAfter);
            // On ne force PAS l'avance de phase ici, c'est le timer/ready qui le fera
        });

        // Relancer la logique de timer pour la Préphase maintenant que le setup est
        // fait
        flow.setupUnstableAndPrephaseTimeout(g);

        store.save(g);

        return g;
    }

    @Transactional
    public Game resolveImageMiroirChoice(String gameId, String playerId, String loc) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Image miroir se résout en PREPHASE3.");
        }

        Player p = g.findPlayer(playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"IMAGE_MIROIR_RESOLVE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "aucune résolution Image miroir en cours.");
        }

        String ownerId = g.getMirrorOwnerId();
        java.util.List<String> alts = g.getMirrorAltLocations();

        if (ownerId == null || alts == null || alts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "aucune Image miroir active.");
        }

        // Lieu principal actuel sur le centre
        String primary = g.getCenter().stream()
                .filter(cb -> cb.getPlayerId().equals(ownerId))
                .map(CenterBoard::getCard)
                .findFirst()
                .orElse(null);

        boolean isPrimary = primary != null && primary.equals(loc);
        boolean isAlt = alts.contains(loc);

        if (!isPrimary && !isAlt) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lieu choisi invalide pour Image miroir.");
        }

        // Fumigation : impossible de se matérialiser sur un lieu fumigé
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(loc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce lieu est protégé par une fumigation d'ail.");
        }

        g.setMirrorChosenLocation(loc);

        // Si le vampire change de lieu, on met à jour la carte du centre + on swappe
        // les cartes lieux
        if (primary != null && !loc.equals(primary)) {
            for (CenterBoard cb : g.getCenter()) {
                if (cb.getPlayerId().equals(ownerId)) {

                    String oldCard = cb.getCard(); // lieu joué au début du raid
                    String newLoc = loc; // primary ou alt choisi via Image miroir

                    if (!java.util.Objects.equals(oldCard, newLoc)) {
                        cb.setCard(newLoc);

                        var hand = p.getHand();
                        if (hand == null) {
                            hand = new java.util.ArrayList<>();
                            p.setHand(hand);
                        }

                        // newLoc vient de la main (vérifié côté setup), on la remet à la place de
                        // l'ancien
                        hand.remove(newLoc);
                        hand.add(oldCard);
                    }

                    break;
                }
            }
        }

        // Optionnel : nettoyer les alts une fois le choix fait
        if (g.getMirrorAltLocations() != null) {
            g.getMirrorAltLocations().clear();
        }

        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        String hist = "Image miroir — " + g.nameOf(playerId)
                + " choisit de se manifester sur " + Location.labelFrOf(loc) + ".";
        g.addHistory(hist);

        flow.refreshPrephaseRevealMessages(g);
        // NE PAS relancer setupUnstableAndPrephaseTimeout ici

        locationEffects.maybeStartLocationEffects(g, gameId);

        store.afterCommit(() -> {
            try {
                // Notifier le front que l'action est résolue et la phase potentiellement
                // changée
                Game fresh = store.read(gameId);
                live.actionResolved(fresh, "IMAGE_MIROIR_RESOLVE", playerId, loc);
                live.phaseChanged(fresh);
            } catch (Exception e) {
                log.error("Error in ImageMiroir afterCommit", e);
            }
        });

        return g;
    }

    @Transactional
    public Game resolveDarkMark(String gameId, String playerId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Marque ténébreuse se résout en PREPHASE3.");
        }

        Player vamp = g.findPlayer(playerId);
        if (vamp == null || !"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"MARQUE_TENEBREUSE".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Marque ténébreuse en cours pour ce joueur.");
        }

        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player target = g.findPlayer(targetId);
        if (target == null || !"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être un chasseur.");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        if (g.getDarkMarkedHunters() == null) {
            g.setDarkMarkedHunters(new java.util.HashSet<>());
        }

        // Marque permanente
        g.getDarkMarkedHunters().add(target.getId());

        // Puce DSP pour ce raid
        g.addRaidMod(target.getId(), "MARKED", 0, "CORRUPTION:MARK:DSP");

        String hist = "Marque ténébreuse — "
                + g.nameOf(vamp.getId())
                + " marque " + g.nameOf(target.getId())
                + ": il augmentera sa corruption de 1 à chaque raid où il croise le vampire, "
                + "tant qu'il n'est pas purifié.";
        g.addHistory(hist);

        a.setTargetId(target.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // On relance la mécanique de préphase (30s, etc.)
        flow.setupUnstableAndPrephaseTimeout(g);

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, "MARQUE_TENEBREUSE", playerId, target.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }


    @Transactional
    public Game resolveOccultWeakening(String gameId, String playerId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Affaiblissement occulte se résout en PREPHASE3.");
        }

        Player vamp = g.findPlayer(playerId);
        if (vamp == null || !"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"AFFAIBLISSEMENT_OCCULTE".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Affaiblissement occulte en cours pour ce joueur.");
        }

        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player target = g.findPlayer(targetId);
        if (target == null || !"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être un chasseur.");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        // --- Appliquer le malus d'attaque -2 pour CE raid ---
        g.addRaidMod(target.getId(), "ATTACK", -2, "ACTION:WEAKENING:ENG");

        String hist = "Affaiblissement occulte — "
                + g.nameOf(vamp.getId())
                + " sape la force de " + g.nameOf(target.getId())
                + " : -2 à son jet d'attaque ce raid.";
        g.addHistory(hist);

        a.setTargetId(target.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // Réévalue la préphase (relance 30s ou avance vers PHASE3 si plus rien à faire)
        flow.setupUnstableAndPrephaseTimeout(g);

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, "AFFAIBLISSEMENT_OCCULTE", playerId, target.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveSecretPassage(String gameId, String playerId, String destination) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Passage secret se résout en PREPHASE3.");
        }

        Player vamp = g.findPlayer(playerId);
        if (vamp == null || !"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"PASSAGE_SECRET".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Passage secret en cours pour ce joueur.");
        }

        String currentLoc = g.locationOf(playerId);
        if (!"manor".equals(currentLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Passage secret n'est utilisable que depuis le Manoir.");
        }

        if (destination == null || destination.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "destination required");
        }
        final String targetLoc = destination.trim().toLowerCase();

        // Construire la liste des lieux autorisés
        java.util.Set<String> allowed = new java.util.HashSet<>();
        // Lieux de base
        allowed.add("forest");
        allowed.add("quarry");
        allowed.add("lake");
        allowed.add("manor");

        // Infras construites -> lieux associés
        if (g.getBuiltInfras() != null) {
            for (Infra infra : g.getBuiltInfras()) {
                switch (infra) {
                    case SAWMILL -> allowed.add("sawmill");
                    case MINE -> allowed.add("mine");
                    case LIBRARY -> allowed.add("library");
                    case LABORATORY -> allowed.add("laboratory");
                    case BALLROOM -> allowed.add("ballroom");
                    case ALTAR -> allowed.add("altar");
                    case FORGE -> allowed.add("forge");
                }
            }
        }

        if (!allowed.contains(targetLoc)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "destination invalide pour Passage secret.");
        }
        if (targetLoc.equals(currentLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Passage secret doit mener à un autre lieu que le Manoir.");
        }

        // Fumigation : impossible d'utiliser Passage secret vers un lieu fumigé
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(targetLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce lieu est protégé par une fumigation d'ail.");
        }

        // Mettre à jour le centre : le vampire change de lieu
        CenterBoard cb = g.getCenter().stream()
                .filter(c -> playerId.equals(c.getPlayerId()))
                .findFirst()
                .orElse(null);

        if (cb == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible de trouver la carte de lieu du vampire.");
        }

        // même logique que pour assignTarget / assignHarvest : on swappe les cartes
        // lieu
        String oldCard = cb.getCard();
        String newLoc = targetLoc;

        if (!java.util.Objects.equals(oldCard, newLoc)) {
            cb.setCard(newLoc); // le pion passe sur newLoc
            vamp.getHand().remove(newLoc); // on "dépense" la carte de destination
            vamp.getHand().add(oldCard); // on récupère l’ancienne carte de lieu dans la main
        }

        // Historique
        String msg = "Passage secret — "
                + g.nameOf(playerId)
                + " quitte le Manoir et rejoint " + Location.labelFrOf(targetLoc) + ".";
        g.addHistory(msg);

        // On clôt l'action
        a.setLocation(targetLoc);
        a.setTargetId(null);
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        flow.refreshPrephaseRevealMessages(g);

        // On relance la mécanique de préphase (combats à venir potentiellement
        // modifiés)
        // NE PAS relancer setupUnstableAndPrephaseTimeout ici (on veut finir la phase)

        locationEffects.maybeStartLocationEffects(g, gameId);

        store.afterCommit(() -> {
            try {
                // Notifier le front
                Game fresh = store.read(gameId);
                live.actionResolved(fresh, "PASSAGE_SECRET", playerId, targetLoc);
                live.phaseChanged(fresh);
            } catch (Exception e) {
                log.error("Error in PassageSecret afterCommit", e);
            }
        });

        return g;
    }

    // Corruption
    /**
     * Reconstruit entièrement les mods de corruption dans raidMods à chaque début
     * de raid (PHASE0),
     * en purgeant d’abord les anciennes entrées "CORRUPTION:*".
     * Règles:
     * - L1: applique -1 ATK / -1 DEF (effet moteur) + 1 chip "affichage"
     * - L2: aucune stat modifiée, 1 chip "instable"
     * - L3: rôle=SERVANT géré lors de la morsure, aucune stat modifiée, 1 chip
     * "serviteur"
     */
}
