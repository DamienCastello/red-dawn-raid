package org.castello.web;

import org.castello.auth.AuthService;
import org.castello.game.Game;
import org.castello.game.GameService;
import org.castello.web.dto.JoinResponse;
import org.castello.web.dto.SelectLocationRequest;
import org.castello.player.PlayerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService games;
    private final AuthService authService;
    private final PlayerService playerService;

    public GameController(GameService games, AuthService authService, PlayerService playerService) {
        this.games = games;
        this.authService = authService;
        this.playerService = playerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Game create(@RequestHeader(value="Authorization", required=false) String authorization) {
        // possible d'exiger un user connecté ici
        return games.create();
    }

    @GetMapping
    public Iterable<Game> list() { return games.list(); }

    @GetMapping("/{id}")
    public Game get(@PathVariable String id) {
        return games.tickAndGet(id); // applique auto-avance si délai dépassé
    }

    @PostMapping("/{id}/join")
    public JoinResponse join(@PathVariable String id,
                             @RequestHeader("Authorization") String authorization) {
        // 1) Auth obligatoire
        var user = authService.requireUser(authorization);

        // 2) username issu de l’auth
        String username = user.getUsername();

        // 3) Joue l’appartenance persistée + reflet dans l’état de jeu
        playerService.joinGame(user.getId(), id, username);
        var g = games.addOrUpdatePlayer(id, user.getId(), username);

        // 4) Retour sans playerToken
        return new JoinResponse(g, user.getId());
    }

    @PostMapping("/{id}/start")
    public Game start(@PathVariable String id,
                      @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id); // refuse si pas joueur de cette game
        return games.start(id);
    }

    @PostMapping("/{id}/select-location")
    public Game selectLocation(@PathVariable String id,
                               @RequestBody SelectLocationRequest req,
                               @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);

        if (req == null || req.card == null || req.card.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "card required");
        }
        return games.selectLocation(id, user.getId(), req.card);
    }

    @PostMapping("/{id}/skip")
    public Game skip(@PathVariable String id,
                     @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        return games.skipAction(id, user.getId());
    }
}
