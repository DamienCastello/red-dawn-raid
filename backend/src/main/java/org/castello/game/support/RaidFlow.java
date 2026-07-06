package org.castello.game.support;

import org.castello.game.Game;
import org.castello.game.Phase;
import org.castello.player.Player;

/**
 * Pont vers le flux de raid (timers, transitions de phase, victoire).
 *
 * Les services de domaine (corruption, effets de lieux, cartes action…)
 * doivent parfois relancer le timer de préphase, programmer une avance de
 * phase ou vérifier une fin de partie. Cette logique vit encore dans
 * GameService (futur PhaseFlowService) ; cette interface permet aux
 * domaines de l'appeler sans dépendance circulaire (injection @Lazy).
 */
public interface RaidFlow {

    /** Recalcule readyForPhase3 + relance le timer de préphase (30 s) ou l'avance rapide. */
    void setupUnstableAndPrephaseTimeout(Game g);

    /** Y a-t-il un combat imminent (cartes révélées) ? */
    boolean computeHasUpcomingCombat(Game g);

    /** Reconstruit les messages de révélation de la préphase. */
    void refreshPrephaseRevealMessages(Game g);

    /** Programme une avance de phase différée (si la partie est toujours dans `expected`). */
    void scheduleAdvance(String gameId, Phase expected, Phase target, long delayMs);

    /** (Re)lance le timer de fin de préphase. */
    void schedulePrephaseTimeout(String gameId, long millis, int expectedVersion);

    /** Enchaîne sur le prochain effet de lieu de la file (ou termine la préphase). */
    void scheduleNextLocationEffect(String gameId);

    /** Traite les morts (défausses) puis vérifie la fin de partie. */
    void handleDeathsAndVictory(Game g);

    /** Retire de la file de combats tous les duels impliquant ce joueur. */
    void cancelPendingCombatsForPlayer(Game g, Player player);
}
