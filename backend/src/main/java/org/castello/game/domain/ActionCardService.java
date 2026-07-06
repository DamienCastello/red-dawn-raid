package org.castello.game.domain;

import org.castello.game.Action;
import org.castello.game.CenterBoard;
import org.castello.game.Game;
import org.castello.game.GameStatus;
import org.castello.game.Infra;
import org.castello.game.Location;
import org.castello.game.Phase;
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
 * Domaine Cartes action — le point d'entrée quand un joueur JOUE une carte
 * (POST /actions/use) et les helpers partagés de préparation.
 *
 * Chaque carte a son cas dans le switch de useAction : préparation d'un
 * piège, pose d'un marqueur de raid, ouverture d'une modale de résolution
 * (currentAction)… Les résolutions interactives (jets, choix de cible)
 * vivent dans HunterActionService / VampireActionService.
 */
@Service
public class ActionCardService {

    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final WeatherService weather;
    private final CorruptionService corruption;
    private final LocationEffectService locationEffects;
    private final HarvestService harvest;
    private final LiveEvents live;
    private final RaidFlow flow;

    public ActionCardService(GameStore store, Dice dice, DeckService decks,
            WeatherService weather, CorruptionService corruption,
            LocationEffectService locationEffects, HarvestService harvest,
            LiveEvents live, @Lazy RaidFlow flow) {
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.weather = weather;
        this.corruption = corruption;
        this.locationEffects = locationEffects;
        this.harvest = harvest;
        this.live = live;
        this.flow = flow;
    }

    private boolean isAlive(Player p) {
        return p.isAlive();
    }

