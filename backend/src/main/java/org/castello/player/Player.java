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


}
