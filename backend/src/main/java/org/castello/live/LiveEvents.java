package org.castello.live;

import org.castello.game.Game;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Component
public class LiveEvents {
    private final SimpMessagingTemplate tpl;
    public LiveEvents(SimpMessagingTemplate tpl){ this.tpl = tpl; }

    public void send(String gameId, GameEvent e){
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
        send(g.getId(), new GameEvent(
                GameEvent.Type.RAID_MODS_UPDATED, g.getId(),
                Map.of("mods", mods),
                System.currentTimeMillis()
        ));
    }

    // petit utilitaire pour envoyer sur le topic lobby
    private void sendLobby(GameEvent e){
        tpl.convertAndSend("/topic/lobby", e);
    }

    public void lobbyUpdated(Game g){
        // push sur /topic/lobby pour rafraîchir la tuile et le panneau de droite
        var players = g.getPlayers().stream()
                .map(p -> Map.of("id", p.getId(), "username", p.getUsername()))
                .toList();

        sendLobby(new GameEvent(
                GameEvent.Type.LOBBY_UPDATED, g.getId(),
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

        sendLobby(new GameEvent(
                GameEvent.Type.GAME_CREATED, g.getId(),
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
        send(g.getId(), new GameEvent(
                GameEvent.Type.CENTER_REVEALED, g.getId(),
                Map.of(), System.currentTimeMillis()
        ));
    }

    public void phaseChanged(Game g){
        send(g.getId(), new GameEvent(
                GameEvent.Type.PHASE_CHANGED, g.getId(),
                Map.of("phase", String.valueOf(g.getPhase()), "raid", g.getRaid()),
                System.currentTimeMillis()
        ));
    }

    public void weatherRolled(Game g){
        send(g.getId(), new GameEvent(
                GameEvent.Type.WEATHER_ROLLED, g.getId(),
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
        send(g.getId(), new GameEvent(
                GameEvent.Type.MESSAGE, g.getId(),
                Map.of("text", text), System.currentTimeMillis()
        ));
    }

    public void locationSelected(Game g, String playerId, String card){
        send(g.getId(), new GameEvent(
                GameEvent.Type.LOCATION_SELECTED, g.getId(),
                Map.of("playerId", playerId, "card", card),
                System.currentTimeMillis()
        ));
    }

    public void diceRolled(Game g, String roundId, String rollerId, String side, int roll){
        send(g.getId(), new GameEvent(
                GameEvent.Type.DICE_ROLLED, g.getId(),
                Map.of("roundId", roundId, "rollerId", rollerId, "side", side, "roll", roll),
                System.currentTimeMillis()
        ));
    }

    public void combatResolved(Game g, String roundId, int dmg, String defenderId, int defenderHp, List<String> breakdown){
        send(g.getId(), new GameEvent(
                GameEvent.Type.COMBAT_RESOLVED, g.getId(),
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
        send(g.getId(), new GameEvent(
                GameEvent.Type.BITE_STARTED, g.getId(),
                Map.of("attackerId", attackerId, "targetId", targetId, "location", location),
                System.currentTimeMillis()
        ));
    }

    public void biteRolled(Game g, int roll, String attackerId, String targetId, Integer newCorruption, boolean becameServant){
        send(g.getId(), new GameEvent(
                GameEvent.Type.BITE_ROLLED, g.getId(),
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
        send(g.getId(), new GameEvent(
                GameEvent.Type.BITE_RESOLVED, g.getId(),
                Map.of(
                        "attackerId", attackerId,
                        "targetId",   targetId,
                        "location",   location
                ),
                System.currentTimeMillis()
        ));
    }

    public void potionUsed(Game g, String playerId, String type){
        send(g.getId(), new GameEvent(
                GameEvent.Type.POTION_USED, g.getId(),
                Map.of("playerId", playerId, "type", type),
                System.currentTimeMillis()
        ));
    }

    public void readyUpdated(Game g, String playerId, int readyCount, int total){
        send(g.getId(), new GameEvent(
                GameEvent.Type.READY_UPDATED, g.getId(),
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

        send(g.getId(), new GameEvent(
                GameEvent.Type.UNSTABLE_ASSIGNED, g.getId(),
                payload,
                System.currentTimeMillis()
        ));
    }
}
