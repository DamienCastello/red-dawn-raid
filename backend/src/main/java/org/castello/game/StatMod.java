package org.castello.game;

public class StatMod {
    private String stat;
    private int amount;
    private String source;

    public StatMod() {}
    public StatMod(String stat, int amount, String source) {
        this.stat = stat; this.amount = amount; this.source = source;
    }

    public String getStat() { return stat; }
    public void setStat(String stat) { this.stat = stat; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
