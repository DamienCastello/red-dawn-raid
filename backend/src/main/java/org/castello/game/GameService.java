package org.castello.game;

import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {

    private final Map<String, Game> store = new ConcurrentHashMap<>();

    public Game create() {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, GameStatus.CREATED, 0);
        store.put(id, game);
        return game;
    }

    public Collection<Game> list() {
        return store.values();
    }

    public Game findOr404(String id) {
        Game g = store.get(id);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
        return g;
    }

    /** Ajoute ou met à jour un joueur (id + nickname) tant que la partie n'a pas démarré. */
    public Game addOrUpdatePlayer(String gameId, String playerId, String nickname) {
        if (nickname == null || nickname.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nickname required");
        Game g = findOr404(gameId);
        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game already started/ended");

        Optional<Player> existing = g.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst();

        if (existing.isPresent()) {
            existing.get().setNickname(nickname);
        } else {
            g.getPlayers().add(new Player(playerId, nickname));
        }
        return g;
    }

    /** Démarre la partie (min 2 joueurs), VAMPIRE aléatoire, 4 lieux par joueur. */
    public Game start(String id) {
        Game g = findOr404(id);
        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already started/ended");
        if (g.getPlayers().size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "need at least 2 players");

        g.setStatus(GameStatus.ACTIVE);
        g.setRaid(1);
        g.setPhase(Phase.PHASE0);
        //g.setPhase(Phase.PHASE1);

        Random rnd = new Random();
        int vampIndex = rnd.nextInt(g.getPlayers().size());
        for (int i = 0; i < g.getPlayers().size(); i++) {
            Player p = g.getPlayers().get(i);
            p.setRole(i == vampIndex ? "VAMPIRE" : "HUNTER");
            p.setHand(new ArrayList<>(List.of("foret", "carriere", "lac", "manoir")));
        }

        g.setVampActionsLeft(20);    g.setVampActionsDiscard(0);
        g.setHunterActionsLeft(35);  g.setHunterActionsDiscard(0);
        g.setPotionsLeft(22);        g.setPotionsDiscard(0);
        g.setCenter(new ArrayList<>());

        return g;
    }
}
