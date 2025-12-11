package org.castello.game;

public enum LocationEffectChoice {
    STUDY,
    THEFT,
    OMEN,
    EXPERIMENT,
    EXPLOSION,
    ALCHEMY,
    RARE_ALCHEMY,
    DEATH_DANCE,
    SNEAK_ATTACK,
    BLOOD_WALTZ,
    LOOTING,
    HEAL,          // Chasseur → -1 corruption sur un chasseur
    CORRUPT_SOULS, // Vampire → sacrifie des âmes pour corrompre l'autel
    CORRUPT,           // Vampire → +1 corruption sur un chasseur
    PURIFY_WATER,      // Chasseur → consomme EAU_BENITE pour purifier l'autel
    FORGE
}
