package org.castello.web.dto;

import java.util.List;

public record EndedGameSummary(
        String id,
        String status,
        String winnerSide,
        List<PlayerSummary> players
) {
    public record PlayerSummary(
            String id,
            String username,
            String role,
            int hp,
            boolean leftGame
    ) {}
}
