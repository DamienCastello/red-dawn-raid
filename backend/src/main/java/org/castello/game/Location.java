package org.castello.game;

public enum Location {
    // Lieux de base (codes actuels)
    FOREST("forest", "Forêt"),
    QUARRY("quarry", "Carrière"),
    LAKE("lake", "Lac"),
    MANOR("manor", "Manoir"),

    // Lieux construits (codes comme tu veux)
    SAWMILL("sawmill", "Scierie"),
    MINE("mine", "Mine"),
    LIBRARY("library", "Bibliothèque");

    private final String code;     // ce qui est stocké dans Game/hand/center
    private final String labelFr;  // affichage

    Location(String code, String labelFr) {
        this.code = code;
        this.labelFr = labelFr;
    }

    public String code() {
        return code;
    }

    public String labelFr() {
        return labelFr;
    }

    /** Pour traduire un code stocké ("forest", "sawmill"...) en enum. */
    public static Location fromCode(String code) {
        if (code == null) return null;
        for (Location loc : values()) {
            if (loc.code.equals(code)) {
                return loc;
            }
        }
        return null;
    }
}
