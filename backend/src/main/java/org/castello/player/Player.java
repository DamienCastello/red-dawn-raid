package org.castello.player;

import java.util.ArrayList;
import java.util.List;

/** Joueur DANS la partie (état de jeu). */
public class Player {
    private String id;                 // userId (vient de l’auth)
    private String username;
    private String role;
    private List<String> hand = new ArrayList<>();

    // --- COMBAT ---
    private int hp;
    private String attackDice;
    private String defenseDice;

    // --- RESSOURCES ---
    private int wood;
    private int herbs;
    private int stone;
    private int iron;
    private int water;
    private int gold;
    private int souls;
    private int silver;

    public Player() {}

    public Player(String id, String username) {
        this.id = id;
        this.username = username;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public List<String> getHand() { return hand; }

    public void setUsername(String username) { this.username = username; }
    public void setRole(String role) { this.role = role; }
    public void setHand(List<String> hand) { this.hand = hand; }

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
}
