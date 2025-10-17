package org.castello.game;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {

    // Stockage en mémoire (thread-safe)
    private final Map<String, Game> store = new ConcurrentHashMap<>();

    /** Crée une partie et la range en mémoire. */
    public Game create() {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, GameStatus.CREATED, 0);
        store.put(id, game);
        return game;
    }

    /** Liste toutes les parties (utile pour le lobby). */
    public Collection<Game> list() {
        return store.values();
    }

    /** Récupère une partie ou 404. */
    public Game findOr404(String id) {
        Game g = store.get(id);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
        return g;
    }

    /** Ajoute un joueur (par pseudo) tant que la partie n'a pas démarré. */
    public Game join(String id, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nickname required");
        }
        Game g = findOr404(id);
        if (g.getStatus() != GameStatus.CREATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game already started/ended");
        }
        if (!g.getPlayers().contains(nickname)) {
            g.getPlayers().add(nickname);
        }
        return g;
    }

    /** Démarre la partie (règle: minimum 2 joueurs). */
    public Game start(String id) {
        Game g = findOr404(id);
        if (g.getStatus() != GameStatus.CREATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already started/ended");
        }
        if (g.getPlayers().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "need at least 2 players");
        }
        g.setStatus(GameStatus.ACTIVE);
        g.setRound(1);
        return g;
    }
}
