package org.castello.candidate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CandidateService {

    // Index mémoire
    private final Map<String, Candidate> byToken = new ConcurrentHashMap<>();
    private final Map<String, Candidate> byId = new ConcurrentHashMap<>();

    /** Crée un joueur et génère un token opaque (UUID). */
    public Candidate create(String gameId, String nickname) {
        String id = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        Candidate p = new Candidate(id, nickname, token, gameId);
        byId.put(id, p);
        byToken.put(token, p);
        return p;
    }

    /** Exige un Bearer token valide pour cette game, sinon 401/403. */
    public Candidate requireByTokenInGame(String bearer, String gameId) {
        String token = extractBearer(bearer);
        Candidate p = byToken.get(token);
        if (p == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid token");
        if (!p.getGameId().equals(gameId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "wrong game");
        return p;
    }

    /** (Optionnel) Récupère par token sans vérifier la game. */
    public Candidate requireByToken(String bearer) {
        String token = extractBearer(bearer);
        Candidate p = byToken.get(token);
        if (p == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid token");
        return p;
    }

    /** (Optionnel) Empêche qu’un même token rejoigne une autre partie. */
    public void ensureNotInAnotherGame(String bearer, String targetGameId) {
        String token = extractBearer(bearer);
        Candidate p = byToken.get(token);
        if (p != null && !p.getGameId().equals(targetGameId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already in game " + p.getGameId());
        }
    }

    private String extractBearer(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing token");
        }
        return auth.substring("Bearer ".length());
    }
}