    /** Ajoute un message au fil central ET l'émet en live. */
    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        live.message(g, msg);
    }

    @Transactional
    public Game useAction(String gameId, String playerId, Action type) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        Player p = g.findPlayer(playerId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        // Bloque les serviteurs (corruption >= 3) d'utiliser des actions
        if (p.getCorruption() >= 3) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Les serviteurs ne peuvent pas utiliser d'actions.");
        }

        // --- FIX: Si une action non-bloquante (info seule) traîne, on la nettoie ---
        Game.Action current = g.getCurrentAction();
        if (current != null && !isBlockingAction(current)) {
            g.setCurrentAction(null);
        }

        boolean isHunter = "HUNTER".equals(p.getRole());
        boolean isVamp = "VAMPIRE".equals(p.getRole());

        // bloque les instables qui succombent à la corruption
        if (g.getPhase() == Phase.PREPHASE3 && corruption.hasSuccumbedToCorruption(g, playerId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu succombes à la corruption : tu ne peux pas utiliser tes cartes ce raid.");
        }

        // Présence écrasante du vampire bloque les actions des chasseurs
        if (isHunter
                && g.isHunterActionsBlockedThisRaid()
                && (g.getPhase() == Phase.PREPHASE3 || g.getPhase() == Phase.PHASE3)) {

            String hunterLoc = g.locationOf(playerId);
            String vampLoc = null;
            var vampOpt = g.vampire();
            if (vampOpt.isPresent()) {
                vampLoc = g.locationOf(vampOpt.get().getId());
            }

            if (hunterLoc != null
                    && vampLoc != null
                    && java.util.Objects.equals(hunterLoc, vampLoc)) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La présence écrasante du vampire t'empêche d'utiliser des cartes d'action sur ce lieu ce raid.");
            }
        }

        // Inventaire sur Player
        List<String> inv = p.getActions();
        if (inv == null || !inv.contains(type.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
        }

        String feedText;

        switch (type) {
            case EAU_BENITE -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
                }

                // Pas deux actions bloquantes en même temps
                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // currentAction = ouverture de la modale de choix
                Game.Action a = new Game.Action();
                a.setMode("EAU_BENITE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = g.nameOf(playerId)
                        + " brandit une fiole d'eau bénite et doit choisir comment l'utiliser.";
                g.addHistory(msg);
                feedText = msg;

                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }
            case FUMIGATION_AIL -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PHASE1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "cette action est utilisable uniquement pendant la PHASE1");

                // Fumigation doit être jouée AVANT de choisir le lieu
                if (g.hasPlayed(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà choisi ton lieu : Fumigation d’ail doit être jouée avant.");
                }

                applyFumigationAilPrepare(g, p);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    decks.discardHunterAction(g, type.name());
                if (isVamp)
                    decks.discardVampAction(g, type.name());

                store.save(g);

                final String fType = type.name();
                final String fUserId = playerId;

                store.afterCommit(() -> {
                    live.actionUsed(g, fUserId, fType);
                });
            }
            case PISTEUR -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PHASE1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "cette action est utilisable uniquement pendant la PHASE1");

                // si une fumigation est préparée, on ne peut PAS jouer Pisteur à la main
                if (g.getPendingGarlicPlayers() != null
                        && g.getPendingGarlicPlayers().contains(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Impossible d'utiliser pisteur pendant une préparation de fumigation.");
                }

                // Est-ce que, AVANT Pisteur, je comptais déjà comme “ayant joué” ?
                boolean alreadyPlayed = g.hasPlayed(playerId);

                if (g.getTrackerHunters() == null) {
                    g.setTrackerHunters(new java.util.HashSet<>());
                }
                if (g.getTrackerHunters().contains(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu suis déjà la piste du vampire.");
                }

                g.getTrackerHunters().add(playerId);
                g.addHistory(g.nameOf(playerId) + " joue Pisteur et traque le vampire.");
                feedText = g.nameOf(playerId) + " se met sur la piste du vampire.";

                // Si je n'avais pas encore “joué” avant, Pisteur peut finir ma phase.
                boolean advanceToP2 = (g.getPhase() == Phase.PHASE1)
                        && !alreadyPlayed
                        && flow.allHuntersSelected(g);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // si c'est une carte bonus boutique -> elle disparaît, PAS de discard
                boolean wasShopBonus = false;
                Integer c = p.getShopPisteurCount();
                if (c != null && c > 0) {
                    p.setShopPisteurCount(c - 1);
                    wasShopBonus = true;
                }

                // 4) envoyer en défausse
                if (!wasShopBonus) {
                    if (isHunter)
                        decks.discardHunterAction(g, type.name());
                    if (isVamp)
                        decks.discardVampAction(g, type.name());
                }

                store.save(g);

                final String gid = g.getId();
                final boolean fAdvance = advanceToP2;
                final String fFeed = feedText;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, playerId, type.name());
                });

                if (advanceToP2) {
                    flow.scheduleAdvance(gid, Phase.PHASE1, Phase.PHASE2, 2500);
                }
            }
            case FEU_DE_CAMP -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Cette action est utilisable juste avant les combats (PREPHASE3).");
                }

                // Météo doit être crépuscule / nuit obscure / nuit claire
                WeatherStatus ws = g.getWeatherStatus();
                if (ws == null
                        || (ws != WeatherStatus.DUSK
                                && ws != WeatherStatus.NIGHT_DARK
                                && ws != WeatherStatus.NIGHT_CLEAR)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Feu de camp n’a d’effet que par temps de Crépuscule, Nuit obscure ou Nuit claire.");
                }

                // Il faut que le chasseur soit sur un lieu (center)
                String loc = g.getCenter().stream()
                        .filter(cb -> playerId.equals(cb.getPlayerId()))
                        .map(CenterBoard::getCard)
                        .findFirst()
                        .orElse(null);

                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Feu de camp.");
                }

                if (g.getCampfireLocations() == null) {
                    g.setCampfireLocations(new java.util.HashSet<>());
                }
                if (g.getCampfireLocations().contains(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "un Feu de camp est déjà allumé à " + Location.labelFrOf(loc) + ".");
                }

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    decks.discardHunterAction(g, type.name());
                if (isVamp)
                    decks.discardVampAction(g, type.name());

                // Marque ce lieu comme protégé par un feu de camp
                g.getCampfireLocations().add(loc);

                String wsName = weather.weatherNameFr(ws);
                String msg = g.nameOf(playerId)
                        + " allume un feu de camp à " + Location.labelFrOf(loc)
                        + " et annule les effets de " + wsName.toLowerCase() + " sur ce lieu.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, playerId, type.name());
                });
            }
            case NET -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Filet est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Doit participer à un combat, sinon on pourrait lui laisser la jouer pour
                // rien.
                String loc = g.locationOf(playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Filet.");
                }

                if (!hasTrapTargetsOnLocation(g, loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Filet n’a d’effet que s’il y a au moins un adversaire sur ton lieu.");
                }

                if (g.getWeatherStatus() == WeatherStatus.NIGHT_DARK && !isCampfireCancellingWeather(g, loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Nuit obscure : piège interdit sans feu de camp sur ce lieu.");
                }

                // Marque ce chasseur comme ayant un Filet préparé pour ce raid
                g.getNetHunters().add(playerId);

                // Incrémenter le compteur de cartes NET pour ce chasseur
                if (g.getNetCardsRemaining() == null) {
                    g.setNetCardsRemaining(new java.util.HashMap<>());
                }
                int currentCount = g.getNetCardsRemaining().getOrDefault(playerId, 0);
                g.getNetCardsRemaining().put(playerId, currentCount + 1);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    decks.discardHunterAction(g, type.name());
                if (isVamp)
                    decks.discardVampAction(g, type.name());

                String msg = g.nameOf(playerId)
                        + " prépare un Filet pour piéger un adversaire.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType); // pour refresh front
                });

                return g;
            }
            case PIT -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Fosse est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                String loc = g.locationOf(playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Fosse.");
                }

                var enemies = g.vampSideOn(loc);
                var monsters = g.monstersOn(loc);

                if (enemies.isEmpty() && monsters.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Fosse n’a d’effet que s’il y a au moins un ennemi sur ton lieu.");
                }

                if (g.getWeatherStatus() == WeatherStatus.NIGHT_DARK && !isCampfireCancellingWeather(g, loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Nuit obscure : piège interdit sans feu de camp sur ce lieu.");
                }

                // Marque ce chasseur comme ayant une Fosse préparée
                g.getPitHunters().add(playerId);

                // Nouveau : enregistre les victimes pour ce chasseur (joueurs + monstres)
                if (g.getPitTargetsByHunter() == null)
                    g.setPitTargetsByHunter(new java.util.HashMap<>());
                if (g.getPitCardsCount() == null)
                    g.setPitCardsCount(new java.util.HashMap<>());
                if (g.getPitIndexByHunter() == null)
                    g.setPitIndexByHunter(new java.util.HashMap<>());

                var victimIds = new java.util.ArrayList<String>();
                enemies.forEach(vv -> victimIds.add(vv.getId()));
                monsters.forEach(mm -> victimIds.add(mm.id));

                // On remplace la liste de victimes (snapshot actue) pour ce hunter
                g.getPitTargetsByHunter().put(playerId, victimIds);
                g.getPitIndexByHunter().put(playerId, 0);

                // On incrémente le nombre de cartes PIT jouées
                if (g.getPitCardsCount() == null) {
                    g.setPitCardsCount(new java.util.HashMap<>());
                }
                int currentCount = g.getPitCardsCount().getOrDefault(playerId, 0);
                g.getPitCardsCount().put(playerId, currentCount + 1);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    decks.discardHunterAction(g, type.name());
                if (isVamp)
                    decks.discardVampAction(g, type.name());

                String msg = g.nameOf(playerId)
                        + " prépare une Fosse pour ses adversaires.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case PROVOCATION -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Provocation est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    // Exception : Provocation peut interrompre Passage Secret ou Image Miroir
                    Game.Action cur = g.getCurrentAction();
                    boolean canInterrupt = cur != null && ("PASSAGE_SECRET".equals(cur.getMode())
                            || "IMAGE_MIROIR_SETUP".equals(cur.getMode())
                            || "IMAGE_MIROIR_RESOLVE".equals(cur.getMode()));

                    if (!canInterrupt) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Une autre action est déjà en cours de résolution.");
                    } else {
                        // On interrompt l'action en cours
                        g.addHistory("Provocation — " + g.nameOf(playerId) + " interrompt la concentration de "
                                + g.nameOf(cur.getOwnerId()) + " !");
                        // L'action précédente est écrasée par la suite
                    }
                }

                String loc = g.locationOf(playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Provocation.");
                }

                // Joueurs sur ce lieu
                java.util.List<Player> onLoc = g.getPlayers().stream()
                        .filter(pl -> loc.equals(g.locationOf(pl.getId())))
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
                            "Provocation nécessite au moins 1 ennemi sur ton lieu.");
                }

                // Consommer la carte dans l'inventaire du chasseur
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                decks.discardHunterAction(g, type.name());

                // currentAction : choix de l'ennemi dans la modale
                Game.Action a = new Game.Action();
                a.setMode("PROVOCATION");
                a.setOwnerId(playerId);
                a.setLocation(loc);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = "Provocation — "
                        + g.nameOf(playerId)
                        + " attire la colère de ses ennemis.";
                g.addHistory(msg);
                feedText = msg;

                // On annule/redémarre le timer de préphase (comme pour Marque /
                // Affaiblissement)
                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case INCENDIAIRE -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Incendiaire est utilisable uniquement pendant la PREPHASE3.");
                }

                String loc = g.locationOf(playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Incendiaire.");
                }

                // 1) Consommer la carte
                inv.remove(type.name());
                decks.discardHunterAction(g, type.name());

                // 2) Enregistrer la préparation : ce chasseur brûlera potentiellement ce lieu
                // en PHASE3
                g.getIncendiaireLocationByHunter().put(playerId, loc);

                String msg = g.nameOf(playerId)
                        + " prépare une action Incendiaire à " + Location.labelFrOf(loc) + ".";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case AMBUSH -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Embuscade est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                String loc = g.locationOf(playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu dois être sur un lieu pour utiliser Embuscade.");
                }

                // Joueurs sur ce lieu
                java.util.List<Player> onLoc = g.getPlayers().stream()
                        .filter(pl -> loc.equals(g.locationOf(pl.getId())))
                        .toList();

                long huntersCount = onLoc.stream()
                        .filter(pl -> "HUNTER".equals(pl.getRole()))
                        .filter(pl -> pl.getHp() > 0)
                        .count();

                long enemyCount = onLoc.stream()
                        .filter(pl -> "VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole()))
                        .filter(pl -> pl.getHp() > 0)
                        .count();

                if (huntersCount < 2 || enemyCount < 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Embuscade nécessite au moins 2 chasseurs et 1 ennemi (vampire ou serviteur) sur ton lieu.");
                }

                if (g.getAmbushLocations() != null && g.getAmbushLocations().contains(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une embuscade a déjà été effectuée sur ce lieu ce raid.");
                }

                // Consommer la carte dans l'inventaire du chasseur
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                decks.discardHunterAction(g, type.name());

                // currentAction : choix de l'ennemi dans la modale
                Game.Action a = new Game.Action();
                a.setMode("AMBUSH");
                a.setOwnerId(playerId);
                a.setLocation(loc);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = "Embuscade — "
                        + g.nameOf(playerId)
                        + " prépare une attaque coordonnée avec les chasseurs présents.";
                g.addHistory(msg);
                feedText = msg;

                // On annule/redémarre le timer de préphase (comme Marque / Affaiblissement)
                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case LONELY -> {
                if (!isHunter)
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                if (g.getPhase() != Phase.PREPHASE3)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Solitaire utilisable uniquement en PREPHASE3.");

                if (hasBlockingActionInProgress(g))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");

                String loc = g.locationOf(playerId);
                if (loc == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu dois être sur un lieu pour utiliser Solitaire.");

                // consomme la carte
                inv.remove(type.name());
                decks.discardHunterAction(g, type.name());

                // Hunters éligibles = vivants + pas récolteur instable
                var harvestMap = g.getUnstableHarvestLocByPlayer();
                java.util.function.Predicate<Player> eligibleHunter = pl -> "HUNTER".equals(pl.getRole())
                        && pl.getHp() > 0
                        && (harvestMap == null || !harvestMap.containsKey(pl.getId()));

                var eligibleHunters = g.getPlayers().stream().filter(eligibleHunter).toList();

                long huntersHere = eligibleHunters.stream()
                        .filter(pl -> loc.equals(g.locationOf(pl.getId())))
                        .count();

                int absent = Math.max(0, eligibleHunters.size() - (int) huntersHere);

                // mods
                g.addRaidMod(playerId, "ATTACK", absent, "ACTION:LONELY:ENG");
                g.addRaidMod(playerId, "DEFENSE", absent, "ACTION:LONELY:ENG");

                String msg = "Solitaire — " + g.nameOf(playerId)
                        + " gagne +" + absent + " ATK et +" + absent + " DEF (" + absent + " absent(s)).";
                g.addHistory(msg);
                feedText = msg;

                // currentAction informatif (comme ECLIPSE)
                Game.Action a = new Game.Action();
                a.setMode("LONELY");
                a.setOwnerId(playerId);
                a.setLocation(loc);
                a.setTargetId(null);
                a.setRoll(absent); // on réutilise roll pour passer le bonus au front
                a.setBreakdownLines(new java.util.ArrayList<>(java.util.List.of(
                        "Solitaire : +" + absent + " ATK et +" + absent + " DEF.",
                        absent + " chasseur(s) absent(s) sur ce lieu.")));
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                // reset timer prephase (comme ECLIPSE)
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g);
                    live.actionUsed(g, fUserId, fType);
                    flow.schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case BLESSED_STAKE -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Pieu béni est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // déjà un épieu actif ? on refuse
                if (p.isBlessedStake()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà un pieu béni prêt à être déclenché.");
                }

                String loc = g.locationOf(playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser pieu béni.");
                }

                // Il doit y avoir au moins un ennemi sur ce lieu (comme Filet)
                boolean hasEnemy = g.getPlayers().stream()
                        .anyMatch(pl -> pl.getHp() > 0
                                && loc.equals(g.locationOf(pl.getId()))
                                && ("VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole())));

                // Consommer la carte dans l'inventaire
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                decks.discardHunterAction(g, type.name());

                // Marquer l'effet PERSISTANT
                p.setBlessedStake(true);

                // Ajouter une puce DSP pour le raid courant
                g.addRaidMod(playerId, "ATTACK", 0, "ACTION:BLESSED_STAKE:DSP");

                String msg = "Pieu béni — "
                        + g.nameOf(playerId)
                        + " s'équipe d'un pieu béni en arme secondaire.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUser = playerId;
                final String fType = type.name();
                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    // pour forcer un refresh de la main / mods côté front
                    live.actionUsed(g, fUser, fType);
                });

                return g;
            }

            case SACRED_ROSARY -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Chapelet sacré est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Si Présence écrasante bloque les actions sur ce lieu, le check global
                // au début de useAction fera déjà le boulot, comme pour les autres cartes.

                // Déjà protégé par un chapelet ?
                if (p.isSacredRosary()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Tu bénéficies déjà de la protection d’un chapelet sacré.");
                }

                // Activer l’effet persistant sur ce chasseur
                p.setSacredRosary(true);

                // Puce DISPLAY pour ce raid (et suivants tant qu’on ne la retire pas)
                if (g.getRaidMods() == null) {
                    g.setRaidMods(new java.util.HashMap<>());
                }
                g.getRaidMods()
                        .computeIfAbsent(playerId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("DEFENSE", 0, "ACTION:SACRED_ROSARY:DSP"));

                // Consommer la carte d’action dans l’inventaire
                inv.remove(type.name());
                if (isHunter)
                    decks.discardHunterAction(g, type.name());

                String msg = g.nameOf(playerId)
                        + " porte un chapelet sacré: il pourra annuler une morsure réussie du vampire.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                    // Pour que la puce apparaisse tout de suite
                    live.raidModsUpdated(g);
                });

                return g;
            }

            case CHARISMATIQUE -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Charismatique est utilisable uniquement lors de la boutique (PHASE4).");
                }

                // Si Présence écrasante bloque les cartes d'action ce raid, on interdit
                if (g.isHunterActionsBlockedThisRaid()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "La présence écrasante du vampire t'empêche d'utiliser des cartes d'action ce raid.");
                }

                if (p.isCharismaticThisRaid()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "L'effet de Charismatique est déjà actif pour ce raid.");
                }

                // Activer le bonus pour ce raid
                p.setCharismaticThisRaid(true);

                // Consommer la carte
                inv.remove(type.name());
                decks.discardHunterAction(g, type.name());

                String msg = g.nameOf(playerId)
                        + " charme les marchands: ses achats à la boutique coûtent 20 or de moins "
                        + "et ses ventes rapportent 20 or de plus par ressource pour ce raid.";
                g.addHistory(msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("CHARISMATIQUE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case CRATE_LAKE, CRATE_MANOR -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
                }

                if (p.isCrateUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vous avez déjà utilisé une caisse ce raid.");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            type.name() + " est utilisable uniquement pendant la PREPHASE3.");
                }

                String loc = g.locationOf(playerId);
                if (type == Action.CRATE_LAKE && !"lake".equals(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cette action nécessite d'être au Lac.");
                }
                if (type == Action.CRATE_MANOR && !"manor".equals(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cette action nécessite d'être au Manoir.");
                }

                // Consommer la carte dans la main du chasseur
                inv.remove(type.name());
                decks.discardHunterAction(g, type.name());

                // Créer l'action interactive pour le front
                Game.Action action = new Game.Action();
                action.setMode(type.name());
                action.setOwnerId(playerId);
                action.setLocation(loc);
                g.setCurrentAction(action);
                p.setCrateUsedThisRaid(true);

                String msg = g.nameOf(playerId) + " découvre une caisse abandonnée au " + Location.labelFrOf(loc) + ".";
                g.addHistory(msg);
                feedText = msg;

                flow.setupUnstableAndPrephaseTimeout(g);
                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case MARCHAND_ITINERANT -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
                }

                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Marchand itinérant est utilisable uniquement pendant la maintenance (PHASE4).");
                }

                // perso : si CE joueur a déjà une offre / un jet / une modale achat ouverte ->
                // refuse
                if (p.isMerchantPending()
                        || p.getShopBonusKind() != null
                        || p.isShopBonusBuyPending()
                        || p.getMerchantRoll() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà une offre du marchand en cours.");
                }

                if (p.isMerchantUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu as déjà appelé un marchand itinérant ce raid.");
                }

                // Consommer la carte dans la main du chasseur
                inv.remove(type.name());
                decks.discardHunterAction(g, type.name());

                // init état marchand perso
                p.setMerchantPending(true);
                p.setMerchantRoll(null);
                p.setShopBonusKind(null);
                p.setShopBonusEquipId(null);
                p.setShopBonusEquipTier(null);
                p.setShopBonusBuyPending(false);
                p.setMerchantUsedThisRaid(true);

                String msg = g.nameOf(playerId) + " appelle un marchand itinérant à la boutique.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case ADVANCED_TRANSMUTATION -> {
                if (!"VAMPIRE".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Transmutation avancée est utilisable uniquement pendant la maintenance (PHASE4).");
                }

                // Si le joueur a déjà une offre / un jet / une modale achat ouverte -> refuse
                if (p.isMerchantPending()
                        || p.getShopBonusKind() != null
                        || p.isShopBonusBuyPending()
                        || p.getMerchantRoll() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà une offre de transmutation en cours.");
                }

                if (p.isAdvancedTransmutationUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu as déjà utilisé la transmutation avancée ce raid.");
                }

                // Consommer la carte dans la main du vampire
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                // init état transmutation perso
                p.setMerchantPending(true);
                p.setMerchantRoll(null);
                p.setShopBonusKind(null);
                p.setShopBonusEquipId(null);
                p.setShopBonusEquipTier(null);
                p.setShopBonusBuyPending(false);
                p.setAdvancedTransmutationUsedThisRaid(true);

                String msg = g.nameOf(playerId) + " active la Transmutation avancée dans l'antre.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case CATACLYSME -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PHASE2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cataclysme est utilisable uniquement pendant la PHASE2.");
                }

                // On ne veut qu'un Cataclysme en cours à la fois
                Game.Action existing = g.getCurrentAction();
                if (existing != null && "CATACLYSME".equals(existing.getMode())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Un Cataclysme est déjà en cours de résolution.");
                }

                // On consomme la carte dans la main du vampire
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                // currentAction pour piloter la modale
                Game.Action a = new Game.Action();
                a.setMode("CATACLYSME");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                a.setBreakdownLines(breakdown);

                a.setRoll(null);
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                // Historique / feed
                String msg = g.nameOf(playerId) + " joue Cataclysme et déchire le ciel.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fType = type.name();
                final String fUserId = playerId;
                final String fFeed = feedText;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    // Comme pour les autres actions, on notifie:
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case CLONES_OMBRE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }
                if (g.getPhase() != Phase.PHASE2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Clones des ombres est utilisable uniquement pendant la PHASE2.");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                Game.Action a = new Game.Action();
                a.setMode("CLONES_OMBRE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = g.nameOf(playerId) + " invoque des clones des ombres.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fType = type.name();
                final String fUserId = playerId;
                final String fFeed = feedText;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case IMAGE_MIROIR -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }
                if (g.getPhase() != Phase.PHASE2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Image miroir est utilisable uniquement pendant la PHASE2.");
                }

                if (g.getProvokedTargetByEnemy() != null && g.getProvokedTargetByEnemy().containsKey(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vous êtes provoqué et ne pouvez pas utiliser Image Miroir !");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte action
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                // DELAYED RESOLUTION: On stocke juste l'intention
                g.setPendingVampireEscape("IMAGE_MIROIR_SETUP");

                // On ne met PAS d'action courante tout de suite (pas de modale)
                // g.setCurrentAction(a);

                String msg = g.nameOf(playerId) + " prépare une image miroir...";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fType = type.name();
                final String fUserId = playerId;
                final String fFeed = feedText;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case PRESENCE_ECRASANTE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Présence écrasante est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Lieu actuel du vampire
                String vampLoc = g.locationOf(playerId);

                // Annule toutes les prépas des chasseurs SUR CE LIEU + récupère la liste déjà
                // formatée pour le message
                java.util.List<String> parts = cancelHunterPrephase3ActionsOnVampLoc(g, vampLoc);

                // Bloque les cartes d'action chasseurs pour le reste du raid
                // (le blocage sera restreint au lieu du vampire dans la garde plus haut)
                g.setHunterActionsBlockedThisRaid(true);

                // Consommer la carte sur le vampire
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                String msg = g.nameOf(playerId) + " déchaîne une présence écrasante : ";

                if (parts.isEmpty()) {
                    msg += "aucune préparation de cartes des chasseurs n'était en cours sur ce lieu, "
                            + "mais les chasseurs prsénts sur " + Location.labelFrOf(vampLoc)
                            + " ne pourront plus utiliser de cartes d'action ce raid.";
                } else {
                    msg += "les préparations de cartes des chasseurs sur ce lieu sont annulées ("
                            + String.join(", ", parts)
                            + "). Les chasseurs présents sur " + Location.labelFrOf(vampLoc)
                            + " ne pourront plus utiliser de cartes d'action ce raid.";
                }

                g.addHistory(msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("PRESENCE_ECRASANTE");
                a.setOwnerId(playerId);
                a.setLocation(vampLoc);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("Les cartes actions chasseurs sur ce lieu sont annulées.");

                a.setBreakdownLines(breakdown);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g);
                    live.actionUsed(g, fUserId, fType);
                    flow.schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case ECLIPSE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Éclipse est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                g.setWeatherStatus(WeatherStatus.FULL_MOON);

                g.setWeatherStatusNameFr(weather.weatherNameFr(WeatherStatus.FULL_MOON));
                g.setWeatherDescriptionFr(weather.weatherDescFr(WeatherStatus.FULL_MOON));

                // Rebuild des mods météo (WEATHER:...)
                weather.rebuildWeatherMods(g);

                // Historique + feed
                String msg = g.nameOf(playerId)
                        + " invoque une éclipse: la pleine lune obscurcit les lieux.";
                g.addHistory(msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("ECLIPSE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("Une eclipse plonge les lieux dans l'obscurité.");
                a.setBreakdownLines(breakdown);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                // --- RESET TIMER PREPHASE ---
                // Reset du timer PREPHASE3 via version
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g);
                    live.actionUsed(g, fUserId, fType);

                    // On relance un timer PREPHASE3 complet de 30s
                    flow.schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case BLOOD_MOON -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Lune sanglante est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Vérifier que la météo contient FULL_MOON (principal ou secondaire)
                WeatherStatus ws1 = g.getWeatherStatus();

                boolean hasFullMoon = (ws1 == WeatherStatus.FULL_MOON);

                if (!hasFullMoon) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Lune sanglante ne peut être utilisée que si la météo est Pleine lune.");
                }

                // Consommer la carte
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                // Transformer la Pleine lune concernée en Lune sanglante
                g.setWeatherStatus(WeatherStatus.BLOOD_MOON);
                g.setWeatherStatusNameFr(weather.weatherNameFr(WeatherStatus.BLOOD_MOON));
                g.setWeatherDescriptionFr(weather.weatherDescFr(WeatherStatus.BLOOD_MOON));

                // Rebuilder les mods météo
                weather.rebuildWeatherMods(g);

                // Historique / feed
                String msg = g.nameOf(playerId)
                        + " invoque la Lune sanglante: la pleine lune devient rouge.";
                g.addHistory(msg);
                feedText = msg;

                // currentAction juste pour afficher la modale d'info
                Game.Action a = new Game.Action();
                a.setMode("BLOOD_MOON");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("+4 Attaque pour le vampire et serviteurs.");
                a.setBreakdownLines(breakdown);

                g.setCurrentAction(a);

                // --- RESET TIMER PREPHASE ---
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g); // pour rafraîchir les mods WEATHER:*
                    live.actionUsed(g, fUserId, fType);

                    // On relance un timer PREPHASE3 complet de 30s
                    flow.schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case VOILE_DE_BRUME -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Voile de brume est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                // Créer action en attente de choix de lieu
                Game.Action a = new Game.Action();
                a.setMode("VOILE_DE_BRUME");
                a.setOwnerId(playerId);
                a.setLocation(null); // Sera défini par le choix
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new ArrayList<>());
                a.setResolvedAtMillis(null); // Pas encore résolu
                g.setCurrentAction(a);

                String msg = g.nameOf(playerId) + " invoque un voile de brume.";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case FAIM_IRREPRESSIBLE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Faim irrépressible est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Consommer la carte sur le vampire
                List<String> actions = p.getActions();
                if (actions == null || !actions.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                decks.discardVampAction(g, type.name());

                // Flag de raid : à partir de maintenant, le vamp peut mordre même sans dégâts
                g.setHungerAllowsBiteThisRaid(true);

                // Message d’historique
                String msg = g.nameOf(playerId)
                        + " succombe à une faim irrépressible: il pourra tenter une morsure"
                        + " même sans infliger de dégâts ce raid.";
                g.addHistory(msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("FAIM_IRREPRESSIBLE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("Ce raid, le vampire pourra tenter une morsure même si l’attaque échoue.");
                a.setBreakdownLines(breakdown);

                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                // Reset du timer PREPHASE3 (comme Présence écrasante)
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                    flow.schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case MARQUE_TENEBREUSE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Marque ténébreuse est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte dans l'inventaire du vampire
                List<String> actions = p.getActions();
                if (actions == null || !actions.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                decks.discardVampAction(g, type.name());

                // currentAction : choix de la cible dans la modale
                Game.Action a = new Game.Action();
                a.setMode("MARQUE_TENEBREUSE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = g.nameOf(playerId)
                        + " invoque une Marque ténébreuse et s'apprête à maudire un chasseur.";
                g.addHistory(msg);
                feedText = msg;

                // On ANNULE le timer de préphase en cours (comme pour ton flag reset)
                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case AFFAIBLISSEMENT_OCCULTE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Affaiblissement occulte est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte dans l'inventaire du vampire
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                decks.discardVampAction(g, type.name());

                // currentAction : choix d'un chasseur dans la modale
                Game.Action a = new Game.Action();
                a.setMode("AFFAIBLISSEMENT_OCCULTE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = "Affaiblissement occulte — "
                        + g.nameOf(playerId)
                        + " prépare un rituel pour affaiblir un chasseur.";
                g.addHistory(msg);
                feedText = msg;

                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case PASSAGE_SECRET -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Passage secret est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (g.getProvokedTargetByEnemy() != null && g.getProvokedTargetByEnemy().containsKey(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vous êtes provoqué et ne pouvez pas utiliser Passage Secret !");
                }

                // Doit être au Manoir
                String loc = g.locationOf(playerId);
                if (!"manor".equals(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Passage secret n'est utilisable que si le vampire est au Manoir.");
                }

                // Pas deux actions bloquantes en même temps
                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                decks.discardVampAction(g, type.name());

                // DELAYED RESOLUTION
                g.setPendingVampireEscape("PASSAGE_SECRET");
                // g.setCurrentAction(null); // Pas d'action immédiate

                String msg = g.nameOf(playerId) + " cherche un passage secret...";
                g.addHistory(msg);
                feedText = msg;

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case AVIDITE_NOCTURNE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }
                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Avidité nocturne est utilisable uniquement lors de la boutique (PHASE4).");
                }

                // déjà utilisée ce raid ?
                if (g.isShopPricesIncreasedThisRaid()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Avidité nocturne a déjà été utilisée ce raid.");
                }

                // le vampire doit réellement avoir la carte
                if (inv == null || !inv.contains(type.name())) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Le vampire ne possède pas la carte Avidité nocturne.");
                }

                // activer l'effet global pour ce raid
                g.setShopPricesIncreasedThisRaid(true);

                // consommer la carte d'action vampire
                inv.remove(type.name());
                decks.discardVampAction(g, type.name());

                String msg = g.nameOf(playerId)
                        + " joue Avidité nocturne: tous les prix en or à la boutique des chasseurs augmentent de 50 ce raid.";
                g.addHistory(msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("AVIDITE_NOCTURNE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                store.save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                store.afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown action");
        }

        return g;

    }

    public boolean isBlockingAction(Game.Action a) {
        if (a == null)
            return false;
        String m = a.getMode();
        if (m == null)
            return false;

        return switch (m) {
            case "NET",
                    "PIT",
                    "INCENDIAIRE",
                    "EAU_BENITE",
                    "PROVOCATION",
                    "AMBUSH",
                    "MARQUE_TENEBREUSE",
                    "AFFAIBLISSEMENT_OCCULTE",
                    "CLONES_OMBRE",
                    "IMAGE_MIROIR_SETUP",
                    "IMAGE_MIROIR_RESOLVE",
                    "PASSAGE_SECRET",
                    "VOILE_DE_BRUME",
                    "CRATE_LAKE",
                    "CRATE_MANOR",
                    "CATACLYSME" ->
                true;

            default -> false; // FAIM, PRESENCE, ECLIPSE, LONELY, BLOOD_MOON => non bloquantes
        };
    }

    public boolean hasBlockingActionInProgress(Game g) {
        return isBlockingAction(g.getCurrentAction());
    }

    // ACTIONS HUNTER
    /**
     * Applique l'effet des chasseurs Pisteur quand le vampire pose son lieu.
     * - si le chasseur avait déjà joué un lieu (ex : fumigé) :
     * - on lui rend ce lieu dans la main
     * - on consomme le lieu du vampire dans sa main
     * - on met sa carte au centre sur le lieu du vampire
     * - la fumigation reste active (garlicBlockedLocations inchangé)
     */
    public void applyTrackerHuntersWhenVampirePlays(Game g, String vampireLocation) {
        var trackers = g.getTrackerHunters();
        if (trackers == null || trackers.isEmpty())
            return;

        // Copie pour éviter ConcurrentModificationException si on modifie le Set
        var ids = new java.util.ArrayList<>(trackers);

        for (String hunterId : ids) {
            var opt = g.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(hunterId))
                    .findFirst();

            if (opt.isEmpty())
                continue;
            var hp = opt.get();

            // uniquement des chasseurs vivants
            if (!"HUNTER".equals(hp.getRole()) || hp.getHp() <= 0)
                continue;

            // === 1) récupérer l'ancien lieu joué par ce chasseur, s'il existe ===
            String oldLoc = null;
            for (CenterBoard cb : g.getCenter()) {
                if (hunterId.equals(cb.getPlayerId())) {
                    oldLoc = cb.getCard();
                    break;
                }
            }

            // === 2) retirer toutes ses cartes du centre (ancien lieu, etc.) ===
            g.getCenter().removeIf(cb -> hunterId.equals(cb.getPlayerId()));

            // === 3) ajuster SA main ===
            var hand = hp.getHand();
            if (hand == null) {
                hand = new java.util.ArrayList<>();
                hp.setHand(hand);
            }

            // 3.a) si il avait joué un lieu avant (ex : fumigé), on lui rend cette carte
            if (oldLoc != null && !hand.contains(oldLoc)) {
                hand.add(oldLoc);
            }

            // 3.b) on consomme le lieu du vampire dans sa main (si présent)
            // -> s'il ne l'a pas dans sa main (cas bizarre), remove ne fait rien
            hand.remove(vampireLocation);

            // === 4) poser au centre une nouvelle carte sur le lieu du vampire ===
            CenterBoard follow = new CenterBoard(hunterId, vampireLocation, /* faceUp= */false);
            g.getCenter().add(follow);

            // === 5) historique lisible ===
            g.addHistory(g.nameOf(hunterId) +
                    " suit la piste du vampire jusqu’à " + Location.labelFrOf(vampireLocation) + ".");
        }

        // effet consommé pour ce raid
        trackers.clear();
    }

    public void applyFumigationAilPrepare(Game g, Player hunter) {
        if (g.getPendingGarlicPlayers() == null) {
            g.setPendingGarlicPlayers(new java.util.HashSet<>());
        }

        // Empêche de spammer / rejouer : une seule fumigation en attente par raid
        if (g.getPendingGarlicPlayers().contains(hunter.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "tu as déjà préparé une Fumigation d’ail pour ce raid");
        }

        g.getPendingGarlicPlayers().add(hunter.getId());

        // Historique “préparation”
        g.addHistory(g.nameOf(hunter.getId()) +
                " prépare une fumigation d’ail pour son futur lieu.");
    }

    public boolean isCampfireCancellingWeather(@NonNull Game g, @NonNull String location) {
        if (g.getCampfireLocations() == null)
            return false;
        if (!g.getCampfireLocations().contains(location))
            return false;

        WeatherStatus ws = g.getWeatherStatus();
        if (ws == null)
            return false;

        // Carte Feu de camp : ne sert que pour ces 3 états
        return ws == WeatherStatus.DUSK
                || ws == WeatherStatus.NIGHT_DARK
                || ws == WeatherStatus.NIGHT_CLEAR;
    }

    /**
     * Prépare la prochaine action de raid à résoudre en PHASE3 :
     * - Filet
     * - Fosse
     * - Incendiaire
     * Un seul à la fois via currentAction.
     */
    public void prepareNextRaidAction(Game g) {
        // reset par sécurité
        g.setCurrentAction(null);

        // 1) NET en priorité
        if (g.getNetHunters() != null) {
            for (String hunterId : g.getNetHunters()) {
                String loc = g.locationOf(hunterId);
                if (loc == null)
                    continue;

                // Vérifier si ce chasseur a encore des cartes NET à résoudre
                int cardsRemaining = g.getNetCardsRemaining() != null
                        ? g.getNetCardsRemaining().getOrDefault(hunterId, 0)
                        : 0;
                if (cardsRemaining <= 0)
                    continue;

                // cible possible = vampire/serviteur ou monstre sur ce lieu
                if (!hasTrapTargetsOnLocation(g, loc))
                    continue;

                Game.Action a = new Game.Action();
                a.setMode("NET");
                a.setOwnerId(hunterId);
                a.setLocation(loc);
                a.setTargetId(null); // cible pas encore choisie (joueur choisit librement)
                a.setRoll(null); // dé pas encore lancé
                a.setBreakdownLines(java.util.List.of());
                a.setResolvedAtMillis(null);

                g.setCurrentAction(a);
                return; // un seul piège à la fois
            }
        }

        // 2) PIT ensuite
        if (g.getPitHunters() != null && g.getPitTargetsByHunter() != null) {
            // A) D'ABORD : Traiter les cibles JOUEURS (Manuel)
            // On veut que toutes les actions manuelles s'enchaînent avant les actions de
            // groupe (monstres)
            for (String hunterId : g.getPitHunters()) {
                String loc = g.locationOf(hunterId);
                if (loc == null)
                    continue;

                var victims = g.getPitTargetsByHunter().get(hunterId);
                if (victims == null || victims.isEmpty())
                    continue;

                int idx = g.getPitIndexByHunter() != null ? g.getPitIndexByHunter().getOrDefault(hunterId, 0) : 0;

                // Nettoyer les cibles mortes/parties
                boolean indexUpdated = false;
                while (idx < victims.size() && !g.isEntityOn(victims.get(idx), loc)) {
                    idx++;
                    indexUpdated = true;
                }
                if (indexUpdated) {
                    if (g.getPitIndexByHunter() == null)
                        g.setPitIndexByHunter(new java.util.HashMap<>());
                    g.getPitIndexByHunter().put(hunterId, idx);
                }

                if (idx < victims.size()) {
                    String currentVictimId = victims.get(idx);
                    // DEBUG TRACE
                    // System.out.println("PIT_DEBUG: Hunter " + hunterId + " target " +
                    // currentVictimId + " idx " + idx);

                    // Si c'est un monstre, on l'ignore ici (sera traité par le batch ensuite)
                    if (g.findMonster(currentVictimId) != null) {
                        continue;
                    }

                    // C'est un joueur -> Action Manuelle Immédiate
                    Game.Action a = new Game.Action();
                    a.setMode("PIT");
                    a.setOwnerId(hunterId);
                    a.setLocation(loc);
                    a.setTargetId(currentVictimId);
                    a.setRoll(null);
                    a.setBreakdownLines(java.util.List.of());
                    a.setResolvedAtMillis(null);
                    g.setCurrentAction(a);
                    return;
                }
            }

            // B) ENSUITE : Identifier le premier monstre cible pour Batch
            String firstMonsterTarget = null;
            String monsterLocation = null;

            for (String hunterId : g.getPitHunters()) {
                String loc = g.locationOf(hunterId);
                if (loc == null)
                    continue;

                var victims = g.getPitTargetsByHunter().get(hunterId);
                if (victims == null || victims.isEmpty())
                    continue;

                int idx = g.getPitIndexByHunter() != null ? g.getPitIndexByHunter().getOrDefault(hunterId, 0) : 0;

                // Nettoyer les cibles qui ne sont plus là
                while (idx < victims.size()) {
                    String victimId = victims.get(idx);
                    if (!g.isEntityOn(victimId, loc)) {
                        idx++;
                        continue;
                    }

                    // Trouver le premier monstre
                    if (g.findMonster(victimId) != null) {
                        firstMonsterTarget = victimId;
                        monsterLocation = loc;
                        break;
                    } else {
                        // C'est un joueur : on sort et on le traite en manuel
                        break;
                    }
                }

                if (firstMonsterTarget != null)
                    break;
            }

            // Si on a trouvé un monstre, grouper TOUS les monstres de ce lieu et TOUS les
            // chasseurs qui les ciblent
            if (firstMonsterTarget != null) {
                java.util.List<String> breakdownLines = new java.util.ArrayList<>();

                final String finalLoc = monsterLocation;
                // On récupère tous les monstres à cet endroit
                java.util.List<String> monstersAtLoc = g.getMonsters().stream()
                        .filter(m -> finalLoc.equals(m.location))
                        .map(m -> m.id)
                        .collect(java.util.stream.Collectors.toList());

                java.util.Set<String> resolvedMonsterIds = new java.util.HashSet<>();
                boolean anyResolved = false;

                for (String monsterId : monstersAtLoc) {
                    boolean monsterResolvedThisTurn = false;
                    for (String hunterId : new java.util.ArrayList<>(g.getPitHunters())) {
                        String loc = g.locationOf(hunterId);
                        if (!monsterLocation.equals(loc))
                            continue;

                        var victims = g.getPitTargetsByHunter().get(hunterId);
                        if (victims == null)
                            continue;

                        // Vérifier si ce monstre est dans la liste des victimes du chasseur
                        if (victims.contains(monsterId)) {
                            // Résoudre le PIT autant de fois que de cartes jouées par ce chasseur
                            int rollsCount = g.getPitCardsCount() != null
                                    ? g.getPitCardsCount().getOrDefault(hunterId, 0)
                                    : 0;

                            // Si le compteur est 0 mais qu'il y a des hunter/targets, c'est peut-être un
                            // vieux state
                            // on force au moins 1 si la logique précédente n'avait pas le compteur
                            if (rollsCount == 0 && g.getPitHunters().contains(hunterId))
                                rollsCount = 1;

                            for (int k = 0; k < rollsCount; k++) {
                                int roll = dice.roll(20);

                                String msg = g.entityName(monsterId) + " jette un dé pour éviter la Fosse de "
                                        + g.nameOf(hunterId) + " (1d20 = " + roll + "). ";

                                if (roll < 8) {
                                    if (g.getRaidMods() == null)
                                        g.setRaidMods(new java.util.HashMap<>());
                                    g.getRaidMods().computeIfAbsent(monsterId, __ -> new java.util.ArrayList<>())
                                            .add(new StatMod("DEFENSE", -2, "ACTION:PIT"));
                                    msg += "Il tombe dans la fosse : sa défense est réduite de 2.";
                                } else {
                                    msg += "Il évite la fosse.";
                                }
                                g.addHistory(msg);
                                // Format: hunterId:targetId:location:roll
                                breakdownLines.add(hunterId + ":" + monsterId + ":" + loc + ":" + roll);
                            }

                            anyResolved = true;
                            monsterResolvedThisTurn = true;

                            // Note: On ne retire pas le chasseur ici, on le laisse pour les autres monstres
                            // On ne gère pas l'index pour les monstres (car on résout tout d'un coup en
                            // batch)
                        }

                    }
                    if (monsterResolvedThisTurn) {
                        resolvedMonsterIds.add(monsterId);
                    }
                }

                // Ajouter le compte des monstres réellement résolus dans cet écran
                breakdownLines.add(0, "TOTAL_MONSTER_TARGETS:" + resolvedMonsterIds.size());

                // Créer l'action avec tous les résultats groupés
                if (anyResolved) {
                    Game.Action a = new Game.Action();
                    a.setMode("PIT");
                    a.setOwnerId(null); // Pas de owner unique, c'est un groupe
                    a.setLocation(monsterLocation);
                    a.setTargetId(firstMonsterTarget);
                    a.setRoll(1); // Dummy roll pour indiquer que c'est résolu
                    a.setBreakdownLines(breakdownLines);
                    a.setResolvedAtMillis(System.currentTimeMillis());

                    g.setCurrentAction(a);

                    final String finalMonsterId = firstMonsterTarget;
                    final String targetName = g.entityName(firstMonsterTarget);
                    store.afterCommit(() -> {
                        pushLive(g, (breakdownLines.size() - 1) + " fosse(s) résolue(s) contre " + targetName + ".");
                        live.raidModsUpdated(g);
                        live.actionRolled(g, "PIT", null, finalMonsterId, 0);
                    });

                    // Une fois résolu pour ce groupe de monstres, on nettoie les hunters QUI ONT
                    // ETE TRAITES
                    // Mais attention, un hunter peut avoir des cibles sur d'autres monstres ?
                    // En fait la logique ici est "Prepare Next Raid Action".
                    // Si on retourne une action, le frontend va l'afficher et attendre.
                    // Une fois l'action finie (auto close), on reviendra ici.
                    // Il faut donc marquer ces monstres comme "traités" pour ce hunter ?
                    // Ou simplement retirer le hunter de la liste ?
                    //
                    // Problème : on a résolu pour TOUS les monstres du lieu.
                    // Donc pour les hunters présents sur ce lieu, on a traité tous les monstres.
                    // Il reste éventuellement les cibles "Joueurs".
                    //
                    // Simplification : on retire les hunters si on a traité tous leurs monstres ?
                    // Non, on a juste fait un affichage. L'action ne modifie pas l'état
                    // "pitHunters"
                    // pour empêcher de refaire l'action ?
                    // SI ! Il faut que l'action soit consommée.

                    // Pour éviter de re-proposer la même action indéfiniment :
                    // On doit retirer les monstres de la liste des victimes des hunters concernés ?
                    // Ou marquer le hunter comme "a fini ses monstres sur ce lieu" ?

                    // Approche : on retire les monstres résolus des listes de victimes des hunters
                    for (String mId : resolvedMonsterIds) {
                        for (String hId : g.getPitHunters()) {
                            List<String> v = g.getPitTargetsByHunter().get(hId);
                            if (v != null) {
                                v.remove(mId); // On retire le monstre traité
                            }
                        }
                    }
                    // Si un hunter n'a plus de victimes, on le retire
                    g.getPitHunters().removeIf(hId -> {
                        List<String> v = g.getPitTargetsByHunter().get(hId);
                        return v == null || v.isEmpty();
                    });

                    return;
                }
            }

        }

        // 3) Enfin : INCENDIAIRE
        Map<String, String> incendiaires = g.getIncendiaireLocationByHunter();
        if (incendiaires != null && !incendiaires.isEmpty()) {
            // On itère sur une copie pour pouvoir remove dans la map
            for (var entry : new java.util.ArrayList<>(incendiaires.entrySet())) {
                String hunterId = entry.getKey();
                String loc = entry.getValue();
                if (loc == null) {
                    incendiaires.remove(hunterId);
                    continue;
                }

                // Est-ce qu'il reste au moins une infra brûlable sur ce lieu ?
                if (!hasIncendiaireTargetsOnLocation(g, loc)) {
                    String msg = g.nameOf(hunterId)
                            + " avait préparé Incendiaire à " + Location.labelFrOf(loc)
                            + ", mais aucune construction n'est présente: l'effet est perdu.";
                    g.addHistory(msg);
                    incendiaires.remove(hunterId);
                    continue;
                }

                Game.Action a = new Game.Action();
                a.setMode("INCENDIAIRE");
                a.setOwnerId(hunterId);
                a.setLocation(loc);
                a.setTargetId(null); // infra choisie côté front
                a.setRoll(null);
                a.setBreakdownLines(java.util.List.of());
                a.setResolvedAtMillis(null);

                g.setCurrentAction(a);
                return;
            }
        }

        // 4) Si on arrive ici : aucun Filet/Fosse/Incendiaire à résoudre ->
        // currentAction reste null
    }

    public boolean hasIncendiaireTargetsOnLocation(Game g, String loc) {
        if (loc == null)
            return false;

        java.util.EnumSet<Infra> built = (g.getBuiltInfras() != null)
                ? g.getBuiltInfras()
                : java.util.EnumSet.noneOf(Infra.class);

        Game.PendingConstruction pc = g.getPendingConstruction();
        Infra pending = (pc != null ? pc.infra : null);

        java.util.function.Predicate<Infra> available = infra -> built.contains(infra) || (pending == infra);

        switch (loc) {
            case "forest":
            case "sawmill":
                return available.test(Infra.SAWMILL);

            case "quarry":
            case "mine":
                return available.test(Infra.MINE);

            case "manor":
                return available.test(Infra.LIBRARY)
                        || available.test(Infra.LABORATORY)
                        || available.test(Infra.BALLROOM)
                        || available.test(Infra.ALTAR)
                        || available.test(Infra.FORGE);

            case "library":
                return available.test(Infra.LIBRARY);

            case "laboratory":
                return available.test(Infra.LABORATORY);

            case "ballroom":
                return available.test(Infra.BALLROOM);

            case "altar":
                return available.test(Infra.ALTAR);

            case "forge":
                return available.test(Infra.FORGE);

            default:
                // lac ou autre
                return false;
        }
    }

    public boolean hasUsableHolyWaterForPlayer(@NonNull Game g, @NonNull Player p) {
        // 1) Il lui faut la carte
        List<String> acts = p.getActions();
        if (acts == null || !acts.contains(Action.EAU_BENITE.name())) {
            return false;
        }

        // 2) Rôle + phase
        if (!"HUNTER".equals(p.getRole()))
            return false;
        if (g.getPhase() != Phase.PREPHASE3)
            return false;

        // 3) Lieu du chasseur
        String loc = g.locationOf(p.getId());
        if (loc == null)
            return false;

        // 4) Présence écrasante peut bloquer les actions des chasseurs sur le même lieu
        // que le vampire
        if (g.isHunterActionsBlockedThisRaid()) {
            Player vamp = g.getPlayers().stream()
                    .filter(pl -> "VAMPIRE".equals(pl.getRole()))
                    .findFirst()
                    .orElse(null);
            if (vamp != null) {
                String vampLoc = g.locationOf(vamp.getId());
                if (vampLoc != null && vampLoc.equals(loc)) {
                    // Bloqué sur ce lieu
                    return false;
                }
            }
        }

        // 5) Y a-t-il au moins UNE cible valide sur ce lieu ?
        // (ici : un chasseur vivant avec corruption > 0 ; tu peux étendre à "marqué" si
        // tu as un helper)
        var groups = g.playersByLocation(); // loc -> List<Player>
        var onLoc = groups.get(loc);
        if (onLoc == null || onLoc.isEmpty())
            return false;

        for (Player target : onLoc) {
            if (!"HUNTER".equals(target.getRole()))
                continue;
            if (target.getHp() <= 0)
                continue;

            Integer corr = target.getCorruption();
            if (corr != null && corr > 0) {
                return true;
            }

            // Si tu as un helper du genre hasDarkMarkThisRaid(g, targetId), tu peux ajouter
            // :
            // if (hasDarkMarkThisRaid(g, target.getId())) return true;
        }

        return false;
    }

    public boolean hasUsableSecretPassageOption(Game g) {
        for (Player p : g.getPlayers()) {
            if (!"VAMPIRE".equals(p.getRole()))
                continue;

            List<String> actions = p.getActions();
            if (actions == null || !actions.contains(Action.PASSAGE_SECRET.name()))
                continue;

            String loc = g.locationOf(p.getId());
            // Passage secret n’est jouable que si le vampire est au Manoir
            if ("manor".equals(loc)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasTrapTargetsOnLocation(Game g, String loc) {
        // Joueurs côté vampire/serviteurs déjà gérés par ta fonction existante
        var enemies = g.vampSideOn(loc);
        boolean hasPlayers = enemies != null && !enemies.isEmpty();

        boolean hasMonsters = g.getMonsters() != null
                && g.getMonsters().stream().anyMatch(m -> loc.equals(m.location));

        return hasPlayers || hasMonsters;
    }

    public java.util.List<String> cancelHunterPrephase3ActionsOnVampLoc(Game g, String vampLoc) {

        int cancelledCampfires = 0;
        int cancelledNets = 0;
        int cancelledPits = 0;
        int cancelledAmbush = 0;
        int cancelledLonely = 0;
        int cancelledIncendiaires = 0;
        int cancelledProvocations = 0;
        int cancelledBlessedStakes = 0;
        int cancelledSacredRosaries = 0;

        // ---------- Feux de camp ----------
        if (g.getCampfireLocations() != null) {
            for (String loc : g.getCampfireLocations()) {
                if (java.util.Objects.equals(loc, vampLoc)) {
                    cancelledCampfires++;
                }
            }
            // On supprime uniquement ceux sur ce lieu
            g.getCampfireLocations().removeIf(loc -> java.util.Objects.equals(loc, vampLoc));
        }

        // ---------- Filets ----------
        if (g.getNetHunters() != null && !g.getNetHunters().isEmpty()) {
            var it = g.getNetHunters().iterator();
            while (it.hasNext()) {
                String hunterId = it.next();
                String hLoc = g.locationOf(hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledNets++;
                    it.remove();
                }
            }
        }

        // ---------- Fosses + maps associées ----------
        if (g.getPitHunters() != null && !g.getPitHunters().isEmpty()) {
            var it = g.getPitHunters().iterator();
            while (it.hasNext()) {
                String hunterId = it.next();
                String hLoc = g.locationOf(hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledPits++;
                    it.remove();
                    if (g.getPitTargetsByHunter() != null) {
                        g.getPitTargetsByHunter().remove(hunterId);
                    }
                    if (g.getPitIndexByHunter() != null) {
                        g.getPitIndexByHunter().remove(hunterId);
                    }
                }
            }
        }

        // ---------- Provocation ----------
        // Map<enemyId, hunterIdProvocateur>
        if (g.getProvokedTargetByEnemy() != null && !g.getProvokedTargetByEnemy().isEmpty()) {
            var it = g.getProvokedTargetByEnemy().entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                String hunterId = e.getValue();
                String hLoc = g.locationOf(hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledProvocations++;
                    it.remove();
                }
            }
        }

        // ---------- Embuscade ----------
        // Map<enemyId, List<hunterId>> : on enlève les chasseurs embusqués sur ce lieu
        if (g.getAmbushHuntersByEnemy() != null && !g.getAmbushHuntersByEnemy().isEmpty()) {
            var it = g.getAmbushHuntersByEnemy().entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                java.util.List<String> hunters = e.getValue();
                hunters.removeIf(hId -> java.util.Objects.equals(g.locationOf(hId), vampLoc));
                if (hunters.isEmpty()) {
                    cancelledAmbush++;
                    it.remove();
                }
            }
        }

        // ---------- Incendiaire ----------
        // Map<hunterId, locCible> : on annule seulement les chasseurs sur ce lieu
        if (g.getIncendiaireLocationByHunter() != null
                && !g.getIncendiaireLocationByHunter().isEmpty()) {

            var it = g.getIncendiaireLocationByHunter().entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                String hunterId = e.getKey();
                String hLoc = g.locationOf(hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledIncendiaires++;
                    it.remove();
                }
            }
        }

        // ---------- Pieu béni / Chapelet sacré / Solitaire (flags + mods) ----------
        if (g.getPlayers() != null && vampLoc != null) {
            for (Player player : g.getPlayers()) {
                if (!"HUNTER".equals(player.getRole()))
                    continue;

                String hunterLoc = g.locationOf(player.getId());
                if (!vampLoc.equals(hunterLoc))
                    continue;

                // --- Pieu béni ---
                if (player.isBlessedStake()) {
                    cancelledBlessedStakes++;
                    player.setBlessedStake(false);

                    if (g.getRaidMods() != null) {
                        var mods = g.getRaidMods().get(player.getId());
                        if (mods != null) {
                            mods.removeIf(m -> {
                                String s = m.getSource();
                                return s != null && s.startsWith("ACTION:BLESSED_STAKE");
                            });
                        }
                    }
                }

                // --- Chapelet sacré ---
                if (player.isSacredRosary()) {
                    cancelledSacredRosaries++;
                    player.setSacredRosary(false);

                    if (g.getRaidMods() != null) {
                        var mods = g.getRaidMods().get(player.getId());
                        if (mods != null) {
                            mods.removeIf(m -> {
                                String s = m.getSource();
                                return s != null && s.startsWith("ACTION:SACRED_ROSARY");
                            });
                        }
                    }
                }

                // ---------- Lonely ----------
                if (g.getRaidMods() != null) {
                    var mods = g.getRaidMods().get(player.getId());
                    if (mods != null) {
                        boolean removed = mods.removeIf(m -> {
                            String s = m.getSource();
                            return s != null && s.startsWith("ACTION:LONELY");
                        });
                        if (removed)
                            cancelledLonely++;
                    }
                }
            }
        }

        // ---------- currentAction de chasseur sur ce lieu ----------
        Game.Action ca = g.getCurrentAction();
        if (ca != null && ("NET".equals(ca.getMode())
                || "PIT".equals(ca.getMode())
                || "INCENDIAIRE".equals(ca.getMode())
                || "PROVOCATION".equals(ca.getMode())
                || "AMBUSH".equals(ca.getMode())
                || "LONELY".equals(ca.getMode())
                || "EAU_BENITE".equals(ca.getMode())
                || "BLESSED_STAKE".equals(ca.getMode()))) {

            boolean sameLoc = java.util.Objects.equals(ca.getLocation(), vampLoc);
            boolean ownerOnLoc = ca.getOwnerId() != null
                    && java.util.Objects.equals(g.locationOf(ca.getOwnerId()), vampLoc);

            if (sameLoc || ownerOnLoc) {
                g.setCurrentAction(null);
            }
        }

        // ---------- parts (pour le message) ----------
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (cancelledCampfires > 0)
            parts.add(cancelledCampfires + " Feu de camp");
        if (cancelledNets > 0)
            parts.add(cancelledNets + " Filet");
        if (cancelledPits > 0)
            parts.add(cancelledPits + " Fosse");
        if (cancelledAmbush > 0)
            parts.add(cancelledAmbush + " Embuscade");
        if (cancelledLonely > 0)
            parts.add(cancelledLonely + " Solitaire");
        if (cancelledIncendiaires > 0)
            parts.add(cancelledIncendiaires + " Incendiaire");
        if (cancelledProvocations > 0)
            parts.add(cancelledProvocations + " Provocation");
        if (cancelledBlessedStakes > 0)
            parts.add(cancelledBlessedStakes + " Épieu béni");
        if (cancelledSacredRosaries > 0)
            parts.add(cancelledSacredRosaries + " Chapelet sacré");

        return parts;
    }

}
