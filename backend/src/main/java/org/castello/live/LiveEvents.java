package org.castello.live;

import org.castello.game.Game;
import org.castello.game.LocationEffectChoice;
import org.castello.player.Player;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Component
public class LiveEvents {
    private final SimpMessagingTemplate tpl;
    public LiveEvents(SimpMessagingTemplate tpl){ this.tpl = tpl; }

    public void send(String gameId, GameEvents e){
        tpl.convertAndSend("/topic/games/" + gameId, e);
    }

    public void raidModsUpdated(Game g, String... playerIds) {
        Map<String, Object> mods = new HashMap<>();
        if (g.getRaidMods() != null) {
            if (playerIds != null && playerIds.length > 0) {
                for (String pid : playerIds) {
                    var list = g.getRaidMods().get(pid);
                    mods.put(pid, list != null ? list : java.util.List.of());
                }
            } else {
                for (var p : g.getPlayers()) {
                    var list = g.getRaidMods().get(p.getId());
                    mods.put(p.getId(), list != null ? list : java.util.List.of());
                }
            }
        }
        send(g.getId(), new GameEvents(
                GameEvents.Type.RAID_MODS_UPDATED, g.getId(),
                Map.of("mods", mods),
                System.currentTimeMillis()
        ));
    }

    // petit utilitaire pour envoyer sur le topic lobby
    private void sendLobby(GameEvents e){
        tpl.convertAndSend("/topic/lobby", e);
    }

    public void lobbyUpdated(Game g){
        // push sur /topic/lobby pour rafraîchir la tuile et le panneau de droite
        var players = g.getPlayers().stream()
                .map(p -> Map.of("id", p.getId(), "username", p.getUsername()))
                .toList();

        sendLobby(new GameEvents(
                GameEvents.Type.LOBBY_UPDATED, g.getId(),
                Map.of(
                        "gameId", g.getId(),
                        "status", String.valueOf(g.getStatus()),
                        "players", players
                ),
                System.currentTimeMillis()
        ));
    }

    public void gameCreated(Game g){
        var players = g.getPlayers().stream()
                .map(p -> Map.of("id", p.getId(), "username", p.getUsername()))
                .toList();

        sendLobby(new GameEvents(
                GameEvents.Type.GAME_CREATED, g.getId(),
                Map.of(
                        "gameId", g.getId(),
                        "status", String.valueOf(g.getStatus()),
                        "players", players
                ),
                System.currentTimeMillis()
        ));
    }

