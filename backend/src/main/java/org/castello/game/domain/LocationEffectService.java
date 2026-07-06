package org.castello.game.domain;

import org.castello.game.CenterBoard;
import org.castello.game.Game;
import org.castello.game.Infra;
import org.castello.game.Location;
import org.castello.game.LocationEffectChoice;
import org.castello.game.Phase;
import org.castello.game.RoundFight;
import org.castello.game.GameStatus;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.game.support.RaidFlow;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Domaine Effets de lieux (bâtiments du Manoir).
 *
 * En fin de préphase, une file d'effets est construite pour chaque joueur
 * posé sur un bâtiment construit (Bibliothèque, Laboratoire, Salle de bal,
 * Autel, Forge) et sans combat sur place. Chaque effet est résolu tour à
 * tour (choix + résolution interactive), puis le flux enchaîne via
 * RaidFlow.scheduleNextLocationEffect.
 */
@Service
public class LocationEffectService {

    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final EquipmentService equipment;
    private final HarvestService harvest;
    private final LiveEvents live;
    private final RaidFlow flow;
    private final CorruptionService corruption;

    public LocationEffectService(GameStore store, Dice dice, DeckService decks,
            EquipmentService equipment, HarvestService harvest, LiveEvents live,
            CorruptionService corruption,
            @Lazy RaidFlow flow) {
        this.corruption = corruption;
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.equipment = equipment;
        this.harvest = harvest;
        this.live = live;
        this.flow = flow;
    }

    private String monsterLabel(Game.MonsterType type) {
        return type.labelFr();
    }

    public void maybeStartLocationEffects(Game g, String gameId) {
        // 1) Si un effet de lieu est DÉJÀ en cours de résolution (pending=true),
        // on ne touche à rien (c'est le process normal qui enchaine).
        if (Boolean.TRUE.equals(g.getLocationEffectPending())
                && g.getLocationEffectsQueue() != null
                && !g.getLocationEffectsQueue().isEmpty()) {
            return;
        }

        // Sinon (pas pending), on part du principe qu'on doit (re)lancer la séquence.
        // On nettoie toute queue existante potentiellement périmée ou incomplète
        // pour forcer un rebuild propre.
        if (g.getLocationEffectsQueue() != null) {
            g.getLocationEffectsQueue().clear();
        }

        // 2) (Re)construire la file d'effets à partir de l'état FINAL de PREPHASE3
        buildLocationEffectsQueue(g);

        if (g.getLocationEffectsQueue() != null && !g.getLocationEffectsQueue().isEmpty()) {
            // Il y a au moins un effet de lieu à résoudre → on reste en PREPHASE3
            g.setCurrentLocationEffectIndex(0);
            g.setLocationEffectPending(true);
            g.setLocationEffectChoice(null);

            store.save(g);

            store.afterCommit(() -> {
                Game fresh = store.read(gameId);

                Game.LocationEffectInstance inst = null;
                if (fresh.getLocationEffectsQueue() != null
                        && fresh.getCurrentLocationEffectIndex() != null) {
                    int idx = fresh.getCurrentLocationEffectIndex();
                    if (idx >= 0 && idx < fresh.getLocationEffectsQueue().size()) {
                        inst = fresh.getLocationEffectsQueue().get(idx);
                    }
                }

                if (inst != null) {
                    live.locationEffectStarted(fresh, inst);
                }
                live.phaseChanged(fresh);
            });
        } else {
            // 3) Aucun effet de lieu → PHASE3 classique
            g.setPhase(Phase.PHASE3);
            flow.applyPhaseEntry(g, Phase.PHASE3);

            store.save(g);

            store.afterCommit(() -> {
                Game fresh = store.read(gameId);
                live.phaseChanged(fresh);
            });
        }
    }

