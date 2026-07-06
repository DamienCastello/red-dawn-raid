package org.castello.game.domain;

import org.castello.game.CenterBoard;
import org.castello.game.Game;
import org.castello.game.Infra;
import org.castello.game.Location;
import org.castello.game.Phase;
import org.castello.game.RoundFight;
import org.castello.game.support.GameStore;
import org.castello.game.support.Dice;
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
import java.util.HashMap;

import static org.castello.game.domain.EquipmentService.*;

/**
 * Domaine Corruption (morsures, instables, marque ténébreuse).
 *
 * Échelle 0..3 : sain, affaibli (-1 ATK/DEF), instable (d6 en préphase,
 * 1-3 = sous contrôle du vampire), serviteur (change de camp).
 */
@Service
public class CorruptionService {

    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final EquipmentService equipment;
    private final LiveEvents live;
    private final RaidFlow flow;
    private final LocationEffectService locationEffects;

    public CorruptionService(GameStore store, Dice dice, DeckService decks,
            EquipmentService equipment, LiveEvents live,
            @Lazy RaidFlow flow, @Lazy LocationEffectService locationEffects) {
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.equipment = equipment;
        this.live = live;
        this.flow = flow;
        this.locationEffects = locationEffects;
    }

    public void rebuildCorruptionMods(@NonNull Game g) {
        if (g.getRaidMods() == null)
            g.setRaidMods(new HashMap<>());

        // Purge des anciennes entrées de corruption
        for (var list : g.getRaidMods().values()) {
            if (list != null) {
                list.removeIf(m -> {
                    String s = m.getSource();
                    return s != null && s.startsWith("CORRUPTION:");
                });
            }
        }

        // Réinjection selon le niveau
        for (var p : g.getPlayers()) {
            int lvl = p.getCorruption();

            if (lvl == 1) {
                // Effet moteur L1 : −1 ATK / −1 DEF
                g.addRaidMod(p.getId(), "ATTACK", -1, "CORRUPTION:L1:ENG");
                g.addRaidMod(p.getId(), "DEFENSE", -1, "CORRUPTION:L1:ENG");
                // Une seule puce d’affichage
                g.addRaidMod(p.getId(), "MULTIPLE", 0, "CORRUPTION:L1:DSP");
            } else if (lvl == 2) {
                // L2 : pas de debuff chiffré — seulement la puce “instable”
                g.addRaidMod(p.getId(), "INSTABLE", 0, "CORRUPTION:L2:DSP");
            } else if (lvl == 3) {
                // L3 : pas de debuff chiffré — seulement la puce “serviteur”
                g.addRaidMod(p.getId(), "SERVITEUR", 0, "CORRUPTION:L3:DSP");
            }
        }
        // --- Marque ténébreuse : puce DSP permanente tant que le chasseur est marqué
        // ---
        if (g.getDarkMarkedHunters() != null && !g.getDarkMarkedHunters().isEmpty()) {
            for (String pid : g.getDarkMarkedHunters()) {
                // sécurité : s'assurer que le joueur existe
                Player h = g.findPlayer(pid);
                if (h == null)
                    continue;

                g.addRaidMod(pid, "MARKED", 0, "CORRUPTION:MARK:DSP");
            }
        }
    }
    public boolean hasSuccumbedToCorruption(Game g, String playerId) {
        var eligTargets = g.getUnstableEligibleTargets();
        var eligLocs = g.getUnstableEligibleLocations();

        boolean markedAtk = (eligTargets != null && eligTargets.containsKey(playerId));
        boolean markedHarv = (eligLocs != null && eligLocs.containsKey(playerId));

        // si le jet de corruption a "foiré" pour ce joueur, on l'a mis dans l'une des
        // deux maps
        return markedAtk || markedHarv;
    }

    public boolean isDarkMarked(Game g, String playerId) {
        return g.getDarkMarkedHunters() != null
                && g.getDarkMarkedHunters().contains(playerId);
    }

