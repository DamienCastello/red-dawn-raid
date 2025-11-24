package org.castello.game;

import org.castello.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

public class Game {
    private String id;
    private GameStatus status;

    // état "tour/raid"
    private int raid;            // n° de raid (1 au start)
    private Phase phase;         // PHASE0 / PHASE1 / PHASE2 / PHASE3 / PHASE4

    // joueurs dans la partie
    private final List<Player> players = new ArrayList<>();

    // cartes posées au centre (face cachée/visible)
    private List<CenterBoard> center = new ArrayList<>();

    // --- Step 3: messages & fenêtre d’actions ---
    private List<String> messages = new ArrayList<>();   // messages à afficher (préphase3 / phase3)
    private final Set<String> readyForPhase3 = new HashSet<>(); // joueurs ayant cliqué “j’ai fini”

    // --- PHASE3 : file de combats + combat courant ---
    private List<RoundFight> combatsQueue = new ArrayList<>();
    private Integer currentCombatIndex;           // null si aucun combat
    private RoundFight currentCombat;            // miroir pour le client

    // --- METEO ---
    private Integer weatherRoll;
    private WeatherStatus weatherStatus;
    private String weatherStatusNameFr;
    private String weatherDescriptionFr;

    // --- Buffs/Debuffs du raid (affichage + calcul) ---
    private Map<String, List<StatMod>> raidMods = new HashMap<>();

    // --- historique ---
    public static class HistoryItem {
        private int raid;
        private Phase phase;
        private long ts;
        private String text;

        public HistoryItem() {}

        public HistoryItem(int raid, Phase phase, long ts, String text) {
            this.raid = raid;
            this.phase = phase;
            this.ts = ts;
            this.text = text;
        }

        public int getRaid() { return raid; }
        public void setRaid(int raid) { this.raid = raid; }

        public Phase getPhase() { return phase; }
        public void setPhase(Phase phase) { this.phase = phase; }

        public long getTs() { return ts; }
        public void setTs(long ts) { this.ts = ts; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    // --- Récolte ---
    private Integer harvestedRaid; // n° de raid pour lequel la récolte a déjà été appliquée (null = pas encore)

    // Lieux protégés par une Fumigation d'ail pour le raid courant.
    // Exemple : "forest", "quarry", ...
    private Set<String> garlicBlockedLocations = new HashSet<>();

    // Joueurs ayant joué FUMIGATION_AIL mais pas encore posé leur lieu
    private Set<String> pendingGarlicPlayers = new HashSet<>();

    // Joueurs chasseurs ayant joué PISTEUR : ils suivront le vampire en PHASE2.
    private Set<String> trackerHunters = new HashSet<>();

    // Feux de camp chasseur sur lieu
    private Set<String> campfireLocations = new java.util.HashSet<>();

    // Chasseurs ayant préparé un Filet pour ce raid (joué en PREPHASE3)
    private java.util.Set<String> netHunters = new java.util.HashSet<>();

    // Chasseurs ayant préparé une Fosse pour ce raid
    private java.util.Set<String> pitHunters = new java.util.HashSet<>();

    // Effets temporaires pour le raid courant (réinitialisés en PHASE0)
    private Map<String, RaidEffects> raidEffects = new HashMap<>();

    // --- Action (affichage / spectate) ---
    public static class Action {
        // "NET" | "PIT" ...
        private String mode;
        // chasseur qui a préparé / résout le piège
        private String ownerId;
        // lieu du piège (forest, quarry, ...)
        private String location;
        // cible en cours de résolution
        private String targetId;
        // résultat du d20 (null tant que pas lancé)
        private Integer roll;
        // détail du calcul (ligne par ligne, pour la modale)
        private List<String> breakdownLines = new ArrayList<>();
        // timestamp quand le jet a été résolu
        private Long resolvedAtMillis;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }

        public Integer getRoll() { return roll; }
        public void setRoll(Integer roll) { this.roll = roll; }

        public List<String> getBreakdownLines() { return breakdownLines; }
        public void setBreakdownLines(List<String> breakdownLines) {
            this.breakdownLines = (breakdownLines != null ? breakdownLines : new ArrayList<>());
        }

        public Long getResolvedAtMillis() { return resolvedAtMillis; }
        public void setResolvedAtMillis(Long resolvedAtMillis) { this.resolvedAtMillis = resolvedAtMillis; }
    }

