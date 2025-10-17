package org.castello.web.dto;

import org.castello.game.Game;

public class JoinResponse {
    public Game game;
    public String playerId;
    public String playerToken;

    public JoinResponse(Game game, String playerId, String playerToken) {
        this.game = game; this.playerId = playerId; this.playerToken = playerToken;
    }
}
