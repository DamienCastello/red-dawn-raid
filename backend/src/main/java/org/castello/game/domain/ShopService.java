package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.Phase;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.castello.game.domain.EquipmentService.*;

/**
 * Domaine Boutique (PHASE4).
 *
 * Tous les achats/ventes : potions, cartes action (prix progressif),
 * argent, eau bénite, pisteur, améliorations d'arme/armure, revente de
 * ressources, transmutation (vampire), plus le Marchand itinérant et la
 * Transmutation avancée (bonus aléatoires avec paiement à confirmer).
 *
 * Modificateurs de prix : Avidité nocturne (+50), Charismatique (-20,
 * revente +10).
 */
@Service
public class ShopService {

    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final EquipmentService equipment;
    private final HarvestService harvest;
    private final LiveEvents live;

    public ShopService(GameStore store, Dice dice, DeckService decks,
            EquipmentService equipment, HarvestService harvest, LiveEvents live) {
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.equipment = equipment;
        this.harvest = harvest;
        this.live = live;
    }

    /** Ajoute un message au fil central ET l'émet en live. */
    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        live.message(g, msg);
    }

    @Transactional
    public Game rollMerchantItinerant(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");
        }

        Player p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!p.isAlive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        }
        if (!"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        }

        // le jet n'existe que si le joueur a déclenché le marchand
        if (!p.isMerchantPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no merchant roll pending");
        }
        if (p.getMerchantRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "merchant already rolled");
        }

        int d6 = dice.roll(6);
        p.setMerchantRoll(d6);
        p.setMerchantPending(false); // plus en attente
        p.setShopBonusBuyPending(false); // reset sécurité

        // reset offer
        p.setShopBonusKind(null);
        p.setShopBonusEquipId(null);
        p.setShopBonusEquipTier(null);

        String kind;
        String line;

        if (d6 == 1 || d6 == 2) {
            kind = "POTION";
            line = "Marchand itinérant — une potion est disponible à la boutique pour " + g.nameOf(userId) + ".";
        } else if (d6 == 3 || d6 == 4) {
            kind = "ELIXIR";
            line = "Marchand itinérant — un élixir est disponible à la boutique pour " + g.nameOf(userId) + ".";
        } else {
            // 5-6 : équipement perso
            int wTier = equipment.hunterWeaponTier(p.getWeapon());
            int aTier = equipment.hunterArmorTier(p.getArmor());

            Integer wOffer = (wTier < 2) ? Math.min(2, wTier + 1) : null;
            Integer aOffer = (aTier < 2) ? Math.min(2, aTier + 1) : null;

            // Règle : si T0/T1 => on propose celui qui est le plus bas, sinon 50/50
            boolean preferWeapon;
            if (wTier < aTier)
                preferWeapon = true;
            else if (aTier < wTier)
                preferWeapon = false;
            else
                preferWeapon = dice.nextBoolean();

            boolean weapon;
            Integer tier;

            if (preferWeapon && wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (!preferWeapon && aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else if (wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else {
                // déjà T2 partout => pas d’équipement (jamais T3) => fallback
                kind = "ELIXIR";
                p.setShopBonusKind(kind);
                g.addHistory("Marchand itinérant — " + g.nameOf(userId)
                        + " est déjà équipé au maximum (T2). Offre remplacée par un élixir.");
                store.save(g);
                store.afterCommit(() -> live.actionResolved(g, "MARCHAND_ITINERANT", userId, null));
                return g;
            }

            kind = weapon ? "EQUIP_WEAPON" : "EQUIP_ARMOR";

            String equipId = weapon ? equipment.merchantWeaponIdForTier(tier) : equipment.merchantArmorIdForTier(tier);

            p.setShopBonusEquipTier(tier); // sert au prix
            p.setShopBonusEquipId(equipId); // offre stable
            line = "Marchand itinérant — " + (weapon ? "une arme" : "une armure")
                    + " T" + tier + " est disponible à la boutique pour " + g.nameOf(userId) + ".";
        }

        p.setShopBonusKind(kind);
        g.addHistory(line);

        store.save(g);

        store.afterCommit(() -> {
            // juste pour forcer les clients à refresh le snapshot (sans modale spectateur)
            live.actionResolved(g, "MARCHAND_ITINERANT", userId, null);
        });

        return g;
    }

    @Transactional
    public Game rollAdvancedTransmutation(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");
        }

        Player p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!p.isAlive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        }
        if (!"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampires only");
        }

        // le jet n'existe que si le joueur a déclenché la carte
        if (!p.isMerchantPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no transmutation roll pending");
        }
        if (p.getMerchantRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "transmutation already rolled");
        }

        int d6 = dice.roll(6);
        p.setMerchantRoll(d6);
        p.setMerchantPending(false); // plus en attente
        p.setShopBonusBuyPending(false); // reset sécurité

        // reset offer
        p.setShopBonusKind(null);
        p.setShopBonusEquipId(null);
        p.setShopBonusEquipTier(null);

        String kind;
        String line;

        if (d6 >= 1 && d6 <= 3) {
            // 1-3: élixir aléatoire
            kind = "ELIXIR";
            line = "Transmutation avancée — un élixir est disponible dans l'antre pour " + g.nameOf(userId) + ".";
        } else {
            // 4-6 : équipement perso (vampire)
            int wTier = equipment.vampireWeaponTier(p.getWeapon());
            int aTier = equipment.vampireArmorTier(p.getArmor());

            Integer wOffer = (wTier < 2) ? Math.min(2, wTier + 1) : null;
            Integer aOffer = (aTier < 2) ? Math.min(2, aTier + 1) : null;

            // Règle : si T0/T1 => on propose celui qui est le plus bas, sinon 50/50
            boolean preferWeapon;
            if (wTier < aTier)
                preferWeapon = true;
            else if (aTier < wTier)
                preferWeapon = false;
            else
                preferWeapon = dice.nextBoolean();

            boolean weapon;
            Integer tier;

            if (preferWeapon && wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (!preferWeapon && aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else if (wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else {
                // déjà T2 partout => pas d'équipement (jamais T3) => fallback
                kind = "ELIXIR";
                p.setShopBonusKind(kind);
                g.addHistory("Transmutation avancée — " + g.nameOf(userId)
                        + " est déjà équipé au maximum (T2). Offre remplacée par un élixir.");
                store.save(g);
                store.afterCommit(() -> live.actionResolved(g, "ADVANCED_TRANSMUTATION", userId, null));
                return g;
            }

            kind = weapon ? "EQUIP_WEAPON" : "EQUIP_ARMOR";

            String equipId = weapon ? equipment.vampireWeaponIdForTier(tier) : equipment.vampireArmorIdForTier(tier);

            p.setShopBonusEquipTier(tier); // sert au prix
            p.setShopBonusEquipId(equipId); // offre stable
            line = "Transmutation avancée — " + (weapon ? "une arme" : "une armure")
                    + " T" + tier + " est disponible dans l'antre pour " + g.nameOf(userId) + ".";
        }

        p.setShopBonusKind(kind);
        g.addHistory(line);

        store.save(g);

        store.afterCommit(() -> {
            // juste pour forcer les clients à refresh le snapshot (sans modale spectateur)
            live.actionResolved(g, "ADVANCED_TRANSMUTATION", userId, null);
        });

        return g;
    }

    @Transactional
    public Game buyResource(String gameId, String userId, String resourceType) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        Player p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!p.isAlive())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        // Check if already bought this raid
        if (p.isResourceBoughtThisRaid())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà acheté une ressource ce raid");

        // Check gold
        if (p.getGold() < 100)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pas assez d'or (100 requis)");

        // Validate resource type
        if (!"WOOD".equals(resourceType) && !"IRON".equals(resourceType)
                && !"WATER".equals(resourceType) && !"HERBS".equals(resourceType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type de ressource invalide");

        // Deduct gold
        p.setGold(p.getGold() - 100);

        // Add resource
        switch (resourceType) {
            case "WOOD" -> p.setWood(p.getWood() + 1);
            case "IRON" -> p.setIron(p.getIron() + 1);
            case "WATER" -> p.setWater(p.getWater() + 1);
            case "HERBS" -> p.setHerbs(p.getHerbs() + 1);
        }

        // Mark as bought
        p.setResourceBoughtThisRaid(true);

        // Add history
        String resName = switch (resourceType) {
            case "WOOD" -> "bois";
            case "IRON" -> "fer";
            case "WATER" -> "eau";
            case "HERBS" -> "plantes";
            default -> resourceType;
        };
        String msg = g.nameOf(userId) + " a acheté 1 " + resName + " pour 100 pièces d'or.";
        g.addHistory(msg);

        store.save(g);
        store.afterCommit(() -> live.stuffBought(g, userId));

        return g;
    }

    @Transactional
    public Game startShopBonusPurchase(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        Player p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!p.isAlive())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        if (!"HUNTER".equals(p.getRole()) && !"VAMPIRE".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters and vampires only");

        String kind = p.getShopBonusKind();
        if (kind == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bonus item available");

        // perso : ouvre la modale paiement seulement pour CE joueur
        p.setShopBonusBuyPending(true);

        store.save(g);

        store.afterCommit(() -> {
            // refresh clients (ne déclenche pas de modale chez les autres)
            String mode = "VAMPIRE".equals(p.getRole()) ? "ADVANCED_TRANSMUTATION_BUY" : "MARCHAND_BONUS_BUY";
            live.actionStarted(g, mode, userId, null, null);
        });

        return g;
    }

    @Transactional
    public Game buyShopBonus(String gameId, String userId, String payment) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        Player p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!p.isAlive())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        boolean isHunter = "HUNTER".equals(p.getRole());
        boolean isVampire = "VAMPIRE".equals(p.getRole());

        if (!isHunter && !isVampire)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters and vampires only");

        // Validate payment mode based on role
        if (payment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment mode required");
        }
        if (isHunter && !"RESOURCE".equals(payment) && !"GOLD".equals(payment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hunters can pay with RESOURCE or GOLD");
        }
        if (isVampire && !"RESOURCE".equals(payment) && !"SOULS".equals(payment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vampires can pay with RESOURCE or SOULS");
        }

        String kind = p.getShopBonusKind();
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bonus item available");
        }

        // prix adaptables
        Integer tier = p.getShopBonusEquipTier();
        boolean greedy = g.isShopPricesIncreasedThisRaid();
        boolean charismatic = p.isCharismaticThisRaid();

        java.util.function.IntUnaryOperator goldCost = (base) -> {
            int c = base;
            if (greedy)
                c += 50;
            if (charismatic)
                c = Math.max(0, c - 20);
            return c;
        };

        String message;

        switch (kind) {
            case "POTION" -> {
                if ("RESOURCE".equals(payment)) {
                    final int waterCost = 1, herbsCost = 2;
                    if (p.getWater() < waterCost || p.getHerbs() < herbsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWater(p.getWater() - waterCost);
                    p.setHerbs(p.getHerbs() - herbsCost);
                } else {
                    int cost = goldCost.applyAsInt(30);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                }

                String potionId = decks.draw(g.getPotionDeck(), g.getPotionDiscard());
                if (potionId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

                if (p.getPotions() == null)
                    p.setPotions(new java.util.ArrayList<>());
                p.getPotions().add(potionId);

                message = g.nameOf(userId) + " achète une potion (" + potionId + ") via le marchand itinérant ("
                        + ("RESOURCE".equals(payment) ? "ressources" : "or") + ").";
                g.addHistory(message);
            }

            case "ELIXIR" -> {
                if ("RESOURCE".equals(payment)) {
                    final int waterCost = 2, herbsCost = 4;
                    if (p.getWater() < waterCost || p.getHerbs() < herbsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWater(p.getWater() - waterCost);
                    p.setHerbs(p.getHerbs() - herbsCost);
                } else if ("GOLD".equals(payment)) {
                    int cost = goldCost.applyAsInt(60);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                } else if ("SOULS".equals(payment)) {
                    final int soulsCost = 50;
                    if (p.getSouls() < soulsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing souls");
                    p.setSouls(p.getSouls() - soulsCost);
                }

                String elixirId = decks.draw(g.getElixirDeck(), g.getElixirDiscard());
                if (elixirId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no elixirs left");

                // FIX: elixirs dans la bonne liste
                if (p.getElixirs() == null)
                    p.setElixirs(new java.util.ArrayList<>());
                p.getElixirs().add(elixirId);

                String source = isVampire ? "transmutation avancée" : "le marchand itinérant";
                String paymentLabel = "RESOURCE".equals(payment) ? "ressources"
                        : ("GOLD".equals(payment) ? "or" : "âmes déchues");
                message = g.nameOf(userId) + " achète un élixir (" + elixirId + ") via " + source + " (" + paymentLabel
                        + ").";
                g.addHistory(message);
            }

            case "EQUIP_WEAPON" -> {
                int t = (tier == null) ? 1 : Math.max(1, Math.min(2, tier)); // sécurité 1..2
                String weaponId = p.getShopBonusEquipId();
                if (weaponId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid offer");

                // coût selon tier
                int woodCost = (t == 2) ? 2 : 1;
                int ironCost = (t == 2) ? 2 : 1;
                int baseGold = (t == 2) ? 150 : 100;
                int soulsCost = (t == 2) ? 150 : 100;

                if ("RESOURCE".equals(payment)) {
                    if (p.getWood() < woodCost || p.getIron() < ironCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWood(p.getWood() - woodCost);
                    p.setIron(p.getIron() - ironCost);
                } else if ("GOLD".equals(payment)) {
                    int cost = goldCost.applyAsInt(baseGold);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                } else if ("SOULS".equals(payment)) {
                    if (p.getSouls() < soulsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing souls");
                    p.setSouls(p.getSouls() - soulsCost);
                }

                int current = isVampire ? equipment.vampireWeaponTier(p.getWeapon()) : equipment.hunterWeaponTier(p.getWeapon());
                if (current >= t)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "tu as déjà une arme supérieure");

                p.setWeapon(weaponId);
                switch (t) {
                    case 1 -> p.setAttackDice("D6");
                    case 2 -> p.setAttackDice("D8");
                }

                String source = isVampire ? "transmutation avancée" : "le marchand";
                String paymentLabel = "RESOURCE".equals(payment) ? "ressources"
                        : ("GOLD".equals(payment) ? "or" : "âmes déchues");
                message = g.nameOf(userId) + " achète une arme T" + t + " (" + weaponId + ") via " + source + " ("
                        + paymentLabel + ").";
                g.addHistory(message);
            }

            case "EQUIP_ARMOR" -> {
                int t = (tier == null) ? 1 : Math.max(1, Math.min(2, tier));
                String armorId = p.getShopBonusEquipId();
                if (armorId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid offer");

                int woodCost = (t == 2) ? 2 : 1;
                int ironCost = (t == 2) ? 2 : 1;
                int baseGold = (t == 2) ? 150 : 100;
                int soulsCost = (t == 2) ? 150 : 100;

                if ("RESOURCE".equals(payment)) {
                    if (p.getWood() < woodCost || p.getIron() < ironCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWood(p.getWood() - woodCost);
                    p.setIron(p.getIron() - ironCost);
                } else if ("GOLD".equals(payment)) {
                    int cost = goldCost.applyAsInt(baseGold);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                } else if ("SOULS".equals(payment)) {
                    if (p.getSouls() < soulsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing souls");
                    p.setSouls(p.getSouls() - soulsCost);
                }

                int current = isVampire ? equipment.vampireArmorTier(p.getArmor()) : equipment.hunterArmorTier(p.getArmor());
                if (current >= t)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "tu as déjà une armure supérieure");

                p.setArmor(armorId);
                switch (t) {
                    case 1 -> p.setDefenseDice("D6");
                    case 2 -> p.setDefenseDice("D8");
                }

                String source = isVampire ? "transmutation avancée" : "le marchand";
                String paymentLabel = "RESOURCE".equals(payment) ? "ressources"
                        : ("GOLD".equals(payment) ? "or" : "âmes déchues");
                message = g.nameOf(userId) + " achète une armure T" + t + " (" + armorId + ") via " + source + " ("
                        + paymentLabel + ").";
                g.addHistory(message);
            }

            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid bonus kind");
        }

        // consume l'offre perso + ferme modale paiement perso
        p.setShopBonusKind(null);
        p.setShopBonusEquipId(null);
        p.setShopBonusEquipTier(null);
        p.setShopBonusBuyPending(false);
        p.setMerchantRoll(null);

        equipment.rebuildEquipmentMods(g);
        store.save(g);

        final String fMessage = message;

        store.afterCommit(() -> {
            pushLive(g, fMessage);
            String mode = isVampire ? "ADVANCED_TRANSMUTATION_BUY" : "MARCHAND_BONUS_BUY";
            live.actionResolved(g, mode, userId, null);
        });

        return g;
    }

    @Transactional
    public Game cancelShopBonusPurchase(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        // annulation basée sur l'état joueur
        if (!p.isShopBonusBuyPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no merchant buy in progress");
        }

        // fermer la modale achat perso
        p.setShopBonusBuyPending(false);

        // sécurité : si un currentAction marchand traîne encore, on le ferme
        Game.Action a = g.getCurrentAction();
        if (a != null
                && "MARCHAND_BONUS_BUY".equals(a.getMode())
                && userId.equals(a.getOwnerId())) {
            g.setCurrentAction(null);
        }

        String msg = g.nameOf(userId) + " renonce à acheter l’objet du marchand itinérant.";
        g.addHistory(msg);

        store.save(g);

        store.afterCommit(() -> {
            pushLive(g, msg);
            live.actionResolved(g, "MARCHAND_BONUS_BUY", userId, null);
        });

        return g;
    }

    // ACTIONS VAMPIRE
    @Transactional
    public Game buyPotion(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!p.isAlive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        int potionsLeft = decks.availableSize(g.getPotionDeck(), g.getPotionDiscard());
        if (potionsLeft <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

        if (p.getWater() < 4 || p.getHerbs() < 3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");

        // paiement
        p.setWater(p.getWater() - 4);
        p.setHerbs(p.getHerbs() - 3);

        // tirage depuis un VRAI deck (avec reshuffle auto depuis la défausse)
        String type = decks.drawPotion(g);
        if (type == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

        if (p.getPotions() == null) {
            p.setPotions(new java.util.ArrayList<>());
        }
        p.getPotions().add(type);

        g.addHistory(g.nameOf(userId) + " achète une potion.");

        // nb restant réellement piochable (deck ou futur reshuffle)
        int remaining = decks.availableSize(g.getPotionDeck(), g.getPotionDiscard());

        store.save(g);

        final String fType = type;
        final int fRemaining = remaining;
        store.afterCommit(() -> live.potionBought(g, userId, fType, fRemaining));

        return g;
    }

    @Transactional
    public Game buyAction(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!p.isAlive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        boolean isVamp = "VAMPIRE".equals(p.getRole());
        boolean isHunter = "HUNTER".equals(p.getRole());

        List<String> deck = isVamp ? g.getVampActionsDeck() : g.getHunterActionsDeck();
        List<String> discard = isVamp ? g.getVampActionsDiscard() : g.getHunterActionsDiscard();

        int actionsLeft = decks.availableSize(deck, discard);
        if (actionsLeft <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no actions left");

        if (g.getActionCardsBoughtThisRaid() == null) {
            g.setActionCardsBoughtThisRaid(new java.util.HashMap<>());
        }
        int boughtCount = g.getActionCardsBoughtThisRaid().getOrDefault(userId, 0);

        final int baseCost = (boughtCount + 1) * 50;
        int costSouls = baseCost;
        int costGold = baseCost;

        if (isHunter) {
            // +50 si Avidité nocturne
            if (g.isShopPricesIncreasedThisRaid()) {
                costGold += 50;
            }
            // -20 si ce chasseur est charismatique
            if (p.isCharismaticThisRaid()) {
                costGold = Math.max(0, costGold - 20);
            }
        }

        // paiement
        if (isVamp) {
            if (p.getSouls() < costSouls)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
            p.setSouls(p.getSouls() - costSouls);
        } else if (isHunter) {
            if (p.getGold() < costGold)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
            p.setGold(p.getGold() - costGold);
        }

        // Tirage (gère le reshuffle auto si deck vide + défausse non vide)
        String type = isVamp ? decks.drawVampAction(g) : decks.drawHunterAction(g);
        if (type == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no actions left");

        if (p.getActions() == null) {
            p.setActions(new java.util.ArrayList<>());
        }
        p.getActions().add(type);

        g.addHistory(
                g.nameOf(userId) + " pioche une carte d'action (" +
                        (isVamp ? "camp vampire" : "camp chasseurs") + ").");

        int remaining = decks.availableSize(deck, discard);

        g.getActionCardsBoughtThisRaid().put(userId, boughtCount + 1);

        store.save(g);

        final String fType = type;
        final int fRemaining = remaining;
        store.afterCommit(() -> {
            live.actionBought(g, userId, fType, fRemaining);
        });

        return g;
    }

    @Transactional
    public Game buySilver(String gameId, String userId, int qty) {
        if (qty <= 0)
            qty = 1;

        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!p.isAlive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        final int baseUnitCost = 50;
        int unitCost = baseUnitCost;

        if (g.isShopPricesIncreasedThisRaid()) {
            unitCost += 50;
        }
        if (p.isCharismaticThisRaid()) {
            unitCost = Math.max(0, unitCost - 20);
        }

        int cost = unitCost * qty;
        if (p.getGold() < cost)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough gold");

        p.setGold(p.getGold() - cost);
        p.setSilver(p.getSilver() + qty);
        g.addHistory(g.nameOf(userId) + " achète " + qty + " argent (" + cost + " or).");

        store.save(g);

        final int fQty = qty;
        final int fCost = cost;

        store.afterCommit(() -> live.silverBought(g, userId, fQty, fCost));
        return g;
    }

    @Transactional
    public Game buyHolyWaterAction(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!p.isAlive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        if (!"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        }

        // Coût de base
        final int baseGoldCost = 150;
        final int waterCost = 4;

        int costGold = baseGoldCost;

        if (g.isShopPricesIncreasedThisRaid()) {
            costGold += 50;
        }
        if (p.isCharismaticThisRaid()) {
            costGold = Math.max(0, costGold - 20);
        }

        // Vérif ressources
        if (p.getWater() < waterCost || p.getGold() < costGold) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
        }

        // Paiement
        p.setWater(p.getWater() - waterCost);
        p.setGold(p.getGold() - costGold);

        // Ajout direct de la carte dans la main
        if (p.getActions() == null) {
            p.setActions(new java.util.ArrayList<>());
        }
        p.getActions().add("EAU_BENITE");

        g.addHistory(
                g.nameOf(userId)
                        + " achète une carte Eau bénite ("
                        + waterCost + " eaux pures, " + costGold + " or).");

        store.save(g);

        final int fCostGold = costGold;
        final int fCostWater = waterCost;
        store.afterCommit(() -> {
            live.holyWaterActionBought(g, userId, fCostWater, fCostGold);
        });

        return g;
    }

    @Transactional
    public Game buyTrackingAction(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!p.isAlive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        if (!"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        }

        // Coût de base
        final int baseGoldCost = 100;

        int costGold = baseGoldCost;

        if (g.isShopPricesIncreasedThisRaid()) {
            costGold += 50;
        }
        if (p.isCharismaticThisRaid()) {
            costGold = Math.max(0, costGold - 20);
        }

        // Vérif ressources
        if (p.getGold() < costGold) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
        }

        // Paiement
        p.setGold(p.getGold() - costGold);

        int cur = (p.getShopPisteurCount() == null) ? 0 : p.getShopPisteurCount();
        p.setShopPisteurCount(cur + 1);

        // Ajout direct de la carte dans la main
        if (p.getActions() == null) {
            p.setActions(new java.util.ArrayList<>());
        }
        p.getActions().add("PISTEUR");

        g.addHistory(
                g.nameOf(userId)
                        + " achète une carte Pisteur (" + costGold + " or).");

        store.save(g);

        final int fCostGold = costGold;
        store.afterCommit(() -> {
            live.trackingActionBought(g, userId, fCostGold);
        });

        return g;
    }

    @Transactional
    public Game buyUpgradeWeapon(String gameId, String userId, int expectedTier, String expectedType) {
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        if (!p.isAlive())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");

        int cur = equipment.hunterWeaponTier(p.getWeapon());
        if (cur >= 2)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "arme déjà au maximum");

        int tier = Math.min(2, cur + 1); // 0->1, 1->2

        // Le front DOIT envoyer ce qu'il affiche
        if (expectedTier != tier) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "offer tier mismatch (refresh)");
        }

        // Normalisation type
        String type = (expectedType == null) ? null : expectedType.trim().toUpperCase();
        if (!"BLEED".equals(type) && !"RANGE".equals(type) && !"STUN".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid offer type");
        }

        // Maps (canon serveur)
        if (g.getShopWeaponOfferTypeByHunter() == null) {
            g.setShopWeaponOfferTypeByHunter(new java.util.HashMap<>());
        }
        if (g.getShopWeaponOfferTierByHunter() == null) {
            g.setShopWeaponOfferTierByHunter(new java.util.HashMap<>());
        }

        var typeMap = g.getShopWeaponOfferTypeByHunter();
        var tierMap = g.getShopWeaponOfferTierByHunter();

        // on aligne l’offre serveur sur ce que le client affiche
        // (ça supprime définitivement le “j’ai acheté spear mais j’ai mace”)
        tierMap.put(userId, tier);
        typeMap.put(userId, type);

        // Mapping type -> weaponId (doit matcher tes assets)
        String weaponId;
        if (tier == 1) {
            weaponId = switch (type) {
                case "BLEED" -> H_WEAPON_T1_SWORD;
                case "RANGE" -> H_WEAPON_T1_SPEAR;
                case "STUN" -> H_WEAPON_T1_MACE;
                default -> H_WEAPON_T1_SWORD;
            };
        } else { // tier == 2
            weaponId = switch (type) {
                case "BLEED" -> H_WEAPON_T2_HALBERD;
                case "RANGE" -> H_WEAPON_T2_CROSSBOW;
                case "STUN" -> H_WEAPON_T2_HAMMER;
                default -> H_WEAPON_T2_HALBERD;
            };
        }

        // Coûts (doit matcher le front)
        int woodCost = (tier == 1) ? 2 : 3;
        int ironCost = (tier == 1) ? 2 : 3;

        if (p.getWood() < woodCost || p.getIron() < ironCost)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");

        p.setWood(p.getWood() - woodCost);
        p.setIron(p.getIron() - ironCost);

        p.setWeapon(weaponId);
        if (tier == 1)
            p.setAttackDice("D6");
        if (tier == 2)
            p.setAttackDice("D8");

        String msg = g.nameOf(userId)
                + " forge une arme de tier " + tier
                + " (" + weaponId + ").";
        g.addHistory(msg);

        // Préparer l’offre suivante (T2) ou clear (si T2 acheté)
        if (tier >= 2) {
            typeMap.remove(userId);
            tierMap.remove(userId);
        } else {
            int nextTier = 2;
            int r2 = dice.nextInt(3);
            String nextType = (r2 == 0) ? "BLEED" : (r2 == 1) ? "RANGE" : "STUN";
            tierMap.put(userId, nextTier);
            typeMap.put(userId, nextType);
        }

        equipment.rebuildEquipmentMods(g);
        store.save(g);

        store.afterCommit(() -> {
            pushLive(g, msg);
            live.stuffBought(g, userId);
        });

        return g;
    }

    @Transactional
    public Game buyUpgradeArmor(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        if (!p.isAlive())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");

        int cur = equipment.hunterArmorTier(p.getArmor());
        if (cur >= 2)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "armure déjà au maximum");

        int tier = cur + 1;
        tier = Math.min(2, tier);

        String armorId = (tier == 1) ? H_ARMOR_T1_BRIGANDINE : H_ARMOR_T2_HAUBERT;

        int woodCost = (tier == 1) ? 2 : 3;
        int ironCost = (tier == 1) ? 2 : 3;

        if (p.getWood() < woodCost || p.getIron() < ironCost)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");

        p.setWood(p.getWood() - woodCost);
        p.setIron(p.getIron() - ironCost);

        p.setArmor(armorId);
        if (tier == 1)
            p.setDefenseDice("D6");
        if (tier == 2)
            p.setDefenseDice("D8");

        String msg = g.nameOf(userId)
                + " achète une armure de tier " + tier
                + " (" + armorId + ").";
        g.addHistory(msg);

        equipment.rebuildEquipmentMods(g);
        store.save(g);

        store.afterCommit(() -> {
            pushLive(g, msg);
            live.stuffBought(g, userId);
        });

        return g;
    }

    @Transactional
    public Game sellResource(String gameId, String userId, String res, int qty) {
        if (qty <= 0)
            qty = 1;
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        Set<String> allowed = Set.of("wood", "herbs", "stone", "iron", "water");
        if (!allowed.contains(res))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource");

        // vérifier stock
        int have = switch (res) {
            case "wood" -> p.getWood();
            case "herbs" -> p.getHerbs();
            case "stone" -> p.getStone();
            case "iron" -> p.getIron();
            case "water" -> p.getWater();
            default -> 0;
        };
        if (have < qty)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough resource");

        // débiter
        switch (res) {
            case "wood" -> p.setWood(have - qty);
            case "herbs" -> p.setHerbs(have - qty);
            case "stone" -> p.setStone(have - qty);
            case "iron" -> p.setIron(have - qty);
            case "water" -> p.setWater(have - qty);
        }
        int base = 10;
        boolean charismatic = p.isCharismaticThisRaid();
        if (charismatic)
            base += 10;

        int gain = base * qty;
        p.setGold(p.getGold() + gain);
        g.addHistory(g.nameOf(userId) + " vend " + qty + " " + harvest.resLabelFr(res) + " (+" + gain + " or).");

        store.save(g);

        final String fRes = res;
        final int fQty = qty;
        final int fGain = gain;

        store.afterCommit(() -> live.resourceSold(g, userId, fRes, fQty, fGain));
        return g;
    }

    @Transactional
    public Game transmute(String gameId, String userId, String recipe) {
        Game g = store.loadForUpdate(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = g.findPlayer(userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"VAMPIRE".equals(p.getRole()) && !"SERVANT".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire/servants only");

        switch (recipe) {
            case "WOOD_TO_IRON" -> { // 2 bois + 1 eau → +2 fer
                if (p.getWood() < 2 || p.getWater() < 1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setWood(p.getWood() - 2);
                p.setWater(p.getWater() - 1);
                p.setIron(p.getIron() + 2);
                g.addHistory(g.nameOf(userId) + " transmute: 2 bois + 1 eau → +2 fer.");
            }
            case "IRON_TO_WOOD" -> { // 2 fer + 1 eau → +2 bois
                if (p.getIron() < 2 || p.getWater() < 1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setIron(p.getIron() - 2);
                p.setWater(p.getWater() - 1);
                p.setWood(p.getWood() + 2);
                g.addHistory(g.nameOf(userId) + " transmute: 2 fer + 1 eau → +2 bois.");
            }
            case "TRINITY_TO_SOULS" -> { // 1 bois + 1 fer + 1 eau → +30 âmes
                if (p.getWood() < 1 || p.getIron() < 1 || p.getWater() < 1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 1);
                p.setWater(p.getWater() - 1);
                p.setSouls(p.getSouls() + 30);
                g.addHistory(g.nameOf(userId) + " transmute: 1 bois + 1 fer + 1 eau → +30 âmes.");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown recipe");
        }

        store.save(g);
        store.afterCommit(() -> live.transmuted(g, userId, recipe));
        return g;
    }

}
