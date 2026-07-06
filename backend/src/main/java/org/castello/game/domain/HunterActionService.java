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
 * Résolutions des cartes action CHASSEUR (jets et choix de cible) :
 * Filet, Fosse, Incendiaire, Provocation, Embuscade, Épieu béni,
 * Caisse abandonnée, Eau bénite.
 */
@Service
public class HunterActionService {

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

    public HunterActionService(GameStore store, Dice dice, DeckService decks,
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

    public Game chooseNetTarget(String gameId, String hunterId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Filet se cible en PHASE3, juste avant les duels.");
        }

        if (g.getNetHunters() == null || !g.getNetHunters().contains(hunterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Filet préparé pour ce joueur.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "aucune action Filet en cours (currentAction null)");
        }
        if (!"NET".equals(a.getMode())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "action courante n’est pas un Filet (mode=" + a.getMode() + ")");
        }
        if (!hunterId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Filet en cours appartient à " + a.getOwnerId() + ", pas à " + hunterId);
        }

        String loc = a.getLocation();
        if (loc == null) {
            loc = g.locationOf(hunterId);
            if (loc == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "le chasseur n’est sur aucun lieu.");
            }
        }

        // Validation cible : vampire/serviteur OU monstre sur ce lieu
        boolean ok = false;

        Player targetPlayer = g.findPlayer(targetId);
        if (targetPlayer != null) {
            String tLoc = g.locationOf(targetId);
            if (loc.equals(tLoc)
                    && ("VAMPIRE".equals(targetPlayer.getRole()) || "SERVANT".equals(targetPlayer.getRole()))) {
                ok = true;
            }
        }

        if (!ok) {
            Game.Monster targetMonster = g.findMonster(targetId);
            if (targetMonster != null && loc.equals(targetMonster.location)) {
                ok = true;
            }
        }

        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour Filet.");
        }

        // On enregistre simplement la cible, sans lancer le dé
        a.setTargetId(targetId);
        a.setRoll(null);
        a.setBreakdownLines(java.util.List.of());
        a.setResolvedAtMillis(null);

        store.save(g);

        final String fLoc = loc;
        final String fTarget = targetId;
        final String fOwner = hunterId;

        store.afterCommit(() -> {
            // Notifie tout le monde que l’action (Filet) a une cible
            live.actionStarted(g, "NET", fOwner, fLoc, fTarget);
        });

        return g;
    }

    @Transactional
    public Game resolveNet(String gameId, String hunterId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Filet se résout en PHASE3, juste avant les duels.");
        }

        if (g.getNetHunters() == null || !g.getNetHunters().contains(hunterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Filet préparé pour ce joueur.");
        }

        Player hunter = g.findPlayer(hunterId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad hunter");
        }

        String loc = g.locationOf(hunterId);
        if (loc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "le chasseur n’est sur aucun lieu.");
        }

        // Validation cible : joueur vampire/serviteur OU monstre sur ce lieu
        boolean ok = false;
        Player targetPlayer = g.findPlayer(targetId);
        Game.Monster targetMonster = g.findMonster(targetId);

        if (targetPlayer != null) {
            String tLoc = g.locationOf(targetId);
            if (loc.equals(tLoc)
                    && ("VAMPIRE".equals(targetPlayer.getRole()) || "SERVANT".equals(targetPlayer.getRole()))) {
                ok = true;
            }
        }
        if (!ok) {
            if (targetMonster != null && loc.equals(targetMonster.location)) {
                ok = true;
            }
        }

        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour Filet.");
        }

        Game.Action a = g.getCurrentAction();
        if (a != null && "NET".equals(a.getMode()) && hunterId.equals(a.getOwnerId())) {
            // Si une cible avait déjà été posée via chooseNetTarget, on vérifie la
            // cohérence
            if (a.getTargetId() != null && !a.getTargetId().equals(targetId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "cible différente de celle choisie pour Filet.");
            }
        }

        int roll = dice.roll(20);

        String msg = g.nameOf(hunterId) + " jette un dé pour son Filet contre "
                + g.entityName(targetId) + " (1d20 = " + roll + "). ";

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet de Filet : 1d20 = " + roll);

        if (roll > 12) {
            if (g.getRaidMods() == null)
                g.setRaidMods(new java.util.HashMap<>());

            if (targetMonster != null) {
                // Monstre : malus engine (ENG)
                g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("DEFENSE", -1, "ACTION:NET:ENG"));
                g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("ATTACK", -1, "ACTION:NET:ENG"));
                msg += "Le Filet se referme : l’attaque et la défense de " + g.entityName(targetId)
                        + " sont réduites.";
                breakdown.add("Succès : ATK -1, DEF -1");
            } else {
                // Joueur : malus de défense standard
                g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("DEFENSE", -1, "ACTION:NET"));
                msg += "Le Filet se referme : la défense de " + g.entityName(targetId) + " est réduite de 1.";
                breakdown.add("Succès : DEF -1");
            }
        } else {
            msg += "Le Filet échoue à piéger sa cible.";
            breakdown.add("Échec : aucun malus");
        }

        g.addHistory(msg);

        // Décrémenter le compteur de cartes NET pour ce chasseur
        if (g.getNetCardsRemaining() == null) {
            g.setNetCardsRemaining(new java.util.HashMap<>());
        }
        int cardsRemaining = g.getNetCardsRemaining().getOrDefault(hunterId, 1) - 1;
        g.getNetCardsRemaining().put(hunterId, cardsRemaining);

        // Retirer le chasseur uniquement s'il n'a plus de cartes NET
        if (cardsRemaining <= 0) {
            g.getNetHunters().remove(hunterId);
        }

        // Remplir currentAction pour le front
        Game.Action current = new Game.Action();
        current.setMode("NET");
        current.setOwnerId(hunterId);
        current.setLocation(loc);
        current.setTargetId(targetId);
        current.setRoll(roll);
        current.setBreakdownLines(breakdown);
        current.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(current);

        store.save(g);

        final String fMsg = msg;
        final int fRoll = roll;
        final String fHunterId = hunterId;
        final String fTargetId = targetId;

        store.afterCommit(() -> {
            pushLive(g, fMsg);
            live.raidModsUpdated(g);
            // event principal pour le front
            live.actionRolled(g, "NET", fHunterId, fTargetId, fRoll);
        });

        return g;
    }

    @Transactional
    public Game resolvePit(String gameId, String victimId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fosse se résout en PHASE3, juste avant les duels.");
        }

        // joueur qui clique (pas forcément la future cible, si c'est un monstre)
        Player clicker = g.findPlayer(victimId);
        if (clicker == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"VAMPIRE".equals(clicker.getRole()) && !"SERVANT".equals(clicker.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "seuls vampire/serviteurs sont concernés par la Fosse.");
        }

        if (g.getPitHunters() == null || g.getPitHunters().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Fosse préparée.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"PIT".equals(a.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Fosse en cours pour ce joueur.");
        }

        String hunterId = a.getOwnerId();
        String loc = a.getLocation();
        if (loc == null) {
            loc = g.locationOf(hunterId);
            if (loc == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "aucune Fosse active sur ce lieu.");
            }
        }

        // le clickeur doit être sur le même lieu que la Fosse
        String clickerLoc = g.locationOf(clicker.getId());
        if (!loc.equals(clickerLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "tu n’es pas sur le lieu de la Fosse.");
        }

        var targetsMap = g.getPitTargetsByHunter();
        var indexMap = g.getPitIndexByHunter();
        if (targetsMap == null || indexMap == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fosse mal initialisée.");
        }

        var victims = targetsMap.get(hunterId);
        if (victims == null || victims.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune cible pour cette Fosse.");
        }

        int idx = indexMap.getOrDefault(hunterId, 0);
        if (idx >= victims.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "plus aucune cible pour cette Fosse.");
        }

        String targetId = victims.get(idx);

        // cohérence avec currentAction
        if (a.getTargetId() != null && !a.getTargetId().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cible de Fosse incohérente.");
        }

        Player targetPlayer = g.findPlayer(targetId);
        Game.Monster targetMonster = g.findMonster(targetId);

        if (targetPlayer == null && targetMonster == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cible de Fosse invalide.");
        }

        // si la cible est un joueur : on garde l’ancien comportement :
        // la "victime" doit cliquer elle-même.
        if (targetPlayer != null) {
            if (!clicker.getId().equals(targetId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "ce n’est pas encore ton tour de résoudre la Fosse.");
            }
        } else {
            // si la cible est un monstre : c’est le vampire qui résout pour lui (si on
            // passe
            // par ici)
            if (!"VAMPIRE".equals(clicker.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "seul le vampire peut résoudre la Fosse pour une créature.");
            }
        }

        resolvePitInternal(g, hunterId, targetId, loc, idx, victims);

        store.save(g);

        store.afterCommit(() -> {
            pushLive(g, "Une fosse a été résolue.");
            live.raidModsUpdated(g);
            live.actionRolled(g, "PIT", hunterId, targetId, g.getCurrentAction().getRoll());
        });

        return g;
    }

    private void resolvePitInternal(Game g, String hunterId, String targetId, String loc, int idx,
            java.util.List<String> victims) {
        // sécurité : la cible (joueur ou monstre) est-elle toujours sur ce lieu ?
        if (!g.isEntityOn(targetId, loc)) {
            // ne devrait pas trop arriver en auto-roll
            return;
        }

        // Jet
        int roll = dice.roll(20);

        String msg = g.entityName(targetId) + " jette un dé pour éviter la Fosse (1d20 = " + roll + "). ";

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet pour la Fosse : 1d20 = " + roll);

        if (roll < 8) {
            if (g.getRaidMods() == null)
                g.setRaidMods(new java.util.HashMap<>());
            g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                    .add(new StatMod("DEFENSE", -2, "ACTION:PIT"));

            msg += "Il tombe dans la fosse : sa défense est réduite de 2.";
            breakdown.add("Échec : DEF -2");
        } else {
            msg += "Il évite la fosse.";
            breakdown.add("Réussite : aucun malus");
        }

        g.addHistory(msg);

        // Met currentAction à l’état "résolu" pour le front
        Game.Action current = new Game.Action();
        current.setMode("PIT");
        current.setOwnerId(hunterId);
        current.setLocation(loc);
        current.setTargetId(targetId);
        current.setRoll(roll);
        current.setBreakdownLines(breakdown);
        current.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(current);

        // Avancer l’index pour ce hunter
        int newIdx = idx + 1;
        g.getPitIndexByHunter().put(hunterId, newIdx);

        // Si plus de cible pour ce "passage"
        if (newIdx >= victims.size()) {
            // Vérifier s'il reste des cartes PIT (pour faire un 2ème passage, etc.)
            int cardsRemaining = (g.getPitCardsCount() != null ? g.getPitCardsCount().getOrDefault(hunterId, 1) : 1)
                    - 1;

            if (cardsRemaining > 0) {
                // On repart pour un tour !
                g.getPitCardsCount().put(hunterId, cardsRemaining);
                g.getPitIndexByHunter().put(hunterId, 0); // Reset index au début
            } else {
                // Terminé pour de bon
                if (g.getPitCardsCount() != null)
                    g.getPitCardsCount().put(hunterId, 0);
                g.getPitHunters().remove(hunterId);
            }
        }
    }

    @Transactional
    public Game resolveIncendiaire(String gameId, String hunterId, String infraCode) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        // Incendiaire se résout en PHASE3 (comme Filet/Fosse)
        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Incendiaire se résout en PHASE3, juste avant les duels.");
        }

        Player hunter = g.findPlayer(hunterId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad hunter");
        }

        // On vérifie que currentAction correspond bien à cet Incendiaire
        Game.Action ca = g.getCurrentAction();
        if (ca == null || !"INCENDIAIRE".equals(ca.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Incendiaire en cours de résolution.");
        }
        if (!hunterId.equals(ca.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ce n'est pas ton Incendiaire à résoudre.");
        }

        String loc = ca.getLocation();
        if (loc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "lieu introuvable pour Incendiaire.");
        }

        // Parsing de l'infra choisie
        final Infra infra;
        try {
            infra = Infra.valueOf(infraCode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "infrastructure inconnue pour Incendiaire.");
        }

        // Jet de dé
        int roll = dice.roll(20);

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet d'Incendiaire : 1d20 = " + roll);

        String infraLocCode = infra.locationCode(); // ex:
                                                    // "sawmill","mine","library","laboratory","ballroom","altar","forge"
        String infraLabel = (infraLocCode != null ? Location.labelFrOf(infraLocCode) : infra.name());

        String msg;

        if (roll > 5) {
            boolean destroyedPending = false;
            boolean willDestroyBuilt = false;

            // 1) Annuler une construction en cours
            Game.PendingConstruction pc = g.getPendingConstruction();
            if (pc != null && pc.infra == infra) {
                g.setPendingConstruction(null);
                destroyedPending = true;
            }

            // 2) Si déjà construite → destruction différée en fin de raid
            if (g.getBuiltInfras() != null && g.getBuiltInfras().contains(infra)) {
                if (g.getInfrasToDestroyEndOfRaid() == null) {
                    g.setInfrasToDestroyEndOfRaid(java.util.EnumSet.noneOf(Infra.class));
                }
                g.getInfrasToDestroyEndOfRaid().add(infra);
                willDestroyBuilt = true;
            }

            if (destroyedPending) {
                msg = g.nameOf(hunterId)
                        + " fait échouer la construction de " + infraLabel
                        + " (1d20 -> " + roll + ").";
                breakdown.add("Succès : la construction de " + infraLabel + " est ravagée par les flammes.");
            } else if (willDestroyBuilt) {
                msg = g.nameOf(hunterId)
                        + " met le feu à " + infraLabel
                        + " (1d20 -> " + roll + ").";
                breakdown.add("Succès : " + infraLabel + " est ravagé par les flammes.");
            } else {
                msg = "Aucune construction à détruire.";
                breakdown.add("Aucune construction correspondant à " + infraLabel + " sur ce lieu.");
            }

            g.addHistory(msg);
        } else {
            msg = g.nameOf(hunterId)
                    + " tente d'incendier " + infraLabel
                    + " (1d20 -> " + roll + ") mais échoue.";
            breakdown.add("Échec : aucune construction n'est affectée.");
            g.addHistory(msg);
        }

        // On marque le résultat sur l'action courante
        ca.setTargetId(infra.name()); // pour que le front sache de quoi on parle
        ca.setRoll(roll);
        ca.setBreakdownLines(breakdown);
        ca.setResolvedAtMillis(System.currentTimeMillis());

        // On retire cet Incendiaire de la liste des préparés
        if (g.getIncendiaireLocationByHunter() != null) {
            g.getIncendiaireLocationByHunter().remove(hunterId);
        }

        store.save(g);

        final String fMsg = msg;
        final int fRoll = roll;
        final String fHunterId = hunterId;
        final String fInfra = infra.name();

        store.afterCommit(() -> {
            pushLive(g, fMsg);
            live.actionRolled(g, "INCENDIAIRE", fHunterId, fInfra, fRoll);
        });

        return g;
    }

    /**
     * Retourne true s'il reste au moins une infrastructure cible possible pour
     * Incendiaire
     * sur ce lieu (construite ou en construction).
     */
    @Transactional
    public Game resolveProvocation(String gameId, String hunterId, String enemyId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Provocation se résout en PREPHASE3.");
        }

        Player hunter = g.findPlayer(hunterId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"PROVOCATION".equals(a.getMode())
                || !hunterId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Provocation en cours pour ce joueur.");
        }

        if (enemyId == null || enemyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player enemy = g.findPlayer(enemyId);
        if (enemy == null
                || (!"VAMPIRE".equals(enemy.getRole()) && !"SERVANT".equals(enemy.getRole()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être le vampire ou un serviteur.");
        }

        if (enemy.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        String hunterLoc = g.locationOf(hunterId);
        String enemyLoc = g.locationOf(enemyId);
        if (hunterLoc == null || !hunterLoc.equals(enemyLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être sur le même lieu que le chasseur.");
        }

        // On vérifie encore la condition 2 chasseurs + 1 ennemi (sécurité)
        java.util.List<Player> onLoc = g.getPlayers().stream()
                .filter(pl -> hunterLoc.equals(g.locationOf(pl.getId())))
                .toList();

        long huntersCount = onLoc.stream()
                .filter(pl -> "HUNTER".equals(pl.getRole()))
                .filter(pl -> pl.getHp() > 0)
                .count();

        long enemyCount = onLoc.stream()
                .filter(pl -> ("VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole())))
                .filter(pl -> pl.getHp() > 0)
                .count();

        if (enemyCount < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Provocation nécessite au moins 1 ennemi sur le lieu.");
        }

        // Enregistrer la provocation pour ce raid
        if (g.getProvokedTargetByEnemy() == null) {
            g.setProvokedTargetByEnemy(new java.util.HashMap<>());
        }
        if (g.getPendingVampireEscape() != null) {
            g.addHistory("Provocation — le stratagème d'évasion du vampire (" + g.getPendingVampireEscape()
                    + ") est annulé !");
            g.setPendingVampireEscape(null);
        }

        g.getProvokedTargetByEnemy().put(enemy.getId(), hunter.getId());

        // Puce display éventuelle
        g.addRaidMod(enemy.getId(), "ATTACK", 0, "ACTION:PROVOCATION:DSP");

        String hist = "Provocation — " + g.nameOf(hunter.getId())
                + " provoque " + g.nameOf(enemy.getId())
                + " : il devra le prendre pour cible en priorité ce raid.";
        g.addHistory(hist);

        a.setTargetId(enemy.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null); // on ferme la modale comme Marque / Affaiblissement

        // Relancer la mécanique de préphase (30s ou passage phase3)
        flow.setupUnstableAndPrephaseTimeout(g);

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, "PROVOCATION", hunterId, enemy.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveAmbush(String gameId, String playerId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Embuscade se résout en PREPHASE3.");
        }

        Player hunter = g.findPlayer(playerId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"AMBUSH".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Embuscade en cours pour ce joueur.");
        }

        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player target = g.findPlayer(targetId);
        if (target == null
                || (!"VAMPIRE".equals(target.getRole()) && !"SERVANT".equals(target.getRole()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être un vampire ou un serviteur.");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        String loc = a.getLocation();
        String targetLoc = g.locationOf(target.getId());
        if (targetLoc == null || !targetLoc.equals(loc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible n'est plus sur le lieu de l'embuscade.");
        }

        // Tous les chasseurs vivants sur ce lieu
        java.util.List<Player> onLoc = g.getPlayers().stream()
                .filter(pl -> loc.equals(g.locationOf(pl.getId())))
                .toList();

        java.util.List<Player> huntersOnLoc = onLoc.stream()
                .filter(pl -> "HUNTER".equals(pl.getRole()))
                .filter(pl -> pl.getHp() > 0)
                .toList();

        if (huntersOnLoc.size() < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "il n'y a plus assez de chasseurs groupés pour Embuscade.");
        }

        int huntersCount = huntersOnLoc.size();

        int bonus = huntersCount;

        for (Player h : huntersOnLoc) {
            g.addRaidMod(h.getId(), "ATTACK", bonus, "ACTION:AMBUSH:ENG");
        }

        // Blocage des ripostes de la cible contre ces chasseurs
        if (g.getAmbushHuntersByEnemy() == null) {
            g.setAmbushHuntersByEnemy(new java.util.HashMap<>());
        }
        java.util.List<String> hunterIds = huntersOnLoc.stream()
                .map(Player::getId)
                .toList();
        g.getAmbushHuntersByEnemy().put(target.getId(), hunterIds);

        // Enregistrer le lieu comme ayant eu une embuscade
        if (g.getAmbushLocations() == null) {
            g.setAmbushLocations(new java.util.HashSet<>());
        }
        g.getAmbushLocations().add(loc);

        String hist = "Embuscade — "
                + g.nameOf(hunter.getId())
                + " coordonne une attaque avec "
                + huntersOnLoc.size() + " chasseurs contre "
                + g.nameOf(target.getId())
                + " : chacun gagne +" + bonus + " ATK ce raid, et "
                + g.nameOf(target.getId())
                + " ne pourra pas riposter contre eux ce raid.";
        g.addHistory(hist);

        a.setTargetId(target.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // on relance la mécanique de préphase
        flow.setupUnstableAndPrephaseTimeout(g);

        store.save(g);

        Game gAfter = g;
        store.afterCommit(() -> {
            live.actionResolved(gAfter, "AMBUSH", playerId, target.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveBlessedStake(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pieu béni se résout pendant les combats (PHASE3).");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"BLESSED_STAKE".equals(a.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action pieu béni en cours.");
        }

        if (!userId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ce pieu béni appartient à un autre chasseur.");
        }

        Player hunter = g.findPlayer(a.getOwnerId());
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad hunter");
        }

        String targetId = a.getTargetId();
        if (targetId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cible manquante pour pieu béni.");
        }

        Player targetPlayer = g.findPlayer(targetId);
        Game.Monster targetMonster = (targetPlayer == null) ? g.findMonster(targetId) : null;
        if (targetPlayer == null && targetMonster == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour pieu béni.");
        }

        // --- Jet d4 ---
        int roll = dice.roll(4); // 1..4

        int beforeHp;
        if (targetPlayer != null) {
            beforeHp = targetPlayer.getHp();
            targetPlayer.setHp(Math.max(0, beforeHp - roll));
        } else {
            beforeHp = targetMonster.hp;
            targetMonster.hp = Math.max(0, beforeHp - roll);
        }

        int afterHp = (targetPlayer != null ? targetPlayer.getHp() : targetMonster.hp);
        int realExtra = beforeHp - afterHp;

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet de pieu béni : 1d4 = " + roll + " → " + realExtra + " dégâts sacrés.");

        String msg = "Pieu béni — "
                + g.nameOf(hunter.getId())
                + " inflige " + realExtra
                + " dégâts sacrés supplémentaires à "
                + g.entityName(targetId)
                + " (1d4 = " + roll + ").";

        g.addHistory(msg);

        // Si la cible est le vampire et qu'il perd des PV, on garde le flag cohérent
        if (realExtra > 0 && targetPlayer != null && "VAMPIRE".equals(targetPlayer.getRole())) {
            g.setVampireTookDamageThisRaid(true);
        }

        // Effet persistant : consommé DEFINITIVEMENT une fois utilisé
        hunter.setBlessedStake(false);

        var list = g.getRaidMods().get(hunter.getId());

        list.removeIf(m -> {
            String src = m.getSource();
            return src != null && src.startsWith("ACTION:BLESSED_STAKE");
        });

        // Remplir currentAction pour le front (modale)
        a.setRoll(roll);
        a.setBreakdownLines(breakdown);
        a.setResolvedAtMillis(System.currentTimeMillis());

        // --- Après les dégâts: gérer morts + fin de partie
        flow.handleDeathsAndVictory(g);

        store.save(g);

        final String fMsg = msg;
        final int fRoll = roll;
        final String fOwner = hunter.getId();
        final String fTarget = targetId;

        store.afterCommit(() -> {
            pushLive(g, fMsg);
            live.raidModsUpdated(g);
            // même pattern que Filet : pour mettre la modale à jour
            live.actionRolled(g, "BLESSED_STAKE", fOwner, fTarget, fRoll);
        });

        return g;
    }

    @Transactional
    public Game rollCrate(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);
        Player p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!p.isAlive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || (!"CRATE_LAKE".equals(a.getMode()) && !"CRATE_MANOR".equals(a.getMode()))
                || !userId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pas de caisse à ouvrir.");
        }

        if (a.getRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Caisse déjà ouverte.");
        }

        int d6 = dice.roll(6);
        a.setRoll(d6);

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        String resMsg;
        if (d6 <= 2) {
            p.grant("wood", 2);
            breakdown.add("Jet : " + d6 + " (1-2) → +2 bois.");
            resMsg = g.nameOf(userId) + " trouve 2 bois dans la caisse (1d6 = " + d6 + ").";
        } else if (d6 <= 4) {
            p.grant("iron", 2);
            breakdown.add("Jet : " + d6 + " (3-4) → +2 fer.");
            resMsg = g.nameOf(userId) + " trouve 2 fer dans la caisse (1d6 = " + d6 + ").";
        } else {
            p.grant("wood", 2);
            p.grant("iron", 2);
            breakdown.add("Jet : " + d6 + " (5-6) → +2 bois et +2 fer.");
            resMsg = g.nameOf(userId) + " trouve 2 bois et 2 fer dans la caisse (1d6 = " + d6 + ").";
        }

        a.setBreakdownLines(breakdown);
        a.setResolvedAtMillis(System.currentTimeMillis());

        g.addHistory(resMsg);
        flow.setupUnstableAndPrephaseTimeout(g);

        store.save(g);

        final int fRoll = d6;
        store.afterCommit(() -> {
            pushLive(g, resMsg);
            live.actionRolled(g, a.getMode(), userId, null, fRoll);
        });

        return g;
    }

    @Transactional
    public Game resolveCrateAction(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);
        Game.Action a = g.getCurrentAction();
        if (a == null || (!"CRATE_LAKE".equals(a.getMode()) && !"CRATE_MANOR".equals(a.getMode()))
                || !userId.equals(a.getOwnerId())) {
            return g;
        }

        String oldMode = a.getMode();
        Player p = g.findPlayer(userId);
        if (p != null) {
            p.setCrateUsedThisRaid(true);
        }

        g.setCurrentAction(null);
        flow.setupUnstableAndPrephaseTimeout(g);
        store.save(g);

        store.afterCommit(() -> live.actionResolved(g, oldMode, userId, null));

        return g;
    }

    @Transactional
    public Game resolveHolyWater(String gameId, String playerId, String mode) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        Player p = g.findPlayer(playerId);
        if (p == null || !"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"EAU_BENITE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Eau bénite en cours pour ce joueur.");
        }

        if (p.getActions() == null || !p.getActions().contains(Action.EAU_BENITE.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune carte Eau bénite dans votre inventaire.");
        }

        String choice = (mode == null ? "" : mode.trim().toUpperCase());
        if (choice.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode required");
        }

        String hist;

        switch (choice) {
            case "REDUCE" -> {
                int before = p.getCorruption();
                if (before > 0) {
                    p.setCorruption(before - 1);
                    hist = "Eau bénite — " + g.nameOf(p.getId())
                            + " purifie une partie de sa corruption (niveau "
                            + p.getCorruption() + ").";
                } else {
                    hist = "Eau bénite — " + g.nameOf(p.getId())
                            + " est déjà indemne de corruption, la purification est sans effet.";
                }
                g.addHistory(hist);
                // on met à jour les mods de corruption pour le raid en cours
                corruption.rebuildCorruptionMods(g);
            }
            case "CLEANSE" -> {
                if (!corruption.isDarkMarked(g, p.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "aucune Marque ténébreuse à purifier.");
                }
                corruption.cleanseDarkMark(g, p);
                hist = "Eau bénite — " + g.nameOf(p.getId())
                        + " dissipe la Marque ténébreuse.";
                g.addHistory(hist);
            }
            case "ATTACK" -> {
                // +4 dégâts sacrés lors de la prochaine attaque contre le vampire
                RaidEffects fx = (g.getRaidEffects() != null)
                        ? g.getRaidEffects().get(p.getId())
                        : null;
                if (fx == null) {
                    fx = new RaidEffects();
                    if (g.getRaidEffects() == null) {
                        g.setRaidEffects(new java.util.HashMap<>());
                    }
                    g.getRaidEffects().put(p.getId(), fx);
                }
                fx.setHolyWaterAttack(true);

                hist = "Eau bénite — " + g.nameOf(p.getId())
                        + " consacre ses armes pour ce raid: ses attaques brûleront le vampire.";
                g.addHistory(hist);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode invalide");
        }

        // La carte est consommée quel que soit le choix
        p.getActions().remove(Action.EAU_BENITE.name());

        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        flow.setupUnstableAndPrephaseTimeout(g);

        store.save(g);

        Game gAfter = g;
        final String fChoice = choice;

        store.afterCommit(() -> {
            live.actionResolved(gAfter, "EAU_BENITE", playerId, fChoice);
            live.phaseChanged(gAfter);
        });

        return g;
    }

    /**
     * Est-ce que ce joueur a vraiment une Eau bénite jouable MAINTENANT ?
     * (équivalent back de canPlayHolyWaterkHere côté front)
     */
}
