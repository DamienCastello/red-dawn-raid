package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.Infra;
import org.castello.game.Location;
import org.castello.game.support.GameStore;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.stereotype.Service;

/**
 * Domaine Constructions (le domaine du vampire).
 *
 * Le vampire planifie une infrastructure en PHASE2 (voir
 * GameService.planConstruction tant que le flux de phases n'est pas
 * extrait) ; elle se finalise en fin de PHASE3 si les ressources sont
 * toujours là : paiement, carte Lieu distribuée à tous, récolte
 * d'inauguration. Les infras marquées (Incendiaire, explosion du labo)
 * sont détruites en fin de raid.
 */
@Service
public class ConstructionService {

    private final GameStore store;
    private final HarvestService harvest;
    private final LiveEvents live;

    public ConstructionService(GameStore store, HarvestService harvest, LiveEvents live) {
        this.store = store;
        this.harvest = harvest;
        this.live = live;
    }

    public boolean hasResourcesForInfra(Player p, Infra infra) {
        return switch (infra) {
            case SAWMILL -> p.getStone() >= 2 && p.getIron() >= 2;
            case MINE -> p.getWood() >= 3 && p.getIron() >= 1;
            case LIBRARY -> p.getWood() >= 4 && p.getStone() >= 2 && p.getIron() >= 1;
            case LABORATORY -> p.getWater() >= 2
                    && p.getHerbs() >= 2
                    && p.getStone() >= 3
                    && p.getSouls() >= 50;
            case BALLROOM -> p.getStone() >= 5 && p.getIron() >= 2 && p.getSouls() >= 50;
            case ALTAR -> p.getStone() >= 4
                    && p.getWood() >= 1
                    && p.getIron() >= 2
                    && p.getSouls() >= 50;
            case FORGE -> p.getIron() >= 5
                    && p.getStone() >= 4
                    && p.getWood() >= 2;
        };
    }

