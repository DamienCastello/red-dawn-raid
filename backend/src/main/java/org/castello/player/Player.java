package org.castello.player;

import java.util.ArrayList;
import java.util.List;

/** Joueur DANS la partie (état de jeu). */
public class Player {
    private String id;                 // userId (vient de l’auth)
    private String username;
    private String role;
    /** Main de cartes LIEU (PHASE1/PHASE2). */
    private List<String> hand = new ArrayList<>();

    /** Inventaire de potions (IDs de l'enum Potion sous forme de String). */
    private List<String> potions = new ArrayList<>();

    /** Potions rares / élixirs (deck spécial). */
    private List<String> elixirs = new ArrayList<>();

    /** Inventaire de cartes d'action (IDs de l'enum Action sous forme de String). */
    private List<String> actions = new ArrayList<>();

    // --- COMBAT ---
    private int hp;
    private String attackDice;
    private String defenseDice;

    // --- ÉQUIPEMENT (Forge) ---
    /**
     * Code d'équipement d'arme actuellement équipée
     * (par ex "H_WEAPON_T1_SWORD", "V_WEAPON_T2_SWORD", etc.)
     * null = équipement de base (T0).
     */
    private String weapon;

    /**
     * Code d'armure actuellement équipée
     * (par ex "H_ARMOR_T1_BRIGANDINE", "V_ARMOR_T1_CARAPACE", etc.)
     * null = équipement de base (T0).
     */
    private String armor;

    private boolean blessedStake;
    private boolean sacredRosary;
    private boolean charismaticThisRaid;
    private Integer shopPisteurCount; // nb de PISTEUR achetés boutique (hors deck)

    // --- RESSOURCES ---
    private int wood;
    private int herbs;
    private int stone;
    private int iron;
    private int water;
    private int gold;
    private int souls;
    private int silver;

    // --- CORRUPTION ---
    private int corruption; // 0 = sain, 1 = affaibli, 2 = instable, 3 = servant

    private boolean leftGame = false;

    public Player() {}

    public Player(String id, String username) {
        this.id = id;
        this.username = username;
    }

    public String getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public boolean isLeftGame() { return leftGame; }
    public void setLeftGame(boolean leftGame) { this.leftGame = leftGame; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    // --- LIEUX ---
    public List<String> getHand() { return hand; }
    public void setHand(List<String> hand) { this.hand = (hand != null ? hand : new ArrayList<>()); }

    // --- POTIONS ---
    public List<String> getPotions() { return potions; }
    public void setPotions(List<String> potions) { this.potions = (potions != null ? potions : new ArrayList<>()); }

    // --- ELIXIRS (POTIONS RARES) ---
    public List<String> getElixirs() { return elixirs; }
    public void setElixirs(List<String> elixirs) {
        this.elixirs = (elixirs != null ? elixirs : new ArrayList<>());
    }

    // --- ACTIONS ---
    public List<String> getActions() { return actions; }
    public void setActions(List<String> actions) { this.actions = (actions != null ? actions : new ArrayList<>()); }

    public int getHp() { return hp; }
    public String getAttackDice() { return attackDice; }
    public String getDefenseDice() { return defenseDice; }
    public void setHp(int hp) { this.hp = hp; }
    public void setAttackDice(String attackDice) { this.attackDice = attackDice; }
    public void setDefenseDice(String defenseDice) { this.defenseDice = defenseDice; }

    public int getWood() { return wood; }
    public void setWood(int v) { this.wood = v; }
    public int getHerbs() { return herbs; }
    public void setHerbs(int v) { this.herbs = v; }
    public int getStone() { return stone; }
    public void setStone(int v) { this.stone = v; }
    public int getIron() { return iron; }
    public void setIron(int v) { this.iron = v; }
    public int getWater() { return water; }
    public void setWater(int v) { this.water = v; }
    public int getGold() { return gold; }
    public void setGold(int v) { this.gold = v; }
    public int getSouls() { return souls; }
    public void setSouls(int v) { this.souls = v; }
    public int getSilver(){ return silver; }
    public void setSilver(int v){ this.silver = v; }
    public int getCorruption() { return corruption; }
    public void setCorruption(int corruption) { this.corruption = Math.max(0, Math.min(3, corruption)); }
    public String getWeapon() { return weapon; }
    public void setWeapon(String weapon) { this.weapon = weapon; }
    public String getArmor() { return armor; }
    public void setArmor(String armor) { this.armor = armor; }
    public boolean isBlessedStake() { return blessedStake; }
    public void setBlessedStake(boolean blessedStake) { this.blessedStake = blessedStake; }
    public boolean isSacredRosary() { return sacredRosary; }
    public void setSacredRosary(boolean sacredRosary) { this.sacredRosary = sacredRosary; }
    public boolean isCharismaticThisRaid() { return charismaticThisRaid; }
    public void setCharismaticThisRaid(boolean charismaticThisRaid) { this.charismaticThisRaid = charismaticThisRaid; }
    public Integer getShopPisteurCount() { return shopPisteurCount; }
    public void setShopPisteurCount(Integer v) { this.shopPisteurCount = v; }

}
