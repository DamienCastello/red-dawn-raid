package org.castello.game.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.castello.game.Game;
import org.castello.persistence.GameEntity;
import org.castello.persistence.GameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

/**
 * Plomberie de persistance d'une partie.
 *
 * Toute la partie vit dans un seul blob JSON (colonne games.state). Ce
 * composant est l'unique porte d'entrée vers ce blob :
 * - read(id) : lecture seule (snapshots, vues) — sans verrou.
 * - loadForUpdate(id) : lecture AVEC verrou pessimiste — à utiliser pour
 *   toute mutation (nécessite une transaction active).
 * - save(game) : re-sérialise et écrit le blob.
 * - afterCommit(r) : exécute r après le commit (events WebSocket, timers),
 *   ou immédiatement si aucune transaction n'est active.
 */
@Component
public class GameStore {

    private final GameRepository gameRepo;
    private final ObjectMapper mapper;

    public GameStore(GameRepository gameRepo, ObjectMapper mapper) {
        this.gameRepo = gameRepo;
        this.mapper = mapper;
    }

    public String toJson(Game g) {
        try {
            return mapper.writeValueAsString(g);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Game fromJson(String json) {
        try {
            return mapper.readValue(json, Game.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lecture seule, sans verrou (snapshots, vues). */
    public Game read(String id) {
        var e = gameRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));
        return fromJson(e.getStateJson());
    }

    /** Lecture avec verrou pessimiste — pour toute mutation. */
    public Game loadForUpdate(String id) {
        GameEntity ge = gameRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "game not found"));
        return fromJson(ge.getStateJson());
    }

    public Collection<Game> readAll() {
        return gameRepo.findAll().stream()
                .map(ge -> fromJson(ge.getStateJson()))
                .toList();
    }

    public void save(@NonNull Game g) {
        String newJson = toJson(g);
        gameRepo.findById(g.getId()).ifPresentOrElse(existing -> {
            existing.setStateJson(newJson);
            gameRepo.save(existing);
        }, () -> {
            GameEntity ne = new GameEntity();
            ne.setId(g.getId());
            ne.setStateJson(newJson);
            gameRepo.save(ne);
        });
    }

    public void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager
                    .registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            // au cas où on l’appelle hors transaction (no-op de tx) : on exécute quand même
            r.run();
        }
    }
}