    // hunterId -> liste des victimes potentielles de sa Fosse (ids de joueurs vamp/serviteurs sur son lieu)
    private Map<String, List<String>> pitTargetsByHunter;

    // hunterId -> index courant dans la liste ci-dessus
    private Map<String, Integer> pitIndexByHunter;

    private Action currentAction;

    // Corruption
    public static class BiteAttempt {
        private String id;
        private String attackerId; // vampire
        private String targetId;   // chasseur mordu
        private String location;   // pour l’affichage
        private Integer roll;      // null tant que pas lancé
        private Long resolvedAtMillis;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getAttackerId() { return attackerId; }
        public void setAttackerId(String s) { this.attackerId = s; }

        public String getTargetId() { return targetId; }
        public void setTargetId(String s) { this.targetId = s; }

        public String getLocation() { return location; }
        public void setLocation(String s) { this.location = s; }

        public Integer getRoll() { return roll; }
        public void setRoll(Integer r) { this.roll = r; }

        public Long getResolvedAtMillis() { return resolvedAtMillis; }
        public void setResolvedAtMillis(Long r) { this.resolvedAtMillis = r; }
    }

    private BiteAttempt currentBite;

    // En PREPHASE3 : options chasseur instable -> cible à attaquer
    private Map<String, String> unstableTargetByPlayer = new HashMap<>();
    // PREPHASE3 — options “récolte pour le vampire”
    private Map<String, List<String>> unstableEligibleLocations = new HashMap<>();
    private Map<String, String> unstableHarvestLocByPlayer = new HashMap<>();
    // Pour afficher une modale de choix au vampire
    private Map<String, List<String>> unstableEligibleTargets = new HashMap<>();

    // Maintenance
    // --- Deck potions (composition) ---
    private Map<String, Integer> potionsPool = new HashMap<>();
    // --- Défausse potions (pour stats / éventuel reshuffle) ---
    private Map<String, Integer> potionsDiscardPool = new HashMap<>();

    // Deck d'actions chasseurs
    private Map<String,Integer> hunterActionsPool = new HashMap<>();
    private Map<String,Integer> hunterActionsDiscardPool = new HashMap<>();

    // Deck d'actions vampire
    private Map<String,Integer> vampActionsPool = new HashMap<>();
    private Map<String,Integer> vampActionsDiscardPool = new HashMap<>();

    // --- Phase4: "j'ai fini" (finishTrade) ---
    private final Set<String> readyForNextRaid = new HashSet<>();

    // --- Optionnel pour un vrai compte à rebours côté front ---
    private Long phase4DeadlineMillis;

    // --- Échanges ---
    public static class Trade {
        private String id;
        private String side; // "HUNTERS" | "VAMP_SIDE"
        private String aId;
        private String bId;
        private Map<String,Integer> offerA = new HashMap<>();
        private Map<String,Integer> offerB = new HashMap<>();
        private String statusA = "PENDING"; // PENDING/CONFIRMED/REFUSED/CANCELLED
        private String statusB = "PENDING";
        private long updatedAt;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }

        public String getAId() { return aId; }
        public void setAId(String aId) { this.aId = aId; }

        public String getBId() { return bId; }
        public void setBId(String bId) { this.bId = bId; }

        public Map<String,Integer> getOfferA() { return offerA; }
        public void setOfferA(Map<String,Integer> offerA) { this.offerA = (offerA!=null?offerA:new HashMap<>()); }

        public Map<String,Integer> getOfferB() { return offerB; }
        public void setOfferB(Map<String,Integer> offerB) { this.offerB = (offerB!=null?offerB:new HashMap<>()); }

