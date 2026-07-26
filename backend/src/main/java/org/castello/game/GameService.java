package org.castello.game;

import org.castello.game.domain.ActionCardService;
import org.castello.game.domain.BankService;
import org.castello.game.domain.CombatService;
import org.castello.game.domain.CorruptionService;
import org.castello.game.domain.GameLifecycleService;
import org.castello.game.domain.HunterActionService;
import org.castello.game.domain.LocationEffectService;
import org.castello.game.domain.PhaseFlowService;
import org.castello.game.domain.ShopService;
import org.castello.game.domain.TradeService;
import org.castello.game.domain.VampireActionService;
import org.castello.game.domain.WeatherService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Façade du moteur de jeu.
 *
 * Point d'entrée unique pour {@code GameController} : chaque endpoint REST
 * appelle une méthode ici, qui délègue au service de domaine responsable.
 * Aucune règle de jeu ne vit dans cette classe — elle ne fait qu'aiguiller
 * vers le bon domaine ({@code game/domain/*}). Voir la lecture de l'état de
 * partie dans {@code SnapshotService} (le contrôleur l'appelle directement).
 */
@Service
public class GameService {

    private final GameLifecycleService lifecycle;
    private final PhaseFlowService phaseFlow;
    private final WeatherService weather;
    private final CombatService combat;
    private final CorruptionService corruption;
    private final ShopService shop;
    private final TradeService trades;
    private final ActionCardService actionCards;
    private final HunterActionService hunterActions;
    private final VampireActionService vampireActions;
    private final LocationEffectService locationEffects;
    private final BankService bank;

    public GameService(GameLifecycleService lifecycle, PhaseFlowService phaseFlow, WeatherService weather,
            CombatService combat, CorruptionService corruption, ShopService shop, TradeService trades,
            ActionCardService actionCards, HunterActionService hunterActions, VampireActionService vampireActions,
            LocationEffectService locationEffects, BankService bank) {
        this.lifecycle = lifecycle;
        this.phaseFlow = phaseFlow;
        this.weather = weather;
        this.combat = combat;
        this.corruption = corruption;
        this.shop = shop;
        this.trades = trades;
        this.actionCards = actionCards;
        this.hunterActions = hunterActions;
        this.vampireActions = vampireActions;
        this.locationEffects = locationEffects;
        this.bank = bank;
    }

    // --- Cycle de vie (lobby, démarrage, abandon) → GameLifecycleService ---
    public Game create() { return lifecycle.create(); }

    public Collection<Game> list() { return lifecycle.list(); }

    public Game join(String gameId, String userId, String username) { return lifecycle.join(gameId, userId, username); }

    public void requestStart(String id) { lifecycle.requestStart(id); }

    public void presence(String gameId, String userId) { lifecycle.presence(gameId, userId); }

    public void bootReady(String gameId, String userId) { lifecycle.bootReady(gameId, userId); }

    public void surrender(String gameId, String userId) { lifecycle.surrender(gameId, userId); }

    public void setRolePreference(String gameId, String requesterId, String targetPlayerId, String role) { lifecycle.setRolePreference(gameId, requesterId, targetPlayerId, role); }

    public void leave(String gameId, String userId) { lifecycle.leave(gameId, userId); }

    // --- Flux de phases (transitions, pose de lieu, maintenance) → PhaseFlowService ---
    public Game advancePhase(String gameId, String userId, Phase to) { return phaseFlow.advancePhase(gameId, userId, to); }

    public Game selectLocation(String gameId, String playerId, String card) { return phaseFlow.selectLocation(gameId, playerId, card); }

    public Game skipAction(String gameId, String playerId) { return phaseFlow.skipAction(gameId, playerId); }

    public Game finishTrade(String gameId, String userId) { return phaseFlow.finishTrade(gameId, userId); }

    public Game planConstruction(String gameId, String playerId, Infra infra) { return phaseFlow.planConstruction(gameId, playerId, infra); }

    // --- Météo → WeatherService ---
    public Game rollWeather(String gameId, String userId) { return weather.rollWeather(gameId, userId); }

    // --- Combat & potions → CombatService ---
    public Game rollDice(String gameId, String userId) { return combat.rollDice(gameId, userId); }

    public Game combatContinue(String gameId, String userId) { return combat.combatContinue(gameId, userId); }

    public Game usePotion(String gameId, String playerId, Potion type) { return combat.usePotion(gameId, playerId, type); }

    // --- Corruption (instables, morsure) → CorruptionService ---
    public Game assignUnstableTarget(String gameId, String userId, String unstableId, String targetId) { return corruption.assignUnstableTarget(gameId, userId, unstableId, targetId); }

    public Game assignUnstableHarvest(String gameId, String userId, String unstableId, String loc) { return corruption.assignUnstableHarvest(gameId, userId, unstableId, loc); }

    public Game assignUnstableNothing(String gameId, String userId, String unstableId) { return corruption.assignUnstableNothing(gameId, userId, unstableId); }

    public Game rollCorruption(String gameId, String userId) { return corruption.rollCorruption(gameId, userId); }

    // --- Boutique → ShopService ---
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

    // --- Échanges → TradeService ---
    public Game tradeSetMyOffer(String gameId, String userId, String targetId, Map<String, Integer> offer) { return trades.tradeSetMyOffer(gameId, userId, targetId, offer); }

    public Game tradeAction(String gameId, String userId, String targetId, String action) { return trades.tradeAction(gameId, userId, targetId, action); }

    // --- Cartes action : jouer une carte → ActionCardService ---
    public Game useAction(String gameId, String playerId, Action type) { return actionCards.useAction(gameId, playerId, type); }

    // --- Résolutions de cartes chasseur → HunterActionService ---
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

    // --- Résolutions de cartes vampire → VampireActionService ---
    public void resolveCataclysme(String gameId, String userId, WeatherStatus first, WeatherStatus second) { vampireActions.resolveCataclysme(gameId, userId, first, second); }

    public Game rollShadowClones(String gameId, String playerId) { return vampireActions.rollShadowClones(gameId, playerId); }

    public Game resolveShadowClones(String gameId, String playerId, List<String> locations, List<Boolean> biteEnabled) { return vampireActions.resolveShadowClones(gameId, playerId, locations, biteEnabled); }

    public Game resolveVoileDeBrume(String gameId, String userId, String location) { return vampireActions.resolveVoileDeBrume(gameId, userId, location); }

    public Game resolveImageMiroirSetup(String gameId, String playerId, String loc) { return vampireActions.resolveImageMiroirSetup(gameId, playerId, loc); }

    public Game resolveImageMiroirChoice(String gameId, String playerId, String loc) { return vampireActions.resolveImageMiroirChoice(gameId, playerId, loc); }

    public Game resolveDarkMark(String gameId, String playerId, String targetId) { return vampireActions.resolveDarkMark(gameId, playerId, targetId); }

    public Game resolveOccultWeakening(String gameId, String playerId, String targetId) { return vampireActions.resolveOccultWeakening(gameId, playerId, targetId); }

    public Game resolveSecretPassage(String gameId, String playerId, String destination) { return vampireActions.resolveSecretPassage(gameId, playerId, destination); }

    public Game resolvePortalInvocation(String gameId, String userId, String location) { return vampireActions.resolvePortalInvocation(gameId, userId, location); }

    // --- Effets de lieux (bâtiments du Manoir) → LocationEffectService ---
    public Game chooseLocationEffect(String gameId, String playerId, LocationEffectChoice choice) { return locationEffects.chooseLocationEffect(gameId, playerId, choice); }

    public Game resolveLibraryTheft(String gameId, String playerId, String targetId, int slotIndex) { return locationEffects.resolveLibraryTheft(gameId, playerId, targetId, slotIndex); }

    public Game resolveLibraryOmen(String gameId, String playerId, List<String> placements) { return locationEffects.resolveLibraryOmen(gameId, playerId, placements); }

    public void updateLaboratoryExperimentDraft(String gameId, String userId, Game.MonsterType type, String location) { locationEffects.updateLaboratoryExperimentDraft(gameId, userId, type, location); }

    public Game resolveLaboratoryExperiment(String gameId, String userId, Game.MonsterType type, String location) { return locationEffects.resolveLaboratoryExperiment(gameId, userId, type, location); }

    public Game resolveLaboratoryExplosion(String gameId, String playerId) { return locationEffects.resolveLaboratoryExplosion(gameId, playerId); }

    public Game resolveAltarHeal(String gameId, String playerId, String targetId) { return locationEffects.resolveAltarHeal(gameId, playerId, targetId); }

    public Game resolveAltarCorrupt(String gameId, String playerId, String targetId) { return locationEffects.resolveAltarCorrupt(gameId, playerId, targetId); }

    public Game resolveForge(String gameId, String playerId, String equipCode) { return locationEffects.resolveForge(gameId, playerId, equipCode); }

    // --- Banque → BankService ---
    public void contributeBankStone(String gameId, String playerId) { bank.contributeBankStone(gameId, playerId); }
}
