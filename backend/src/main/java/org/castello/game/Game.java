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

    private long phaseStartMillis;            // timestamp d’entrée dans la phase courante
    private Phase pendingNextPhase;           // phase à appliquer automatiquement (sinon null)
    private long nextAutoAdvanceAtMillis;     // quand appliquer pendingNextPhase (ms)

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
    private long prePhaseDeadlineMillis;                 // quand se termine la fenêtre PREPHASE3 (ms)
    private final Set<String> readyForPhase3 = new HashSet<>(); // joueurs ayant cliqué “j’ai fini”

    // --- PHASE3 : file de combats + combat courant ---
    private List<RoundFight> combatsQueue = new ArrayList<>();
    private Integer currentCombatIndex;           // null si aucun combat
    private RoundFight currentCombat;            // miroir pour le client
    private long currentCombatNextAdvanceAtMillis;// 0 si pas planifié

    // --- METEO ---
    private long weatherModalNotBeforeMillis;
    private Integer weatherRoll;
    private WeatherStatus weatherStatus;
    private String weatherStatusNameFr;
    private String weatherDescriptionFr;
    private long weatherShowUntilMillis;

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

    // gestion des phases
    public long getPhaseStartMillis() { return phaseStartMillis; }
    public Phase getPendingNextPhase() { return pendingNextPhase; }
    public long getNextAutoAdvanceAtMillis() { return nextAutoAdvanceAtMillis; }

    public void setPhaseStartMillis(long v) { this.phaseStartMillis = v; }
    public void setPendingNextPhase(Phase p) { this.pendingNextPhase = p; }
    public void setNextAutoAdvanceAtMillis(long v) { this.nextAutoAdvanceAtMillis = v; }

    // messages
    public List<String> getMessages() { return messages; }
    public void setMessages(List<String> messages) { this.messages = messages; }

    // fenêtre PREPHASE3
    public long getPrePhaseDeadlineMillis() { return prePhaseDeadlineMillis; }
    public void setPrePhaseDeadlineMillis(long v) { this.prePhaseDeadlineMillis = v; }

    // skip/ready
    public Set<String> getReadyForPhase3() { return readyForPhase3; }

    // fight
    public List<RoundFight> getCombatsQueue() { return combatsQueue; }
    public void setCombatsQueue(List<RoundFight> combatsQueue) { this.combatsQueue = combatsQueue; }

    public Integer getCurrentCombatIndex() { return currentCombatIndex; }
    public void setCurrentCombatIndex(Integer currentCombatIndex) { this.currentCombatIndex = currentCombatIndex; }

    public RoundFight getCurrentCombat() { return currentCombat; }
    public void setCurrentCombat(RoundFight currentCombat) { this.currentCombat = currentCombat; }

    public long getCurrentCombatNextAdvanceAtMillis() { return currentCombatNextAdvanceAtMillis; }
    public void setCurrentCombatNextAdvanceAtMillis(long currentCombatNextAdvanceAtMillis) {
        this.currentCombatNextAdvanceAtMillis = currentCombatNextAdvanceAtMillis;
    }

    // meteo
    public long getWeatherModalNotBeforeMillis() { return weatherModalNotBeforeMillis; }
    public void setWeatherModalNotBeforeMillis(long v) { this.weatherModalNotBeforeMillis = v; }
    public Integer getWeatherRoll() { return weatherRoll; }
    public void setWeatherRoll(Integer weatherRoll) { this.weatherRoll = weatherRoll; }
    public WeatherStatus getWeatherStatus() { return weatherStatus; }
    public void setWeatherStatus(WeatherStatus weatherStatus) { this.weatherStatus = weatherStatus; }
    public String getWeatherStatusNameFr() { return weatherStatusNameFr; }
    public void setWeatherStatusNameFr(String weatherStatusNameFr) { this.weatherStatusNameFr = weatherStatusNameFr; }
    public String getWeatherDescriptionFr() { return weatherDescriptionFr; }
    public void setWeatherDescriptionFr(String weatherDescriptionFr) { this.weatherDescriptionFr = weatherDescriptionFr; }
    public long getWeatherShowUntilMillis() { return weatherShowUntilMillis; }
    public void setWeatherShowUntilMillis(long weatherShowUntilMillis) { this.weatherShowUntilMillis = weatherShowUntilMillis; }

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

    public Map<String, RaidEffects> getRaidEffects() { return raidEffects; }
    public void setRaidEffects(Map<String, RaidEffects> m) { this.raidEffects = m; }
}
