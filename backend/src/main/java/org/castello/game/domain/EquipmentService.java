package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.Infra;
import org.castello.game.StatMod;
import org.castello.player.Player;
import org.castello.game.support.Dice;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Domaine Équipement (armes / armures, chasseurs et camp vampire).
 *
 * Les équipements sont identifiés par des codes ("H_WEAPON_T1_SWORD", ...)
 * portant le camp (H_/V_), le tier (T1..T3) et le modèle. Ce service
 * centralise : les tiers, les effets d'arme (saignement, étourdissement,
 * distance, régénération), les bonus d'armure, les coûts de forge et la
 * conversion d'équipement chasseur -> serviteur.
 */
@Service
public class EquipmentService {

    private final Dice dice;

    public EquipmentService(Dice dice) {
        this.dice = dice;
    }

    // --- ÉQUIPEMENTS FORGE (codes) ---

    // CHASSEURS — armes T1
    public static final String H_WEAPON_T1_SWORD = "H_WEAPON_T1_SWORD"; // épée fer, bleed +1
    public static final String H_WEAPON_T1_MACE = "H_WEAPON_T1_MACE"; // masse fer, stun -1
    public static final String H_WEAPON_T1_SPEAR = "H_WEAPON_T1_SPEAR"; // lance fer, range 1

    // CHASSEURS — armure T1
    public static final String H_ARMOR_T1_BRIGANDINE = "H_ARMOR_T1_BRIGANDINE"; // D6

    // CHASSEURS — armes T2
    public static final String H_WEAPON_T2_HALBERD = "H_WEAPON_T2_HALBERD"; // bleed +2
    public static final String H_WEAPON_T2_HAMMER = "H_WEAPON_T2_HAMMER"; // stun -2
    public static final String H_WEAPON_T2_CROSSBOW = "H_WEAPON_T2_CROSSBOW"; // range 1-2

    // CHASSEURS — armure T2
    public static final String H_ARMOR_T2_HAUBERT = "H_ARMOR_T2_HAUBERT"; // D8

    // CHASSEURS — armes T3
    public static final String H_WEAPON_T3_WRIST_BLADES = "H_WEAPON_T3_WRIST_BLADES"; // bleed +3
    public static final String H_WEAPON_T3_FLAIL = "H_WEAPON_T3_FLAIL"; // stun -3
    public static final String H_WEAPON_T3_PISTOL = "H_WEAPON_T3_PISTOL"; // range 1-3

    // CHASSEURS — armure T3
    public static final String H_ARMOR_T3_PLATE_SILVER = "H_ARMOR_T3_PLATE_SILVER"; // D20 + effet morsure

    // VAMPIRE/SERVITEUR — armes
    public static final String V_WEAPON_T1_SCYTHE = "V_WEAPON_T1_SCYTHE"; // D6, regen 1 sur roll 7-8
    public static final String V_WEAPON_T2_SWORD = "V_WEAPON_T2_SWORD"; // D8, regen 2 sur roll 10-12
    public static final String V_WEAPON_T3_CLAWS = "V_WEAPON_T3_CLAWS"; // D12, regen 3 sur roll >=10

    // VAMPIRE/SERVITEUR — armures
    public static final String V_ARMOR_T1_CARAPACE = "V_ARMOR_T1_CARAPACE"; // D6 +1 DEF
    public static final String V_ARMOR_T2_HAUBERT = "V_ARMOR_T2_HAUBERT"; // D8 +1 DEF
    public static final String V_ARMOR_T3_ECORCE = "V_ARMOR_T3_ECORCE"; // D12 + esquive sur 12

    public int currentWeaponTier(Player p) {
        String w = p.getWeapon();
        if (w == null)
            return 0;
        if (w.contains("_T1_"))
            return 1;
        if (w.contains("_T2_"))
            return 2;
        if (w.contains("_T3_"))
            return 3;
        return 0;
    }

    public int currentArmorTier(Player p) {
        String a = p.getArmor();
        if (a == null)
            return 0;
        if (a.contains("_T1_"))
            return 1;
        if (a.contains("_T2_"))
            return 2;
        if (a.contains("_T3_"))
            return 3;
        return 0;
    }

