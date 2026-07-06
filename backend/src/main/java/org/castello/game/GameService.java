package org.castello.game;

import org.castello.game.domain.ActionCardService;
import org.castello.game.domain.BankService;
import org.castello.game.domain.CombatService;
import org.castello.game.domain.ConstructionService;
import org.castello.game.domain.CorruptionService;
import org.castello.game.domain.TradeService;
import org.castello.game.domain.DeckService;
import org.castello.game.domain.EquipmentService;
import org.castello.game.domain.GameLifecycleService;
import org.castello.game.domain.HarvestService;
import org.castello.game.domain.HunterActionService;
import org.castello.game.domain.LocationEffectService;
import org.castello.game.domain.PhaseFlowService;
import org.castello.game.domain.ShopService;
import org.castello.game.domain.VampireActionService;
import org.castello.game.domain.WeatherService;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.castello.persistence.GameRepository;
import org.castello.persistence.PlayerRepository;
import org.castello.player.PlayerService;

import java.util.*;

import static org.castello.game.domain.EquipmentService.*;

@Service
public class GameService {

    private final TaskScheduler raidScheduler;
    private final TransactionTemplate tx;

    // ----- PERSISTENCE -----
    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final WeatherService weather;
    private final HarvestService harvest;
    private final BankService bank;
    private final TradeService trades;
    private final EquipmentService equipment;
    private final ShopService shop;
    private final ConstructionService construction;
    private final CorruptionService corruption;
    private final LocationEffectService locationEffects;
    private final ActionCardService actionCards;
    private final HunterActionService hunterActions;
    private final VampireActionService vampireActions;
    private final CombatService combat;
    private final GameLifecycleService lifecycle;
    private final PhaseFlowService phaseFlow;
    private final GameRepository gameRepo;
    private final PlayerRepository playerRepo;
    private final org.castello.live.LiveEvents live;
    private final PlayerService playerService;

    public GameService(GameStore store, Dice dice, DeckService decks, WeatherService weather,
            HarvestService harvest, BankService bank, TradeService trades, EquipmentService equipment,
            ShopService shop, ConstructionService construction, CorruptionService corruption,
            LocationEffectService locationEffects, ActionCardService actionCards,
            HunterActionService hunterActions, VampireActionService vampireActions,
            CombatService combat, GameLifecycleService lifecycle, PhaseFlowService phaseFlow,
            GameRepository gameRepo, PlayerRepository playerRepo,
            @Qualifier("raidTaskScheduler") TaskScheduler raidScheduler, PlatformTransactionManager tm,
            org.castello.live.LiveEvents live, PlayerService playerService) {
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.weather = weather;
        this.harvest = harvest;
        this.bank = bank;
        this.trades = trades;
        this.equipment = equipment;
        this.shop = shop;
        this.construction = construction;
        this.corruption = corruption;
        this.locationEffects = locationEffects;
        this.actionCards = actionCards;
        this.hunterActions = hunterActions;
        this.vampireActions = vampireActions;
        this.combat = combat;
        this.lifecycle = lifecycle;
        this.phaseFlow = phaseFlow;
        this.gameRepo = gameRepo;
        this.playerRepo = playerRepo;
        this.raidScheduler = raidScheduler;
        this.tx = new TransactionTemplate(tm);
        this.live = live;
        this.playerService = playerService;
    }

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    // Plomberie déléguée à GameStore (voir game/support/GameStore.java).

    private void afterCommit(Runnable r) {
        store.afterCommit(r);
    }

    /** Sauvegarde en préservant la version (évite les inserts involontaires). */
    private void save(@NonNull Game g) {
        store.save(g);
    }

    // ---------- utilitaires ----------

    // Helpers délégués au modèle de domaine (Game / Player).
    private boolean isAlive(Player p) {
        return p.isAlive();
    }

    private void addHistory(@NonNull Game g, @NonNull String text) {
        g.addHistory(text);
    }

    private boolean hasPlayed(@NonNull Game g, String playerId) {
        return g.hasPlayed(playerId);
    }

    @NonNull
    public List<Player> getHunters(@NonNull Game g) {
        return g.hunters();
    }

    private String nameOf(Game g, String playerId) {
        return g.nameOf(playerId);
    }

    private void rebuildEquipmentMods(Game g) {
        equipment.rebuildEquipmentMods(g);
    }

    private void addRaidMod(Game g, String playerId, String stat, int amount, String source) {
        g.addRaidMod(playerId, stat, amount, source);
    }

    // --- Cycle de vie : délégués vers GameLifecycleService ---
    public Game create() { return lifecycle.create(); }

    public Collection<Game> list() { return lifecycle.list(); }

    public Game join(String gameId, String userId, String username) { return lifecycle.join(gameId, userId, username); }

