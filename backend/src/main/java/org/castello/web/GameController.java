package org.castello.web;

import org.castello.candidate.CandidateService;
import org.castello.game.Game;
import org.castello.game.GameService;
import org.castello.web.dto.JoinRequest;
import org.castello.web.dto.JoinResponse;
import org.castello.web.dto.SelectLocationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService games;
    private final CandidateService candidates;

    public GameController(GameService games, CandidateService candidates) {
        this.games = games;
        this.candidates = candidates;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Game create() { return games.create(); }

    @GetMapping
    public Iterable<Game> list() { return games.list(); }

    @GetMapping("/{id}")
    public Game get(@PathVariable String id) {
        return games.tickAndGet(id); // applique auto-avance si délai dépassé
    }

    @PostMapping("/{id}/join")
    public JoinResponse join(@PathVariable String id,
                             @RequestBody JoinRequest req,
                             @RequestHeader(value="Authorization", required=false) String auth) {
        // Si le client a déjà un token, on l’utilise (et on vérifie qu’il correspond bien à cette partie)
        if (auth != null && !auth.isBlank()) {
            var c = candidates.requireByTokenInGame(auth, id);   // 401/403 si pas ok
            var g = games.addOrUpdatePlayer(id, c.getId(), req.nickname);
            return new JoinResponse(g, c.getId(), c.getToken());
        }

        // Pas de token → on crée un nouveau Candidate
        var c = candidates.create(id, req.nickname);
        var g = games.addOrUpdatePlayer(id, c.getId(), req.nickname);
        return new JoinResponse(g, c.getId(), c.getToken());
    }

    @PostMapping("/{id}/start")
    public Game start(@PathVariable String id,
                      @RequestHeader(value="Authorization", required=false) String auth) {
        candidates.requireByTokenInGame(auth, id); // refuse si pas joueur de la bonne game
        return games.start(id);
    }

    @PostMapping("/{id}/select-location")
    public Game selectLocation(@PathVariable String id,
                               @RequestBody SelectLocationRequest req,
                               @RequestHeader(value="Authorization", required=false) String auth) {
        var c = candidates.requireByTokenInGame(auth, id);
        if (req == null || req.card == null || req.card.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "card required");
        }
        return games.selectLocation(id, c.getId(), req.card);
    }

    // POST /api/games/{id}/skip - player dit “j’ai fini” en PREPHASE3
    @PostMapping("/{id}/skip")
    public Game skip(@PathVariable String id,
                     @RequestHeader(value="Authorization", required=false) String auth) {
        var c = candidates.requireByTokenInGame(auth, id);
        return games.skipAction(id, c.getId());
    }
}