    public void applyDarkMarkCorruptionOncePerRaid(Game g, Player hunter) {
        if (g.getDarkMarkCorruptedThisRaid() == null) {
            g.setDarkMarkCorruptedThisRaid(new java.util.HashSet<>());
        }
        java.util.Set<String> done = g.getDarkMarkCorruptedThisRaid();
        if (done.contains(hunter.getId()))
            return; // déjà pris 1 corruption ce raid

        int before = hunter.getCorruption();
        if (before >= 3)
            return; // pas au-delà du max

        hunter.setCorruption(before + 1);

        done.add(hunter.getId());

        if (hunter.getCorruption() == 3) {
            decks.discardAllActionsOf(g, hunter);
            hunter.setRole("SERVANT");
            equipment.convertHunterGearToServant(g, hunter);
            flow.cancelPendingCombatsForPlayer(g, hunter);

            // Si une morsure est en cours pour ce joueur (ex: combat déclenché), on marque
            // la transformation
            if (g.getCurrentBite() != null && hunter.getId().equals(g.getCurrentBite().getTargetId())) {
                g.getCurrentBite().setBecameServant(true);
            }
        }

        String line = "Marque ténébreuse — "
                + g.nameOf(hunter.getId())
                + " subit la corruption de la marque qui augmente de 1 -> niveau " + (before + 1) + ".";
        g.addHistory(line);
    }

    public void cleanseDarkMark(Game g, Player hunter) {
        // 1) Supprimer le flag permanent
        if (g.getDarkMarkedHunters() != null) {
            g.getDarkMarkedHunters().remove(hunter.getId());
        }
        if (g.getDarkMarkCorruptedThisRaid() != null) {
            g.getDarkMarkCorruptedThisRaid().remove(hunter.getId());
        }

        // 2) Supprimer la puce DISPLAY
        if (g.getRaidMods() != null) {
            var list = g.getRaidMods().get(hunter.getId());
            if (list != null) {
                list.removeIf(m -> {
                    String src = m.getSource();
                    return src != null
                            && src.startsWith("CORRUPTION:MARK")
                            && src.endsWith(":DSP");
                });
            }
        }
    }

