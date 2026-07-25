package org.castello.bot;

import org.castello.game.Game;
import org.castello.game.GameStatus;
import org.castello.game.support.GameStore;
import org.castello.live.LiveEvents;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Cycle de vie des bots (voir docs/BOT-DESIGN.md).
 *
 * Un bot est un {@link Player} ordinaire dans le blob Game, avec un id
 * synthétique "bot:<uuid>" et le flag bot=true. Il n'a NI compte utilisateur
 * NI ligne SQL players : il n'appelle jamais l'API REST, ses actions passent
 * par la façade GameService (voir BotOrchestrator / BotBrain).
 */
@Service
public class BotManager {

    /** Même limite que GameLifecycleService (1 vampire + 6 chasseurs). */
    private static final int MAX_PLAYERS = 7;
    /** Décision produit : au plus 6 bots par partie. */
    private static final int MAX_BOTS = 6;

    /** Noms thématiques (chasseurs de l'ombre) ; le suffixe 🤖 rend le bot
     *  identifiable partout. Role-neutral : un bot peut être chasseur OU vampire. */
    private static final List<String> NAME_POOL = List.of(
            "Gabriel", "Sonja", "Belmont", "Silas", "Vesper", "Dante", "Cordelia", "Ronan");

    private final GameStore store;
    private final LiveEvents live;

    public BotManager(GameStore store, LiveEvents live) {
        this.store = store;
        this.live = live;
    }

    @Transactional
    public Game addBot(String gameId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game already started/ended");

        long activeCount = g.getPlayers().stream().filter(p -> !p.isLeftGame()).count();
        if (activeCount >= MAX_PLAYERS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "game is full (max " + MAX_PLAYERS + ")");

        long botCount = g.getPlayers().stream()
                .filter(p -> !p.isLeftGame())
                .filter(Player::isBot)
                .count();
        if (botCount >= MAX_BOTS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "max " + MAX_BOTS + " bots per game");

        Player bot = new Player("bot:" + UUID.randomUUID(), pickName(g));
        bot.setBot(true);
        g.getPlayers().add(bot);

        store.save(g);
        store.afterCommit(() -> live.lobbyUpdated(g));
        return g;
    }

    @Transactional
    public Game removeBot(String gameId, String botId) {
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game already started/ended");

        Player p = g.getPlayers().stream()
                .filter(x -> x.getId().equals(botId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "bot not found"));

        if (!p.isBot())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "player is not a bot");

        g.getPlayers().remove(p);
        if (g.getReadyForStart() != null)
            g.getReadyForStart().remove(botId);

        store.save(g);
        store.afterCommit(() -> live.lobbyUpdated(g));
        return g;
    }

    private String pickName(Game g) {
        var used = g.getPlayers().stream().map(Player::getUsername).toList();
        for (String name : NAME_POOL) {
            String candidate = name + " 🤖";
            if (!used.contains(candidate))
                return candidate;
        }
        // Pool épuisé (impossible avec MAX_BOTS=6 < 8 noms, mais restons sûrs)
        return "Bot-" + (used.size() + 1) + " 🤖";
    }
}