    public Game addOrUpdatePlayer(String gameId, String playerId, String username) { return lifecycle.addOrUpdatePlayer(gameId, playerId, username); }

    public void requestStart(String id) { lifecycle.requestStart(id); }

    public void presence(String gameId, String userId) { lifecycle.presence(gameId, userId); }

    public void bootReady(String gameId, String userId) { lifecycle.bootReady(gameId, userId); }

    public Game startReal(Game g) { return lifecycle.startReal(g); }

    public void surrender(String gameId, String userId) { lifecycle.surrender(gameId, userId); }

    public void leave(String gameId, String userId) { lifecycle.leave(gameId, userId); }

    private void initDecks(Game g) {
        decks.initDecks(g);
    }

    public Game advancePhase(String gameId, String userId, Phase to) { return phaseFlow.advancePhase(gameId, userId, to); }

    // ---------- Sélection lieu ----------
    public Game selectLocation(String gameId, String playerId, String card) { return phaseFlow.selectLocation(gameId, playerId, card); }

    // Regroupe les joueurs par lieu posé au centre (délégué au modèle).

    private String locationOf(Game g, String playerId) {
        return g.locationOf(playerId);
    }

    @NonNull

    // Mini label FR pour l’affichage des lieux

    // Tout le monde prêt pour PHASE3 ?
    /**
     * Tous les joueurs "vivants" sont-ils prêts pour passer en PHASE3 ?
     * On se base UNIQUEMENT sur readyForPhase3, pour éviter les divergences
     * avec la logique front.
     */

    public Game skipAction(String gameId, String playerId) { return phaseFlow.skipAction(gameId, playerId); }

    public Game finishTrade(String gameId, String userId) { return phaseFlow.finishTrade(gameId, userId); }

    // Fight
    /**
     * Construit la file de duels (PHASE3) :
     * - pour chaque lieu où vampire + ≥1 chasseur: push Hunter->Vamp puis
     * Vamp->Hunter.
     * - ajoute ensuite les duels "instable -> cible" enregistrés en PREPHASE3.
     * Initialise currentCombat et currentCombatIndex si la file n’est pas vide.
     */
    private boolean isEntityAlive(Game g, String entityId) {
        return g.isEntityAlive(entityId);
    }

    // Météo
    // alimente raidMods
    private void rebuildWeatherMods(Game g) {
        weather.rebuildWeatherMods(g);
    }

    public Game rollWeather(String gameId, String userId) {
        return weather.rollWeather(gameId, userId);
    }

    private void applyWindRepairs(@NonNull Game g) {
        weather.applyWindRepairs(g);
    }

    private int rollD100Tens() {
        return harvest.rollD100Tens();
    }

    private String resLabelFr(String res) {
        return harvest.resLabelFr(res);
    }

    private void applyHarvests(@NonNull Game g) {
        harvest.applyHarvests(g);
    }