    /**
     * Redirige un chasseur instable (corruption=2, jet 1–3) vers une cible choisie
     * par le vampire.
     * - Valide la phase (PREPHASE3) et les droits (vampire uniquement).
     * - Vérifie que la cible fait partie des éligibles calculés à la révélation.
     * - Remplace la carte posée au centre par celle de la cible ET rend l’ancienne
     * carte à la main de l’instable.
     * - Enregistre la décision pour planifier un duel instable -> cible lors de
     * PHASE3.
     */
    @Transactional
    public Game assignUnstableTarget(String gameId, String userId, String unstableId, String targetId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = g.vampire().orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        boolean eligibleNow = g.getUnstableEligibleTargets().containsKey(unstableId)
                || g.getUnstableEligibleLocations().containsKey(unstableId);
        if (!eligibleNow)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no pending unstable choice");

        var elig = g.getUnstableEligibleTargets().get(unstableId);
        if (elig == null || !elig.contains(targetId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid target");

        // --- Mutations (aucun event ici) ---
        // 1) Enregistre la cible et invalide l’autre choix
        g.getUnstableTargetByPlayer().put(unstableId, targetId);
        g.getUnstableHarvestLocByPlayer().remove(unstableId);

        // 2) Redirige la carte de l’instable vers la localisation de la cible
        String targetLoc = g.getCenter().stream()
                .filter(cb -> cb.getPlayerId().equals(targetId))
                .map(CenterBoard::getCard).findFirst()
                .orElse(null);

        if (targetLoc != null) {
            g.getCenter().stream()
                    .filter(cb -> cb.getPlayerId().equals(unstableId))
                    .findFirst()
                    .ifPresent(cb -> {
                        String oldCard = cb.getCard();
                        String newLoc = targetLoc;
                        if (!java.util.Objects.equals(oldCard, newLoc)) {
                            cb.setCard(newLoc);
                            var unstable = g.getPlayers().stream()
                                    .filter(pp -> pp.getId().equals(unstableId)).findFirst().orElse(null);
                            if (unstable != null) {
                                unstable.getHand().add(oldCard);
                                unstable.getHand().remove(newLoc);
                            }
                        }
                    });

            String targetName = g.nameOf(targetId);
            String unstableName = g.nameOf(unstableId);
            String locLabel = Location.labelFrOf(targetLoc);

            String interruptionLine = "Récolte de " + targetName + " perturbée par " + unstableName
                    + " (récolte réduite).";
            String combatLine = "Combat — " + unstableName + " VS " + targetName + " à " + locLabel + ".";

            if (g.getMessages() == null)
                g.setMessages(new ArrayList<>());
            g.getMessages().add(combatLine);
            g.addHistory(interruptionLine);
            g.addHistory(combatLine);
        }

        // Marque ténébreuse : +1 corruption si chasseur marqué instable assigné sur le
        // lieu du vampire
        Player unstablePlayer = g.findPlayer(unstableId);
        if (unstablePlayer != null && isDarkMarked(g, unstableId)) {
            String vampLoc = g.locationOf(vamp.getId());
            if (vampLoc != null && vampLoc.equals(targetLoc)) {
                applyDarkMarkCorruptionOncePerRaid(g, unstablePlayer);
            }
        }

        // 3) Consomme toute l’éligibilité pour cet instable
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) (RE)calcule APRÈS mutation
        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter = flow.computeHasUpcomingCombat(g);
        g.setHasUpcomingCombat(upcomingAfter);

        // --- Commit
        store.save(g);

        // --- Events APRÈS COMMIT
        store.afterCommit(() -> {
            live.unstableAssigned(g, unstableId, "TARGET", targetId);

            // S’il n’y a PLUS de choix instables ET PAS de combat → avance rapide (4s)
            if (!hasPendingAfter && !upcomingAfter) {
                flow.scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
            } else {
                // sinon, on laisse vivre la fenêtre (potions, autres instables…) / ou timer
                // long déjà en place
                live.phaseChanged(g); // petit heartbeat pour rafraîchir les onglets
            }
        });

        return g;
    }

    @Transactional
    public Game assignUnstableHarvest(String gameId, String userId, String unstableId, String loc) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = g.vampire().orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        boolean eligibleNow = g.getUnstableEligibleTargets().containsKey(unstableId)
                || g.getUnstableEligibleLocations().containsKey(unstableId);
        if (!eligibleNow)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no pending unstable choice");

        var eligLocs = g.getUnstableEligibleLocations().get(unstableId);
        if (eligLocs == null || !eligLocs.contains(loc))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid location");

        // --- Mutations (aucun event ici) ---
        // 1) Enregistre le lieu et invalide l’autre choix
        g.getUnstableHarvestLocByPlayer().put(unstableId, loc);
        g.getUnstableTargetByPlayer().remove(unstableId);

        // 2) Redirige la carte au centre (et rend l’ancienne)
        g.getCenter().stream()
                .filter(cb -> cb.getPlayerId().equals(unstableId))
                .findFirst()
                .ifPresent(cb -> {
                    String oldCard = cb.getCard();
                    String newLoc = loc;
                    if (!java.util.Objects.equals(oldCard, newLoc)) {
                        cb.setCard(newLoc);
                        var unstable = g.getPlayers().stream()
                                .filter(pp -> pp.getId().equals(unstableId)).findFirst().orElse(null);
                        if (unstable != null) {
                            unstable.getHand().add(oldCard);
                            unstable.getHand().remove(newLoc);
                        }
                    }
                });

        String line = g.nameOf(unstableId) + " récoltera à " + Location.labelFrOf(loc) + " pour " + g.nameOf(vamp.getId())
                + ".";
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(line);
        g.addHistory(line);

        // Marque ténébreuse : +1 corruption si chasseur marqué instable assigné sur le
        // lieu du vampire
        Player unstablePlayer = g.findPlayer(unstableId);
        if (unstablePlayer != null && isDarkMarked(g, unstableId)) {
            String vampLoc = g.locationOf(vamp.getId());
            if (vampLoc != null && vampLoc.equals(loc)) {
                applyDarkMarkCorruptionOncePerRaid(g, unstablePlayer);
            }
        }

        // 3) Consomme l’éligibilité
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) (RE)calcule APRÈS mutation
        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter = flow.computeHasUpcomingCombat(g);
        g.setHasUpcomingCombat(upcomingAfter);

        // --- Commit
        store.save(g);

        // --- Events APRÈS COMMIT
        store.afterCommit(() -> {
            live.unstableAssigned(g, unstableId, "HARVEST", loc);

            if (!hasPendingAfter && !upcomingAfter) {
                // pas d’autres choix, pas de combat → passe en PHASE3 rapidement (récoltes)
                flow.scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
            } else {
                live.phaseChanged(g);
            }
        });

