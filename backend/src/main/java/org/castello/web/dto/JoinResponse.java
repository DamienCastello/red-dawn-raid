package org.castello.web.dto;

import org.castello.game.Game;

public class JoinResponse {
    public Game game;
    public String playerId; // = userId

    public JoinResponse(Game game, String playerId) {
        this.game = game;
        this.playerId = playerId;
    }
}