    public boolean hasResourcesForEquipment(Player p, String equipCode) {
        // CHASSEURS
        switch (equipCode) {
            // --- T1 hunters ---
            case H_WEAPON_T1_SWORD -> {
                return p.getWood() >= 1 && p.getIron() >= 2;
            }
            case H_WEAPON_T1_MACE -> {
                return p.getWood() >= 1 && p.getIron() >= 2;
            }
            case H_WEAPON_T1_SPEAR -> {
                return p.getWood() >= 2 && p.getIron() >= 1;
            }
            case H_ARMOR_T1_BRIGANDINE -> {
                return p.getIron() >= 3;
            }

            // --- T2 hunters ---
            case H_WEAPON_T2_HALBERD -> {
                return p.getWood() >= 2 && p.getIron() >= 2;
            }
            case H_WEAPON_T2_HAMMER -> {
                return p.getWood() >= 1 && p.getIron() >= 3;
            }
            case H_WEAPON_T2_CROSSBOW -> {
                return p.getWood() >= 3 && p.getIron() >= 1;
            }
            case H_ARMOR_T2_HAUBERT -> {
                return p.getIron() >= 4;
            }

            // --- T3 hunters ---
            case H_WEAPON_T3_WRIST_BLADES -> {
                return p.getIron() >= 4 && p.getSilver() >= 3;
            }
            case H_WEAPON_T3_FLAIL -> {
                return p.getWood() >= 2 && p.getIron() >= 2 && p.getSilver() >= 3;
            }
            case H_WEAPON_T3_PISTOL -> {
                return p.getWood() >= 4 && p.getSilver() >= 3;
            }
            case H_ARMOR_T3_PLATE_SILVER -> {
                return p.getIron() >= 4 && p.getSilver() >= 4;
            }

            // --- VAMPIRE / SERVITEUR ---
            case V_WEAPON_T1_SCYTHE -> {
                return p.getWood() >= 2 && p.getIron() >= 2 && p.getSouls() >= 40;
            }
            case V_ARMOR_T1_CARAPACE -> {
                return p.getIron() >= 3 && p.getSouls() >= 40;
            }

            case V_WEAPON_T2_SWORD -> {
                return p.getWood() >= 2 && p.getIron() >= 4 && p.getSouls() >= 60;
            }
            case V_ARMOR_T2_HAUBERT -> {
                return p.getIron() >= 4 && p.getSouls() >= 60;
            }

            case V_WEAPON_T3_CLAWS -> {
                return p.getWood() >= 5 && p.getIron() >= 5 && p.getSouls() >= 100;
            }
            case V_ARMOR_T3_ECORCE -> {
                return p.getWood() >= 2 && p.getIron() >= 4 && p.getSouls() >= 100;
            }

            default -> {
                return false;
            }
        }
    }

