package org.castello.game;

import java.util.ArrayList;
import java.util.List;

public class Game {
    private String id;
    private GameStatus status;
    private int round;
    private final List<String> players = new ArrayList<>();

    public Game(String id, GameStatus status, int round) {
        this.id = id;
        this.status = status;
        this.round = round;
    }

    public String getId() { return id; }
    public GameStatus getStatus() { return status; }
    public int getRound() { return round; }
    public List<String> getPlayers() { return players; }

    public void setStatus(GameStatus status) { this.status = status; }
    public void setRound(int round) { this.round = round; }
}