    private void payResourcesForInfra(Player p, Infra infra) {
        switch (infra) {
            case SAWMILL -> {
                p.setStone(p.getStone() - 2);
                p.setIron(p.getIron() - 2);
            }
            case MINE -> {
                p.setWood(p.getWood() - 3);
                p.setIron(p.getIron() - 1);
            }
            case LIBRARY -> {
                p.setWood(p.getWood() - 4);
                p.setStone(p.getStone() - 2);
                p.setIron(p.getIron() - 1);
            }
            case LABORATORY -> {
                p.setWater(p.getWater() - 2);
                p.setHerbs(p.getHerbs() - 2);
                p.setStone(p.getStone() - 3);
                p.setSouls(p.getSouls() - 50);
            }
            case BALLROOM -> {
                p.setStone(p.getStone() - 5);
                p.setIron(p.getIron() - 2);
                p.setSouls(p.getSouls() - 50);
            }
            case ALTAR -> {
                p.setStone(p.getStone() - 4);
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 2);
                p.setSouls(p.getSouls() - 50);
            }
            case FORGE -> {
                p.setIron(p.getIron() - 5);
                p.setStone(p.getStone() - 4);
                p.setWood(p.getWood() - 2);
            }
        }
    }

    /** Récolte du lieu construit (au lieu de Forêt/Carrière). */
    private void applyInfraHarvest(Game g, Player vamp, Infra infra) {
        java.util.List<String> gains = new java.util.ArrayList<>();
        switch (infra) {
            case SAWMILL -> {
                vamp.grant("wood", 6);
                gains.add("+6 bois");
            }
            case MINE -> {
                vamp.grant("iron", 6);
                gains.add("+6 fer");
            }
            case LIBRARY, LABORATORY, BALLROOM, ALTAR, FORGE -> {
                // Pour l’instant : même logique que Manoir, tu ajusteras si tu as déjà un case
                // "manor"
                // Exemple : +1d100 or OU +1d100 âmes déchues selon rôle
                int d100 = harvest.rollD100Tens();
                if ("VAMPIRE".equals(vamp.getRole())) {
                    vamp.grant("souls", d100);
                    gains.add("+" + d100 + " âmes déchues");
                } else {
                    vamp.grant("gold", d100);
                    gains.add("+" + d100 + " or");
                }
            }
        }

        if (!gains.isEmpty()) {
            String who = g.nameOf(vamp.getId());
            String line = "Récoltes — " + who + " (" + infra.labelFr() + ") : "
                    + String.join(", ", gains);
            g.addHistory(line);
        }
    }

    private void giveInfraCardToAllPlayers(Game g, Infra infra) {
        String cardCode = infra.locationCode(); // "sawmill" ou "mine"

        for (var p : g.getPlayers()) {
            p.getHand().add(cardCode);
        }
    }

    /** À appeler à la fin de la PHASE3, avant de passer en PHASE4. */
    public void resolveInfraConstruction(Game g) {
        var pc = g.getPendingConstruction();
        if (pc == null)
            return;

        // On consomme la construction en attente dans tous les cas
        g.setPendingConstruction(null);

        var vampOpt = g.vampire();
        if (vampOpt.isEmpty())
            return;
        var vamp = vampOpt.get();

        /*
         * // 1) Si le vampire a pris des dégâts, la construction échoue,
         * // mais il récolte le lieu d'origine (forêt/carrière)
         * if (g.isVampireTookDamageThisRaid()) {
         * g.addHistory("La construction de " + pc.infra
         * + " échoue : le vampire a subi des dégâts durant le raid.");
         * applyBaseLocationHarvestForInfra(g, vamp, pc.infra);
         * return;
         * }
         */

        // 2) Vérifier qu'il a encore les ressources
        if (!hasResourcesForInfra(vamp, pc.infra)) {
            g.addHistory("La construction de " + pc.infra
                    + " échoue : le vampire n'a plus les ressources nécessaires.");
            applyBaseLocationHarvestForInfra(g, vamp, pc.infra);
            return;
        }

        // 3) Payer les ressources
        payResourcesForInfra(vamp, pc.infra);

        // 4) Marquer l'infrastructure comme construite (une seule fois par partie)
        g.getBuiltInfras().add(pc.infra);

        if (pc.infra == Infra.ALTAR) {
            // Première fois qu'on le construit → autel corrompu
            g.setAltarCorrupted(Boolean.TRUE);
        }

        g.addHistory("La construction de " + pc.infra + " est achevée.");

        // 5) Donner la carte Lieu correspondante à tous les joueurs
        giveInfraCardToAllPlayers(g, pc.infra);

        // 6) Récolte du nouveau lieu pour ce raid
        applyInfraHarvest(g, vamp, pc.infra);

        // persiste
        store.save(g);

        // events après commit
        final String vampId = vamp.getId();
        final String infraName = pc.infra.name();

        store.afterCommit(() -> {
            live.infraBuilt(g, vampId, infraName);
        });
    }

    /** Récolte du lieu d'origine (FOREST/QUARRY) quand la construction échoue. */
    private void applyBaseLocationHarvestForInfra(Game g, Player vamp, Infra infra) {
        java.util.List<String> gains = new java.util.ArrayList<>();
        String locKey;
        String locLabel;

        switch (infra) {
            case SAWMILL -> {
                // même logique que case "forest" de applyHarvests pour le vampire
                vamp.grant("wood", 2);
                gains.add("+1 bois");
                vamp.grant("herbs", 4);
                gains.add("+2 herbe médicinale");
                locKey = "forest";
                locLabel = Location.labelFrOf("forest");
            }
            case MINE -> {
                // même logique que case "quarry"
                vamp.grant("iron", 2);
                gains.add("+1 fer");
                vamp.grant("stone", 4);
                gains.add("+2 pierre");
                locKey = "quarry";
                locLabel = Location.labelFrOf("quarry");
            }
            case LIBRARY, LABORATORY, BALLROOM, ALTAR, FORGE -> {
                // même logique que "manor" quand la construction échoue
                int d100 = harvest.rollD100Tens();
                if ("VAMPIRE".equals(vamp.getRole())) {
                    vamp.grant("souls", d100 + 100);
                    gains.add("+" + (d100 + 100) + " âmes déchues");
                } else {
                    vamp.grant("gold", d100 + 100);
                    gains.add("+" + (d100 + 100) + " or");
                }
                locKey = "manor";
                locLabel = Location.labelFrOf("manor");
            }
            default -> {
                return; // au cas où d'autres infras plus tard
            }
        }

        if (!gains.isEmpty()) {
            String who = g.nameOf(vamp.getId());
            String line = "Récoltes — " + who + " (" + locLabel + ") : " + String.join(", ", gains);
            g.addHistory(line);
        }
    }

    /**
     * Fin de PREPHASE3 :
     * - soit on démarre / poursuit la file d'effets de lieu,
     * - soit on bascule en PHASE3 (récoltes + combats + pièges).
     *
     * Cette méthode :
     * - suppose qu'on est encore en PREPHASE3,
     * - suppose qu'il n'y a plus de choix "instable" en attente,
     * - ne vérifie PAS readyForPhase3 (utile pour les timers serveur).
     */
    /**
     * Fin de PREPHASE3 : Etape 1 - Résolution d'actions différées
     *
     * Si le vampire a une action en attente (Passage Secret, Image Miroir...),
     * on la déclenche maintenant (pause du flux).
     * Sinon, on passe à l'étape 2 (Effets de lieu ou PHASE3).
     */
    public void destroyRaidInfrasAtEnd(Game g) {
        // 1) Construire l'ensemble des infras à détruire
        java.util.EnumSet<Infra> toDestroy = java.util.EnumSet.noneOf(Infra.class);

        if (g.getInfrasToDestroyEndOfRaid() != null) {
            toDestroy.addAll(g.getInfrasToDestroyEndOfRaid());
        }
        if (g.isLaboratoryToDestroy()) {
            toDestroy.add(Infra.LABORATORY);
        }

        if (toDestroy.isEmpty()) {
            return;
        }

        // 2) Retirer les cartes de lieux correspondantes de la main des joueurs
        for (var p : g.getPlayers()) {
            var hand = p.getHand();

            for (Infra infra : toDestroy) {
                String locCode = infra.locationCode();
                if (locCode == null)
                    continue;
                hand.removeIf(card -> card.equals(locCode));
            }
        }

        // 3) Enlever les infras construites + effets permanents associés
        if (g.getBuiltInfras() != null) {
            for (Infra infra : toDestroy) {
                if (!g.getBuiltInfras().contains(infra))
                    continue;

                g.getBuiltInfras().remove(infra);

                String locCode = infra.locationCode();
                String label = (locCode != null ? Location.labelFrOf(locCode) : infra.name());

                switch (infra) {
                    case LABORATORY -> {
                        // Explosion du labo / destruction via Incendiaire
                        g.addHistory("Laboratoire occulte — le laboratoire est réduit en ruines.");
                    }
                    case BALLROOM -> {
                        g.setBallroomDeathDance(false);
                        g.setBallroomSneakAttack(false);
                        g.setBallroomBloodWaltz(false);
                        g.setBallroomBloodWaltzBestRoll(null);
                        g.setBallroomBloodWaltzRolls(new java.util.ArrayList<>());
                        g.addHistory(label + " est détruit par les flammes.");
                    }
                    case ALTAR -> {
                        g.setAltarCorrupted(null);
                        g.setAltarBiteOccurredThisRaid(false);
                        g.setAltarVampTookDamageThisRaid(false);
                        g.addHistory("L'autel est réduit en cendres.");
                    }
                    case SAWMILL, MINE, LIBRARY, FORGE -> {
                        g.addHistory(label + " est détruit par les flammes.");
                    }
                    default -> {
                        g.addHistory(label + " est détruit.");
                    }
                }
            }
        }

        // 4) Reset des flags pour le prochain raid
        if (g.getInfrasToDestroyEndOfRaid() != null) {
            g.getInfrasToDestroyEndOfRaid().clear();
        }
        g.setLaboratoryToDestroy(false);
    }

}