    public void payResourcesForEquipment(Player p, String equipCode) {
        switch (equipCode) {
            // CHASSEURS T1
            case H_WEAPON_T1_SWORD -> {
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 2);
            }
            case H_WEAPON_T1_MACE -> {
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 2);
            }
            case H_WEAPON_T1_SPEAR -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 1);
            }
            case H_ARMOR_T1_BRIGANDINE -> {
                p.setIron(p.getIron() - 3);
            }

            // CHASSEURS T2
            case H_WEAPON_T2_HALBERD -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 2);
            }
            case H_WEAPON_T2_HAMMER -> {
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 3);
            }
            case H_WEAPON_T2_CROSSBOW -> {
                p.setWood(p.getWood() - 3);
                p.setIron(p.getIron() - 1);
            }
            case H_ARMOR_T2_HAUBERT -> {
                p.setIron(p.getIron() - 4);
            }

            // CHASSEURS T3
            case H_WEAPON_T3_WRIST_BLADES -> {
                p.setIron(p.getIron() - 4);
                p.setSilver(p.getSilver() - 3);
            }
            case H_WEAPON_T3_FLAIL -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 2);
                p.setSilver(p.getSilver() - 3);
            }
            case H_WEAPON_T3_PISTOL -> {
                p.setWood(p.getWood() - 4);
                p.setSilver(p.getSilver() - 3);
            }
            case H_ARMOR_T3_PLATE_SILVER -> {
                p.setIron(p.getIron() - 4);
                p.setSilver(p.getSilver() - 4);
            }

            // VAMPIRE / SERVITEUR
            case V_WEAPON_T1_SCYTHE -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 2);
                p.setSouls(p.getSouls() - 40);
            }
            case V_ARMOR_T1_CARAPACE -> {
                p.setIron(p.getIron() - 3);
                p.setSouls(p.getSouls() - 40);
            }
            case V_WEAPON_T2_SWORD -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 4);
                p.setSouls(p.getSouls() - 60);
            }
            case V_ARMOR_T2_HAUBERT -> {
                p.setIron(p.getIron() - 4);
                p.setSouls(p.getSouls() - 60);
            }
            case V_WEAPON_T3_CLAWS -> {
                p.setWood(p.getWood() - 5);
                p.setIron(p.getIron() - 5);
                p.setSouls(p.getSouls() - 100);
            }
            case V_ARMOR_T3_ECORCE -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 4);
                p.setSouls(p.getSouls() - 100);
            }
        }
    }

    public boolean canForgeAnything(Game g, Player p) {
        return !listForgeOptionsForPlayer(g, p).isEmpty();
    }

    /**
     * Retourne la liste des codes d'équipement que ce joueur PEUT forger
     * maintenant (Tier suivant dispo + ressources suffisantes).
     */
    public java.util.List<String> listForgeOptionsForPlayer(Game g, Player p) {
        java.util.List<String> opts = new java.util.ArrayList<>();

        boolean isHunter = "HUNTER".equals(p.getRole());
        boolean isVSide = "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole());

        if (!isHunter && !isVSide) {
            return opts;
        }

        int wTier = currentWeaponTier(p);
        int aTier = currentArmorTier(p);

        if (isHunter) {
            // --- Armes chasseur ---
            if (wTier == 0) {
                // accès T1
                for (String code : new String[] {
                        H_WEAPON_T1_SWORD, H_WEAPON_T1_MACE, H_WEAPON_T1_SPEAR
                }) {
                    if (hasResourcesForEquipment(p, code))
                        opts.add(code);
                }
            } else if (wTier == 1) {
                for (String code : new String[] {
                        H_WEAPON_T2_HALBERD, H_WEAPON_T2_HAMMER, H_WEAPON_T2_CROSSBOW
                }) {
                    if (hasResourcesForEquipment(p, code))
                        opts.add(code);
                }
            } else if (wTier == 2) {
                for (String code : new String[] {
                        H_WEAPON_T3_WRIST_BLADES, H_WEAPON_T3_FLAIL, H_WEAPON_T3_PISTOL
                }) {
                    if (hasResourcesForEquipment(p, code))
                        opts.add(code);
                }
            }
            // pas de T4

            // --- Armures chasseur ---
            if (aTier == 0) {
                if (hasResourcesForEquipment(p, H_ARMOR_T1_BRIGANDINE)) {
                    opts.add(H_ARMOR_T1_BRIGANDINE);
                }
            } else if (aTier == 1) {
                if (hasResourcesForEquipment(p, H_ARMOR_T2_HAUBERT)) {
                    opts.add(H_ARMOR_T2_HAUBERT);
                }
            } else if (aTier == 2) {
                if (hasResourcesForEquipment(p, H_ARMOR_T3_PLATE_SILVER)) {
                    opts.add(H_ARMOR_T3_PLATE_SILVER);
                }
            }
        } else if (isVSide) {
            // --- Armes vampire/serviteur ---
            if (wTier == 0) {
                if (hasResourcesForEquipment(p, V_WEAPON_T1_SCYTHE)) {
                    opts.add(V_WEAPON_T1_SCYTHE);
                }
            } else if (wTier == 1) {
                if (hasResourcesForEquipment(p, V_WEAPON_T2_SWORD)) {
                    opts.add(V_WEAPON_T2_SWORD);
                }
            } else if (wTier == 2) {
                if (hasResourcesForEquipment(p, V_WEAPON_T3_CLAWS)) {
                    opts.add(V_WEAPON_T3_CLAWS);
                }
            }

            // --- Armures vampire/serviteur ---
            if (aTier == 0) {
                if (hasResourcesForEquipment(p, V_ARMOR_T1_CARAPACE)) {
                    opts.add(V_ARMOR_T1_CARAPACE);
                }
            } else if (aTier == 1) {
                if (hasResourcesForEquipment(p, V_ARMOR_T2_HAUBERT)) {
                    opts.add(V_ARMOR_T2_HAUBERT);
                }
            } else if (aTier == 2) {
                if (hasResourcesForEquipment(p, V_ARMOR_T3_ECORCE)) {
                    opts.add(V_ARMOR_T3_ECORCE);
                }
            }
        }

        return opts;
    }

    public void applyForge(Game g, Player p, String equipCode) {
        // sécurité : vérifier que c'est bien une option valide
        if (!listForgeOptionsForPlayer(g, p).contains(equipCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "forge option not allowed for player");
        }

        // payer
        payResourcesForEquipment(p, equipCode);

        // appliquer stats
        switch (equipCode) {
            // --- weapons ---
            case H_WEAPON_T1_SWORD, H_WEAPON_T1_MACE, H_WEAPON_T1_SPEAR, V_WEAPON_T1_SCYTHE -> {
                p.setWeapon(equipCode);
                p.setAttackDice("D6");
            }
            case H_WEAPON_T2_HALBERD, H_WEAPON_T2_HAMMER, H_WEAPON_T2_CROSSBOW, V_WEAPON_T2_SWORD -> {
                p.setWeapon(equipCode);
                p.setAttackDice("D8");
            }
            case H_WEAPON_T3_WRIST_BLADES, H_WEAPON_T3_FLAIL, H_WEAPON_T3_PISTOL, V_WEAPON_T3_CLAWS -> {
                p.setWeapon(equipCode);
                p.setAttackDice("D12");
            }

            // --- armors ---
            case H_ARMOR_T1_BRIGANDINE, V_ARMOR_T1_CARAPACE -> {
                p.setArmor(equipCode);
                p.setDefenseDice("D6");
            }
            case H_ARMOR_T2_HAUBERT, V_ARMOR_T2_HAUBERT -> {
                p.setArmor(equipCode);
                p.setDefenseDice("D8");
            }
            case H_ARMOR_T3_PLATE_SILVER, V_ARMOR_T3_ECORCE -> {
                p.setArmor(equipCode);
                p.setDefenseDice("D12");
            }

            default -> throw new IllegalArgumentException("unknown equipment: " + equipCode);
        }

        String who = g.nameOf(p.getId());
        g.addHistory("Forge — " + who + " fabrique " + labelEquipmentFr(equipCode) + " et s'en équipe.");
    }

    public String labelEquipmentFr(String equipCode) {
        return switch (equipCode) {
            // Hunters
            case H_WEAPON_T1_SWORD -> "une épée en fer";
            case H_WEAPON_T1_MACE -> "une masse en fer";
            case H_WEAPON_T1_SPEAR -> "une lance en fer";
            case H_ARMOR_T1_BRIGANDINE -> "une brigandine en fer";

            case H_WEAPON_T2_HALBERD -> "une hallebarde en fer";
            case H_WEAPON_T2_HAMMER -> "un marteau à deux mains en fer";
            case H_WEAPON_T2_CROSSBOW -> "une arbalète en fer";
            case H_ARMOR_T2_HAUBERT -> "un haubert de fer";

            case H_WEAPON_T3_WRIST_BLADES -> "des lames de poignet en argent";
            case H_WEAPON_T3_FLAIL -> "un fléau en argent";
            case H_WEAPON_T3_PISTOL -> "un pistolet en argent";
            case H_ARMOR_T3_PLATE_SILVER -> "une armure de plates en argent";

            // Vampire side
            case V_WEAPON_T1_SCYTHE -> "une faux occulte";
            case V_ARMOR_T1_CARAPACE -> "une carapace nocturne";
            case V_WEAPON_T2_SWORD -> "une épée vampirique";
            case V_ARMOR_T2_HAUBERT -> "un haubert impie";
            case V_WEAPON_T3_CLAWS -> "les griffes des damnés";
            case V_ARMOR_T3_ECORCE -> "l'écorce de la Nuit";

            default -> equipCode;
        };
    }

    public int hunterWeaponTier(String weaponId) {
        if (weaponId == null)
            return 0;
        if (weaponId.startsWith("H_WEAPON_T1_"))
            return 1;
        if (weaponId.startsWith("H_WEAPON_T2_"))
            return 2;
        if (weaponId.startsWith("H_WEAPON_T3_"))
            return 3;
        return 0;
    }

    public int hunterArmorTier(String armorId) {
        if (armorId == null)
            return 0;
        if (armorId.startsWith("H_ARMOR_T1_"))
            return 1;
        if (armorId.startsWith("H_ARMOR_T2_"))
            return 2;
        if (armorId.startsWith("H_ARMOR_T3_"))
            return 3;
        return 0;
    }

    public String merchantWeaponIdForTier(int tier) {
        // sécurité: clamp 1..2
        tier = Math.max(1, Math.min(2, tier));

        int r3 = dice.roll(3); // 1..3 → bleed / stun / ranged

        return switch (tier) {
            case 1 -> switch (r3) {
                case 1 -> H_WEAPON_T1_SWORD;
                case 2 -> H_WEAPON_T1_MACE;
                case 3 -> H_WEAPON_T1_SPEAR;
                default -> H_WEAPON_T1_SWORD;
            };
            case 2 -> switch (r3) {
                case 1 -> H_WEAPON_T2_HALBERD;
                case 2 -> H_WEAPON_T2_HAMMER;
                case 3 -> H_WEAPON_T2_CROSSBOW;
                default -> H_WEAPON_T2_HALBERD;
            };
            default -> H_WEAPON_T1_SWORD;
        };
    }

    public String merchantArmorIdForTier(int tier) {
        // sécurité: clamp 1..2
        tier = Math.max(1, Math.min(2, tier));

        return switch (tier) {
            case 1 -> H_ARMOR_T1_BRIGANDINE;
            case 2 -> H_ARMOR_T2_HAUBERT;
            default -> H_ARMOR_T1_BRIGANDINE;
        };
    }

    // === Vampire Equipment Helpers ===
    public int vampireWeaponTier(String weaponId) {
        if (weaponId == null)
            return 0;
        if (weaponId.startsWith("V_WEAPON_T1_"))
            return 1;
        if (weaponId.startsWith("V_WEAPON_T2_"))
            return 2;
        if (weaponId.startsWith("V_WEAPON_T3_"))
            return 3;
        return 0;
    }

    public int vampireArmorTier(String armorId) {
        if (armorId == null)
            return 0;
        if (armorId.startsWith("V_ARMOR_T1_"))
            return 1;
        if (armorId.startsWith("V_ARMOR_T2_"))
            return 2;
        if (armorId.startsWith("V_ARMOR_T3_"))
            return 3;
        return 0;
    }

    public String vampireWeaponIdForTier(int tier) {
        // sécurité: clamp 1..2 (never offer T3)
        tier = Math.max(1, Math.min(2, tier));
        return switch (tier) {
            case 1 -> V_WEAPON_T1_SCYTHE;
            case 2 -> V_WEAPON_T2_SWORD;
            default -> V_WEAPON_T1_SCYTHE;
        };
    }

    public String vampireArmorIdForTier(int tier) {
        // sécurité: clamp 1..2 (never offer T3)
        tier = Math.max(1, Math.min(2, tier));
        return switch (tier) {
            case 1 -> V_ARMOR_T1_CARAPACE;
            case 2 -> V_ARMOR_T2_HAUBERT;
            default -> V_ARMOR_T1_CARAPACE;
        };
    }

    public void rebuildEquipmentMods(@NonNull Game g) {
        if (g.getRaidMods() == null) {
            g.setRaidMods(new HashMap<>());
        }

        // 1) Purge des anciens mods EQUIP:*
        for (var list : g.getRaidMods().values()) {
            if (list != null) {
                list.removeIf(m -> {
                    String s = m.getSource();
                    return s != null && s.startsWith("EQUIP:");
                });
            }
        }

        // 2) Réinjection en fonction de l'équipement actuel
        for (var p : g.getPlayers()) {

            // --- AURA ARMURE VAMP T1/T2 (ENGINE + chip implicite via
            // buildModBreakdownLines) ---
            if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                int bonus = vampArmorDefenseBonus(p.getArmor()); // 0 ou 1
                if (bonus != 0) {
                    g.addRaidMod(p.getId(), "DEFENSE", bonus, "EQUIP:VAMPIRE_ARMOR:ENG");
                }
            }

            // --- HUNTERS : puces DISPLAY permanentes en fonction de l’arme/armure ---
            if ("HUNTER".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                String w = p.getWeapon();
                String a = p.getArmor();

                if (hunterWeaponHasBleed(w)) {
                    g.addRaidMod(p.getId(), "ATTACK", 0, "EQUIP:BLEED_WEAPON:DSP");
                }
                if (hunterWeaponHasStun(w)) {
                    g.addRaidMod(p.getId(), "ATTACK", 0, "EQUIP:STUN_WEAPON:DSP");
                }
                if (hunterWeaponIsRanged(w)) {
                    g.addRaidMod(p.getId(), "ATTACK", 0, "EQUIP:RANGED_WEAPON:DSP");
                }
                if (hunterArmorIsAntiBite(a)) {
                    g.addRaidMod(p.getId(), "DEFENSE", 0, "EQUIP:HUNTER_ARMOR:DSP");
                }
            }

            // --- VAMPIRE : puces DISPLAY permanentes ---
            if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                String w = p.getWeapon();
                String a = p.getArmor();

                if (vampWeaponHasRegen(w)) {
                    g.addRaidMod(p.getId(), "ATTACK", 0, "EQUIP:VAMPIRE_WEAPON:DSP");
                }
                if (vampArmorHasEvasion(a)) {
                    g.addRaidMod(p.getId(), "DEFENSE", 0, "EQUIP:VAMPIRE_ARMOR_T3:DSP");
                }
            }
        }
    }

    // --- Hunters : saignement ---
    public int bleedBonusForWeapon(String weaponCode) {
        if (weaponCode == null)
            return 0;
        return switch (weaponCode) {
            case H_WEAPON_T1_SWORD -> 1;
            case H_WEAPON_T2_HALBERD -> 2;
            case H_WEAPON_T3_WRIST_BLADES -> 3;
            default -> 0;
        };
    }

    // --- Hunters : étourdissement ---
    public int stunPenalityForWeapon(String weaponCode) {
        if (weaponCode == null)
            return 0;
        return switch (weaponCode) {
            case H_WEAPON_T1_MACE -> 1;
            case H_WEAPON_T2_HAMMER -> 2;
            case H_WEAPON_T3_FLAIL -> 3;
            default -> 0;
        };
    }

    // --- Hunters : tenue à distance ---
    /**
     * Retourne true si le jet d'attaque brut d'un chasseur avec une arme à distance
     * déclenche l'effet "tenu à distance".
     *
     * - Lance en fer (1d8) : 8
     * - Arbalète (1d12) : 11 ou 12
     * - Pistolet (1d20) : 18, 19 ou 20
     */
    public boolean rangedKeepAwayTriggered(String weaponCode, int rawAtk) {
        if (weaponCode == null)
            return false;
        return switch (weaponCode) {
            case H_WEAPON_T1_SPEAR -> (rawAtk == 6);
            case H_WEAPON_T2_CROSSBOW -> (rawAtk == 7 || rawAtk == 8);
            case H_WEAPON_T3_PISTOL -> (rawAtk == 10 || rawAtk == 11 || rawAtk == 12);
            default -> false;
        };
    }

    // --- Vampire : régénération via arme ---
    public int vampRegenAmount(String weaponCode, int attackRoll) {
        if (weaponCode == null)
            return 0;

        return switch (weaponCode) {
            case V_WEAPON_T1_SCYTHE -> (attackRoll == 6) ? 1 : 0;
            case V_WEAPON_T2_SWORD -> (attackRoll >= 7 && attackRoll <= 8) ? 2 : 0;
            case V_WEAPON_T3_CLAWS -> (attackRoll >= 10) ? 3 : 0;
            default -> 0;
        };
    }

    // --- Conversion équipement Hunter -> Servant
    public void convertHunterGearToServant(Game g, Player p) {
        String w = p.getWeapon();
        String a = p.getArmor();

        // 1) Armes
        if (H_WEAPON_T1_SWORD.equals(w) || H_WEAPON_T1_MACE.equals(w) || H_WEAPON_T1_SPEAR.equals(w)) {
            p.setWeapon(V_WEAPON_T1_SCYTHE);
        } else if (H_WEAPON_T2_HALBERD.equals(w) || H_WEAPON_T2_HAMMER.equals(w) || H_WEAPON_T2_CROSSBOW.equals(w)) {
            p.setWeapon(V_WEAPON_T2_SWORD);
        } else if (H_WEAPON_T3_WRIST_BLADES.equals(w) || H_WEAPON_T3_FLAIL.equals(w) || H_WEAPON_T3_PISTOL.equals(w)) {
            p.setWeapon(V_WEAPON_T3_CLAWS);
        }

        // 2) Armures
        if (H_ARMOR_T1_BRIGANDINE.equals(a)) {
            p.setArmor(V_ARMOR_T1_CARAPACE);
        } else if (H_ARMOR_T2_HAUBERT.equals(a)) {
            p.setArmor(V_ARMOR_T2_HAUBERT);
        } else if (H_ARMOR_T3_PLATE_SILVER.equals(a)) {
            p.setArmor(V_ARMOR_T3_ECORCE);
        }

        // Forcer le refresh des mods
        rebuildEquipmentMods(g);
    }

    // --- Vampire : bonus défensifs d'armure ---
    public int vampArmorDefenseBonus(String armorCode) {
        if (armorCode == null)
            return 0;
        return switch (armorCode) {
            case V_ARMOR_T1_CARAPACE, V_ARMOR_T2_HAUBERT -> 1;
            default -> 0;
        };
    }

    public boolean hunterWeaponHasBleed(String w) {
        return H_WEAPON_T1_SWORD.equals(w)
                || H_WEAPON_T2_HALBERD.equals(w)
                || H_WEAPON_T3_WRIST_BLADES.equals(w);
    }

    public boolean hunterWeaponHasStun(String w) {
        return H_WEAPON_T1_MACE.equals(w)
                || H_WEAPON_T2_HAMMER.equals(w)
                || H_WEAPON_T3_FLAIL.equals(w);
    }

    public boolean hunterWeaponIsRanged(String w) {
        return H_WEAPON_T1_SPEAR.equals(w)
                || H_WEAPON_T2_CROSSBOW.equals(w)
                || H_WEAPON_T3_PISTOL.equals(w);
    }

    public boolean vampWeaponHasRegen(String w) {
        return V_WEAPON_T1_SCYTHE.equals(w)
                || V_WEAPON_T2_SWORD.equals(w)
                || V_WEAPON_T3_CLAWS.equals(w);
    }

    public boolean hunterArmorIsAntiBite(String armor) {
        return H_ARMOR_T3_PLATE_SILVER.equals(armor);
    }

    public boolean vampArmorHasEvasion(String armorCode) {
        return V_ARMOR_T3_ECORCE.equals(armorCode);
    }

}