    /**
     * (Re)construit la file des effets de lieu pour le raid courant.
     *
     * - Réinitialise la structure (queue, index courant, flags).
     * - Ajoute une entrée par joueur éligible pour chaque lieu à effet
     * (actuellement LIBRARY et LABORATORY).
     * - Utilise l'état final de PREPHASE3 (positions, instables, combats)
     * pour décider qui a droit à un effet.
     */
    private void buildLocationEffectsQueue(Game g) {
        // Réinit de la structure
        if (g.getLocationEffectsQueue() == null) {
            g.setLocationEffectsQueue(new java.util.ArrayList<>());
        } else {
            g.getLocationEffectsQueue().clear();
        }
        g.setCurrentLocationEffectIndex(null);
        g.setLocationEffectPending(false);
        g.setLocationEffectChoice(null);

        // Si aucune infra à effet n'est construite, rien à faire.
        if (g.getBuiltInfras() == null || g.getBuiltInfras().isEmpty()) {
            return;
        }

        boolean hasLibrary = g.getBuiltInfras().contains(Infra.LIBRARY);
        boolean hasLaboratory = g.getBuiltInfras().contains(Infra.LABORATORY);
        boolean hasBallroom = g.getBuiltInfras().contains(Infra.BALLROOM);
        boolean hasAltar = g.getBuiltInfras().contains(Infra.ALTAR);
        boolean hasForge = g.getBuiltInfras().contains(Infra.FORGE);

        if (!hasLibrary && !hasLaboratory && !hasBallroom && !hasAltar && !hasForge) {
            return; // aucune infra à effet
        }

        // Positions finales des joueurs par lieu (après instables)
        var groups = g.playersByLocation();

        // Instables déjà réaffectés (attaque OU récolte)
        var unstableAssigned = new java.util.HashSet<String>();
        if (g.getUnstableTargetByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableTargetByPlayer().keySet());
        }
        if (g.getUnstableHarvestLocByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableHarvestLocByPlayer().keySet());
        }

        // Joueurs impliqués dans des duels instables par lieu
        java.util.Map<String, java.util.Set<String>> unstableCombatPlayersByLoc = new java.util.HashMap<>();
        if (g.getUnstableTargetByPlayer() != null) {
            for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
                String unstableId = entry.getKey();
                String targetId = entry.getValue();

                String loc = g.getCenter().stream()
                        .filter(cb -> cb.getPlayerId().equals(targetId))
                        .map(CenterBoard::getCard)
                        .findFirst()
                        .orElse(null);
                if (loc == null)
                    continue;

                var set = unstableCombatPlayersByLoc
                        .computeIfAbsent(loc, __ -> new java.util.HashSet<>());
                set.add(unstableId);
                set.add(targetId);
            }
        }

        // --- Construction de la file pour chaque joueur qui a joué "library" ---
        if (hasLibrary) {
            // Code de la carte associée à LIBRARY (normalement "library")
            String libraryCardCode = Infra.LIBRARY.locationCode();

            // Tous les joueurs physiquement sur "library"
            var onLibrary = groups.getOrDefault(libraryCardCode, java.util.List.<Player>of());

            // Ennemis (VAMPIRE/SERVANT) présents
            var enemiesOnLibrary = onLibrary.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            // Chasseurs "par défaut" (comme dans buildCombatsQueue)
            var huntersDefaultOnLibrary = onLibrary.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            // Monstres vivants sur ce lieu
            var monstersOnLibrary = g.monstersOn(libraryCardCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            // Joueurs impliqués dans des duels instables SUR "library"
            java.util.Set<String> unstableCombatOnLibrary = unstableCombatPlayersByLoc.getOrDefault(libraryCardCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!libraryCardCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);
                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                // Vampire : garde toujours l'effet de lieu
                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    // 1) Duel instable sur library ?
                    if (unstableCombatOnLibrary.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        // 2) Combats classiques comme dans buildCombatsQueue

                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnLibrary.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnLibrary.isEmpty() || !monstersOnLibrary.isEmpty())) {
                                // chasseur vs (vampire/serviteur ou monstre)
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnLibrary.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId())); // il est dans enemiesOnLibrary
                            if (isServantEnemy && !huntersDefaultOnLibrary.isEmpty()) {
                                // serviteur vs chasseur
                                willFightHere = true;
                            }
                        }
                    }

                    // Chasseur / Serviteur qui va combattre sur la Bibliothèque → pas d'effet
                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.LIBRARY;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }

            // --- Sécurité : s'assurer que le vampire a bien une entrée s'il a joué
            // Bibliothèque ---
            var vampOpt = g.vampire();
            if (vampOpt.isPresent()) {
                var vamp = vampOpt.get();

                boolean vampPlayedLibrary = g.getCenter().stream()
                        .anyMatch(cb -> libraryCardCode.equals(cb.getCard())
                                && vamp.getId().equals(cb.getPlayerId()));

                if (vampPlayedLibrary) {
                    boolean alreadyQueued = g.getLocationEffectsQueue().stream()
                            .anyMatch(inst -> inst.infra == Infra.LIBRARY
                                    && vamp.getId().equals(inst.ownerId));
                    if (!alreadyQueued) {
                        Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                        inst.ownerId = vamp.getId();
                        inst.infra = Infra.LIBRARY;
                        inst.choice = null;
                        g.getLocationEffectsQueue().add(inst);
                    }
                }
            }
        }

        // --- Effets du Laboratoire occulte ---
        if (hasLaboratory) {
            String labCardCode = Infra.LABORATORY.locationCode(); // ex : "laboratory"

            // Tous les joueurs physiquement sur "laboratory"
            var onLab = groups.getOrDefault(labCardCode, java.util.List.<Player>of());

            var enemiesOnLab = onLab.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnLab = onLab.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnLab = g.monstersOn(labCardCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnLab = unstableCombatPlayersByLoc.getOrDefault(labCardCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!labCardCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnLab.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnLab.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnLab.isEmpty() || !monstersOnLab.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnLab.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnLab.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId(); // VAMPIRE, HUNTER ou SERVANT
                inst.infra = Infra.LABORATORY;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }

        // --- Effets de la Salle de bal (BALLROOM) ---
        if (hasBallroom) {
            String ballroomCode = Infra.BALLROOM.locationCode(); // ex: "ballroom"

            var onBallroom = groups.getOrDefault(ballroomCode, java.util.List.<Player>of());

            var enemiesOnBallroom = onBallroom.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnBallroom = onBallroom.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnBallroom = g.monstersOn(ballroomCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnBallroom = unstableCombatPlayersByLoc.getOrDefault(ballroomCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!ballroomCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnBallroom.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnBallroom.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnBallroom.isEmpty() || !monstersOnBallroom.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnBallroom.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnBallroom.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.BALLROOM;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }

        // --- Effets de autel ---
        if (hasAltar) {
            String altarCode = Infra.ALTAR.locationCode();

            var onAltar = groups.getOrDefault(altarCode, java.util.List.<Player>of());

            var enemiesOnAltar = onAltar.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnAltar = onAltar.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnAltar = g.monstersOn(altarCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnAltar = unstableCombatPlayersByLoc.getOrDefault(altarCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!altarCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet de lieu
                // Sur l'autel : les serviteurs n'ont JAMAIS accès à l'effet de lieu
                if ("SERVANT".equals(owner.getRole())) {
                    continue;
                }

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnAltar.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnAltar.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnAltar.isEmpty() || !monstersOnAltar.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnAltar.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnAltar.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.ALTAR;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }

        // --- Effets de la Forge ---
        if (hasForge) {
            String forgeCode = Infra.FORGE.locationCode(); // ex : "forge"

            var onForge = groups.getOrDefault(forgeCode, java.util.List.<Player>of());

            var enemiesOnForge = onForge.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnForge = onForge.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnForge = g.monstersOn(forgeCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnForge = unstableCombatPlayersByLoc.getOrDefault(forgeCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!forgeCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                // Règle générique : chasseur/serviteur qui VA combattre sur ce lieu → pas
                // d'effet
                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnForge.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnForge.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnForge.isEmpty() || !monstersOnForge.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnForge.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnForge.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                // Règle spécifique Forge : si le joueur ne peut rien forger (aucun choix
                // dispo),
                // on NE l'ajoute PAS à la queue d'effets.
                if (!equipment.canForgeAnything(g, owner)) {
                    continue;
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.FORGE;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }
    }

    /**
     * Traite le choix d’effet de lieu pour l’instance en cours.
     *
     * - Vérifie que l’on est bien en PREPHASE3 et sur un effet en attente.
     * - Contrôle que le joueur appelant est le propriétaire de l’effet.
     * - Applique ou prépare l’effet selon le choix (STUDY / THEFT / OMEN, pour
     * LIBRARY).
     * - Marque l’effet comme interactif si une étape supplémentaire est nécessaire
     * (THEFT / OMEN), sinon enchaîne directement sur l’effet suivant.
     */
    @Transactional
    public Game chooseLocationEffect(String gameId, String playerId, LocationEffectChoice choice) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "location effect only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce n'est pas votre effet de lieu.");
        }

        var p = g.findPlayer(playerId);

        // Flag générique : est-ce qu'on DOIT attendre une étape supplémentaire
        // avant de passer à l'effet suivant ?
        boolean waitForExtraResolution = false;

        // --- Branche selon l'infrastructure concernée ---
        if (inst.infra == Infra.LIBRARY) {
            // Pour l’instant: seulement LIBRARY gérée, avec les 3 effets.
            switch (choice) {
                case STUDY -> {
                    // effet immédiat
                    applyLibraryStudyEffect(g, p);
                }
                case THEFT -> {
                    // THEFT interactif : on vérifie d'abord s'il y a au moins une cible possible
                    if (!canUseLibraryTheft(g, p)) {
                        g.addHistory("Bibliothèque — " + g.nameOf(p.getId())
                                + " tente de subtiliser un manuscrit, mais aucun adversaire ne possède de carte Action.");
                        // pas d'étape supplémentaire → on laissera enchaîner la file
                    } else {
                        // On va ouvrir la modale d'action côté front
                        waitForExtraResolution = true;
                    }
                }
                case OMEN -> {
                    // 1) Vérif serveur : deck adverse doit avoir > 3 cartes (en tenant compte du
                    // reshuffle)
                    if (!canUseLibraryOmen(g, p)) {
                        // On NE throw plus : on log et on consomme l'effet
                        g.addHistory("Bibliothèque — " + g.nameOf(p.getId())
                                + " ne peut pas utiliser la Prédiction occulte "
                                + "(la pioche adverse contient 3 cartes ou moins).");
                        // pas d'étape supplémentaire → waitForExtraResolution reste false
                    } else {
                        // 2) Prépare les 3 cartes dans libraryOmenState (sans avancer la file)
                        int drawn = applyLibraryOmenEffect(g, p);

                        if (drawn > 0) {
                            // On a bien des cartes en attente → on attend /effect-omen
                            waitForExtraResolution = true;
                        } else {
                            // Cas très rare (course condition) : on n'a finalement rien pu préparer
                            g.addHistory("Bibliothèque — " + g.nameOf(p.getId())
                                    + " tente une Prédiction occulte, mais aucune carte n'a pu être préparée.");
                        }
                    }
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid library choice");
            }
        } else if (inst.infra == Infra.LABORATORY) {
            switch (choice) {
                case EXPERIMENT -> {
                    if (!"VAMPIRE".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seul le vampire peut utiliser le Laboratoire occulte.");
                    }

                    // Vérif "est-ce qu'au moins une expérience est possible ?"
                    if (!canUseLaboratoryExperiment(g, p)) {
                        // IMPORTANT : on NE throw plus, on log et on consomme l'effet
                        g.addHistory("Laboratoire occulte — " + g.nameOf(p.getId())
                                + " voudrait expérimenter, mais il n'a pas assez d'âmes pour invoquer une créature.");
                        // pas d'étape supplémentaire → waitForExtraResolution reste false
                    } else {
                        g.setLaboratoryDraftMonsterType(null);
                        g.setLaboratoryDraftLocation(null);

                        // Ici on NE crée PAS encore le monstre : on attend le POST /effect-experiment
                        waitForExtraResolution = true;
                    }
                }
                case ALCHEMY -> {
                    applyLaboratoryAlchemyEffect(g, p);
                }

                case RARE_ALCHEMY -> {
                    applyLaboratoryRareAlchemyEffect(g, p);
                }
                case EXPLOSION -> {
                    if (!"HUNTER".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seul un chasseur peut tenter de faire exploser le Laboratoire.");
                    }

                    // On prépare une résolution interactive (jet requis)
                    // (pas obligatoire, mais utile pour UI/anti double roll)
                    g.setLaboratoryExplosionRoll(null);

                    // IMPORTANT : on n'applique pas l'effet ici
                    // Et on n'avance pas la file d'effets ici : on attend
                    // resolveLaboratoryExplosion()
                    waitForExtraResolution = true;

                    g.addHistory("Laboratoire occulte — " + g.nameOf(playerId)
                            + " tente de déclencher une explosion alchimique… (jet de d20 requis).");
                }
            }
        } else if (inst.infra == Infra.BALLROOM) {
            switch (choice) {
                case DEATH_DANCE -> {
                    if (!"VAMPIRE".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seul le vampire peut utiliser la Salle de bal pour la Danse macabre.");
                    }

                    g.setBallroomDeathDance(true);

                    g.addHistory("Salle de bal — " + g.nameOf(p.getId())
                            + " déclenche la Danse macabre : chaque attaque réussie "
                            + "contre un chasseur sur ce lieu lui infligera aussi 1 point de corruption.");
                }
                case SNEAK_ATTACK -> {
                    g.setBallroomSneakAttack(true);
                    g.addHistory("Salle de bal — "
                            + g.nameOf(p.getId())
                            + " se prépare pour une attaque sournoise.");
                }
                case BLOOD_WALTZ -> {
                    g.setBallroomBloodWaltz(true);
                    g.setBallroomBloodWaltzBestRoll(null);
                    g.setBallroomBloodWaltzRolls(new java.util.ArrayList<>());

                    g.addHistory("Salle de bal — "
                            + g.nameOf(p.getId())
                            + " prépare une Valse sanguinaire pour ce raid.");
                }
                case LOOTING -> {
                    if (!"HUNTER".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seuls les chasseurs peuvent utiliser l'effet Pillage.");
                    }

                    resolveHunterPillage(g, inst);
                }

                default -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "invalid ballroom choice");
            }
        } else if (inst.infra == Infra.ALTAR) {
            boolean isVamp = "VAMPIRE".equals(p.getRole());
            boolean isHunter = "HUNTER".equals(p.getRole());

            boolean corrupted = isAltarCorrupted(g);

            if (!corrupted) {
                // === ÉTAT pur ===
                switch (choice) {
                    case HEAL -> {
                        if (!isHunter) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul un chasseur peut utiliser l'autel pour apaiser la corruption.");
                        }

                        if (!canUseAltarHeal(g, p)) {
                            g.addHistory("Autel — "
                                    + g.nameOf(p.getId())
                                    + " voudrait apaiser la corruption d'un chasseur, "
                                    + "mais aucun chasseur n'est corrompu.");
                            // pas d'étape interactive
                        } else {
                            // on attend /resolve-altar-heal pour choisir la cible
                            waitForExtraResolution = true;
                        }
                    }

                    case CORRUPT_SOULS -> {
                        if (!isVamp) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul le vampire peut corrompre l'autel avec des âmes.");
                        }

                        applyAltarCorruptWithSouls(g, p);
                    }

                    default -> throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "invalid altar choice in altar state");
                }
            } else {
                // === ÉTAT corrompu ===
                switch (choice) {
                    case CORRUPT -> {
                        if (!isVamp) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul le vampire peut exploiter l'autel pour corrompre un chasseur.");
                        }

                        if (!canUseAltarCorrupt(g, p)) {
                            g.addHistory("Autel sanglant — "
                                    + g.nameOf(p.getId())
                                    + " voudrait corrompre un chasseur, mais aucune cible valide n'est présente.");
                            // pas d'étape interactive
                        } else {
                            // étape interactive : choisir quel chasseur gagne +1 corruption
                            waitForExtraResolution = true;
                        }
                    }

                    case PURIFY_WATER -> {
                        if (!isHunter) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul un chasseur peut purifier l'autel avec de l'eau bénite.");
                        }

                        applyAltarPurifyWithHolyWater(g, p);
                    }

                    default -> throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "invalid sanctualtar choice in altar state");
                }
            }
        } else if (inst.infra == Infra.FORGE) {
            // Pour l’instant : un seul "type" de choix au niveau de l’infra :
            // utiliser la Forge. Le détail (arme/armure/quel item) sera dans un
            // endpoint dédié, comme pour EXPERIMENT ou HEAL.

            if (choice != LocationEffectChoice.FORGE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid forge choice");
            }

            if (!equipment.canForgeAnything(g, p)) {
                g.addHistory("Forge — " + g.nameOf(p.getId())
                        + " voudrait fabriquer un équipement, mais il n'a pas assez de ressources.");
                // Pas d'étape interactive, on avance simplement la file
                // (waitForExtraResolution reste false).
            } else {
                // Étape interactive : le front affichera la liste des équipements permis
                // en se basant sur gameSnapshot (weapon/armor, ressources, etc.)
                // puis appellera un endpoint /effect-forge avec le code choisi.
                waitForExtraResolution = true;
            }
        } else {
            // S'il y a d'autres infras plus tard, tu pourras les gérer ici
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "unknown location effect infra: " + inst.infra);
        }

        // On mémorise le choix pour l'effet courant
        inst.choice = choice;
        g.setLocationEffectChoice(choice); // pour affichage côté front

        store.save(g);

        final LocationEffectChoice fChoice = choice;
        final boolean fWaitExtra = waitForExtraResolution;

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Notifie le front que l'effet a été choisi (ouvre la modale correspondante)
            live.locationEffectUsed(fresh, fChoice, freshOwner);

            // STUDY = immédiat ; THEFT/OMEN/EXPERIMENT interactifs → on attend la
            // résolution dédiée
            if (!fWaitExtra) {
                flow.scheduleNextLocationEffect(fresh.getId());
            }
        });

        return g;
    }

    /**
     * Effet de Bibliothèque : STUDY.
     *
     * Fait piocher 1 carte Action au joueur (dans son deck de camp)
     * et l’ajoute à sa main, si possible. Ajoute aussi un message dans
     * l’historique.
     */
    private void applyLibraryStudyEffect(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());

        String cardId;
        if (isVamp) {
            cardId = decks.drawVampAction(g);
        } else {
            cardId = decks.drawHunterAction(g);
        }

        if (cardId == null) {
            g.addHistory("Bibliothèque — " + g.nameOf(user.getId())
                    + " n'a pas pu piocher (pioche Action vide).");
            return;
        }

        if (user.getActions() == null) {
            user.setActions(new java.util.ArrayList<>());
        }
        user.getActions().add(cardId);

        String who = g.nameOf(user.getId());
        g.addHistory("Bibliothèque — " + who
                + " étudie les grimoires et pioche 1 carte Action.");
    }

    /**
     * Effet de Bibliothèque : THEFT — application concrète du vol.
     *
     * - Retire une carte Action dans la main de la cible (slotIndex).
     * - Replace cette carte dans le deck d’Actions du camp de la cible
     * (côté adverse du lanceur), puis mélange le deck.
     * - Log l’action dans l’historique.
     *
     * Les validations (cible, index, rôles, phase…) sont supposées faites en amont.
     */
    private void applyLibraryTheftEffect(Game g, Player owner, Player target, int slotIndex) {
        var hand = target.getActions();
        if (hand == null || hand.isEmpty()) {
            throw new IllegalStateException("target has no action cards");
        }
        if (slotIndex < 0 || slotIndex >= hand.size()) {
            throw new IllegalStateException("invalid slot index");
        }

        // 1) On enlève la carte choisie
        String stolen = hand.remove(slotIndex);

        // 2) On remet la carte dans le DECK de la cible (camp adverse)
        List<String> deck;
        boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

        if (ownerIsVamp) {
            // Vampire vole une carte à un chasseur → carte remise dans le deck Actions
            // CHASSEURS
            deck = g.getHunterActionsDeck();
            if (deck == null) {
                deck = new java.util.ArrayList<>();
                g.setHunterActionsDeck(deck);
            }
        } else {
            // Chasseur vole une carte au vampire → carte remise dans le deck Actions
            // VAMPIRE
            deck = g.getVampActionsDeck();
            if (deck == null) {
                deck = new java.util.ArrayList<>();
                g.setVampActionsDeck(deck);
            }
        }

        deck.add(stolen);
        dice.shuffle(deck);

        // 3) Historique
        String who = g.nameOf(owner.getId());
        String who2 = g.nameOf(target.getId());
        g.addHistory("Bibliothèque — " + who
                + " subtilise un manuscrit à " + who2
                + " et le replace dans la pioche Action.");
    }

    private boolean canUseLibraryTheft(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());
        boolean isHunter = "HUNTER".equals(user.getRole());

        if (isVamp) {
            // Au moins un chasseur vivant avec ≥1 carte Action
            return g.getPlayers().stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .anyMatch(p -> p.getActions() != null && !p.getActions().isEmpty());
        }

        if (isHunter) {
            // Cible unique = vampire
            var vampOpt = g.vampire();
            if (vampOpt.isEmpty())
                return false;
            Player vamp = vampOpt.get();
            if (vamp.getHp() <= 0)
                return false;
            return vamp.getActions() != null && !vamp.getActions().isEmpty();
        }

        return false;
    }

    /**
     * Résout l’effet interactif de Bibliothèque THEFT côté serveur.
     *
     * - Valide le contexte (phase, effet en cours, propriétaire, cible, index).
     * - Applique le vol via applyLibraryTheftEffect().
     * - Sauvegarde la partie, notifie le front et enchaîne sur
     * le prochain effet de lieu via flow.scheduleNextLocationEffect().
     */
    @Transactional
    public Game resolveLibraryTheft(String gameId, String playerId, String targetId, int slotIndex) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "theft only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        // On ne résout que THEFT (Bibliothèque)
        if (inst.infra != Infra.LIBRARY || inst.choice != LocationEffectChoice.THEFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no theft effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your theft effect");
        }

        var owner = g.findPlayer(playerId);
        var target = g.findPlayer(targetId);

        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid target");
        }

        boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());
        boolean ownerIsHunter = "HUNTER".equals(owner.getRole());

        if (!ownerIsVamp && !ownerIsHunter) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid role for theft");
        }

        if (ownerIsVamp && !"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "vampire must target a hunter");
        }
        if (ownerIsHunter && !"VAMPIRE".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "hunter must target the vampire");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target is dead");
        }

        var hand = target.getActions();
        if (hand == null || hand.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target has no action cards");
        }

        if (slotIndex < 0 || slotIndex >= hand.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid slot index");
        }

        // ---- Ici on applique enfin l'effet ----
        applyLibraryTheftEffect(g, owner, target, slotIndex);

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu (THEFT)
            live.locationEffectUsed(fresh, LocationEffectChoice.THEFT, freshOwner);
            flow.scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Prédiction occulte :
     * - prépare libraryOmenState avec jusqu'à 3 cartes du deck adverse
     * - ne fait PAS avancer la file d'effets (résolution en 2 temps).
     * 
     * @return nombre de cartes réellement préparées (0 si impossibilité)
     */
    private int applyLibraryOmenEffect(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());

        List<String> deck = isVamp ? g.getHunterActionsDeck() : g.getVampActionsDeck();
        List<String> discard = isVamp ? g.getHunterActionsDiscard() : g.getVampActionsDiscard();

        if (deck == null) {
            deck = new java.util.ArrayList<>();
            if (isVamp)
                g.setHunterActionsDeck(deck);
            else
                g.setVampActionsDeck(deck);
        }
        if (discard == null) {
            discard = new java.util.ArrayList<>();
            if (isVamp)
                g.setHunterActionsDiscard(discard);
            else
                g.setVampActionsDiscard(discard);
        }

        // Si deck vide mais discard non vide → on recrée le deck maintenant
        if (deck.isEmpty() && !discard.isEmpty()) {
            dice.shuffle(discard);
            deck.addAll(discard);
            discard.clear();
        }

        Game.LibraryOmenState state = new Game.LibraryOmenState();
        state.ownerId = user.getId();
        state.targetSide = isVamp ? "HUNTERS" : "VAMPIRE";
        state.cards = new java.util.ArrayList<>();

        for (int i = 0; i < 3; i++) {
            String c = decks.draw(deck, discard);
            if (c == null)
                break;
            state.cards.add(c);
        }

        if (state.cards.isEmpty()) {
            // Rien à prévisualiser
            g.setLibraryOmenState(null);
            return 0;
        }

        g.setLibraryOmenState(state);
        return state.cards.size(); // normalement 3
    }

    private boolean canUseLibraryOmen(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());

        List<String> deck = isVamp ? g.getHunterActionsDeck() : g.getVampActionsDeck();
        List<String> discard = isVamp ? g.getHunterActionsDiscard() : g.getVampActionsDiscard();

        int available = decks.availableSize(deck, discard);

        // OMEN interdit si 3 cartes ou moins "piochables"
        return available > 3;
    }

    /**
     * Résout l’effet interactif de Bibliothèque OMEN côté serveur.
     *
     * - Utilise l’état temporaire libraryOmenState préparé auparavant.
     * - Replace chaque carte préparée en haut ou en bas du deck adverse
     * selon la liste placements.
     * - Ajoute un message d’historique, nettoie l’état OMEN,
     * sauvegarde et enchaîne sur le prochain effet de lieu.
     */
    @Transactional
    public Game resolveLibraryOmen(String gameId, String playerId, java.util.List<String> placements) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "omen only in PREPHASE3");

        var omen = g.getLibraryOmenState();
        if (omen == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no omen pending");

        if (!playerId.equals(omen.ownerId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your omen");

        if (omen.cards == null || omen.cards.isEmpty()) {
            g.setLibraryOmenState(null);
            store.save(g);
            return g;
        }

        if (placements == null || placements.size() != omen.cards.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid placements");

        boolean targetIsVamp = "VAMPIRE".equals(omen.targetSide);
        List<String> deck = targetIsVamp ? g.getVampActionsDeck() : g.getHunterActionsDeck();
        List<String> discard = targetIsVamp ? g.getVampActionsDiscard() : g.getHunterActionsDiscard();

        if (deck == null) {
            deck = new java.util.ArrayList<>();
            if (targetIsVamp)
                g.setVampActionsDeck(deck);
            else
                g.setHunterActionsDeck(deck);
        }
        if (discard == null) {
            discard = new java.util.ArrayList<>();
            if (targetIsVamp)
                g.setVampActionsDiscard(discard);
            else
                g.setHunterActionsDiscard(discard);
        }

        for (int i = 0; i < omen.cards.size(); i++) {
            String cardId = omen.cards.get(i);
            String where = placements.get(i);

            if ("BOTTOM".equalsIgnoreCase(where)) {
                decks.putOnBottom(deck, cardId);
            } else {
                decks.putOnTop(deck, cardId);
            }
        }

        String who = g.nameOf(playerId);
        g.addHistory("Bibliothèque — " + who
                + " manipule secrètement le futur des cartes d'action (Prédiction occulte).");

        g.setLibraryOmenState(null);

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu
            live.locationEffectUsed(fresh, LocationEffectChoice.OMEN, freshOwner);
            flow.scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Effet de Laboratoire occulte : EXPERIMENT.
     *
     * - Consomme un certain nombre d'âmes sur le vampire
     * en fonction du type de monstre.
     * - Crée le monstre et l'ajoute à g.getMonsters().
     * - Ajoute un message d'historique.
     *
     * Les validations "générales" (phase, propriétaire, etc.)
     * sont faites dans resolveLaboratoryExperiment().
     */
    private void applyLaboratoryExperimentEffect(Game g,
            Player vamp,
            Game.MonsterType type,
            String location) {

        if (!"VAMPIRE".equals(vamp.getRole())) {
            throw new IllegalStateException("only vampire can experiment");
        }

        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("monster location required");
        }

        // --- Coût et stats du monstre selon le type ---
        int soulsCost;
        int hp;
        String atkDice;
        String defDice;

        switch (type) {
            case REVENANT -> {
                soulsCost = 100;
                hp = 5;
                atkDice = "D6";
                defDice = "D4";
            }
            case BAT -> {
                soulsCost = 100;
                hp = 5;
                atkDice = "D4";
                defDice = "D6";
            }
            case GARGOYLE -> {
                soulsCost = 400;
                hp = 10;
                atkDice = "D6";
                defDice = "D8";
            }
            case WOLF -> {
                soulsCost = 400;
                hp = 10;
                atkDice = "D8";
                defDice = "D6";
            }
            case ABERRATION -> {
                soulsCost = 600;
                hp = 15;
                atkDice = "D8";
                defDice = "D8";
            }
            case LICHE -> {
                soulsCost = 600;
                hp = 10;
                atkDice = "D8";
                defDice = "D8";
            }
            default -> throw new IllegalArgumentException("unsupported monster type: " + type);
        }

        // Par sécurité : ne devrait pas arriver si canUseLaboratoryExperiment a été
        // testé
        if (vamp.getSouls() < soulsCost) {
            throw new IllegalStateException("not enough souls for " + type);
        }

        // Paiement
        vamp.setSouls(vamp.getSouls() - soulsCost);

        // Création du monstre
        if (g.getMonsters() == null) {
            g.setMonsters(new java.util.ArrayList<>());
        }

        Game.Monster m = new Game.Monster();
        m.id = java.util.UUID.randomUUID().toString();
        m.type = type;
        m.location = location;
        m.hp = hp;
        m.attackDice = atkDice;
        m.defenseDice = defDice;

        g.getMonsters().add(m);

        // Historique
        String who = g.nameOf(vamp.getId());
        g.addHistory("Laboratoire occulte — " + who
                + " engendre une " + monsterLabel(type)
                + " pour défendre " + location + ".");
    }

    private boolean canUseLaboratoryExperiment(Game g, Player user) {
        // Seul le vampire peut expérimenter
        if (!"VAMPIRE".equals(user.getRole())) {
            return false;
        }

        return user.getSouls() >= 100;
    }

    @Transactional
    public void updateLaboratoryExperimentDraft(String gameId,
            String playerId,
            Game.MonsterType type,
            String location) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "experiment only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.LABORATORY || inst.choice != LocationEffectChoice.EXPERIMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no experiment effect to draft");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your laboratory effect");
        }

        var vamp = g.findPlayer(playerId);
        if (!"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only vampire can experiment");
        }

        // normalize : blank => null
        String loc = (location != null && !location.isBlank()) ? location : null;

        // stocke le draft
        g.setLaboratoryDraftMonsterType(type);
        g.setLaboratoryDraftLocation(loc);

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);

            // déclencher un event WS qui force les clients à refresh snapshot
            live.draftUpdated(fresh);

        });
    }

    @Transactional
    public Game resolveLaboratoryExperiment(String gameId,
            String playerId,
            Game.MonsterType type,
            String location) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "experiment only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        // On ne résout que EXPERIMENT pour LABORATORY
        if (inst.infra != Infra.LABORATORY || inst.choice != LocationEffectChoice.EXPERIMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no experiment effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your laboratory effect");
        }

        var vamp = g.findPlayer(playerId);
        if (!"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only vampire can experiment");
        }

        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monster type required");
        }

        if (location == null || location.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid monster location");
        }

        // --- Restriction Raid ---
        int currentRaid = g.getRaid();
        if (type == Game.MonsterType.GARGOYLE || type == Game.MonsterType.WOLF) {
            if (currentRaid < 5) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Gargouille/Loup débloqués au raid 5 (Raid actuel: " + currentRaid + ")");
            }
        }
        if (type == Game.MonsterType.ABERRATION || type == Game.MonsterType.LICHE) {
            if (currentRaid < 10) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Liche/Aberration débloqués au raid 10 (Raid actuel: " + currentRaid + ")");
            }
        }

        // ---- Ici on applique enfin l'effet, comme pour
        // applyLibraryTheftEffect/applyLibraryOmenEffect ----
        applyLaboratoryExperimentEffect(g, vamp, type, location);

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            Player freshVamp = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu (EXPERIMENT)
            live.locationEffectUsed(fresh, LocationEffectChoice.EXPERIMENT, freshVamp);
            flow.scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    private void applyLaboratoryAlchemyEffect(Game g, Player user) {
        String cardId = decks.drawPotion(g);
        if (cardId == null) {
            g.addHistory("Laboratoire occulte — " + g.nameOf(user.getId())
                    + " tente de préparer une potion, mais la réserve de potions communes est épuisée.");
            return;
        }

        if (user.getPotions() == null) {
            user.setPotions(new java.util.ArrayList<>());
        }
        user.getPotions().add(cardId);

        g.addHistory("Laboratoire occulte — " + g.nameOf(user.getId())
                + " distille une potion alchimique.");
    }

    /**
     * Laboratoire : Alchimie rare (RARE_ALCHEMY).
     * - Coût : 6 eau + 6 herbes
     * - Pioche 1 potion rare depuis le deck rare.
     */
    private void applyLaboratoryRareAlchemyEffect(Game g, Player player) {
        // Vérification ressources
        if (player.getWater() < 6 || player.getHerbs() < 6) {
            g.addHistory("Laboratoire occulte — " + g.nameOf(player.getId())
                    + " voudrait préparer une potion rare, mais manque d'ingrédients (6 eau, 6 herbes).");
            return;
        }

        // Paiement
        player.setWater(player.getWater() - 6);
        player.setHerbs(player.getHerbs() - 6);

        String cardId = decks.drawElixir(g);
        if (cardId == null) {
            g.addHistory("Laboratoire occulte — " + g.nameOf(player.getId())
                    + " consacre des ingrédients à une potion rare, mais la réserve de potions rares est épuisée.");
            return;
        }

        if (player.getElixirs() == null) {
            player.setElixirs(new java.util.ArrayList<>());
        }
        player.getElixirs().add(cardId);

        g.addHistory("Laboratoire occulte — " + g.nameOf(player.getId())
                + " prépare une potion rare d'alchimie.");
    }

    @Transactional
    public Game resolveLaboratoryExplosion(String gameId, String playerId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "explosion only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        // On ne résout que EXPLOSION pour LABORATORY
        if (inst.infra != Infra.LABORATORY || inst.choice != LocationEffectChoice.EXPLOSION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no explosion effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your laboratory effect");
        }

        var hunter = g.findPlayer(playerId);
        if (hunter == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid player");
        }
        if (!"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only hunter can roll explosion");
        }

        // Anti double roll
        if (g.getLaboratoryExplosionRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "explosion already resolved");
        }

        int roll = dice.roll(20);
        g.setLaboratoryExplosionRoll(roll);

        String who = g.nameOf(hunter.getId());

        if (roll >= 15) {
            g.addHistory("Laboratoire occulte — " + who
                    + " jette un d20 (" + roll + ") : explosion réussie !");
            applyLaboratoryExplosionEffect(g, hunter);
        } else {
            int before = hunter.getHp();
            int after = Math.max(0, before - 2);
            hunter.setHp(after);

            g.addHistory("Laboratoire occulte — " + who
                    + " jette un d20 (" + roll + ") : échec. Il subit 2 dégâts (" + before + " → " + after + ").");

            // si tu as un helper pour gérer mort/conséquences, appelle-le ici si after == 0
        }

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu (EXPLOSION)
            live.locationEffectUsed(fresh, LocationEffectChoice.EXPLOSION, freshOwner);
            flow.scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Effet de Laboratoire : EXPLOSION.
     *
     * - Utilisable uniquement par un chasseur.
     * - Immédiat en PREPHASE3 : le vampire perd 1 ressource aléatoire (si possible)
     * et 20 âmes déchues.
     * - La destruction effective du Laboratoire est différée : elle sera appliquée
     * à la fin des combats, juste avant de passer en PHASE4 (via
     * laboratoryToDestroy).
     */
    private void applyLaboratoryExplosionEffect(Game g, Player hunter) {
        if (!"HUNTER".equals(hunter.getRole())) {
            throw new IllegalStateException("only hunter can trigger lab explosion");
        }

        var vampOpt = g.vampire();
        if (vampOpt.isEmpty()) {
            return;
        }
        Player vamp = vampOpt.get();

        // 1) Ressource aléatoire à -1 (si possible)
        List<String> resourceKeys = new ArrayList<>();
        if (vamp.getWood() > 0)
            resourceKeys.add("wood");
        if (vamp.getHerbs() > 0)
            resourceKeys.add("herbs");
        if (vamp.getStone() > 0)
            resourceKeys.add("stone");
        if (vamp.getIron() > 0)
            resourceKeys.add("iron");
        if (vamp.getWater() > 0)
            resourceKeys.add("water");

        String lostResKey = null;
        if (!resourceKeys.isEmpty()) {
            lostResKey = resourceKeys.get(dice.nextInt(resourceKeys.size()));
            switch (lostResKey) {
                case "wood" -> vamp.setWood(vamp.getWood() - 1);
                case "herbs" -> vamp.setHerbs(vamp.getHerbs() - 1);
                case "stone" -> vamp.setStone(vamp.getStone() - 1);
                case "iron" -> vamp.setIron(vamp.getIron() - 1);
                case "water" -> vamp.setWater(vamp.getWater() - 1);
            }
        }

        // 2) Perte de 20 âmes (ou moins si pas assez)
        int soulsBefore = vamp.getSouls();
        int soulsLost = 20;
        if (soulsBefore >= 20) {
            vamp.setSouls(soulsBefore - soulsLost);
        } else {
            soulsLost = soulsBefore;
            vamp.setSouls(0);
        }

        String hunterName = g.nameOf(hunter.getId());
        String vampName = g.nameOf(vamp.getId());

        if (lostResKey != null && soulsLost > 0) {
            g.addHistory("Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire : " + vampName
                    + " perd 1 " + harvest.resLabelFr(lostResKey)
                    + " et " + soulsLost + " âmes déchues.");
        } else if (lostResKey != null) {
            g.addHistory("Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire : " + vampName
                    + " perd 1 " + harvest.resLabelFr(lostResKey) + ".");
        } else if (soulsLost > 0) {
            g.addHistory("Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire : " + vampName
                    + " perd " + soulsLost + " âmes déchues.");
        } else {
            g.addHistory("Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire, mais " + vampName
                    + " n'avait plus de ressources à perdre.");
        }

        // 3) On marque que le labo devra être détruit après les combats
        g.setLaboratoryToDestroy(true);
    }

    public boolean isBallroomBloodWaltzAttack(Game g,
            RoundFight r,
            String userId,
            Player attackerPlayer0,
            Player defenderPlayer0) {
        if (!g.isBallroomBloodWaltz())
            return false;
        if (attackerPlayer0 == null || defenderPlayer0 == null)
            return false;
        if (!"VAMPIRE".equals(attackerPlayer0.getRole()))
            return false;
        if (!"HUNTER".equals(defenderPlayer0.getRole()))
            return false;
        if (!userId.equals(r.getAttackerId()))
            return false;

        if (g.getBuiltInfras() == null || !g.getBuiltInfras().contains(Infra.BALLROOM))
            return false;

        String ballroomCode = Infra.BALLROOM.locationCode();
        return ballroomCode != null && ballroomCode.equals(r.getLocation());
    }

    /**
     * Effet de lieu : Pillage
     * - utilisable uniquement par un chasseur
     * - donne 1 jet de récolte d'or (D100×10) au chasseur qui a choisi l'effet
     */
    private void resolveHunterPillage(Game g, Game.LocationEffectInstance eff) {
        // 1) Récupérer le propriétaire de l'effet
        Player owner = g.getPlayers().stream()
                .filter(p -> p.getId().equals(eff.ownerId))
                .findFirst()
                .orElse(null);

        if (owner == null) {
            // Sécurité : si jamais l'instance est cassée, on log et on sort
            g.addHistory("Pillage — effet sans propriétaire valide.");
            return;
        }

        // 2) Vérifier que c'est bien un chasseur vivant
        if (!"HUNTER".equals(owner.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seuls les chasseurs peuvent déclencher Pillage.");
        }
        if (owner.getHp() <= 0) {
            g.addHistory("Pillage — " + g.nameOf(owner.getId())
                    + " est à terre et ne peut pas piller.");
            return;
        }

        // 3) Vérifier qu'il est bien sur le lieu de l'effet (optionnel mais propre)
        String locCode = (eff.infra != null ? eff.infra.locationCode() : null);
        if (locCode != null) {
            String currentLoc = g.locationOf(owner.getId());
            if (!locCode.equals(currentLoc)) {
                // Au cas où : l’effet est mal configuré / joueur déplacé.
                g.addHistory("Pillage — " + g.nameOf(owner.getId())
                        + " n'est plus sur " + Location.labelFrOf(locCode)
                        + " : aucun butin supplémentaire.");
                return;
            }
        }

        // 4) Jet d'or garanti pour CE chasseur uniquement
        int roll = harvest.rollD100Tens();
        owner.grant("gold", roll);

        String locLabel = (locCode != null ? Location.labelFrOf(locCode) : "ce lieu");
        g.addHistory("Pillage — " + g.nameOf(owner.getId())
                + " obtient +" + roll + " or sur " + locLabel + ".");
    }

    private static final int ALTAR_SOULS_COST_TO_CORRUPT = 30;

    public boolean isAltarBuilt(Game g) {
        return g.getBuiltInfras() != null && g.getBuiltInfras().contains(Infra.ALTAR);
    }

    public boolean isAltarCorrupted(Game g) {
        // si jamais null -> on considère "corrupted" par défaut
        return Boolean.TRUE.equals(g.getAltarCorrupted());
    }

    public void resetAltarRaidFlags(Game g) {
        g.setAltarBiteOccurredThisRaid(false);
        g.setAltarVampTookDamageThisRaid(false);
    }

    private boolean canUseAltarHeal(Game g, Player user) {
        if (!"HUNTER".equals(user.getRole()))
            return false;

        return g.getPlayers().stream()
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> p.getHp() > 0)
                .anyMatch(p -> p.getCorruption() > 0);
    }

    private boolean canUseAltarCorrupt(Game g, Player user) {
        if (!"VAMPIRE".equals(user.getRole()))
            return false;

        return g.getPlayers().stream()
                .filter(p -> "HUNTER".equals(p.getRole()))
                .anyMatch(p -> p.getHp() > 0);
    }

    private void applyAltarCorruptWithSouls(Game g, Player vamp) {
        if (!"VAMPIRE".equals(vamp.getRole())) {
            g.addHistory("Autel — "
                    + g.nameOf(vamp.getId())
                    + " tente de corrompre l'autel, mais ce n'est pas le vampire.");
            return;
        }

        if (isAltarCorrupted(g)) {
            g.addHistory("Autel — "
                    + g.nameOf(vamp.getId())
                    + " tente de corrompre un autel déjà profané.");
            return;
        }

        if (vamp.getSouls() < ALTAR_SOULS_COST_TO_CORRUPT) {
            g.addHistory("Autel — "
                    + g.nameOf(vamp.getId())
                    + " n'a pas assez d'âmes déchues pour profaner l'autel "
                    + "(" + ALTAR_SOULS_COST_TO_CORRUPT + " requises).");
            return;
        }

        vamp.setSouls(vamp.getSouls() - ALTAR_SOULS_COST_TO_CORRUPT);
        g.setAltarCorrupted(Boolean.TRUE);

        g.addHistory("Autel — "
                + g.nameOf(vamp.getId())
                + " sacrifie " + ALTAR_SOULS_COST_TO_CORRUPT
                + " âmes déchues : l'autel est profané.");
    }

    private static final String HOLY_WATER_ACTION_ID = "EAU_BENITE";

    public void applyAltarPurifyWithHolyWater(Game g, Player hunter) {
        if (!"HUNTER".equals(hunter.getRole())) {
            g.addHistory("Autel — "
                    + g.nameOf(hunter.getId())
                    + " tente de purifier l'autel, mais ce n'est pas un chasseur.");
            return;
        }

        if (!isAltarCorrupted(g)) {
            g.addHistory("Autel — "
                    + g.nameOf(hunter.getId())
                    + " tente de purifier un autel déjà pur.");
            return;
        }

        List<String> actions = hunter.getActions();
        if (actions == null || !actions.contains(HOLY_WATER_ACTION_ID)) {
            g.addHistory("Autel — "
                    + g.nameOf(hunter.getId())
                    + " voudrait utiliser de l'eau bénite, mais n'en possède pas.");
            return;
        }

        // On retire UNE carte EAU_BENITE de la main du chasseur
        actions.remove(HOLY_WATER_ACTION_ID);

        // Purification
        g.setAltarCorrupted(Boolean.FALSE);

        g.addHistory("Autel — "
                + g.nameOf(hunter.getId())
                + " consume une fiole d'eau bénite : l'autel est purifié.");
    }

    @Transactional
    public Game resolveAltarHeal(String gameId, String playerId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "altar heal only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.ALTAR || inst.choice != LocationEffectChoice.HEAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no altar-heal effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your altar effect");
        }

        var user = g.findPlayer(playerId);
        var target = g.findPlayer(targetId);

        if (user == null || target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid player/target");
        }

        if (!"HUNTER".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only hunter can resolve altar heal");
        }
        if (!"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target must be a hunter");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target is dead");
        }

        int currentCorruption = target.getCorruption();

        // 0 min
        if (currentCorruption <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target has no corruption to heal");
        }

        // Verrou max: si le chasseur a déjà atteint 3, l’autel ne peut plus l’alléger
        if (currentCorruption >= 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "max corruption cannot be healed at the altar");
        }

        int newCorruption = currentCorruption - 1;
        target.setCorruption(newCorruption);

        g.addHistory("Autel — "
                + g.nameOf(user.getId())
                + " apaise la corruption de "
                + g.nameOf(target.getId())
                + " (-1 corruption, niveau " + newCorruption + ").");

        // important : mettre à jour les mods liés à la corruption
        corruption.rebuildCorruptionMods(g);

        // ---- CONSOMMER L'EFFET MAINTENANT (anti double resolve) ----
        g.setLocationEffectPending(false);
        g.setLocationEffectChoice(null);

        // Très important : invalider l'instance courante
        inst.choice = null;

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            Player freshUser = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            live.locationEffectUsed(fresh, LocationEffectChoice.HEAL, freshUser);
            flow.scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    @Transactional
    public Game resolveAltarCorrupt(String gameId, String playerId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "altar corrupt only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.ALTAR || inst.choice != LocationEffectChoice.CORRUPT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no altar-corrupt effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your altar effect");
        }

        var user = g.findPlayer(playerId);
        var target = g.findPlayer(targetId);

        if (user == null || target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid player/target");
        }

        if (!"VAMPIRE".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only vampire can corrupt from altar");
        }
        if (!"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target must be a hunter");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target is dead");
        }

        int currentCorruption = target.getCorruption();

        // 3 max : impossible d’aller plus haut
        if (currentCorruption >= 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target already at max corruption");
        }

        int newCorruption = currentCorruption + 1;
        target.setCorruption(newCorruption);

        g.addHistory("Autel — "
                + g.nameOf(user.getId())
                + " renforce la corruption de "
                + g.nameOf(target.getId())
                + " (+1 corruption, niveau " + newCorruption + ").");

        // Si on atteint 3 par l’autel -> transformation immédiate en serviteur
        if (newCorruption == 3) {
            decks.discardAllActionsOf(g, target);
            target.setRole("SERVANT");
            equipment.convertHunterGearToServant(g, target);
            flow.cancelPendingCombatsForPlayer(g, target);
            target.setSouls(target.getSouls() + target.getGold());
            target.setGold(0);

            g.addHistory("Autel — "
                    + g.nameOf(target.getId())
                    + " succombe à la corruption: il rejoint le vampire en tant que serviteur.");
        }

        corruption.rebuildCorruptionMods(g);

        // ---- CONSOMMER L'EFFET MAINTENANT (anti double resolve) ----
        g.setLocationEffectPending(false);
        g.setLocationEffectChoice(null);

        // Très important : invalider l'instance courante
        inst.choice = null;

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            Player freshUser = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            live.locationEffectUsed(fresh, LocationEffectChoice.CORRUPT, freshUser);
            flow.scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    public void onAltarBite(Game g, RoundFight r, Player attacker, Player defender) {
        if (!isAltarBuilt(g))
            return;

        String code = Infra.ALTAR.locationCode();
        if (!code.equals(r.getLocation()))
            return;

        // On ne considère que les morsures du vampire sur un chasseur
        if (attacker == null || defender == null)
            return;
        if (!"VAMPIRE".equals(attacker.getRole()))
            return;
        if (!"HUNTER".equals(defender.getRole()))
            return;

        // Au moins une morsure a eu lieu sur l'autel ce raid
        g.setAltarBiteOccurredThisRaid(true);

        // Effet passif : si l'autel était encore pur, il devient corrompu immédiatement
        if (!isAltarCorrupted(g)) {
            g.setAltarCorrupted(Boolean.TRUE);

            g.addHistory("Autel — "
                    + g.nameOf(attacker.getId())
                    + " accompli le rituel: "
                    + "l'autel est profané.");
        }
    }

    public void onAltarVampireDamaged(Game g, RoundFight r, int damage) {
        if (!isAltarBuilt(g))
            return;
        if (damage <= 0)
            return;

        String code = Infra.ALTAR.locationCode();
        if (!code.equals(r.getLocation()))
            return;

        g.setAltarVampTookDamageThisRaid(true);
    }

    public void resolveAltarEndOfRaid(Game g) {
        if (!isAltarBuilt(g)) {
            resetAltarRaidFlags(g);
            return;
        }

        // Si l'autel est déjà pur, on reset juste les flags
        if (!isAltarCorrupted(g)) {
            resetAltarRaidFlags(g);
            return;
        }

        // Règle voulue :
        // - l'autel est corrompu
        // - le vampire a pris au moins 1 dégât sur l'autel pendant ce raid
        // - aucun rituel de morsure n'a réussi sur l'autel ce raid
        if (g.isAltarVampTookDamageThisRaid()
                && !g.isAltarBiteOccurredThisRaid()) {

            g.setAltarCorrupted(Boolean.FALSE);

            g.addHistory("Autel — "
                    + "Le vampire est repoussé sans accomplir le rituel: "
                    + "l'autel est purifié.");
        }

        // Dans tous les cas, on reset les flags pour le raid suivant
        resetAltarRaidFlags(g);
    }

    @Transactional
    public Game resolveForge(String gameId, String playerId, String equipCode) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE
                || g.getPhase() != Phase.PREPHASE3
                || !g.getLocationEffectPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no forge effect pending");
        }

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.FORGE || inst.choice != LocationEffectChoice.FORGE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "current effect is not FORGE");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce n'est pas votre effet de Forge.");
        }

        var p = g.findPlayer(playerId);

        // sécurité : vérifier que equipCode fait partie des options actuelles
        var opts = equipment.listForgeOptionsForPlayer(g, p);
        if (!opts.contains(equipCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "equipement non disponible pour ce joueur");
        }

        equipment.applyForge(g, p, equipCode);

        equipment.rebuildEquipmentMods(g);

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            // Notifier le front, puis passer à l'effet de lieu suivant
            // (même pattern que pour les autres effets interactifs)
            flow.scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Reconstruit entièrement les mods d'équipement (EQUIP:*) dans raidMods.
     *
     * Pour l’instant :
     * - V_ARMOR_T1_CARAPACE et V_ARMOR_T2_HAUBERT donnent +1 DEF
     * via un mod moteur persistant "EQUIP:VAMP_ARMOR_DEF".
     *
     * Appelée :
     * - au début de raid (PHASE0),
     * - après un changement d'équipement (Forge, loot plus tard, etc.).
     */
}