        public String getStatusA() { return statusA; }
        public void setStatusA(String statusA) { this.statusA = statusA; }

        public String getStatusB() { return statusB; }
        public void setStatusB(String statusB) { this.statusB = statusB; }

        public long getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    }

    private List<Trade> trades = new ArrayList<>();

    public Game() {}

    public Game(String id, GameStatus status, int raid) {
        this.id = id;
        this.status = status;
        this.raid = raid;
        this.phase = null;
    }

    // getters de base
    public String getId() { return id; }
    public GameStatus getStatus() { return status; }
    public int getRound() { return raid; }

    public void setStatus(GameStatus status) { this.status = status; }

    // état de partie
    public int getRaid() { return raid; }
    public void setRaid(int raid) { this.raid = raid; }

    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }

    // joueurs
    public List<Player> getPlayers() { return players; }

    // centre
    public List<CenterBoard> getCenter() { return center; }
    public void setCenter(List<CenterBoard> center) { this.center = center; }

    // messages
    public List<String> getMessages() { return messages; }
    public void setMessages(List<String> messages) { this.messages = messages; }

    // skip/ready
    public Set<String> getReadyForPhase3() { return readyForPhase3; }

    // fight
    public List<RoundFight> getCombatsQueue() { return combatsQueue; }
    public void setCombatsQueue(List<RoundFight> combatsQueue) { this.combatsQueue = combatsQueue; }

    public Integer getCurrentCombatIndex() { return currentCombatIndex; }
    public void setCurrentCombatIndex(Integer currentCombatIndex) { this.currentCombatIndex = currentCombatIndex; }

    public RoundFight getCurrentCombat() { return currentCombat; }
    public void setCurrentCombat(RoundFight currentCombat) { this.currentCombat = currentCombat; }

    // meteo
    public Integer getWeatherRoll() { return weatherRoll; }
    public void setWeatherRoll(Integer weatherRoll) { this.weatherRoll = weatherRoll; }
    public WeatherStatus getWeatherStatus() { return weatherStatus; }
    public void setWeatherStatus(WeatherStatus weatherStatus) { this.weatherStatus = weatherStatus; }
    public String getWeatherStatusNameFr() { return weatherStatusNameFr; }
    public void setWeatherStatusNameFr(String weatherStatusNameFr) { this.weatherStatusNameFr = weatherStatusNameFr; }
    public String getWeatherDescriptionFr() { return weatherDescriptionFr; }
    public void setWeatherDescriptionFr(String weatherDescriptionFr) { this.weatherDescriptionFr = weatherDescriptionFr; }

    // buffs/debuffs
    public Map<String, List<StatMod>> getRaidMods() { return raidMods; }
    public void setRaidMods(Map<String, List<StatMod>> raidMods) { this.raidMods = raidMods; }

    //historique
    private boolean hasUpcomingCombat;
    private List<HistoryItem> history = new ArrayList<>();

    public boolean isHasUpcomingCombat() { return hasUpcomingCombat; }
    public void setHasUpcomingCombat(boolean v) { this.hasUpcomingCombat = v; }

    public List<HistoryItem> getHistory() { return history; }
    public void setHistory(List<HistoryItem> h) { this.history = h; }

    // récolte
    public Integer getHarvestedRaid() { return harvestedRaid; }
    public void setHarvestedRaid(Integer v) { this.harvestedRaid = v; }

    public Map<String, RaidEffects> getRaidEffects() { return raidEffects; }
    public void setRaidEffects(Map<String, RaidEffects> m) { this.raidEffects = m; }

    public Set<String> getGarlicBlockedLocations() { return garlicBlockedLocations; }
    public void setGarlicBlockedLocations(Set<String> s) { this.garlicBlockedLocations = s; }

    public Set<String> getPendingGarlicPlayers() { return pendingGarlicPlayers; }
    public void setPendingGarlicPlayers(Set<String> s) { this.pendingGarlicPlayers = s; }

    public Set<String> getTrackerHunters() { return trackerHunters; }
    public void setTrackerHunters(Set<String> s) { this.trackerHunters = s; }

    public Set<String> getCampfireLocations() {
        return campfireLocations;
    }
    public void setCampfireLocations(Set<String> campfireLocations) {
        this.campfireLocations = campfireLocations;
    }

    public Set<String> getNetHunters() { return netHunters; }
    public void setNetHunters(Set<String> s) { this.netHunters = s; }

    public Set<String> getPitHunters() { return pitHunters; }
    public void setPitHunters(Set<String> s) { this.pitHunters = s; }

    public Action getCurrentAction() { return currentAction; }
    public void setCurrentAction(Action currentAction) { this.currentAction = currentAction; }

    public Map<String, List<String>> getPitTargetsByHunter() { return pitTargetsByHunter; }
    public void setPitTargetsByHunter(Map<String, List<String>> pitTargetsByHunter) { this.pitTargetsByHunter = pitTargetsByHunter; }

    public Map<String, Integer> getPitIndexByHunter() { return pitIndexByHunter; }
    public void setPitIndexByHunter(Map<String, Integer> pitIndexByHunter) { this.pitIndexByHunter = pitIndexByHunter;}

    // corruption
    public Map<String, List<String>> getUnstableEligibleTargets() { return unstableEligibleTargets; }
    public void setUnstableEligibleTargets(Map<String, List<String>> m) { this.unstableEligibleTargets = m; }

    public Map<String, String> getUnstableTargetByPlayer() { return unstableTargetByPlayer; }
    public void setUnstableTargetByPlayer(Map<String, String> m) { this.unstableTargetByPlayer = m; }

    public BiteAttempt getCurrentBite() { return currentBite; }
    public void setCurrentBite(BiteAttempt b) { this.currentBite = b; }

    public Map<String, List<String>> getUnstableEligibleLocations() { return unstableEligibleLocations; }
    public void setUnstableEligibleLocations(Map<String, List<String>> m) { this.unstableEligibleLocations = m; }

    public Map<String, String> getUnstableHarvestLocByPlayer() { return unstableHarvestLocByPlayer; }
    public void setUnstableHarvestLocByPlayer(Map<String, String> m) { this.unstableHarvestLocByPlayer = m; }

    //Maintenance
    public Map<String,Integer> getPotionsPool() { return potionsPool; }
    public void setPotionsPool(Map<String,Integer> m) { this.potionsPool = m; }

    public Map<String,Integer> getPotionsDiscardPool() { return potionsDiscardPool; }
    public void setPotionsDiscardPool(Map<String,Integer> m) { this.potionsDiscardPool = m; }

    public Map<String,Integer> getHunterActionsPool() { return hunterActionsPool; }
    public void setHunterActionsPool(Map<String,Integer> m) { this.hunterActionsPool = m; }

    public Map<String,Integer> getHunterActionsDiscardPool() { return hunterActionsDiscardPool; }
    public void setHunterActionsDiscardPool(Map<String,Integer> m) { this.hunterActionsDiscardPool = m; }

    public Map<String,Integer> getVampActionsPool() { return vampActionsPool; }
    public void setVampActionsPool(Map<String,Integer> m) { this.vampActionsPool = m; }

    public Map<String,Integer> getVampActionsDiscardPool() { return vampActionsDiscardPool; }
    public void setVampActionsDiscardPool(Map<String,Integer> m) { this.vampActionsDiscardPool = m; }

    public Set<String> getReadyForNextRaid() { return readyForNextRaid; }

    public Long getPhase4DeadlineMillis() { return phase4DeadlineMillis; }
    public void setPhase4DeadlineMillis(Long v) { this.phase4DeadlineMillis = v; }

    public List<Trade> getTrades(){ return trades; }
    public void setTrades(List<Trade> t){ this.trades = t; }
}
