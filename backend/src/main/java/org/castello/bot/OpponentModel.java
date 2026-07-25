package org.castello.bot;

import org.castello.game.Game;
import org.castello.game.Phase;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modèle d'adversaire (étape 4) — estimation de l'information cachée à partir de
 * l'OBSERVATION PUBLIQUE, pour remplacer les paris fixes du bot par des paris
 * informés (§5 de docs/BOT-DESIGN.md), sans jamais tricher.
 *
 * <p>Principe : chaque raid, une fois les lieux RÉVÉLÉS (centre face visible dès
 * la PREPHASE3 — exactement ce qu'un humain voit), on enregistre où chaque joueur
 * est allé. On en tire une fréquence par lieu et par joueur, et on prédit le lieu
 * le plus habituel. Un chasseur peut ainsi traquer le lieu probable du vampire ;
 * le vampire peut viser les lieux de récolte probables des chasseurs.
 *
 * <p><b>Stockage en RAM, par partie</b> (comme {@code rotationByGame} de
 * l'orchestrateur) : rien n'est écrit dans le blob {@code Game} → aucune fuite
 * dans le snapshot, rien à sérialiser. Perdu au redémarrage serveur (acceptable
 * pour une heuristique — on ré-observe).
 */
@Service
public class OpponentModel {

    /** En dessous de ce nombre d'observations, on ne prédit pas (trop peu de recul). */
    private static final int MIN_OBSERVATIONS = 2;

    private static final class GameObs {
        /** playerId → (code de lieu → nombre de fois observé). */
        final Map<String, Map<String, Integer>> locCountByPlayer = new HashMap<>();
        /** Dernier raid déjà enregistré (évite de compter deux fois le même raid). */
        int lastRecordedRaid = Integer.MIN_VALUE;
    }

    private final Map<String, GameObs> byGame = new ConcurrentHashMap<>();

    /**
     * À appeler à chaque tick de l'orchestrateur : enregistre les lieux du raid
     * courant UNE fois qu'ils sont révélés (PREPHASE3+), au plus une fois par raid.
     * Ne lit que le centre (information publique une fois face visible).
     */
    public void observe(Game g) {
        Phase phase = g.getPhase();
        // Avant la PREPHASE3, les lieux sont face cachée → ne rien observer (équité).
        if (phase == null || phase == Phase.PHASE0 || phase == Phase.PHASE1 || phase == Phase.PHASE2)
            return;

        int raid = g.getRaid();
        GameObs obs = byGame.computeIfAbsent(g.getId(), k -> new GameObs());
        if (obs.lastRecordedRaid >= raid)
            return; // ce raid est déjà comptabilisé

        if (g.getCenter() != null) {
            for (var cb : g.getCenter()) {
                if (cb.getPlayerId() == null || cb.getCard() == null)
                    continue;
                obs.locCountByPlayer
                        .computeIfAbsent(cb.getPlayerId(), k -> new HashMap<>())
                        .merge(cb.getCard(), 1, Integer::sum);
            }
        }
        obs.lastRecordedRaid = raid;
    }

    /**
     * Lieu le plus HABITUEL d'un joueur (code), ou {@code null} si l'historique est
     * trop mince ({@link #MIN_OBSERVATIONS}). À départage égal, un lieu arbitraire
     * mais stable est renvoyé.
     */
    public String predictLocation(String gameId, String playerId) {
        GameObs obs = byGame.get(gameId);
        if (obs == null)
            return null;
        Map<String, Integer> counts = obs.locCountByPlayer.get(playerId);
        if (counts == null)
            return null;
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total < MIN_OBSERVATIONS)
            return null;
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Combien de fois le joueur a été observé sur ce lieu précis (0 si inconnu).
     * Sert à pondérer un pari agrégé (ex. où concentrer des clones).
     */
    public int timesSeenAt(String gameId, String playerId, String locCode) {
        GameObs obs = byGame.get(gameId);
        if (obs == null)
            return 0;
        Map<String, Integer> counts = obs.locCountByPlayer.get(playerId);
        if (counts == null)
            return 0;
        return counts.getOrDefault(locCode, 0);
    }

    /** Oublier une partie terminée (appelé par l'orchestrateur au nettoyage). */
    public void forget(String gameId) {
        byGame.remove(gameId);
    }

    /** Ne conserver que les parties encore actives (purge de fin de tick). */
    public void retainOnly(java.util.Set<String> activeGameIds) {
        byGame.keySet().retainAll(activeGameIds);
    }
}
