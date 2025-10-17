package org.castello.game;

import org.castello.player.Player;

import java.util.ArrayList;
import java.util.List;

public class Game {
    private String id;
    private GameStatus status;

    // état "tour/raid"
    private int raid;            // n° de raid (1 au start)
    private Phase phase;         // PHASE0 / PHASE1 / PHASE2 / PHASE3 / PHASE4

    // joueurs dans la partie
    private final List<Player> players = new ArrayList<>();

    // cartes posées au centre (face cachée/visible)
    private List<CenterBoard> center = new ArrayList<>();

    // compteurs des decks/pioches
    private int vampActionsLeft, vampActionsDiscard;
    private int hunterActionsLeft, hunterActionsDiscard;
    private int potionsLeft, potionsDiscard;

    public Game(String id, GameStatus status, int raid) {
        this.id = id;
        this.status = status;
        this.raid = raid;
        this.phase = null;
    }

    // getters de base
    public String getId() { return id; }
    public GameStatus getStatus() { return status; }
    // compat: certains fronts lisent "round" -> on renvoie le raid
    public int getRound() { return raid; }

    public void setStatus(GameStatus status) { this.status = status; }

    // état de partie
    public int getRaid() { return raid; }
    public void setRaid(int raid) { this.raid = raid; }

    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }

    // joueurs
    public List<Player> getPlayers() { return players; }

    // centre
    public List<CenterBoard> getCenter() { return center; }
    public void setCenter(List<CenterBoard> center) { this.center = center; }

    // compteurs
    public int getVampActionsLeft() { return vampActionsLeft; }
    public void setVampActionsLeft(int v) { this.vampActionsLeft = v; }

    public int getVampActionsDiscard() { return vampActionsDiscard; }
    public void setVampActionsDiscard(int v) { this.vampActionsDiscard = v; }

    public int getHunterActionsLeft() { return hunterActionsLeft; }
    public void setHunterActionsLeft(int v) { this.hunterActionsLeft = v; }

    public int getHunterActionsDiscard() { return hunterActionsDiscard; }
    public void setHunterActionsDiscard(int v) { this.hunterActionsDiscard = v; }

    public int getPotionsLeft() { return potionsLeft; }
    public void setPotionsLeft(int v) { this.potionsLeft = v; }

    public int getPotionsDiscard() { return potionsDiscard; }
    public void setPotionsDiscard(int v) { this.potionsDiscard = v; }
}
