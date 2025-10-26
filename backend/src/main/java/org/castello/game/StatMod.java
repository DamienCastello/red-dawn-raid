package org.castello.game;

public class StatMod {
    private String stat;
    private int amount;
    private String source;
    private String description;

    public StatMod() {}
    public StatMod(String stat, int amount, String source, String description) {
        this.stat = stat; this.amount = amount; this.source = source; this.description = description;
    }

    public String getStat() { return stat; }
    public void setStat(String stat) { this.stat = stat; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
}
