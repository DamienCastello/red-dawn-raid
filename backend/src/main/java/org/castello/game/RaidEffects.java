package org.castello.game;

public class RaidEffects {
    // “+1 dé” = avantage : on jette 2 dés et on garde le meilleur
    private boolean attackAdvantage;
    private boolean defenseAdvantage;

    // pour d’autres potions plus tard
    private boolean invulnerable;         // (rare) ignore les dégâts ce raid
    private boolean doubleAttack;         // (rare) x2 attaque ce raid
    private boolean doubleDefense;        // (rare) x2 défense ce raid
    private boolean ignoreCorruption;     // Lucidité ce raid
    private boolean ignoreWeatherPenalties; // Chaleur ce tour (rain/blizzard/wind)

    // getters/setters…
    public boolean isAttackAdvantage() { return attackAdvantage; }
    public void setAttackAdvantage(boolean b) { this.attackAdvantage = b; }

    public boolean isDefenseAdvantage() { return defenseAdvantage; }
    public void setDefenseAdvantage(boolean b) { this.defenseAdvantage = b; }

    public boolean isInvulnerable() { return invulnerable; }
    public void setInvulnerable(boolean invulnerable) { this.invulnerable = invulnerable; }

    public boolean isDoubleAttack() { return doubleAttack; }
    public void setDoubleAttack(boolean doubleAttack) { this.doubleAttack = doubleAttack; }

    public boolean isDoubleDefense() { return doubleDefense; }
    public void setDoubleDefense(boolean doubleDefense) { this.doubleDefense = doubleDefense; }

    public boolean isIgnoreCorruption() { return ignoreCorruption; }
    public void setIgnoreCorruption(boolean ignoreCorruption) { this.ignoreCorruption = ignoreCorruption; }

    public boolean isIgnoreWeatherPenalties() { return ignoreWeatherPenalties; }
    public void setIgnoreWeatherPenalties(boolean b) { this.ignoreWeatherPenalties = b; }
}
