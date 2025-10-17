package org.castello.player;

import java.util.ArrayList;
import java.util.List;

/** Joueur DANS la partie (état de jeu). */
public class Player {
    private String id;                 // candidateId (vient de CandidateService)
    private String nickname;           // affichage
    private String role;               // "VAMPIRE" | "HUNTER"
    private List<String> hand = new ArrayList<>(); // cartes lieu ("foret","carriere","lac","manoir")

    public Player(String id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }

    public String getId() { return id; }
    public String getNickname() { return nickname; }
    public String getRole() { return role; }
    public List<String> getHand() { return hand; }

    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setRole(String role) { this.role = role; }
    public void setHand(List<String> hand) { this.hand = hand; }
}
