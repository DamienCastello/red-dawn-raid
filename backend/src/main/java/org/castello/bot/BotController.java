package org.castello.bot;

import org.castello.auth.AuthService;
import org.castello.game.Game;
import org.castello.player.PlayerService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints lobby des bots : ajouter/retirer un bot dans une partie CREATED.
 * Seul un joueur déjà membre de la partie peut le faire.
 */
@RestController
@RequestMapping("/api/games")
public class BotController {

    private final BotManager bots;
    private final AuthService authService;
    private final PlayerService playerService;

    public BotController(BotManager bots, AuthService authService, PlayerService playerService) {
        this.bots = bots;
        this.authService = authService;
        this.playerService = playerService;
    }

    @PostMapping("/{id}/bots")
    public Game addBot(@PathVariable String id,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        return bots.addBot(id);
    }

    @PostMapping("/{id}/bots/{botId}/remove")
    public Game removeBot(@PathVariable String id,
            @PathVariable String botId,
            @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        return bots.removeBot(id, botId);
    }
}
