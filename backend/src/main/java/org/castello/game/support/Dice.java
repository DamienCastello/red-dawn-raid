package org.castello.game.support;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Source unique d'aléatoire du jeu (jets de dés, mélanges, tirages).
 * Centralisée pour rendre les jets traçables et, plus tard, simulables
 * (tests reproductibles, bot).
 */
@Component
public class Dice {

    private final Random rnd = new Random();

    /** Jet de dé classique : résultat entre 1 et sides inclus. */
    public int roll(int sides) {
        return 1 + rnd.nextInt(sides);
    }

    /** Entier entre 0 (inclus) et bound (exclu) — pour les choix aléatoires. */
    public int nextInt(int bound) {
        return rnd.nextInt(bound);
    }

    public boolean nextBoolean() {
        return rnd.nextBoolean();
    }

    public void shuffle(List<?> list) {
        Collections.shuffle(list, rnd);
    }
}