        return g;
    }

    @Transactional
    public Game assignUnstableNothing(String gameId, String userId, String unstableId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = g.vampire().orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        // --- Debug précis : ce qu’on a au moment T
        var eligT = g.getUnstableEligibleTargets();
        var eligL = g.getUnstableEligibleLocations();
        boolean inTargets = eligT != null && eligT.containsKey(unstableId);
        boolean inLocations = eligL != null && eligL.containsKey(unstableId);

        boolean eligibleNow = inTargets || inLocations;

        // --- Idempotence : si déjà consommé/expiré, on répond OK et on force juste un
        // refresh
        if (!eligibleNow) {
            store.save(g);
            store.afterCommit(() -> live.phaseChanged(g));
            return g;
        }

        // --- Mutations : consomme l’option sans target/harvest
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);
        g.getUnstableTargetByPlayer().remove(unstableId); // sécurité
        g.getUnstableHarvestLocByPlayer().remove(unstableId); // sécurité

        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter = flow.computeHasUpcomingCombat(g);
        g.setHasUpcomingCombat(upcomingAfter);

        g.addHistory(g.nameOf(unstableId) + " n’a reçu aucun ordre du vampire.");

        store.save(g);

        store.afterCommit(() -> {
            // AVANT: live.unstableAssigned(g, unstableId, "NOTHING", null);
            live.unstableAssigned(g, unstableId, "NOTHING", "");
            if (!hasPendingAfter && !upcomingAfter) {
                flow.scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
            } else {
                live.phaseChanged(g);
            }
        });

        return g;
    }

    /**
     * Tente / résout une morsure.
     *
     * Deux étapes possibles :
     * - Étape 1 (vampire) : jet de morsure (d20, réussite sur > 8 ; clone : d20 > 12).
     * * si échec → on clôt la morsure comme avant.
     * * si réussite :
     * - cible sans armure de plates argent → corruption appliquée immédiatement
     * (comme avant).
     * - cible avec armure de plates argent → on NE modifie pas la corruption,
     * la cible pourra lancer un D4 sur un second appel.
     *
     * - Étape 2 (cible, si armure de plates argent) : jet d’armure (D4).
     * * si 4 → la morsure est entièrement annulée (pas de corruption).
     * * sinon → la corruption est appliquée maintenant.
     */
    @Transactional
    public Game rollCorruption(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE3 || g.getCurrentBite() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bite to resolve");
        }

        var b = g.getCurrentBite();

        // Cible actuelle de la morsure
        var target = g.getPlayers().stream()
                .filter(p -> p.getId().equals(b.getTargetId()))
                .findFirst()
                .orElse(null);

        boolean targetHasSilverPlate = (target != null
                && H_ARMOR_T3_PLATE_SILVER.equals(target.getArmor()));

        boolean isSacredRosaryUsed = false;

        String altarCode = Infra.ALTAR.locationCode();
        RoundFight fight = g.getCurrentCombat();

        // ======================
        // ÉTAPE 1 : d20 de morsure par le vampire
        // ======================
        if (b.getRoll() == null) {
            // Seul le vampire (attaquant) peut faire le premier jet
            if (!userId.equals(b.getAttackerId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll the bite");
            }

            // --- MORSURE DE CLONE ---
            if (fight != null && fight.isCloneAttack()) {
                // D20, seuil 12, +30 âmes + 1 corruption
                int roll = dice.roll(20);
                b.setRoll(roll);
                g.addHistory(g.nameOf(b.getAttackerId()) + " — jet de morsure (Clone) = " + roll + ".");

                if (roll > 12) {
                    Player att = g.findPlayer(b.getAttackerId());
                    if (att != null) {
                        att.setSouls(att.getSouls() + 30);
                        g.addHistory("Morsure réussie ! (Clone) — " + g.nameOf(att.getId())
                                + " draine 30 âmes à " + g.nameOf(target != null ? target.getId() : "la cible") + ".");
                    }

                    if (target != null && target.getCorruption() < 3) {
                        target.setCorruption(target.getCorruption() + 1);
                        g.addHistory(g.nameOf(target.getId()) + " subit 1 corruption (Morsure de clone).");
                    }

                } else {
                    g.addHistory("Morsure ratée (Clone).");
                }

                // On laisse le roll s'afficher, la résolution se fera au prochain appel
                b.setResolvedAtMillis(System.currentTimeMillis());
                store.save(g);

                final int rollF = roll;
                final String attF = b.getAttackerId();
                final String tgtF = b.getTargetId();
                final Integer newCF = (target != null) ? target.getCorruption() : null;

                store.afterCommit(() -> {
                    live.biteRolled(g, rollF, attF, tgtF, newCF, false, false);
                    live.phaseChanged(g);
                });
                return g;
            }

            // AUTEL — message "before" pour la corruption par morsure (premier jet
            // uniquement)
            if (fight != null
                    && altarCode != null
                    && altarCode.equals(fight.getLocation())
                    && locationEffects.isAltarBuilt(g)
                    && !locationEffects.isAltarCorrupted(g) // sanctuaire encore pur
                    && !g.isAltarBiteOccurredThisRaid()) // aucune morsure réussie sur ce lieu ce raid
            {
                g.addHistory("Autel — "
                        + g.nameOf(b.getAttackerId())
                        + " prépare un rituel sanglant: "
                        + "si cette morsure réussit sur le sanctuaire, l'autel sera profané.");
            }

            int roll = dice.roll(20);
            b.setRoll(roll);
            g.addHistory(g.nameOf(b.getAttackerId()) + " — jet de morsure = " + roll + ".");

            boolean sendMods = false;
            boolean becameServant = false;

            if (target != null && roll > 8) {
                // Morsure réussie côté d20

                boolean targetHasSacredRosary = (target != null && target.isSacredRosary());

                if (targetHasSacredRosary) {
                    // Chapelet sacré : la morsure est entièrement annulée

                    // Consommer l'effet persistant
                    target.setSacredRosary(false);

                    // Retirer la puce DISPLAY ACTION:SACRED_ROSARY:DSP
                    if (g.getRaidMods() != null) {
                        var mods = g.getRaidMods().get(target.getId());
                        if (mods != null) {
                            mods.removeIf(m -> {
                                String s = m.getSource();
                                return s != null && s.startsWith("ACTION:SACRED_ROSARY");
                            });
                        }
                    }

                    isSacredRosaryUsed = true;

                    String line = "Chapelet sacré — "
                            + g.nameOf(target.getId())
                            + " invoque une protection divine: la morsure du vampire est annulée.";
                    g.addHistory(line);

                    // Pas de corruption, pas de transformation en serviteur, pas d'autel profané
                    // On marque simplement la morsure comme résolue
                    b.setResolvedAtMillis(System.currentTimeMillis());

                    // On a touché aux raidMods → forcer un refresh côté front
                    sendMods = true;

                } else if (targetHasSilverPlate) {
                    // Armure de plates en argent : comportement existant
                    g.addHistory("Armure de plates en argent — "
                            + g.nameOf(target.getId())
                            + " peut tenter de repousser la morsure en lançant un d4.");
                    // Pas de corruption ici, pas de onAltarBite, pas de resolvedAtMillis
                    // (on attend le D4)
                } else {
                    // Comportement d'origine : on applique la corruption tout de suite
                    int before = target.getCorruption();
                    int after = Math.min(3, before + 1);
                    target.setCorruption(after);
                    g.addHistory(g.nameOf(target.getId())
                            + " se fait mordre... sa corruption passe de " + before + " à " + after + ".");
                    if (after == 3) {
                        decks.discardAllActionsOf(g, target);
                        target.setRole("SERVANT");
                        equipment.convertHunterGearToServant(g, target);
                        target.setSouls(target.getSouls() + target.getGold());
                        target.setGold(0);
                        becameServant = true;
                        b.setBecameServant(true);
                        flow.cancelPendingCombatsForPlayer(g, target);
                    }

                    var attacker = g.getPlayers().stream()
                            .filter(pp -> pp.getId().equals(b.getAttackerId()))
                            .findFirst()
                            .orElse(null);

                    if (fight != null
                            && altarCode != null
                            && altarCode.equals(fight.getLocation())) {
                        locationEffects.onAltarBite(g, fight, attacker, target);
                    }

                    rebuildCorruptionMods(g);
                    sendMods = true;

                    // Bonus : morsure réussie => +50 âmes au vampire (ou +30 si clone)
                    var vampOpt2 = g.vampire();
                    if (vampOpt2.isPresent()) {
                        Player vamp2 = vampOpt2.get();
                        int reward = 50;
                        if (fight != null && fight.isCloneAttack()) {
                            reward = 30;
                        }

                        vamp2.setSouls(vamp2.getSouls() + reward);
                        g.addHistory("Morsure — le sang versé nourrit le vampire : +" + reward + " âmes déchues.");
                    }

                    b.setResolvedAtMillis(System.currentTimeMillis());
                }

            } else {
                // Échec de la morsure (ou pas de cible pour une raison X)
                g.addHistory(g.nameOf(b.getAttackerId()) + " échoue sa tentative de morsure.");
                b.setResolvedAtMillis(System.currentTimeMillis());
            }

            store.save(g);

            final boolean sendModsF = sendMods;
            final int rollF = roll;
            final String attF = b.getAttackerId();
            final String tgtF = b.getTargetId();
            final Integer newCF = (target != null) ? target.getCorruption() : null;
            final boolean becameServantF = becameServant;
            final boolean isSacredRosaryUsedF = isSacredRosaryUsed;
            final String locF = b.getLocation();
            final Long resolvedAtF = b.getResolvedAtMillis();

            store.afterCommit(() -> {
                if (sendModsF) {
                    live.raidModsUpdated(g);
                }
                // Ici rollF = d20 (morsure)
                live.biteRolled(g, rollF, attF, tgtF, newCF, becameServantF, isSacredRosaryUsedF);

                // Si le bite est résolu immédiatement (e.g. transformation Servant, chapelet
                // sacré),
                // émettre BITE_RESOLVED pour que les spectateurs ferment aussi le modal
                if (resolvedAtF != null) {
                    live.biteResolved(g, attF, tgtF, locF);
                }
            });

            return g;
        }

        // ======================
        // ÉTAPE 2 : D4 par la cible (si armure de plates en argent)
        // ======================

        // On ne doit arriver ici que si :
        // - un d20 a déjà été lancé (b.getRoll() != null),
        // - la morsure n'est pas encore résolue (resolvedAtMillis == null),
        // - la cible possède l'armure de plates argent,
        // - l'appel vient de la cible.
        if (!targetHasSilverPlate
                || target == null
                || b.getResolvedAtMillis() != null
                || !userId.equals(b.getTargetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no armor roll expected from you now");
        }

        if (b.getArmorRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "armor roll already done");
        }

        int d4 = dice.roll(4);
        b.setArmorRoll(d4);
        g.addHistory("Armure de plates en argent — "
                + g.nameOf(target.getId())
                + " jette un d4 contre la morsure (" + d4 + ").");

        boolean sendMods2 = false;
        boolean becameServant2 = false;

        if (d4 == 4) {
            // L'armure repousse complètement le vampire : pas de corruption
            g.addHistory("L'armure de plates en argent repousse le vampire : "
                    + "la morsure est annulée.");
            // Pas de onAltarBite, pas de rebuildCorruptionMods
        } else {
            // La morsure "passe" malgré l'armure : on applique la corruption maintenant
            int before = target.getCorruption();
            int after = Math.min(3, before + 1);
            target.setCorruption(after);
            g.addHistory(g.nameOf(target.getId())
                    + " se fait finalement corrompre... sa corruption passe de "
                    + before + " à " + after + ".");
            if (after == 3) {
                decks.discardAllActionsOf(g, target);
                target.setRole("SERVANT");
                equipment.convertHunterGearToServant(g, target);
                target.setSouls(target.getSouls() + target.getGold());
                target.setGold(0);
                becameServant2 = true;
                b.setBecameServant(true);
                flow.cancelPendingCombatsForPlayer(g, target);
            }

            var attacker = g.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(b.getAttackerId()))
                    .findFirst()
                    .orElse(null);

            if (fight != null
                    && altarCode != null
                    && altarCode.equals(fight.getLocation())) {
                locationEffects.onAltarBite(g, fight, attacker, target);
            }

            rebuildCorruptionMods(g);
            sendMods2 = true;

            // Bonus : morsure réussie malgré armure => +50 âmes au vampire
            var vampOpt2 = g.vampire();
            if (vampOpt2.isPresent()) {
                Player vamp2 = vampOpt2.get();
                vamp2.setSouls(vamp2.getSouls() + 50);
                g.addHistory("Morsure — le sang versé nourrit le vampire : +50 âmes déchues.");
            }
        }

        b.setResolvedAtMillis(System.currentTimeMillis());
        store.save(g);

        final boolean sendModsF2 = sendMods2;
        final int rollF2 = d4; // ici on envoie le D4
        final String attF2 = b.getAttackerId();
        final String tgtF2 = b.getTargetId();
        final Integer newCF2 = (target != null) ? target.getCorruption() : null;
        final boolean becameServantF2 = becameServant2;
        final boolean isSacredRosaryUsedF = isSacredRosaryUsed;

        store.afterCommit(() -> {
            if (sendModsF2) {
                live.raidModsUpdated(g);
            }
            // Ici rollF2 = D4
            live.biteRolled(g, rollF2, attF2, tgtF2, newCF2, becameServantF2, isSacredRosaryUsedF);
        });

        return g;
    }

}
