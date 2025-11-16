package org.castello.web;

import org.castello.auth.AuthService;
import org.castello.game.Game;
import org.castello.game.GameService;
import org.castello.game.Potion;
import org.castello.web.dto.GameSnapshot;
import org.castello.player.PlayerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService games;
    private final AuthService authService;
    private final PlayerService playerService;

    private static final Logger log = LoggerFactory.getLogger(GameController.class);


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
    public GameSnapshot view(@PathVariable String id,
                             @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        return games.viewSnapshot(id, user.getId());
    }

    @PostMapping("/{id}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void join(@PathVariable String id,
                     @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        String username = user.getUsername();
        playerService.joinGame(user.getId(), id, username);
        games.addOrUpdatePlayer(id, user.getId(), username);
    }

    @PostMapping("/{id}/start")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void start(@PathVariable String id,
                      @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.start(id);
    }

    @PostMapping("/{id}/advance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void advance(@PathVariable String id,
                        @RequestParam("to") String to,
                        @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.advancePhase(id, user.getId(), org.castello.game.Phase.valueOf(to));
    }

    public record SelectLocationReq(String card) {}

    @PostMapping("/{id}/select-location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void selectLocation(@PathVariable String id,
                               @RequestBody SelectLocationReq req,
                               @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);

        var card = (req != null) ? req.card() : null;
        if (card == null || card.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "card required");
        }
        games.selectLocation(id, user.getId(), card);
    }

    @PostMapping("/{id}/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(@PathVariable String id,
                     @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.skipAction(id, user.getId());
    }

    @PostMapping("/{id}/roll")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void roll(@PathVariable String id,
                     @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.rollDice(id, user.getId());
    }

    @PostMapping("/{id}/combat/continue")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void combatContinue(@PathVariable String id,
                               @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.combatContinue(id, user.getId()); // émettra live.phaseChanged(g)
    }

    @PostMapping("/{id}/weather/roll")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rollWeather(@PathVariable String id,
                            @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.rollWeather(id, user.getId());
    }

    // --- Utiliser une potion ---
    public static class UsePotionReq { public String type; } // tu peux garder

    @PostMapping("/{id}/potions/use")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void usePotion(@PathVariable String id,
                          @RequestBody UsePotionReq body,
                          @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        if (body == null || body.type == null || body.type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type required");
        }
        games.usePotion(id, user.getId(), Potion.valueOf(body.type));
    }

    // --- Corruption ---
    // Choisir la cible d’un chasseur instable (vampire only)
    @PostMapping("/{id}/unstable/assign-target")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignUnstable(@PathVariable String id,
                               @RequestParam String unstableId,
                               @RequestParam String targetId,
                               @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.assignUnstableTarget(id, user.getId(), unstableId, targetId);
    }

    @PostMapping("/{id}/unstable/assign-harvest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignUnstableHarvest(@PathVariable String id,
                                      @RequestParam String unstableId,
                                      @RequestParam String loc,
                                      @RequestHeader("Authorization") String authorization) {
        log.info("[{}] HTTP assign-harvest unstableId={}", id, unstableId);

        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.assignUnstableHarvest(id, user.getId(), unstableId, loc);
    }

    @PostMapping("/{id}/unstable/assign-nothing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignUnstableNothing(@PathVariable String id,
                                      @RequestParam String unstableId,
                                      @RequestHeader("Authorization") String authorization) {
        log.info("[{}] HTTP assign-nothing unstableId={}", id, unstableId);

        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.assignUnstableNothing(id, user.getId(), unstableId);
    }

    @PostMapping("/{id}/corruption/roll")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rollCorruption(@PathVariable String id,
                               @RequestHeader("Authorization") String authorization) {
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.rollCorruption(id, user.getId());
    }

    // Maintenance
    // --- Phase4: "ne rien faire" (finishTrade)
    @PostMapping("/{id}/phase4/finish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finishTrade(@PathVariable String id,
                            @RequestHeader("Authorization") String authorization){
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.finishTrade(id, user.getId());
    }

    // --- Boutique / Transmutation ---
    @PostMapping("/{id}/shop/buy-potion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void buyPotion(@PathVariable String id,
                          @RequestHeader("Authorization") String authorization){
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.buyPotion(id, user.getId());
    }

    @PostMapping("/{id}/shop/buy-silver")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void buySilver(@PathVariable String id,
                          @RequestParam(defaultValue="1") int qty,
                          @RequestHeader("Authorization") String authorization){
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.buySilver(id, user.getId(), qty);
    }

    public static class SellReq { public String res; public Integer qty; }
    @PostMapping("/{id}/shop/sell")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sell(@PathVariable String id,
                     @RequestBody SellReq body,
                     @RequestHeader("Authorization") String authorization){
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.sellResource(id, user.getId(), body.res, body.qty!=null?body.qty:1);
    }

    public static class TransmuteReq { public String recipe; }
    @PostMapping("/{id}/transmutation/do")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transmute(@PathVariable String id,
                          @RequestBody TransmuteReq body,
                          @RequestHeader("Authorization") String authorization){
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.transmute(id, user.getId(), body.recipe);
    }

    // --- Trades ---
    public static class OfferReq { public String targetId; public java.util.Map<String,Integer> offer; }

    @PostMapping("/{id}/trade/offer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void tradeOffer(@PathVariable String id,
                           @RequestBody OfferReq body,
                           @RequestHeader("Authorization") String authorization){
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.tradeSetMyOffer(id, user.getId(), body.targetId, body.offer);
    }

    @PostMapping("/{id}/trade/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void tradeAction(@PathVariable String id,
                            @PathVariable String action,     // confirm | refuse | cancel
                            @RequestParam String targetId,
                            @RequestHeader("Authorization") String authorization){
        var user = authService.requireUser(authorization);
        playerService.requireInGame(user.getId(), id);
        games.tradeAction(id, user.getId(), targetId, action);
    }
}
