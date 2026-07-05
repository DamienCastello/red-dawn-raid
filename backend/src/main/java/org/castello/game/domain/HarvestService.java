package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.Location;
import org.castello.game.support.Dice;
import org.castello.player.Player;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

/**
 * Domaine Récolte.
 *
 * En PHASE3, chaque joueur récolte les ressources du lieu qu'il a joué.
 * La récolte est divisée par 2 en cas de combat sur le lieu, pour la cible
 * d'un duel d'instable, ou sous Voile de brume. Les instables "récolte pour
 * le vampire" versent leur butin (converti en âmes) au vampire.
 */
@Service
public class HarvestService {

    private final Dice dice;

    public HarvestService(Dice dice) {
        this.dice = dice;
    }

    public int rollD100Tens() {
        return dice.nextInt(10) * 10;
    }


    public String resLabelFr(String res) {
        return switch (res) {
            case "wood" -> "bois";
            case "herbs" -> "herbe médicinale";
            case "stone" -> "pierre";
            case "iron" -> "fer";
            case "water" -> "eau pure";
            case "gold" -> "or";
            case "souls" -> "âmes déchues";
            case "silver" -> "argent";
            default -> res;
        };
    }

    /**
     * Applique les récoltes pour les lieux SANS combat (une seule fois par raid).
     */
    public void applyHarvests(@NonNull Game g) {
        var vamp = g.vampire().orElse(null);
        var groups = g.playersByLocation();

        // Lieux où au moins un monstre vivant est présent
        java.util.Set<String> monsterLocs = new java.util.HashSet<>();
        if (g.getMonsters() != null) {
            g.getMonsters().stream()
                    .filter(m -> m.hp > 0)
                    .forEach(m -> monsterLocs.add(m.location));
        }

        // Lieux des clones
        java.util.Set<String> cloneLocs = new java.util.HashSet<>();
        if (g.getClonesLocations() != null) {
            cloneLocs.addAll(g.getClonesLocations());
        }

        // Duels instable -> cible :
        // - l'instable ne récolte pas
        // - la cible récolte /2
        java.util.Set<String> duelUnstables = new java.util.HashSet<>();
        java.util.Set<String> duelTargets = new java.util.HashSet<>();
        if (g.getUnstableTargetByPlayer() != null) {
            g.getUnstableTargetByPlayer().forEach((unstableId, targetId) -> {
                if (unstableId != null)
                    duelUnstables.add(unstableId);
                if (targetId != null)
                    duelTargets.add(targetId);
            });
        }

        java.util.Set<String> unstableHarvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Collections.emptySet();

        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            var onLoc = e.getValue();

            boolean enemyHere = onLoc.stream()
                    .anyMatch(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0);

            boolean monsterHere = monsterLocs.contains(loc);

            boolean hunterCausingCombatHere = onLoc.stream()
                    .anyMatch(p -> "HUNTER".equals(p.getRole())
                            && p.getHp() > 0
                            && !unstableHarvesters.contains(p.getId()));

            // Combat s’il y a au moins un chasseur + (ennemi joueur OU monstre)
            // NERF CLONES : on ne compte plus les clones ici pour ne pas impacter la
            // récolte
            boolean combatHere = (enemyHere || monsterHere) && hunterCausingCombatHere;

            for (var p : onLoc) {
                boolean harvestForVamp = g.getUnstableHarvestLocByPlayer() != null
                        && g.getUnstableHarvestLocByPlayer().containsKey(p.getId());

                boolean isVamp = "VAMPIRE".equals(p.getRole());
                boolean isHunter = "HUNTER".equals(p.getRole());

                // 1) Voile de brume : divise la récolte par 2 si chasseur sur le lieu affecté
                boolean fogAffected = false;
                if (g.getFogAffectedLocation() != null
                        && isHunter
                        && !harvestForVamp
                        && g.getFogAffectedLocation().equals(loc)) {
                    fogAffected = true;
                }

                // 2) Duel instable -> cible :
                // - instable ne récolte pas
                if (duelUnstables.contains(p.getId()))
                    continue;

                // 3) Détermine si la récolte doit être divisée par 2
                boolean halfHarvest = false;

                // - la cible du duel récolte /2
                if (duelTargets.contains(p.getId()))
                    halfHarvest = true;

                // - si combat prévu : chasseur et serviteur récoltent /2
                // (mais on garde les exceptions : instable harvestForVamp + vampire)
                if (combatHere && !harvestForVamp && !isVamp) {
                    halfHarvest = true;
                }

                // - Voile de brume : chasseur sur le lieu affecté récolte /2
                if (fogAffected) {
                    halfHarvest = true;
                }

                if (skipHarvestBecauseOfConstruction(g, p, loc)) {
                    continue;
                }

                Player recipient = (harvestForVamp && vamp != null) ? vamp : p;

                java.util.List<String> gains = new java.util.ArrayList<>();
                switch (loc) {
                    case "forest" -> {
                        int wood = halfHarvest ? (2 / 2) : 2;
                        int herbs = halfHarvest ? (4 / 2) : 4;

                        recipient.grant("wood", wood);
                        gains.add("+" + wood + " bois"
                                + (fogAffected ? " (base: 2, divisé par 2 par Voile de brume)" : ""));
                        recipient.grant("herbs", herbs);
                        gains.add("+" + herbs + " herbe médicinale"
                                + (fogAffected ? " (base: 4, divisé par 2 par Voile de brume)" : ""));
                    }
                    case "quarry" -> {
                        int iron = halfHarvest ? (2 / 2) : 2;
                        int stone = halfHarvest ? (4 / 2) : 4;

                        recipient.grant("iron", iron);
                        gains.add("+" + iron + " fer"
                                + (fogAffected ? " (base: 2, divisé par 2 par Voile de brume)" : ""));
                        recipient.grant("stone", stone);
                        gains.add("+" + stone + " pierre"
                                + (fogAffected ? " (base: 4, divisé par 2 par Voile de brume)" : ""));
                    }
                    case "lake" -> {
                        int herbs = halfHarvest ? (2 / 2) : 2;
                        int water = halfHarvest ? (4 / 2) : 4;

                        recipient.grant("herbs", herbs);
                        gains.add("+" + herbs + " herbe médicinale"
                                + (fogAffected ? " (base: 2, divisé par 2 par Voile de brume)" : ""));
                        recipient.grant("water", water);
                        gains.add("+" + water + " eau pure"
                                + (fogAffected ? " (base: 4, divisé par 2 par Voile de brume)" : ""));
                    }
                    case "manor" -> {
                        int roll = rollD100Tens();
                        int baseAmt = roll + 100;
                        int finalAmt = halfHarvest ? (baseAmt / 2) : baseAmt;
                        if (halfHarvest) {
                            // arrondir à la dizaine supérieure
                            finalAmt = (int) (Math.ceil(finalAmt / 10.0) * 10);
                        }

                        if (harvestForVamp && vamp != null) {
                            // Instable qui récolte pour le vampire au Manoir → conversion en âmes
                            // (Note: pour le vampire on ne divise pas, car harvestForVamp=true ->
                            // halfHarvest est false par défaut, sauf si Voile est sur lui?)
                            // fogAffected=false -> halfHarvest=false (sauf duelTargets)
                            // Donc ici on applique finalAmt, qui est égal à baseAmt si halfHarvest=false.
                            // Si par hasard halfHarvest=true (ex: duelTargets), alors oui on divise.
                            vamp.grant("souls", finalAmt);
                            gains.add("+" + finalAmt + " âmes déchues (pour " + g.nameOf(vamp.getId()) + ")"
                                    + (halfHarvest
                                            ? " (base: " + baseAmt + ", divisé par 2"
                                                    + (fogAffected ? " par Voile de brume" : "") + ")"
                                            : ""));
                        } else {
                            if ("HUNTER".equals(p.getRole())) {
                                p.grant("gold", finalAmt);
                                gains.add("+" + finalAmt + " or"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            } else if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                                p.grant("souls", finalAmt);
                                gains.add("+" + finalAmt + " âmes déchues"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            }
                        }
                    }
                    case "sawmill" -> {
                        int base = 4;
                        int wood = halfHarvest ? (base / 2) : base;
                        if (wood <= 0)
                            wood = 1;

                        recipient.grant("wood", wood);
                        gains.add("+" + wood + " bois"
                                + (fogAffected ? " (base: " + base + ", divisé par 2 par Voile de brume)" : ""));
                    }

                    case "mine" -> {
                        int base = 4;
                        int iron = halfHarvest ? (base / 2) : base;
                        if (iron <= 0)
                            iron = 1;

                        recipient.grant("iron", iron);
                        gains.add("+" + iron + " fer"
                                + (fogAffected ? " (base: " + base + ", divisé par 2 par Voile de brume)" : ""));
                    }

                    case "library", "laboratory", "ballroom", "altar", "forge" -> {
                        int roll = rollD100Tens();
                        int baseAmt = roll + 50;
                        int finalAmt = halfHarvest ? (baseAmt / 2) : baseAmt;
                        if (halfHarvest) {
                            // arrondir à la dizaine supérieure
                            finalAmt = (int) (Math.ceil(finalAmt / 10.0) * 10);
                        }

                        if (harvestForVamp && vamp != null) {
                            // Instable qui récolte pour le vampire → âmes
                            vamp.grant("souls", finalAmt);
                            gains.add("+" + finalAmt + " âmes déchues (pour " + g.nameOf(vamp.getId()) + ")"
                                    + (halfHarvest
                                            ? " (base: " + baseAmt + ", divisé par 2"
                                                    + (fogAffected ? " par Voile de brume" : "") + ")"
                                            : ""));
                        } else {
                            // Récolte normale sur les lieux spéciaux du Manoir :
                            // - Chasseur : or
                            // - Vampire / Serviteur : âmes
                            if ("HUNTER".equals(p.getRole())) {
                                recipient.grant("gold", finalAmt);
                                gains.add("+" + finalAmt + " or"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            } else if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                                recipient.grant("souls", finalAmt);
                                gains.add("+" + finalAmt + " âmes déchues"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            }
                        }
                    }
                    default -> {
                        /* plus tard */ }
                }

                if (!gains.isEmpty()) {
                    String who = (harvestForVamp && vamp != null)
                            ? (g.nameOf(p.getId()) + " pour " + g.nameOf(vamp.getId()))
                            : g.nameOf(p.getId());

                    String line = "Récoltes — " + who + " (" + Location.labelFrOf(loc) + ") : " + String.join(", ", gains);
                    g.addHistory(line);
                }
            }
        }
    }

    private boolean skipHarvestBecauseOfConstruction(Game g, Player p, String loc) {
        var pc = g.getPendingConstruction();
        if (pc == null)
            return false;

        var vampOpt = g.vampire();
        if (vampOpt.isEmpty())
            return false;
        var vamp = vampOpt.get();

        // On ne bloque que la récolte du vampire lui-même
        if (!p.getId().equals(vamp.getId()))
            return false;

        // Mapping infra -> lieu de base
        return switch (pc.infra) {
            case SAWMILL -> "forest".equals(loc);
            case MINE -> "quarry".equals(loc);
            case LIBRARY -> "manor".equals(loc);
            case LABORATORY -> "manor".equals(loc);
            case BALLROOM -> "manor".equals(loc);
            case ALTAR -> "manor".equals(loc);
            case FORGE -> "manor".equals(loc);
        };
    }

}
