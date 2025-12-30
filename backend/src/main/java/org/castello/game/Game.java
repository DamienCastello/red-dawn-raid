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
    private Set<String> readyForPhase3 = new HashSet<>(); // joueurs ayant cliqué “j’ai fini”

    // --- PHASE3 : file de combats + combat courant ---
    private List<RoundFight> combatsQueue = new ArrayList<>();
    private Integer currentCombatIndex;           // null si aucun combat
    private RoundFight currentCombat;            // miroir pour le client

    // --- METEO ---
    private Integer weatherRoll;
    private WeatherStatus weatherStatus;
    private WeatherStatus secondaryWeatherStatus;
    private WeatherStatus thirdWeatherStatus;
    private String weatherStatusNameFr;
    private String weatherDescriptionFr;
    private String secondaryWeatherStatusNameFr;
    private String secondaryWeatherDescriptionFr;
    private String thirdWeatherStatusNameFr;
    private String thirdWeatherDescriptionFr;

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

    // Provocation chasseur
    private Map<String, String> provokedTargetByEnemy = new HashMap<>();

    // Chasseurs ayant préparé un Incendiaire pour ce raid : hunterId -> lieu ("forest", "manor", etc.)
    private Map<String, String> incendiaireLocationByHunter = new HashMap<>();

    // Ennemi ciblé par Embuscade -> liste des chasseurs embusqués
    private Map<String, List<String>> ambushHuntersByEnemy = new HashMap<>();

    // Infras qui seront détruites à la fin du raid courant (Incendiaire)
    private java.util.EnumSet<Infra> infrasToDestroyEndOfRaid = java.util.EnumSet.noneOf(Infra.class);

    // Boutique : bonus unique (Marchand itinérant)
    private String shopBonusKind; // "POTION", "ELIXIR", "EQUIP_WEAPON", "EQUIP_ARMOR";

    // Blocage des actions chasseur
    private boolean hunterActionsBlockedThisRaid;

    // lieux des clones vampire
    private java.util.List<String> clonesLocations = new java.util.ArrayList<>();
    private boolean clonesFaceUp;

    // lieux de l'image miroir
    private String mirrorOwnerId;
    private java.util.List<String> mirrorAltLocations;
    private String mirrorChosenLocation;

    // annule récolte chasseurs
    private boolean fogBlocksHunterHarvestThisRaid;

    // morsure garantie si attaque fail
    private boolean hungerAllowsBiteThisRaid;

    // Chasseurs marqués par Marque ténébreuse (effet permanent)
    private java.util.Set<String> darkMarkedHunters;

    // Chasseurs déjà pris 1 corruption par la marque sur CE raid
    private java.util.Set<String> darkMarkCorruptedThisRaid;

    // Avidité augmente prix boutique
    private boolean shopPricesIncreasedThisRaid;

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
        private String attackerId;
        private String targetId;
        private String location;
        private Integer roll;
        private Integer armorRoll;
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

        public Integer getArmorRoll() { return armorRoll; }
        public void setArmorRoll(Integer armorRoll) { this.armorRoll = armorRoll; }

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
    // --- Decks ordonnés (top/bottom) ---

    // Potions (deck commun)
    private List<String> potionDeck = new ArrayList<>();
    private List<String> potionDiscard = new ArrayList<>();

    // Potions rares (deck spécial Laboratoire / loot rare)
    private List<String> elixirDeck = new ArrayList<>();
    private List<String> elixirDiscard = new ArrayList<>();


    // Actions chasseurs
    private List<String> hunterActionsDeck = new ArrayList<>();
    private List<String> hunterActionsDiscard = new ArrayList<>();

    // Actions vampire
    private List<String> vampActionsDeck = new ArrayList<>();
    private List<String> vampActionsDiscard = new ArrayList<>();


    // --- Phase4: "j'ai fini" (finishTrade) ---
    private final Set<String> readyForNextRaid = new HashSet<>();

    private int prephaseTimerVersion;
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

    // Constructions
    // Infrastructures déjà construites dans la partie
    private java.util.EnumSet<Infra> builtInfras = java.util.EnumSet.noneOf(Infra.class);

    // Construction en cours sur ce raid (null s'il n'y en a pas)
    public static class PendingConstruction {
        public Infra infra;
        public String builderId;
    }

    private PendingConstruction pendingConstruction;
    private boolean vampireTookDamageThisRaid;

    public static class LocationEffectInstance {
        public String ownerId;                 // joueur qui a joué le lieu
        public Infra infra;                    // LIBRARY, etc.
        public LocationEffectChoice choice;    // null tant qu'il n'a pas choisi
    }

    private java.util.List<LocationEffectInstance> locationEffectsQueue;
    private Integer currentLocationEffectIndex;

    private boolean locationEffectPending;          // au moins un effet de lieu en cours sur ce raid
    private LocationEffectChoice locationEffectChoice; // choix actuel (pour l'effet en cours)

    public static class LibraryOmenState {
        public String ownerId;              // joueur qui résout l'effet
        public String targetSide;           // "HUNTERS" ou "VAMPIRE"
        public java.util.List<String> cards = new java.util.ArrayList<>();
    }

    private LibraryOmenState libraryOmenState;

    public static class Monster {
        public String id;
        public MonsterType type;
        public String location;    // "forest","quarry","manor","lab",...
        public int hp;
        public String attackDice;   // "D6","D8","D12"
        public String defenseDice;  // peut être null ou "NONE" pour Revenant
    }

    public enum MonsterType {
        REVENANT, GARGOYLE, ABERRATION
    }

    private boolean laboratoryToDestroy;
    private boolean ballroomDeathDance;
    private boolean ballroomSneakAttack;
    private boolean ballroomBloodWaltz;        // effet activé ce raid
    private Integer ballroomBloodWaltzBestRoll;            // meilleur dé retenu
    private java.util.List<Integer> ballroomBloodWaltzRolls = new java.util.ArrayList<>();

    // État persistant du lieu : null ou FALSE = pure, TRUE = corrompu.
    // non-null que quand l'infra est réellement construite.
    private Boolean altarCorrupted;

    // Flags "par raid" (reset entre 2 raids)
    private boolean altarBiteOccurredThisRaid;           // au moins une morsure sur ce lieu
    private boolean altarVampTookDamageThisRaid;         // le vampire a pris des dégâts sur ce lieu ce raid

    private Map<String, Integer> bleedDamageByTarget = new HashMap<>();

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
    public void setReadyForPhase3(Set<String> s) { this.readyForPhase3 = s; }

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
    public WeatherStatus getSecondaryWeatherStatus() { return secondaryWeatherStatus; }
    public void setSecondaryWeatherStatus(WeatherStatus secondaryWeatherStatus) { this.secondaryWeatherStatus = secondaryWeatherStatus; }
    public WeatherStatus getThirdWeatherStatus() { return thirdWeatherStatus; }
    public void setThirdWeatherStatus(WeatherStatus thirdWeatherStatus) { this.thirdWeatherStatus = thirdWeatherStatus; }
    public String getWeatherStatusNameFr() { return weatherStatusNameFr; }
    public void setWeatherStatusNameFr(String weatherStatusNameFr) { this.weatherStatusNameFr = weatherStatusNameFr; }
    public String getWeatherDescriptionFr() { return weatherDescriptionFr; }
    public void setWeatherDescriptionFr(String weatherDescriptionFr) { this.weatherDescriptionFr = weatherDescriptionFr; }
    public String getSecondaryWeatherStatusNameFr() { return secondaryWeatherStatusNameFr; }
    public void setSecondaryWeatherStatusNameFr(String v) { this.secondaryWeatherStatusNameFr = v; }
    public String getSecondaryWeatherDescriptionFr() { return secondaryWeatherDescriptionFr; }
    public void setSecondaryWeatherDescriptionFr(String v) { this.secondaryWeatherDescriptionFr = v; }
    public String getThirdWeatherStatusNameFr() { return thirdWeatherStatusNameFr; }
    public void setThirdWeatherStatusNameFr(String v) { this.thirdWeatherStatusNameFr = v; }
    public String getThirdWeatherDescriptionFr() { return thirdWeatherDescriptionFr; }
    public void setThirdWeatherDescriptionFr(String v) { this.thirdWeatherDescriptionFr = v; }

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

    //ACTIONS
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

    public Map<String, String> getProvokedTargetByEnemy() { return provokedTargetByEnemy; }
    public void setProvokedTargetByEnemy(Map<String, String> m) {
        this.provokedTargetByEnemy = (m != null ? m : new HashMap<>());
    }

    public Map<String, String> getIncendiaireLocationByHunter() {
        if (incendiaireLocationByHunter == null) {
            incendiaireLocationByHunter = new HashMap<>();
        }
        return incendiaireLocationByHunter;
    }
    public void setIncendiaireLocationByHunter(Map<String, String> m) {
        this.incendiaireLocationByHunter = (m != null ? m : new HashMap<>());
    }

    public Map<String, List<String>> getAmbushHuntersByEnemy() {
        if (ambushHuntersByEnemy == null) {
            ambushHuntersByEnemy = new HashMap<>();
        }
        return ambushHuntersByEnemy;
    }
    public void setAmbushHuntersByEnemy(Map<String, List<String>> m) {
        this.ambushHuntersByEnemy = (m != null ? m : new HashMap<>());
    }

    public String getShopBonusKind() { return shopBonusKind; }
    public void setShopBonusKind(String shopBonusKind) { this.shopBonusKind = shopBonusKind; }

    public java.util.EnumSet<Infra> getInfrasToDestroyEndOfRaid() { return infrasToDestroyEndOfRaid; }
    public void setInfrasToDestroyEndOfRaid(java.util.EnumSet<Infra> v) { this.infrasToDestroyEndOfRaid = v; }

    public boolean isHunterActionsBlockedThisRaid() { return hunterActionsBlockedThisRaid; }
    public void setHunterActionsBlockedThisRaid(boolean hunterActionsBlockedThisRaid) { this.hunterActionsBlockedThisRaid = hunterActionsBlockedThisRaid; }

    public Action getCurrentAction() { return currentAction; }
    public void setCurrentAction(Action currentAction) { this.currentAction = currentAction; }

    public java.util.List<String> getClonesLocations() {
        if (clonesLocations == null) {
            clonesLocations = new java.util.ArrayList<>();
        }
        return clonesLocations;
    }
    public void setClonesLocations(java.util.List<String> locs) {
        this.clonesLocations = (locs != null ? locs : new java.util.ArrayList<>());
    }

    public boolean isClonesFaceUp() { return clonesFaceUp; }
    public void setClonesFaceUp(boolean v) { this.clonesFaceUp = v; }

    public String getMirrorOwnerId() { return mirrorOwnerId; }
    public void setMirrorOwnerId(String mirrorOwnerId) { this.mirrorOwnerId = mirrorOwnerId; }

    public List<String> getMirrorAltLocations() { return mirrorAltLocations; }
    public void setMirrorAltLocations(List<String> mirrorAltLocations) { this.mirrorAltLocations = mirrorAltLocations; }

    public String getMirrorChosenLocation() { return mirrorChosenLocation; }
    public void setMirrorChosenLocation(String mirrorChosenLocation) { this.mirrorChosenLocation = mirrorChosenLocation; }

    public boolean isFogBlocksHunterHarvestThisRaid() { return fogBlocksHunterHarvestThisRaid; }
    public void setFogBlocksHunterHarvestThisRaid(boolean fogBlocksHunterHarvestThisRaid) { this.fogBlocksHunterHarvestThisRaid = fogBlocksHunterHarvestThisRaid; }

    public boolean isHungerAllowsBiteThisRaid() { return hungerAllowsBiteThisRaid; }
    public void setHungerAllowsBiteThisRaid(boolean hungerAllowsBiteThisRaid) { this.hungerAllowsBiteThisRaid = hungerAllowsBiteThisRaid; }

    public java.util.Set<String> getDarkMarkedHunters() { return darkMarkedHunters; }
    public void setDarkMarkedHunters(java.util.Set<String> darkMarkedHunters) { this.darkMarkedHunters = darkMarkedHunters; }

    public java.util.Set<String> getDarkMarkCorruptedThisRaid() { return darkMarkCorruptedThisRaid; }
    public void setDarkMarkCorruptedThisRaid(java.util.Set<String> darkMarkCorruptedThisRaid) { this.darkMarkCorruptedThisRaid = darkMarkCorruptedThisRaid; }

    public boolean isShopPricesIncreasedThisRaid() { return shopPricesIncreasedThisRaid; }
    public void setShopPricesIncreasedThisRaid(boolean shopPricesIncreasedThisRaid) { this.shopPricesIncreasedThisRaid = shopPricesIncreasedThisRaid; }

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
    // Potions
    public List<String> getPotionDeck() { return potionDeck; }
    public void setPotionDeck(List<String> deck) { this.potionDeck = deck; }

    public List<String> getPotionDiscard() { return potionDiscard; }
    public void setPotionDiscard(List<String> discard) { this.potionDiscard = discard; }

    // Potions rares
    public List<String> getElixirDeck() { return elixirDeck; }
    public void setElixirDeck(List<String> deck) { this.elixirDeck = deck; }

    public List<String> getElixirDiscard() { return elixirDiscard; }
    public void setElixirDiscard(List<String> discard) { this.elixirDiscard = discard; }

    // Actions chasseurs
    public List<String> getHunterActionsDeck() { return hunterActionsDeck; }
    public void setHunterActionsDeck(List<String> deck) { this.hunterActionsDeck = deck; }

    public List<String> getHunterActionsDiscard() { return hunterActionsDiscard; }
    public void setHunterActionsDiscard(List<String> discard) { this.hunterActionsDiscard = discard; }

    // Actions vampire
    public List<String> getVampActionsDeck() { return vampActionsDeck; }
    public void setVampActionsDeck(List<String> deck) { this.vampActionsDeck = deck; }

    public List<String> getVampActionsDiscard() { return vampActionsDiscard; }
    public void setVampActionsDiscard(List<String> discard) { this.vampActionsDiscard = discard; }


    public Set<String> getReadyForNextRaid() { return readyForNextRaid; }

    public int getPrephaseTimerVersion() { return prephaseTimerVersion; }
    public void setPrephaseTimerVersion(int v) { this.prephaseTimerVersion = v; }

    public Long getPhase4DeadlineMillis() { return phase4DeadlineMillis; }
    public void setPhase4DeadlineMillis(Long v) { this.phase4DeadlineMillis = v; }

    public List<Trade> getTrades(){ return trades; }
    public void setTrades(List<Trade> t){ this.trades = t; }

    public java.util.EnumSet<Infra> getBuiltInfras() { return builtInfras; }
    public void setBuiltInfras(java.util.EnumSet<Infra> builtInfras) { this.builtInfras = builtInfras; }

    public PendingConstruction getPendingConstruction() { return pendingConstruction; }
    public void setPendingConstruction(PendingConstruction pendingConstruction) { this.pendingConstruction = pendingConstruction; }

    public boolean isVampireTookDamageThisRaid() { return vampireTookDamageThisRaid; }
    public void setVampireTookDamageThisRaid(boolean vampireTookDamageThisRaid) { this.vampireTookDamageThisRaid = vampireTookDamageThisRaid; }

    // ---- Effets de lieu ----
    public java.util.List<LocationEffectInstance> getLocationEffectsQueue() { return locationEffectsQueue; }
    public void setLocationEffectsQueue(java.util.List<LocationEffectInstance> locationEffectsQueue) { this.locationEffectsQueue = locationEffectsQueue; }

    public Integer getCurrentLocationEffectIndex() { return currentLocationEffectIndex; }
    public void setCurrentLocationEffectIndex(Integer currentLocationEffectIndex) { this.currentLocationEffectIndex = currentLocationEffectIndex; }

    public boolean getLocationEffectPending() { return locationEffectPending; }
    public void setLocationEffectPending(boolean locationEffectPending) { this.locationEffectPending = locationEffectPending; }

    public LocationEffectChoice getLocationEffectChoice() { return locationEffectChoice; }
    public void setLocationEffectChoice(LocationEffectChoice locationEffectChoice) { this.locationEffectChoice = locationEffectChoice; }

    // LIBRARY
    public LibraryOmenState getLibraryOmenState() { return libraryOmenState; }
    public void setLibraryOmenState(LibraryOmenState s) { this.libraryOmenState = s; }

    // LABORATORY
    // --- Monstres invoqués par le Laboratoire occulte ---
    private List<Monster> monsters = new ArrayList<>();

    public List<Monster> getMonsters() {
        // on garantit jamais null
        if (monsters == null) {
            monsters = new ArrayList<>();
        }
        return monsters;
    }

    public void setMonsters(List<Monster> monsters) {
        this.monsters = (monsters != null ? monsters : new ArrayList<>());
    }

    public boolean isLaboratoryToDestroy() { return laboratoryToDestroy; }
    public void setLaboratoryToDestroy(boolean v) { this.laboratoryToDestroy = v; }

    // BALLROOM
    public boolean isBallroomDeathDance() { return ballroomDeathDance; }
    public void setBallroomDeathDance(boolean v) { this.ballroomDeathDance = v; }

    public boolean isBallroomSneakAttack() {
        return ballroomSneakAttack;
    }
    public void setBallroomSneakAttack(boolean v) {
        this.ballroomSneakAttack = v;
    }

    public boolean isBallroomBloodWaltz() { return ballroomBloodWaltz; }
    public void setBallroomBloodWaltz(boolean v) { this.ballroomBloodWaltz = v; }

    public Integer getBallroomBloodWaltzBestRoll() { return ballroomBloodWaltzBestRoll; }
    public void setBallroomBloodWaltzBestRoll(Integer v) { this.ballroomBloodWaltzBestRoll = v; }

    public java.util.List<Integer> getBallroomBloodWaltzRolls() {
        if (ballroomBloodWaltzRolls == null) {
            ballroomBloodWaltzRolls = new java.util.ArrayList<>();
        }
        return ballroomBloodWaltzRolls;
    }
    public void setBallroomBloodWaltzRolls(java.util.List<Integer> rolls) {
        this.ballroomBloodWaltzRolls = (rolls != null ? rolls : new java.util.ArrayList<>());
    }

    // ALTAR
    public Boolean getAltarCorrupted() { return altarCorrupted; }
    public void setAltarCorrupted(Boolean altarCorrupted) { this.altarCorrupted = altarCorrupted; }

    public boolean isAltarBiteOccurredThisRaid() { return altarBiteOccurredThisRaid; }
    public void setAltarBiteOccurredThisRaid(boolean v) { this.altarBiteOccurredThisRaid = v; }

    public boolean isAltarVampTookDamageThisRaid() { return altarVampTookDamageThisRaid; }
    public void setAltarVampTookDamageThisRaid(boolean v) { this.altarVampTookDamageThisRaid = v; }

    public Map<String, Integer> getBleedDamageByTarget() { return bleedDamageByTarget; }
    public void setBleedDamageByTarget(Map<String,Integer> m) { this.bleedDamageByTarget = m; }
}