    private void grant(Player p, String res, int qty) {
        if (p == null)
            return;
        p.grant(res, qty);
    }

    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        // notifie le front (déjà géré dans onLiveEvent: MESSAGE)
        live.message(g, msg);
    }

    @Transactional
    // --- Cartes action : délégués vers ActionCardService ---
    public Game useAction(String gameId, String playerId, Action type) { return actionCards.useAction(gameId, playerId, type); }

    private boolean hasIncendiaireTargetsOnLocation(Game g, String loc) { return actionCards.hasIncendiaireTargetsOnLocation(g, loc); }

    private boolean hasUsableHolyWaterForPlayer(@NonNull Game g, @NonNull Player p) { return actionCards.hasUsableHolyWaterForPlayer(g, p); }

    private boolean hasUsableSecretPassageOption(Game g) { return actionCards.hasUsableSecretPassageOption(g); }

    private boolean hasTrapTargetsOnLocation(Game g, String loc) { return actionCards.hasTrapTargetsOnLocation(g, loc); }

    private boolean hasBlockingActionInProgress(Game g) { return actionCards.hasBlockingActionInProgress(g); }

    // --- Résolutions de cartes : délégués vers HunterActionService / VampireActionService ---
    public Game chooseNetTarget(String gameId, String hunterId, String targetId) { return hunterActions.chooseNetTarget(gameId, hunterId, targetId); }

    public Game resolveNet(String gameId, String hunterId, String targetId) { return hunterActions.resolveNet(gameId, hunterId, targetId); }

    public Game resolvePit(String gameId, String victimId) { return hunterActions.resolvePit(gameId, victimId); }

    public Game resolveIncendiaire(String gameId, String hunterId, String infraCode) { return hunterActions.resolveIncendiaire(gameId, hunterId, infraCode); }

    public Game resolveProvocation(String gameId, String hunterId, String enemyId) { return hunterActions.resolveProvocation(gameId, hunterId, enemyId); }

    public Game resolveAmbush(String gameId, String playerId, String targetId) { return hunterActions.resolveAmbush(gameId, playerId, targetId); }

    public Game resolveBlessedStake(String gameId, String userId) { return hunterActions.resolveBlessedStake(gameId, userId); }

    public Game rollCrate(String gameId, String userId) { return hunterActions.rollCrate(gameId, userId); }

    public Game resolveCrateAction(String gameId, String userId) { return hunterActions.resolveCrateAction(gameId, userId); }

    public Game resolveHolyWater(String gameId, String playerId, String mode) { return hunterActions.resolveHolyWater(gameId, playerId, mode); }

    public void resolveCataclysme(String gameId, String userId, WeatherStatus first, WeatherStatus second) { vampireActions.resolveCataclysme(gameId, userId, first, second); }

    public Game rollShadowClones(String gameId, String playerId) { return vampireActions.rollShadowClones(gameId, playerId); }

    public Game resolveShadowClones(String gameId, String playerId, java.util.List<String> locations, java.util.List<Boolean> biteEnabled) { return vampireActions.resolveShadowClones(gameId, playerId, locations, biteEnabled); }

    public Game resolveVoileDeBrume(String gameId, String userId, String location) { return vampireActions.resolveVoileDeBrume(gameId, userId, location); }

    public Game resolveImageMiroirSetup(String gameId, String playerId, String loc) { return vampireActions.resolveImageMiroirSetup(gameId, playerId, loc); }

    public Game resolveImageMiroirChoice(String gameId, String playerId, String loc) { return vampireActions.resolveImageMiroirChoice(gameId, playerId, loc); }

    public Game resolveDarkMark(String gameId, String playerId, String targetId) { return vampireActions.resolveDarkMark(gameId, playerId, targetId); }

    public Game resolveOccultWeakening(String gameId, String playerId, String targetId) { return vampireActions.resolveOccultWeakening(gameId, playerId, targetId); }

    public Game resolveSecretPassage(String gameId, String playerId, String destination) { return vampireActions.resolveSecretPassage(gameId, playerId, destination); }

    private void rebuildCorruptionMods(@NonNull Game g) {
        corruption.rebuildCorruptionMods(g);
    }

    // --- Corruption : délégués vers CorruptionService ---
    private boolean hasSuccumbedToCorruption(Game g, String playerId) { return corruption.hasSuccumbedToCorruption(g, playerId); }

    private boolean isDarkMarked(Game g, String playerId) { return corruption.isDarkMarked(g, playerId); }

    public Game assignUnstableTarget(String gameId, String userId, String unstableId, String targetId) { return corruption.assignUnstableTarget(gameId, userId, unstableId, targetId); }

    public Game assignUnstableHarvest(String gameId, String userId, String unstableId, String loc) { return corruption.assignUnstableHarvest(gameId, userId, unstableId, loc); }

    public Game assignUnstableNothing(String gameId, String userId, String unstableId) { return corruption.assignUnstableNothing(gameId, userId, unstableId); }

    public Game rollCorruption(String gameId, String userId) { return corruption.rollCorruption(gameId, userId); }

    private void discardAllActionsOf(Game g, Player p) {
        decks.discardAllActionsOf(g, p);
    }

    private void discardAllPotionsOf(Game g, Player p) {
        decks.discardAllPotionsOf(g, p);
    }

    // Maintenance
    @Nullable
    private Player findPlayer(Game g, String id) {
        return g.findPlayer(id);
    }

    // Pioches/défausses déléguées à DeckService.

    private void putOnTop(List<String> deck, String cardId) {
        decks.putOnTop(deck, cardId);
    }

    private void putOnBottom(List<String> deck, String cardId) {
        decks.putOnBottom(deck, cardId);
    }

    private String drawHunterAction(Game g) {
        return decks.drawHunterAction(g);
    }

    private String drawVampAction(Game g) {
        return decks.drawVampAction(g);
    }

    private String drawPotion(Game g) {
        return decks.drawPotion(g);
    }

    private String drawElixir(Game g) {
        return decks.drawElixir(g);
    }

    private void discardHunterAction(Game g, String cardId) {
        decks.discardHunterAction(g, cardId);
    }

    private void discardVampAction(Game g, String cardId) {
        decks.discardVampAction(g, cardId);
    }

    private void discardPotion(Game g, String cardId) {
        decks.discardPotion(g, cardId);
    }

    private void discardElixir(Game g, String cardId) {
        decks.discardElixir(g, cardId);
    }

    // --- Boutique : délégués vers ShopService ---
    public Game rollMerchantItinerant(String gameId, String userId) { return shop.rollMerchantItinerant(gameId, userId); }

    public Game rollAdvancedTransmutation(String gameId, String userId) { return shop.rollAdvancedTransmutation(gameId, userId); }

    public Game buyResource(String gameId, String userId, String resourceType) { return shop.buyResource(gameId, userId, resourceType); }

    public Game startShopBonusPurchase(String gameId, String userId) { return shop.startShopBonusPurchase(gameId, userId); }

    public Game buyShopBonus(String gameId, String userId, String payment) { return shop.buyShopBonus(gameId, userId, payment); }

    public Game cancelShopBonusPurchase(String gameId, String userId) { return shop.cancelShopBonusPurchase(gameId, userId); }

    public Game buyPotion(String gameId, String userId) { return shop.buyPotion(gameId, userId); }

    public Game buyAction(String gameId, String userId) { return shop.buyAction(gameId, userId); }

    public Game buySilver(String gameId, String userId, int qty) { return shop.buySilver(gameId, userId, qty); }

    public Game buyHolyWaterAction(String gameId, String userId) { return shop.buyHolyWaterAction(gameId, userId); }

    public Game buyTrackingAction(String gameId, String userId) { return shop.buyTrackingAction(gameId, userId); }

    public Game buyUpgradeWeapon(String gameId, String userId, int expectedTier, String expectedType) { return shop.buyUpgradeWeapon(gameId, userId, expectedTier, expectedType); }

    public Game buyUpgradeArmor(String gameId, String userId) { return shop.buyUpgradeArmor(gameId, userId); }

    public Game sellResource(String gameId, String userId, String res, int qty) { return shop.sellResource(gameId, userId, res, qty); }

    public Game transmute(String gameId, String userId, String recipe) { return shop.transmute(gameId, userId, recipe); }

    public Game tradeSetMyOffer(String gameId, String userId, String targetId, Map<String, Integer> offer) {
        return trades.tradeSetMyOffer(gameId, userId, targetId, offer);
    }

    public Game tradeAction(String gameId, String userId, String targetId, String action) {
        return trades.tradeAction(gameId, userId, targetId, action);
    }

    private boolean isFinal(String s) {
        return trades.isFinal(s);
    }

    // Construction
    public Game planConstruction(String gameId, String playerId, Infra infra) { return phaseFlow.planConstruction(gameId, playerId, infra); }

    private boolean hasResourcesForInfra(Player p, Infra infra) {
        return construction.hasResourcesForInfra(p, infra);
    }

    private void resolveInfraConstruction(Game g) {
        construction.resolveInfraConstruction(g);
    }

    private void destroyRaidInfrasAtEnd(Game g) {
        construction.destroyRaidInfrasAtEnd(g);
    }

    // --- Effets de lieux : délégués vers LocationEffectService ---

    public Game chooseLocationEffect(String gameId, String playerId, LocationEffectChoice choice) { return locationEffects.chooseLocationEffect(gameId, playerId, choice); }

    public Game resolveLibraryTheft(String gameId, String playerId, String targetId, int slotIndex) { return locationEffects.resolveLibraryTheft(gameId, playerId, targetId, slotIndex); }

    public Game resolveLibraryOmen(String gameId, String playerId, java.util.List<String> placements) { return locationEffects.resolveLibraryOmen(gameId, playerId, placements); }

    public void updateLaboratoryExperimentDraft(String gameId, String userId, Game.MonsterType type, String location) { locationEffects.updateLaboratoryExperimentDraft(gameId, userId, type, location); }

    public Game resolveLaboratoryExperiment(String gameId, String userId, Game.MonsterType type, String location) { return locationEffects.resolveLaboratoryExperiment(gameId, userId, type, location); }

    public Game resolveLaboratoryExplosion(String gameId, String playerId) { return locationEffects.resolveLaboratoryExplosion(gameId, playerId); }

    public Game resolveAltarHeal(String gameId, String playerId, String targetId) { return locationEffects.resolveAltarHeal(gameId, playerId, targetId); }

    public Game resolveAltarCorrupt(String gameId, String playerId, String targetId) { return locationEffects.resolveAltarCorrupt(gameId, playerId, targetId); }

    public Game resolveForge(String gameId, String playerId, String equipCode) { return locationEffects.resolveForge(gameId, playerId, equipCode); }

    private String entityName(Game g, String id) {
        return g.entityName(id);
    }

    private Game.Monster findMonster(Game g, String monsterId) {
        return g.findMonster(monsterId);
    }

    @Transactional
    // --- Combat : délégués vers CombatService ---
    public Game rollDice(String gameId, String userId) { return combat.rollDice(gameId, userId); }

    public Game combatContinue(String gameId, String userId) { return combat.combatContinue(gameId, userId); }

    public Game usePotion(String gameId, String playerId, Potion type) { return combat.usePotion(gameId, playerId, type); }

    public void contributeBankStone(String gameId, String playerId) {
        bank.contributeBankStone(gameId, playerId);
    }

    private void applyBankBonusOnPhase4Entry(Game g) {
        bank.applyBankBonusOnPhase4Entry(g);
    }
}
