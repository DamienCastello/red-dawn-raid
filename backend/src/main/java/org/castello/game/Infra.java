package org.castello.game;

public enum Infra {
    SAWMILL(Location.SAWMILL),
    MINE   (Location.MINE),
    LIBRARY(Location.LIBRARY),
    LABORATORY(Location.LABORATORY),
    BALLROOM(Location.BALLROOM),
    ALTAR(Location.ALTAR),
    FORGE(Location.FORGE);

    private final Location locationCard;

    Infra(Location locationCard) {
        this.locationCard = locationCard;
    }

    public Location getLocationCard() {
        return locationCard;
    }

    /** Code stocké dans la main / centerBoard ("sawmill", "mine"...). */
    public String locationCode() {
        return locationCard.code();
    }

    /** Label FR du lieu ("Scierie", "Mine"). */
    public String labelFr() {
        return locationCard.labelFr();
    }
}
