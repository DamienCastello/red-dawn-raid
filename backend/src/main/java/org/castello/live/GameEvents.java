package org.castello.live;

public class GameEvents {
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
        UNSTABLE_ASSIGNED,
        POTION_BOUGHT,
        ACTION_BOUGHT,
        SILVER_BOUGHT,
        RESOURCE_SOLD,
        TRANSMUTED,
        TRADE_SYNC,
        TRADE_DELETED,
        PHASE4_READY_UPDATED,
        ACTION_USED,
        ACTION_STARTED,
        ACTION_ROLLED,
        ACTION_RESOLVED
    }
    private Type type; private String gameId; private Object payload; private long ts;
    public GameEvents() {}
    public GameEvents(Type t, String gid, Object p, long ts){
        this.type=t; this.gameId=gid; this.payload=p; this.ts=ts;
    }
    public Type getType(){ return type; }
    public String getGameId(){ return gameId; }
    public Object getPayload(){ return payload; }
    public long getTs(){ return ts; }
}
