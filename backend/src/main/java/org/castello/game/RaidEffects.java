package org.castello.game;

public class RaidEffects {
    // pour d’autres potions plus tard
    private boolean invulnerable;
    private boolean doubleAttack;
    private boolean doubleDefense;
    private boolean focus;
    private boolean leech;
    private boolean invisible;
    private boolean rapid;

    // getters/setters…
    public boolean isFocus() { return focus; }
    public void setFocus(boolean focus) { this.focus = focus; }

    public boolean isLeech() { return leech; }
    public void setLeech(boolean leech) { this.leech = leech; }

    public boolean isInvulnerable() { return invulnerable; }
    public void setInvulnerable(boolean invulnerable) { this.invulnerable = invulnerable; }

    public boolean isDoubleAttack() { return doubleAttack; }
    public void setDoubleAttack(boolean doubleAttack) { this.doubleAttack = doubleAttack; }

    public boolean isDoubleDefense() { return doubleDefense; }
    public void setDoubleDefense(boolean doubleDefense) { this.doubleDefense = doubleDefense; }

    public boolean isInvisible() { return invisible; }
    public void setInvisible(boolean invisible) { this.invisible = invisible; }

    public boolean isRapid() { return rapid; }
    public void setRapid(boolean rapid) { this.rapid = rapid; }
}
