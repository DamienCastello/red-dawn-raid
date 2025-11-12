package org.castello.live;

public class GameEvent {
    public enum Type {
        LOBBY_UPDATED,
        GAME_CREATED,
        RAID_MODS_UPDATED,
        CENTER_REVEALED,
        PHASE_CHANGED,
        WEATHER_ROLLED,
        MESSAGE,
        LOCATION_SELECTED,
        DICE_ROLLED,
        COMBAT_RESOLVED,
        BITE_STARTED,
        BITE_ROLLED,
        BITE_RESOLVED,
        POTION_USED,
        READY_UPDATED,
        UNSTABLE_ASSIGNED
    }
    private Type type; private String gameId; private Object payload; private long ts;
    public GameEvent() {}
    public GameEvent(Type t, String gid, Object p, long ts){
        this.type=t; this.gameId=gid; this.payload=p; this.ts=ts;
    }
    public Type getType(){ return type; }
    public String getGameId(){ return gameId; }
    public Object getPayload(){ return payload; }
    public long getTs(){ return ts; }
}