    /** Déclenché dès que les cartes du centre passent face visible (pour animer le “flip”). */
    public void centerRevealed(Game g) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.CENTER_REVEALED, g.getId(),
                Map.of(), System.currentTimeMillis()
        ));
    }

    public void phaseChanged(Game g){
        send(g.getId(), new GameEvents(
                GameEvents.Type.PHASE_CHANGED, g.getId(),
                Map.of("phase", String.valueOf(g.getPhase()), "raid", g.getRaid()),
                System.currentTimeMillis()
        ));
    }

    public void weatherRolled(Game g){
        send(g.getId(), new GameEvents(
                GameEvents.Type.WEATHER_ROLLED, g.getId(),
                Map.of(
                        "roll", g.getWeatherRoll(),
                        "status", String.valueOf(g.getWeatherStatus()),
                        "nameFr", g.getWeatherStatusNameFr(),
                        "descFr", g.getWeatherDescriptionFr()
                ),
                System.currentTimeMillis()
        ));
    }

    // --- nouveaux helpers ---

    public void message(Game g, String text){
        send(g.getId(), new GameEvents(
                GameEvents.Type.MESSAGE, g.getId(),
                Map.of("text", text), System.currentTimeMillis()
        ));
    }

    public void locationSelected(Game g, String playerId, String card){
        send(g.getId(), new GameEvents(
                GameEvents.Type.LOCATION_SELECTED, g.getId(),
                Map.of("playerId", playerId, "card", card),
                System.currentTimeMillis()
        ));
    }

    public void diceRolled(Game g, String roundId, String rollerId, String side, int roll, List<String> breakdown){
        send(g.getId(), new GameEvents(
                GameEvents.Type.DICE_ROLLED, g.getId(),
                Map.of("roundId", roundId, "rollerId", rollerId, "side", side, "roll", roll, "breakdown", breakdown),
                System.currentTimeMillis()
        ));
    }

    public void combatResolved(Game g, String roundId, int dmg, String defenderId, int defenderHp, List<String> breakdown){
        send(g.getId(), new GameEvents(
                GameEvents.Type.COMBAT_RESOLVED, g.getId(),
                Map.of(
                        "roundId", roundId,
                        "dmg", dmg,
                        "defenderId", defenderId,
                        "defenderHp", defenderHp,
                        "breakdown", breakdown
                ),
                System.currentTimeMillis()
        ));
    }

    public void biteStarted(Game g, String attackerId, String targetId, String location){
        send(g.getId(), new GameEvents(
                GameEvents.Type.BITE_STARTED, g.getId(),
                Map.of("attackerId", attackerId, "targetId", targetId, "location", location),
                System.currentTimeMillis()
        ));
    }

    public void biteRolled(Game g, int roll, String attackerId, String targetId, Integer newCorruption, boolean becameServant){
        send(g.getId(), new GameEvents(
                GameEvents.Type.BITE_ROLLED, g.getId(),
                Map.of(
                        "roll", roll,
                        "attackerId", attackerId,
                        "targetId", targetId,
                        "newCorruption", newCorruption,
                        "becameServant", becameServant
                ),
                System.currentTimeMillis()
        ));
    }



    public void biteResolved(Game g, String attackerId, String targetId, String location) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.BITE_RESOLVED, g.getId(),
                Map.of(
                        "attackerId", attackerId,
                        "targetId",   targetId,
                        "location",   location
                ),
                System.currentTimeMillis()
        ));
    }

    public void potionUsed(Game g, String playerId, String type){
        send(g.getId(), new GameEvents(
                GameEvents.Type.POTION_USED, g.getId(),
                Map.of("playerId", playerId, "type", type),
                System.currentTimeMillis()
        ));
    }

    public void locationEffectStarted(Game g, Game.LocationEffectInstance inst) {
        Player owner = g.getPlayers().stream()
                .filter(p -> p.getId().equals(inst.ownerId))
                .findFirst().orElse(null);

        send(g.getId(), new GameEvents(
                GameEvents.Type.LOCATION_STARTED, g.getId(),
                Map.of(
                        "ownerId", inst.ownerId,
                        "username", owner != null ? owner.getUsername() : "?",
                        "infra", inst.infra.name()
                ),
                System.currentTimeMillis()
        ));
    }

    public void locationEffectUsed(Game g, LocationEffectChoice choice, Player player) {
        Game.LocationEffectInstance inst = null;
        if (g.getLocationEffectsQueue() != null && g.getCurrentLocationEffectIndex() != null) {
            int idx = g.getCurrentLocationEffectIndex();
            if (idx >= 0 && idx < g.getLocationEffectsQueue().size()) {
                inst = g.getLocationEffectsQueue().get(idx);
            }
        }

        String infra = (inst != null ? inst.infra.name() : null);

        send(g.getId(), new GameEvents(
                GameEvents.Type.LOCATION_USED, g.getId(),
                Map.of(
                        "choice", choice.name(),
                        "playerId", player.getId(),
                        "username", player.getUsername(),
                        "infra", infra
                ),
                System.currentTimeMillis()
        ));
    }

    public void readyUpdated(Game g, String playerId, int readyCount, int total){
        send(g.getId(), new GameEvents(
                GameEvents.Type.READY_UPDATED, g.getId(),
                Map.of("playerId", playerId, "ready", readyCount, "total", total),
                System.currentTimeMillis()
        ));
    }

    public void unstableAssigned(Game g, String unstableId, String kind, String value){
        // kind ∈ {"TARGET","HARVEST","NOTHING"}
        // NB: Map.of() n'accepte pas les null → construire à la main
        var payload = new java.util.HashMap<String, Object>();
        payload.put("unstableId", unstableId);
        payload.put("kind", kind);
        if (value != null) payload.put("value", value); // on n'ajoute pas "value" si null

        send(g.getId(), new GameEvents(
                GameEvents.Type.UNSTABLE_ASSIGNED, g.getId(),
                payload,
                System.currentTimeMillis()
        ));
    }

    public void potionBought(Game g, String playerId, String type, int left) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.POTION_BOUGHT, g.getId(),
                Map.of("playerId", playerId, "type", type, "left", left),
                System.currentTimeMillis()
        ));
    }

    public void actionBought(Game g, String playerId, String type, int left) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.ACTION_BOUGHT, g.getId(),
                Map.of("playerId", playerId, "type", type, "left", left),
                System.currentTimeMillis()
        ));
    }

    public void silverBought(Game g, String playerId, int qty, int cost) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.SILVER_BOUGHT, g.getId(),
                Map.of("playerId", playerId, "qty", qty, "cost", cost),
                System.currentTimeMillis()
        ));
    }

    public void resourceSold(Game g, String playerId, String res, int qty, int gain) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.RESOURCE_SOLD, g.getId(),
                Map.of("playerId", playerId, "res", res, "qty", qty, "gain", gain),
                System.currentTimeMillis()
        ));
    }

    public void transmuted(Game g, String playerId, String recipe) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.TRANSMUTED, g.getId(),
                Map.of("playerId", playerId, "recipe", recipe),
                System.currentTimeMillis()
        ));
    }

    public void phase4ReadyUpdated(Game g, String playerId, int ready, int total) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.PHASE4_READY_UPDATED, g.getId(),
                Map.of("playerId", playerId, "ready", ready, "total", total),
                System.currentTimeMillis()
        ));
    }

    public void tradeSync(Game g, Game.Trade t) {
        Map<String,Object> payload = new java.util.HashMap<>();
        payload.put("id", t.getId());
        payload.put("side", t.getSide());
        payload.put("aId", t.getAId());
        payload.put("bId", t.getBId());
        payload.put("offerA", t.getOfferA());
        payload.put("offerB", t.getOfferB());
        payload.put("statusA", t.getStatusA());
        payload.put("statusB", t.getStatusB());
        payload.put("updatedAt", t.getUpdatedAt());
        send(g.getId(), new GameEvents(
                GameEvents.Type.TRADE_SYNC, g.getId(), payload, System.currentTimeMillis()
        ));
    }

    public void tradeDeleted(
            Game g,
            String tradeId,
            String aId,
            String bId,
            String reason,                // "FINAL" | "FINISH_PHASE4"...
            String result,                // "SUCCESS" | "CLOSED"
            Map<String,Object> extra      // ex: { offerA: Map, offerB: Map }
    ) {
        var payload = new HashMap<String,Object>();
        payload.put("id", tradeId);
        payload.put("aId", aId);
        payload.put("bId", bId);
        payload.put("reason", reason);
        payload.put("result", result);
        if (extra != null) payload.putAll(extra);

        send(g.getId(), new GameEvents(
                GameEvents.Type.TRADE_DELETED, g.getId(), payload, System.currentTimeMillis()
        ));
    }

    public void actionUsed(Game g, String playerId, String type) {
        send(g.getId(), new GameEvents(
                GameEvents.Type.ACTION_USED, g.getId(),
                Map.of("playerId", playerId, "type", type),
                System.currentTimeMillis()
        ));
    }

    public void actionStarted(Game g, String mode, String ownerId, String location, String targetId) {
        var payload = new HashMap<String, Object>();
        payload.put("mode", mode);           // "NET" ou "PIT"
        payload.put("ownerId", ownerId);
        payload.put("location", location);
        if (targetId != null) {
            payload.put("targetId", targetId);
        }

        send(g.getId(), new GameEvents(
                GameEvents.Type.ACTION_STARTED,
                g.getId(),
                payload,
                System.currentTimeMillis()
        ));
    }

    public void actionRolled(Game g, String mode, String ownerId, String targetId, int roll) {
        var payload = new HashMap<String, Object>();
        payload.put("mode", mode);          // "NET" ou "PIT"
        payload.put("ownerId", ownerId);
        payload.put("targetId", targetId);
        payload.put("roll", roll);

        send(g.getId(), new GameEvents(
                GameEvents.Type.ACTION_ROLLED,
                g.getId(),
                payload,
                System.currentTimeMillis()
        ));
    }

    public void actionResolved(Game g, String mode, String ownerId, String targetId) {
        var payload = new HashMap<String, Object>();
        payload.put("mode", mode);
        payload.put("ownerId", ownerId);
        if (targetId != null) {
            payload.put("targetId", targetId);
        }

        send(g.getId(), new GameEvents(
                GameEvents.Type.ACTION_RESOLVED,
                g.getId(),
                payload,
                System.currentTimeMillis()
        ));
    }

}
