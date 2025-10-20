package org.castello.player;

import org.castello.persistence.PlayerEntity;
import org.castello.persistence.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class PlayerService {
    private final PlayerRepository repo;
    public PlayerService(PlayerRepository repo){ this.repo = repo; }

    /** Rejoint une game (ou met à jour le username) ; impose 1 seule game par user. */
    public PlayerEntity joinGame(String userId, String gameId, String username) {
        var any = repo.findByUserId(userId).orElse(null);
        if (any != null && !any.getGameId().equals(gameId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user already in game " + any.getGameId());
        }
        var same = repo.findByUserIdAndGameId(userId, gameId).orElse(null);
        if (same != null) {
            same.setUsername(username);
            return repo.save(same);
        }
        var p = new PlayerEntity();
        p.setId(UUID.randomUUID().toString());
        p.setUserId(userId);
        p.setGameId(gameId);
        p.setUsername(username);
        return repo.save(p);
    }

    /** Vérifie que le user est bien joueur de la game. */
    public void requireInGame(String userId, String gameId) {
        if (repo.findByUserIdAndGameId(userId, gameId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not a player of this game");
        }
    }
}
