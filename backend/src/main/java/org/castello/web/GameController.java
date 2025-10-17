package org.castello.web;

import org.castello.game.Game;
import org.castello.game.GameService;
import org.castello.player.Player;
import org.castello.player.PlayerService;
import org.castello.web.dto.JoinRequest;
import org.castello.web.dto.JoinResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService games;
    private final PlayerService players;

    public GameController(GameService games, PlayerService players) {
        this.games = games;
        this.players = players;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Game create() { return games.create(); }

    @GetMapping
    public Iterable<Game> list() { return games.list(); }

    @GetMapping("/{id}")
    public Game get(@PathVariable String id) { return games.findOr404(id); }

    @PostMapping("/{id}/join")
    public JoinResponse join(@PathVariable String id, @RequestBody JoinRequest req) {
        Game g = games.join(id, req.nickname);          // ajoute le pseudo dans la Game
        Player p = players.create(id, req.nickname);    // crée l’identité côté serveur
        return new JoinResponse(g, p.getId(), p.getToken());
    }

    @PostMapping("/{id}/start")
    public Game start(@PathVariable String id,
                      @RequestHeader(value="Authorization", required=false) String auth) {
        players.requireByTokenInGame(auth, id);         // refuse si pas joueur de la partie
        return games.start(id);
    }
}
