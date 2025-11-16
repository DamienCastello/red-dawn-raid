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

    // compteurs des decks/pioches
    private int vampActionsLeft, vampActionsDiscard;
    private int hunterActionsLeft, hunterActionsDiscard;
    private int potionsLeft, potionsDiscard;

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

    // --- actions & potions ---
    // Inventaire de potions par joueur (liste d'IDs de potions).
    // Ex: "FORCE", "ENDURANCE", "VIE", ...
    private Map<String, List<String>> potionsByPlayer = new HashMap<>();

    // Effets temporaires pour le raid courant (réinitialisés en PHASE0)
    private Map<String, RaidEffects> raidEffects = new HashMap<>();

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

    // compteurs
    public int getVampActionsLeft() { return vampActionsLeft; }
    public void setVampActionsLeft(int v) { this.vampActionsLeft = v; }

    public int getVampActionsDiscard() { return vampActionsDiscard; }
    public void setVampActionsDiscard(int v) { this.vampActionsDiscard = v; }

    public int getHunterActionsLeft() { return hunterActionsLeft; }
    public void setHunterActionsLeft(int v) { this.hunterActionsLeft = v; }

    public int getHunterActionsDiscard() { return hunterActionsDiscard; }
    public void setHunterActionsDiscard(int v) { this.hunterActionsDiscard = v; }

    public int getPotionsLeft() { return potionsLeft; }
    public void setPotionsLeft(int v) { this.potionsLeft = v; }

    public int getPotionsDiscard() { return potionsDiscard; }
    public void setPotionsDiscard(int v) { this.potionsDiscard = v; }

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

    // actions & potions
    public Map<String, List<String>> getPotionsByPlayer() { return potionsByPlayer; }
    public void setPotionsByPlayer(Map<String, List<String>> m) { this.potionsByPlayer = m; }

    public List<String> potionsOf(String playerId) {
        var m = getPotionsByPlayer();
        return m != null ? m.getOrDefault(playerId, java.util.List.of()) : java.util.List.of();
    }

    public boolean hasPotion(String playerId, Potion type) {
        return potionsOf(playerId).contains(type.name());
    }

    public Map<String, RaidEffects> getRaidEffects() { return raidEffects; }
    public void setRaidEffects(Map<String, RaidEffects> m) { this.raidEffects = m; }

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

    public Map<String,Integer> getPotionsPool() { return potionsPool; }
    public void setPotionsPool(Map<String,Integer> m) { this.potionsPool = m; }

    public Set<String> getReadyForNextRaid() { return readyForNextRaid; }

    public Long getPhase4DeadlineMillis() { return phase4DeadlineMillis; }
    public void setPhase4DeadlineMillis(Long v) { this.phase4DeadlineMillis = v; }

    public List<Trade> getTrades(){ return trades; }
    public void setTrades(List<Trade> t){ this.trades = t; }
}
