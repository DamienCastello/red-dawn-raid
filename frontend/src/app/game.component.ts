import { Component, inject, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, GameSnapshot, RawStatMod, Phase, Pile } from './api.service';
import { LiveService, GameEvent } from './live.service';
import { NotifyService } from './services/notif.service';
import { ChangeDetectorRef } from '@angular/core';

import { PhaseBubbleComponent } from './phase-buble.component';
import { ToastComponent } from './toast.component';
import { DeathModalComponent } from './components/limit-modals/death-modal/death-modal.component';
import { EndGameModalComponent } from './components/limit-modals/end-game-modal/end-game-modal.component';
import { ZoomOverlayComponent } from './components/ui/zoom-overlay/zoom-overlay.component';
import { WeatherModalComponent } from './components/modals/weather-modal/weather-modal.component';
import { BiteModalComponent } from './components/modals/bite-modal/bite-modal.component';
import { CorruptionModalComponent } from './components/modals/corruption-modal/corruption-modal.component';

import { RollModalVm, RollModalActions } from './components/modals/roll-modal/roll-modal.vm';
import { RollModalComponent } from './components/modals/roll-modal/roll-modal.component';
import { SpectateModalVm } from './components/modals/spectate-modal/spectate-modal.vm';
import { SpectateModalComponent } from './components/modals/spectate-modal/spectate-modal.component';
import { ShopModalVm, ShopModalActions, ShopModalHelpers } from './components/modals/shop-modal/shop-modal.vm';
import { ShopModalComponent } from './components/modals/shop-modal/shop-modal.component';
import { ActionModalVm, ActionModalActions, ActionModalHelpers } from './components/modals/action-modal/action-modal.vm';
import { ActionModalComponent } from './components/modals/action-modal/action-modal.component';
import { BuildModalVm, BuildModalActions, BuildModalHelpers, InfraCode } from './components/modals/build-modal/build-modal.vm';
import { BuildModalComponent } from './components/modals/build-modal/build-modal.component';
import { BuildConfirmModalVm, BuildConfirmModalActions } from './components/modals/build-confirm-modal/build-confirm-modal.vm';
import { BuildConfirmModalComponent } from './components/modals/build-confirm-modal/build-confirm-modal.component';

type ForgeRes = 'wood' | 'iron' | 'silver' | 'souls';
type ForgeCost = Partial<Record<ForgeRes, number>>;

const FORGE_COSTS: Record<string, ForgeCost> = {
  // Hunters T1
  H_WEAPON_T1_SWORD: { wood: 1, iron: 2 },
  H_WEAPON_T1_MACE: { wood: 1, iron: 2 },
  H_WEAPON_T1_SPEAR: { wood: 2, iron: 1 },
  H_ARMOR_T1_BRIGANDINE: { iron: 3 },

  // Hunters T2
  H_WEAPON_T2_HALBERD: { wood: 2, iron: 2 },
  H_WEAPON_T2_HAMMER: { wood: 1, iron: 3 },
  H_WEAPON_T2_CROSSBOW: { wood: 3, iron: 1 },
  H_ARMOR_T2_HAUBERT: { iron: 4 },

  // Hunters T3
  H_WEAPON_T3_WRIST_BLADES: { iron: 5, silver: 3 },
  H_WEAPON_T3_FLAIL: { wood: 2, iron: 3, silver: 3 },
  H_WEAPON_T3_PISTOL: { wood: 5, iron: 3, silver: 3 },
  H_ARMOR_T3_PLATE_SILVER: { iron: 5, silver: 4 },

  // Vamp/Servant
  V_WEAPON_T1_SCYTHE: { iron: 2, wood: 2, souls: 40 },
  V_ARMOR_T1_CARAPACE: { iron: 3, souls: 40 },

  V_WEAPON_T2_SWORD: { iron: 2, wood: 4, souls: 60 },
  V_ARMOR_T2_HAUBERT: { iron: 4, souls: 60 },

  V_WEAPON_T3_CLAWS: { iron: 5, wood: 5, souls: 100 },
  V_ARMOR_T3_ECORCE: { iron: 4, wood: 2, souls: 100 },
};


// InfraCode is now imported from build-modal.vm.ts
type RoundFightView = GameSnapshot['combatsQueue'][number];
type SPlayer = GameSnapshot['players'][number];
type SMonster = NonNullable<GameSnapshot['monsters']>[number];
type UiStatMod = RawStatMod & { labelFr?: string; displayOnly?: boolean };
type DefaultFightInfo = { willFight: boolean; loc?: string; opponentName?: string };
type TradeStatus = 'PENDING' | 'CONFIRMED' | 'REFUSED' | 'CANCELLED';
type TradeSide = 'HUNTERS' | 'VAMP_SIDE';
interface STrade {
  id: string; side: TradeSide; aId: string; bId: string;
  offerA: Record<string, number>; offerB: Record<string, number>;
  statusA: TradeStatus; statusB: TradeStatus; updatedAt: number;
}
interface ForgeOption {
  id: string;
  type: 'WEAPON' | 'ARMOR';
  tier: 1 | 2 | 3;
  label: string;
  desc: string;
  cost: any;
}


@Component({
  standalone: true,
  selector: 'app-game',
  imports: [CommonModule, PhaseBubbleComponent, ToastComponent, DeathModalComponent, EndGameModalComponent, ZoomOverlayComponent, WeatherModalComponent,
    BiteModalComponent, CorruptionModalComponent, RollModalComponent,
    SpectateModalComponent, ShopModalComponent, ActionModalComponent,
    BuildModalComponent, BuildConfirmModalComponent
  ],
  templateUrl: './game.component.html',
  styleUrls: ['./game.component.scss']
})
export class GameComponent {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private live = inject(LiveService);

  get rollVm(): RollModalVm {
    const r = this.currentCombat;
    const mySide = this.waitingForMyRoll;
    const myEntityId = r ? (mySide === 'ATTACK' ? r.attackerId : r.defenderId) : null;
    const myPlayer = myEntityId ? this.getPlayer(myEntityId) : null;

    return {
      show: this.showRollModal && !this.isGameEnded && !!r,
      title: r ? this.modalTitle(r) : '',
      backgroundImage: this.setImageBackground('location'),
      waitingForMyRoll: mySide,
      isRolling: this.isRolling,
      canRoll: !!mySide && !this.isRolling,
      rollButtonLabel: this.rollButtonLabel,
      isMyFocusFirstStep: this.isMyFocusFirstStep,
      combat: {
        monsterHp: r ? (this.monsterHpInCombat(r) ?? null) : null,
        currentCombat: r,
      },
      dice: {
        main: r && myPlayer ? {
          value: mySide === 'ATTACK' ? r.attackerRoll : r.defenderRoll,
          src: this.diceAsset(mySide === 'ATTACK' ? myPlayer.attackDice : myPlayer.defenseDice, this.roleColorOf(myPlayer))
        } : {}
      },
      mods: r && myPlayer && mySide ? this.modsForStat(myPlayer, mySide).map(m => ({
        label: this.labelOrChip(m),
        title: this.titleFor(m),
        iconSrc: m.source?.startsWith('WEATHER:') ? this.weatherIconSrcForMod(m) :
          m.source?.startsWith('POTION:') ? (this.isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png') :
            m.source?.startsWith('CORRUPTION') ? '/assets/corruption/corruption-icon.png' :
              this.modIconSrc(m.source),
        kind: m.source?.split(':')[0]
      })) : [],

      helpers: {
        entityHaloIcon: this.entityHaloIcon.bind(this),
        entityRoleIcon: this.entityRoleIcon.bind(this),
        isBallroomWaltzFight: this.isBallroomWaltzFight.bind(this),
        hasFocus: this.hasFocus.bind(this),
        waltzPlaceholderDice: this.waltzPlaceholderDice.bind(this),
        diceAsset: this.diceAsset.bind(this),
        getPlayer: this.getPlayer.bind(this),
        roleColorOf: this.roleColorOf.bind(this),
        getRole: this.getRole.bind(this),
        roleIcon: this.roleIcon.bind(this),
        waltzRolls: this.waltzRolls,
      }
    };
  }

  get spectateVm(): SpectateModalVm {
    const r = this.currentCombat;
    return {
      show: this.showSpectatorModal && !this.isGameEnded && !!r,
      title: r ? this.modalTitle(r) : '',
      hp: r ? (this.monsterHpInCombat(r) ?? null) : null,
      backgroundImage: this.setImageBackground('location'),
      hasFocus: !!r && (this.hasFocus(r.attackerId) || this.hasFocus(r.defenderId)),
      biteActive: !!this.game?.currentBite && !this.isBeforeBiteModal,
      combat: r,
      waltzRolls: this.waltzRolls,
      helpers: {
        nameOrId: this.nameOrId.bind(this),
        modsForEntityStat: this.modsForEntityStat.bind(this),
        titleFor: this.titleFor.bind(this),
        labelOrChip: this.labelOrChip.bind(this),
        weatherIconSrcForMod: this.weatherIconSrcForMod.bind(this),
        isElixirMod: this.isElixirMod.bind(this),
        modIconSrc: this.modIconSrc.bind(this),
        entityHaloIcon: this.entityHaloIcon.bind(this),
        entityRoleIcon: this.entityRoleIcon.bind(this),
        diceAsset: this.diceAsset.bind(this),
        entityAttackDice: this.entityAttackDice.bind(this),
        entityDefenseDice: this.entityDefenseDice.bind(this),
        entityColor: this.entityColor.bind(this),
        isBallroomWaltzFight: this.isBallroomWaltzFight.bind(this),
        hasFocus: this.hasFocus.bind(this),
        showFocusSpectate: this.showFocusSpectate.bind(this),
        waltzPlaceholderDice: this.waltzPlaceholderDice.bind(this),
        getPlayer: this.getPlayer.bind(this),
        roleColorOf: this.roleColorOf.bind(this),
      }
    };
  }

  get actionVm(): ActionModalVm {
    return {
      show: this.showActionModal && !this.isGameEnded,
      mode: this.actionMode,
      ownerId: this.actionOwnerId,
      location: this.actionLocation,
      trapEnemies: this.trapEnemies,
      selectedTargetId: this.actionSelectedTargetId,
      trapCurrentIndex: this.trapCurrentIndex,
      roll: this.actionRoll,
      breakdownLines: this.actionBreakdownLines,
      resolving: this.actionResolving,
      isActor: this.isActionActor,
      targetName: this.ActionTargetName,
      currentPitTarget: this.currentPitTarget,
      incendiaireChoices: this.incendiaireChoices,
      ambushEnemies: this.ambushEnemies,
      clonesIndexes: this.clonesIndexes,
      clonesLocationChoices: this.clonesLocationChoices,
      clonesSelectedLocations: this.clonesSelectedLocations,
      weatherSecondChoices: this.weatherSecondChoices,
      weatherThirdChoices: this.weatherThirdChoices,
      selectedWeather2: this.selectedWeather2,
      selectedWeather3: this.selectedWeather3,
      cataclysmeLabelPair: this.cataclysmeLabelPair,
      mirrorLocationChoices: this.mirrorLocationChoices,
      selectedMirrorLoc: this.selectedMirrorLoc,
      mirrorChoices: this.mirrorChoices,
      mirrorHuntersByLoc: this.mirrorHuntersByLoc,
      hunterPlayers: this.hunterPlayers,
      secretPassageChoices: this.secretPassageChoices,
      selectedSecretPassageLoc: this.selectedSecretPassageLoc,
      diceColor: this.actionDiceColor,
      isGameEnded: this.isGameEnded,
      game: this.game
    };
  }

  readonly actionActions: ActionModalActions = {
    onHolyWaterChoice: (choice) => this.onHolyWaterChoice(choice),
    onNetRoll: () => this.onNetRoll(),
    selectActionTarget: (id) => this.selectActionTarget(id),
    onPitRoll: () => this.onPitRoll(),
    onProvocationChoose: (id) => this.onProvocationChoose(id),
    onIncendiaireRoll: () => this.onIncendiaireRoll(),
    onAmbushChoose: (id) => this.onAmbushChoose(id),
    onBlessedStakeRoll: () => this.onBlessedStakeRoll(),
    onMerchantRoll: () => this.onMerchantRoll(),
    onConfirmBonus: (mode) => this.onConfirmBonus(mode),
    onCancelBonus: () => this.onCancelBonus(),
    onSelectWeather2: (ws) => this.onSelectWeather2(ws),
    onSelectWeather3: (ws) => this.onSelectWeather3(ws),
    onCataclysmeConfirm: () => this.onCataclysmeConfirm(),
    onClonesRoll: () => this.onClonesRoll(),
    onCloneLocationChange: (idx, ev) => this.onCloneLocationChange(idx, ev),
    onClonesConfirm: () => this.onClonesConfirm(),
    onMirrorSetupConfirm: () => this.onMirrorSetupConfirm(),
    onMirrorResolveChoose: (loc) => this.onMirrorResolveChoose(loc),
    onDarkMarkChoose: (id) => this.onDarkMarkChoose(id),
    onOccultWeakeningTarget: (id) => this.onOccultWeakeningTarget(id),
    onSecretPassageConfirm: () => this.onSecretPassageConfirm(),
    setSelectedActionTargetId: (id) => this.actionSelectedTargetId = id,
    setSelectedMirrorLoc: (loc) => this.selectedMirrorLoc = loc,
    setSelectedSecretPassageLoc: (loc) => this.selectedSecretPassageLoc = loc,
  };

  readonly actionHelpers: ActionModalHelpers = {
    actionBackgroundSrc: this.actionBackgroundSrc.bind(this),
    actionLabelFr: this.actionLabelFr.bind(this),
    canHolyWaterReduce: this.canHolyWaterReduce.bind(this),
    canHolyWaterAttack: this.canHolyWaterAttack.bind(this),
    canHolyWaterCleanse: this.canHolyWaterCleanse.bind(this),
    labelLocation: this.labelLocation.bind(this),
    merchantResultText: this.merchantResultText.bind(this),
    merchantBuyText: this.merchantBuyText.bind(this),
    canPayBonusWithResource: this.canPayBonusWithResource.bind(this),
    canPayBonusWithGold: this.canPayBonusWithGold.bind(this),
    labelWeather: this.labelWeather.bind(this),
    diceAsset: this.diceAsset.bind(this),
    canConfirmClones: this.canConfirmClones.bind(this),
  };

  get shopVm(): ShopModalVm {
    return {
      show: this.shopOpen && !this.isGameEnded,
      isHunter: this.isHunter,
      isVampireSide: this.isVampireSide,
      isMeVampire: this.isMeVampire,
      isMeDead: this.isMeDead,
      waitingDone: this.waitingDone,
      meId: this.meId,
      me: this.me,
      phase4LeftSec: this.phase4LeftSec,
      actionPrice: this.actionPrice,
      silverPrice: this.silverPrice,
      holyWaterGoldPrice: this.holyWaterGoldPrice,
      trackingGoldPrice: this.trackingGoldPrice,
      salesAmount: this.salesAmount,
      canBuyPotion: this.canBuyPotion,
      canBuyHunterAction: this.canBuyHunterAction,
      canBuyVampAction: this.canBuyVampAction,
      canBuyTrackingAction: this.canBuyTrackingAction,
      canBuyHolyWaterAction: this.canBuyHolyWaterAction,
      canBuySilver: this.canBuySilver,
      selectedTradeTargetId: this.selectedTradeTargetId,
      eligibleTradeTargets: this.eligibleTradeTargets,
      myOffer: this.myOffer,
      game: this.game,
      sellableResources: this.sellableResources,
      bonusKind: this.me?.shopBonusKind ?? null,
      bonusTitle: this.bonusTitle(),
      bonusResCost: this.bonusResCost(),
      bonusGoldCost: this.bonusGoldCost(),
      canBuyBonus: this.canBuyBonus(),
      bonusBuyDisabledTitle: this.bonusBuyDisabledTitle(),
      bonusBuyImgSrc: this.bonusBuyImgSrc(),
      helpers: this.shopHelpers
    };
  }

  readonly shopActions: ShopModalActions = {
    onFinishPhase4: () => this.onFinishPhase4(),
    onBuyAction: (ev) => this.onBuyAction(ev),
    onBuyPotion: (ev) => this.onBuyPotion(ev),
    onBuySilver: (qty) => this.onBuySilver(qty),
    onBuyHolyWaterAction: (ev) => this.onBuyHolyWaterAction(ev),
    onBuyTrackingAction: (ev) => this.onBuyTrackingAction(ev),
    onSell: (res, qty) => this.onSell(res, qty),
    onTransmute: (recipe) => this.onTransmute(recipe),
    selectTradeTarget: (id) => this.selectTradeTarget(id),
    bumpOffer: (res, delta) => this.bumpOffer(res, delta),
    onTradeActionFor: (action, targetId) => this.onTradeActionFor(action, targetId),
    onContributeBankStone: () => this.onContributeBankStone(),
    onBuyUpgradeWeapon: (ev) => this.onBuyUpgradeWeapon(ev),
    onBuyUpgradeArmor: (ev) => this.onBuyUpgradeArmor(ev),
    onBuyBonus: (ev) => this.onBuyBonus(ev),
    useAction: (action) => this.useAction(action),
    zoomEnter: (ev, card, isHunter, side) => this.zoomEnter(ev, card, isHunter, side),
    zoomMove: (ev) => this.zoomMove(ev),
    zoomLeave: () => this.zoomLeave()
  };

  readonly shopHelpers: ShopModalHelpers = {
    actionImg: this.actionImg.bind(this),
    actionLabelFr: this.actionLabelFr.bind(this),
    deckBackFor: this.deckBackFor.bind(this),
    deckCount: this.deckCount.bind(this),
    usernameOf: this.usernameOf.bind(this),
    resOf: this.resOf.bind(this),
    bankLevel: this.bankLevel.bind(this),
    canContributeBankStone: this.canContributeBankStone.bind(this),
    bankNextCost: this.bankNextCost.bind(this),
    bankStoneProgress: this.bankStoneProgress.bind(this),
    bankBonusText: this.bankBonusText.bind(this),
    weaponUpgradeCost: this.weaponUpgradeCost.bind(this),
    weaponUpgradeImgSrc: this.weaponUpgradeImgSrc.bind(this),
    canBuyUpgradeWeapon: this.canBuyUpgradeWeapon.bind(this),
    armorUpgradeCost: this.armorUpgradeCost.bind(this),
    armorUpgradeImgSrc: this.armorUpgradeImgSrc.bind(this),
    canBuyUpgradeArmor: this.canBuyUpgradeArmor.bind(this),
    canBuySilverQty: this.canBuySilverQty.bind(this),
    myMaintenanceActions: this.myMaintenanceActions.bind(this),
    canUseActionNow: this.canUseActionNow.bind(this),
    tradeResources: this.tradeResources.bind(this),
    offerQty: (res) => this.myOffer[res] ?? 0,
    activeFlashes: this.activeFlashes.bind(this),
    myTradesSorted: this.myTradesSorted.bind(this),
    otherIdFromTrade: this.otherIdFromTrade.bind(this),
    iAmA: this.iAmA.bind(this),
    otherStatus: this.otherStatus.bind(this),
    myStatus: this.myStatus.bind(this),
    statusClassFrom: this.statusClassFrom.bind(this),
    isClosing: this.isClosing.bind(this),
    isClosingOk: this.isClosingOk.bind(this),
    isClosingKo: this.isClosingKo.bind(this)
  };

  get buildVm(): BuildModalVm {
    return {
      show: this.buildModalOpen && !this.isGameEnded,
      backgroundImage: this.setImageBackground('construire') || '',
      title: 'Construire un lieu',
      me: this.me ?? null,
      buildOptions: this.buildOptions
    };
  }

  readonly buildActions: BuildModalActions = {
    chooseInfra: (code) => this.onChooseInfra(code),
    close: () => this.closeBuildModal()
  };

  readonly buildHelpers: BuildModalHelpers = {
    isInfraBuilt: (code) => this.isInfraBuilt(code),
    infraImg: (code) => this.infraImg(code),
    infraCostList: (code) => this.infraCostList(code),
    locationInfo: (code) => this.locationInfo(code),
    zoomEnter: (ev, badge, isHunter, size, info) => this.zoomEnter(ev, badge, isHunter, size, info),
    zoomMove: (ev) => this.zoomMove(ev),
    zoomLeave: () => this.zoomLeave()
  };

  get buildConfirmVm(): BuildConfirmModalVm {
    return {
      show: this.buildConfirmModalOpen && !!this.buildChoice && !this.isGameEnded,
      backgroundImage: this.setImageBackground('construction') || '',
      text: this.buildChoice ? this.getInfraConfirmText(this.buildChoice) : ''
    };
  }

  readonly buildConfirmActions: BuildConfirmModalActions = {
    confirm: () => this.doBuild(),
    cancel: () => this.cancelBuild()
  };

  readonly rollActions: RollModalActions = {
    rollNow: () => this.rollNow()
  };
  private unsubscribeGameTopic?: () => void; // coupe l’abonnement WS au destroy
  private notify = inject(NotifyService);
  private cdr = inject(ChangeDetectorRef);

  private weatherWaitTimer?: any;

  manyModsForDebug(p: SPlayer, times = 3) {
    const base = this.modsForDisplay(p);
    return Array(times).fill(base).flat();
  }

  gameId = '';
  game?: GameSnapshot;
  errorMsg = '';
  hasSkipped = false;
  private lastPhaseNotified?: Phase;
  private harvestBubbleShown = false;

  private pendingBonusFly: { src: string; rect: DOMRect } | null = null;
  private shouldPlayBonusFlyAfterClose = false;

  meId = sessionStorage.getItem('userId') || '';
  username = sessionStorage.getItem('username') || '';

  deathModalOpen = false;
  deathModalSeen = false;

  get showDeathModal(): boolean {
    return !this.isGameEnded && this.isMeDead && this.deathModalOpen;
  }

  get isMeDead(): boolean {
    const me = this.game?.players?.find(p => p.id === this.meId);
    return !!me && !me.leftGame && me.hp <= 0;
  }

  selectedLocation: string | null = null;
  selectedAction: string | null = null;
  selectedActionIndex: number | null = null;
  selectedPotion: string | null = null;
  selectedPotionIndex: number | null = null;

  private seenEventKeys = new Set<string>();

  private stableEventKey(ev: any): string {
    const t = ev?.type || 'UNKNOWN';
    const ts = ev?.ts ?? 0;
    const p = ev?.payload || {};

    switch (t) {
      case 'LOCATION_SELECTED':
        return `${t}|${ts}|${p.playerId ?? ''}|${p.card ?? ''}`;
      case 'DICE_ROLLED':
        return `${t}|${ts}|${p.roundId ?? ''}|${p.who ?? ''}|${p.side ?? ''}|${p.roll ?? ''}`;
      case 'COMBAT_RESOLVED':
        return `${t}|${ts}|${p.roundId ?? ''}|${p.dmg ?? ''}`;
      case 'BITE_STARTED':
        return `${t}|${ts}|${p.attackerId ?? ''}|${p.targetId ?? ''}|${p.location ?? ''}`;
      case 'BITE_ROLLED':
        return `${t}|${ts}|${p.attackerId ?? ''}|${p.targetId ?? ''}|${p.roll ?? ''}`;
      case 'READY_UPDATED':
        return `${t}|${ts}|${p.playerId ?? ''}|${p.ready ?? ''}/${p.total ?? ''}`;
      default:
        // fallback très discriminant
        return `${t}|${ts}|${JSON.stringify(p)}`;
    }
  }

  private weatherAdvanceSent = false;
  private phase2AdvanceSent = false;
  private prephase3AdvanceSent = false;
  private phase4AdvanceSent = false;
  private toPhase0AdvanceSent = false;

  // === PREPHASE3 — timer local (30 s non-autoritaire) ===
  prephaseEndsAtMillis: number | null = null;   // timestamp local de fin
  remainingPrePhaseSeconds = 0;                 // affichage "XX s"
  private prephaseTicker: any | null = null;    // setInterval handle
  private static readonly PREPHASE_MS = 30_000;

  // --- Actions ---
  preparedGarlicForThisRaid = false;
  // NET ou PIT, ou null si pas de piège en cours
  actionMode: 'NET' | 'PIT' | 'INCENDIAIRE' | 'PROVOCATION' | 'AMBUSH' | 'LONELY' | 'BLESSED_STAKE' | 'CHARISMATIQUE' | 'MARCHAND_ITINERANT' | 'MARCHAND_BONUS_BUY' | 'PRESENCE_ECRASANTE' | 'CATACLYSME' | 'CLONES_OMBRE' | 'IMAGE_MIROIR_SETUP' | 'IMAGE_MIROIR_RESOLVE' | 'ECLIPSE' | 'BLOOD_MOON' | 'VOILE_DE_BRUME' | 'FAIM_IRREPRESSIBLE' | 'MARQUE_TENEBREUSE' | 'AFFAIBLISSEMENT_OCCULTE' | 'PASSAGE_SECRET' | 'AVIDITE_NOCTURNE' | 'EAU_BENITE' | null = null;
  actionOwnerId: string | null = null;
  actionLocation: string | null = null;
  trapEnemies: SPlayer[] = [];
  // Pour le FILET ou INCENDIAIRE : cible choisie par le chasseur
  actionSelectedTargetId: string | null = null;
  // Pour la FOSSE : index de la cible en cours dans trapEnemies
  trapCurrentIndex = 0;
  // Résultat courant à afficher dans la modale
  actionRoll: number | null = null;
  actionBreakdownLines: string[] = [];
  // Afficher / cacher la modale
  showActionModal = false;
  // Flag pour éviter les doubles clics pendant l'appel API
  actionResolving = false;

  hadIncendiaireThisPrephase = false;
  // Choix possibles pour INCENDIAIRE (codes d’infra)
  incendiaireChoices: (
    'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE'
  )[] = [];

  ambushEnemies: SPlayer[] = [];
  private lastLonelyShownAt = 0;
  isSacredRosaryUsed: boolean = false;
  lastCharismRaid: number | null = null;

  clonesSelectedLocations: string[] = [];

  selectedWeather2: string | null = null;
  selectedWeather3: string | null = null;

  weatherSecondChoices: string[] = ['WIND', 'STORM', 'RAIN', 'BLIZZARD'];
  weatherThirdChoices: string[] = ['WIND', 'STORM', 'RAIN', 'BLIZZARD'];


  // Pour éviter de réafficher 15 fois la modale Présence écrasante
  private actionTimeoutId: any = null;
  private lastPresenceRaid: number | null = null;
  private lastEclipseRaid: number | null = null;
  private lastBloodMoonRaid: number | null = null;
  private lastFogRaid: number | null = null;
  private lastCataclysmeRaid: number | null = null;
  private lastMerchantShownKey: string | null = null;

  // Image miroir
  selectedMirrorLoc: string | null = null;
  mirrorPrimaryLoc: string | null = null;
  mirrorAltLocations: string[] = [];
  mirrorChoices: string[] = [];
  mirrorHuntersByLoc: Record<string, SPlayer[]> = {};

  // Passage secret
  secretPassageChoices: string[] = [];
  selectedSecretPassageLoc: string | null = null;

  // Avidité nocturne
  lastGreedRaid: number | null = null;

  // --- Morsure : état purement UI (pas dans GameSnapshot)
  isRolling = false;
  private biteNotBeforeMillis = 0; // petit délai de lecture avant d'ouvrir la modale
  unstableLockedIds: Set<string> = new Set();

  isUnstableLocked = (unstableId: string) => this.unstableLockedIds.has(unstableId);
  private lockUnstable(id: string) { this.unstableLockedIds.add(id); }
  private unlockUnstable(id: string) { this.unstableLockedIds.delete(id); }

  // --- Météo: états/temporisations contrôlées côté client ---
  weatherBgActive = false;                // quand true => on affiche le fond météo
  private weatherTimer?: any;             // timer pour le délai AVANT fond
  private weatherPostTimer?: any;         // timer après affichage du fond
  private lastWeatherRollSeen: number | null = null;

  // Hold pour garder la modale visible même si phase != PHASE0
  private weatherModalHold = false;

  private static readonly WEATHER_WAIT_BEFORE_MODAL_MS = 2000;
  readonly WEATHER_WHEEL_MS = 2000;   // durée de la roue
  readonly WEATHER_BG_MS = 4000;   // fondu du fond
  readonly WEATHER_HOLD_MS = 4000;   // petite pause lecturegarantie après fond

  readonly CENTER_FLIP_HOLD_MS = 4000;   // petite pause après le flip en PREPHASE3

  private readonly SPECTATE_HOLD_MS = 5000;

  // roue = 12 pales => 30° par pale. Offset pour aligner la pale.
  private static readonly WHEEL_DEG_PER_FACE = 30;
  private static readonly WHEEL_BASE_OFFSET = 15;
  private static readonly WEATHER_ICON_RADIUS = 120;

  // --- SHOP (modale Phase 4) --- //
  shopOpen = false;
  waitingDone = false;      // vrai après clic "Ne rien faire"
  phase4LeftSec = 0;
  private phase4TimerId: any = null;

  // Constructions
  buildModalOpen = false;
  buildConfirmModalOpen = false;
  buildChoice: 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE' | null = null;
  effectChoice: 'STUDY' | 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'ALCHEMY' | 'RARE_ALCHEMY' | 'EXPLOSION' | 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING' | 'HEAL' | 'CORRUPT_SOULS' | 'CORRUPT' | 'PURIFY_WATER' | 'FORGE' | null = null;

  // ----- Effet de lieu : modale d'action (Bibliothèque) -----
  locationActionModalOpen = false;
  locationActionKind: 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'EXPLOSION' | 'HEAL' | 'CORRUPT' | 'FORGE' | null = null;

  // THEFT
  theftTargets: { id: string; username: string; actionsCount: number }[] = [];
  theftSelectedTargetId: string | null = null;
  theftSlots: number[] = [];
  theftSelectedSlotIndex: number | null = null;
  theftSubmitting = false;

  // OMEN
  omenCards: string[] = [];
  omenPlacements: ('TOP' | 'BOTTOM' | null)[] = [];
  omenSubmitting = false;

  // EXPERIMENT
  experimentMonsterType: 'REVENANT' | 'GARGOYLE' | 'ABERRATION' | null = null;
  experimentLocation: string | null = null;
  experimentSubmitting = false;
  experimentPossibleLocations: string[] = [];

  experimentMonsterMeta = {
    REVENANT: { cost: 100, hp: 5, atkDice: 'D6', defDice: 'D4' },
    GARGOYLE: { cost: 200, hp: 10, atkDice: 'D8', defDice: 'D6' },
    ABERRATION: { cost: 400, hp: 15, atkDice: 'D12', defDice: 'D8' },
  } as const;

  // ALTAR
  altarTargets: { id: string; username: string; hp: number; corruption: number }[] = [];
  altarSelectedTargetId: string | null = null;
  altarSubmitting = false;

  // ----- FORGE -----
  forgeOptions: ForgeOption[] = [];
  forgeSelectedId: string | null = null;
  forgeSubmitting = false;
  forgeResolvedLabel: string | null = null;

  // Echanges
  selectedTradeTargetId: string | null = null;
  myOffersByTarget: Record<string, Record<string, number>> = {};
  myOffer: Record<string, number> = {};

  private closingUntil: Record<string, number> = {};
  private closingKind: Record<string, 'ok' | 'ko'> = {};

  // Ressources vendables à la boutique (typage littéral)
  readonly sellableResources: ReadonlyArray<'wood' | 'herbs' | 'stone' | 'iron' | 'water'> =
    ['wood', 'herbs', 'stone', 'iron', 'water'] as const;

  // Ressources complètes pour la zone "Mes ressources" de l’échange
  allResources: Array<'gold' | 'silver' | 'souls' | 'wood' | 'herbs' | 'stone' | 'iron' | 'water'> =
    ['gold', 'silver', 'souls', 'wood', 'herbs', 'stone', 'iron', 'water'] as const;

  back() { this.router.navigate(['/lobby']); }

  private emitPhaseBubble(prev: Phase | undefined, g: GameSnapshot) {
    if (!g?.phase) return;
    if (prev === g.phase && this.lastPhaseNotified === g.phase) return;

    const txt = this.phaseTextFor(g.phase, g);
    if (txt) {
      this.notify.phase(txt);
      this.lastPhaseNotified = g.phase;
    }
  }

  private phaseTextFor(phase: Phase, g: GameSnapshot): string | null {
    const me = g.players?.find(p => p.id === this.meId);
    const role = me?.role; // 'HUNTER' | 'VAMPIRE' | 'SERVANT' | undefined
    const isHunter = role === 'HUNTER';
    const isVampSide = role === 'VAMPIRE' || role === 'SERVANT';

    // "Est-ce qu'au moins un joueur du camp a une carte lieu en main ?"
    const anyHunterHasLoc =
      (g.players || []).some(p => p.role === 'HUNTER' && !p.leftGame && (p.hp ?? 0) > 0 && (p.hand?.length ?? 0) > 0);

    const anyVampSideHasLoc =
      (g.players || []).some(p => (p.role === 'VAMPIRE' || p.role === 'SERVANT') && !p.leftGame && (p.hp ?? 0) > 0 && (p.hand?.length ?? 0) > 0);

    if (phase === 'PHASE0') {
      return 'Tirage météo';
    }

    // PHASE1 = tour chasseurs (lieu)
    if (phase === 'PHASE1') {
      if (isHunter) {
        return 'À vous de jouer !';
      }
      return 'Les chasseurs partent en raid !';
    }

    // PHASE2 = tour vampire/serviteurs (lieu) OU réveil si pas de carte
    if (phase === 'PHASE2') {
      if (isVampSide) {
        return 'À vous de jouer !';
      }
      return 'Le vampire s’éveille !';
    }

    if (phase === 'PREPHASE3' && this.imInUpcomingCombat()) return 'Préparation au combat !';

    const hasCombat = g.currentCombat != null;

    if (phase === 'PHASE3') {
      return hasCombat ? 'Résolution des combats' : 'Résolution des récoltes';
    }
    if (phase === 'PHASE4') return 'Fin de raid... Préparez-vous pour le raid suivant !';

    return null;
  }

  private resetHarvestBubbleIfPhaseChanged(prev: GameSnapshot | undefined, g: GameSnapshot) {
    const prevPhase = prev?.phase as Phase | undefined;
    const nextPhase = g?.phase as Phase | undefined;
    if (!nextPhase) return;

    // Dès qu'on entre dans PHASE3 (nouveau raid/phase3), on réarme
    if (prevPhase !== nextPhase && nextPhase === 'PHASE3') {
      this.harvestBubbleShown = false;
    }

    // sécurité : si on sort de PHASE3, on réarme aussi
    if (prevPhase !== nextPhase && nextPhase !== 'PHASE3') {
      this.harvestBubbleShown = false;
    }
  }

  private maybeEmitHarvestBubble(g: GameSnapshot) {
    if (this.harvestBubbleShown) return;
    if (g.phase !== 'PHASE3') return;

    // tant qu'il y a un combat OU une morsure en cours => pas de récolte
    if (g.currentCombat != null) return;
    if (g.currentBite != null) return;

    this.notify.phase('Résolution des récoltes');
    this.harvestBubbleShown = true;
  }

  private toastActionUsed(payload: any) {
    const pid = payload?.playerId;
    const code = payload?.type;

    const who = pid ? this.usernameOf(pid) : 'Un joueur';
    const label = this.actionLabelFr(code as any);

    const role = pid ? (this.game?.players?.find(p => p.id === pid) as any)?.role : null;
    const tone = (role === 'HUNTER') ? 'HUNTER'
      : (role === 'VAMPIRE' || role === 'SERVANT') ? 'VAMP'
        : 'NEUTRAL';

    this.notify.toast(`${who} utilise l'action ${label} !`, 4500, tone);
  }

  private toastPotionUsed(payload: any) {
    const pid = payload?.playerId;
    const code = payload?.type;

    const isPotion =
      code === 'FORCE'
      || code === 'ENDURANCE'
      || code === 'VIE'
      || code === 'FOCALISATION'
      || code === 'SANGSUE';

    const kind = isPotion ? 'la potion' : "l'élixir";

    const who = pid ? this.usernameOf(pid) : 'Un joueur';
    const label = this.potionLabelFr(code as any);

    const role = pid ? (this.game?.players?.find(p => p.id === pid) as any)?.role : null;
    const tone = (role === 'HUNTER') ? 'HUNTER'
      : (role === 'VAMPIRE' || role === 'SERVANT') ? 'VAMP'
        : 'NEUTRAL';

    this.notify.toast(`${who} utilise ${kind} ${label} !`, 4500, tone);
  }

  private toastInfraBuilt(payload: any) {
    const builderId = payload?.builderId ?? payload?.playerId ?? payload?.uid;
    const infra = payload?.infra ?? payload?.code ?? payload?.kind;

    const who = builderId ? this.usernameOf(builderId) : 'Le vampire';
    const what = infra ? (this.labelLocation(infra) || String(infra)) : 'un lieu';

    // infra = vampire/serviteur -> rouge
    this.notify.toast(`${who} construit ${what}`, 4500, 'VAMP');
  }

  leave() {
    if (!this.game) return;

    const ok = window.confirm(
      "Êtes-vous sûr de vouloir abandonner la partie ?\n" +
      "Votre personnage meurt si vous abandonnez."
    );
    if (!ok) return;

    this.api.surrenderGame(this.game.id).subscribe({
      next: () => {
        this.deathModalSeen = false;
        this.deathModalOpen = true;
      },
      error: e => this.showError(e)
    });
  }

  observeAfterDeath() {
    this.deathModalOpen = false;
    this.deathModalSeen = true;
  }

  leaveAfterDeath() {
    if (!this.game) { this.router.navigate(['/lobby']); return; }

    // là on quitte vraiment
    this.api.leaveGame(this.game.id).subscribe({
      next: () => {
        sessionStorage.removeItem('gameId');
        this.router.navigate(['/lobby']);
      },
      error: e => this.showError(e)
    });
  }

  leaveAfterEnd() {
    if (!this.game) { this.router.navigate(['/lobby']); return; }

    this.api.leaveGame(this.game.id).subscribe({
      next: () => {
        sessionStorage.removeItem('gameId');
        this.router.navigate(['/lobby']);
      },
      error: e => this.showError(e)
    });
  }

  get isGameEnded(): boolean {
    const g = this.game;
    return !!g && g.status === 'ENDED' && !!g.winnerSide;
  }

  winnerTitle(): string {
    const g = this.game;
    if (!g || !g.winnerSide) return 'Fin de partie';

    switch (g.winnerSide) {
      case 'HUNTERS':
        return 'Victoire des chasseurs';
      case 'VAMPIRE':
        return 'Victoire du vampire';
      default:
        return 'Fin de partie';
    }
  }

  winnerSubtitle(): string {
    const g = this.game;
    if (!g || !g.winnerSide) return '';

    switch (g.winnerSide) {
      case 'HUNTERS':
        return 'Le vampire est terrassé. Les chasseurs triomphent.';
      case 'VAMPIRE':
        return 'Plus aucun chasseur n’est debout. Le vampire règne sans partage.';
      default:
        return '';
    }
  }

  private showError(e: any) {
    try { this.errorMsg = e?.error?.message || 'Erreur'; } catch { this.errorMsg = 'Erreur'; }
    setTimeout(() => this.errorMsg = '', 4000);
  }

  private advanceToPhase1IfNeeded() {
    if (this.weatherAdvanceSent) return;
    if (!this.game?.id) return;
    if (this.game.phase !== 'PHASE0') return;

    this.weatherAdvanceSent = true;
    this.api.advancePhase(this.game.id, 'PHASE1').subscribe({
      error: (httpError) => {
        // si un autre client a déjà avancé (409), on ignore
        if (httpError?.status !== 409) {
          this.weatherAdvanceSent = false; // autorise un retry si vraie erreur
          this.showError(httpError);
        }
      }
    });
  }

  /** Renvoie les compteurs "prêts/total" d'après l'état courant. */
  private readyCounts(): { ready: number; total: number } {
    const g: any = this.game || {};
    const ready = (typeof g.readyCount === 'number') ? g.readyCount : (g.readyForPhase3?.length || 0);
    const total = (typeof g.readyTotal === 'number') ? g.readyTotal : (g.players?.length || 0);
    return { ready, total };
  }

  /** Lance (ou relance) le timer PREPHASE3 local. */
  private startPrephaseTimer(seedEndsAt?: number): void {
    // Si on donne une seed (ex: relance après reload), on la respecte, sinon on part de maintenant + 30s
    this.prephaseEndsAtMillis = seedEndsAt ?? (Date.now() + GameComponent.PREPHASE_MS);

    // Nettoie un éventuel ancien interval
    if (this.prephaseTicker) {
      clearInterval(this.prephaseTicker);
      this.prephaseTicker = null;
    }

    // Tick 4x/s pour un affichage fluide
    this.prephaseTicker = setInterval(() => {
      if (!this.prephaseEndsAtMillis) return;

      const left = this.prephaseEndsAtMillis - Date.now();
      this.remainingPrePhaseSeconds = Math.max(0, Math.ceil(left / 1000));

      if (left <= 0) {
        this.stopPrephaseTimer();
        this.remainingPrePhaseSeconds = 0;
        // ❌ On ne fait rien ici (pas d’auto-advance côté front) :
        // le serveur forcera PHASE3 via son propre timer, et on recevra PHASE_CHANGED en WS.
      }
    }, 250);
  }

  /** Stoppe le timer local et remet à zéro les valeurs. */
  private stopPrephaseTimer(): void {
    if (this.prephaseTicker) {
      clearInterval(this.prephaseTicker);
      this.prephaseTicker = null;
    }
    this.prephaseEndsAtMillis = null;
    this.remainingPrePhaseSeconds = 0;
  }

  private startPhase4Timer(deadlineMillis?: number | null) {
    this.stopPhase4Timer();
    const deadline = deadlineMillis ?? (Date.now() + 120000); // fallback robuste
    this.phase4TimerId = setInterval(() => {
      const ms = Math.max(0, deadline - Date.now());
      this.phase4LeftSec = Math.ceil(ms / 1000);
      if (ms <= 0) this.stopPhase4Timer();
    }, 1000);
  }

  private stopPhase4Timer() {
    if (this.phase4TimerId) { clearInterval(this.phase4TimerId); this.phase4TimerId = null; }
    this.phase4LeftSec = 0;
  }

  /** Déclenche automatiquement l'avance vers PHASE3 si tout le monde est prêt. */
  private maybeAutoAdvanceToPhase3(): void {
    if (!this.game) return;
    if (this.game.phase !== 'PREPHASE3') return;
    if (this.prephase3AdvanceSent) return;

    const { ready, total } = this.readyCounts();
    if (total > 0 && ready >= total) {
      this.prephase3AdvanceSent = true;
      // petit hold UX (facultatif)
      setTimeout(() => {
        this.api.advancePhase(this.gameId, 'PHASE3').subscribe({
          error: e => {
            if (e?.status === 409 || e?.error?.message === 'illegal advance') return;
            this.prephase3AdvanceSent = false;
            this.showError(e);
          }
        });
      }, 400);
    }
  }

  /** Avance automatiquement en PHASE4 quand PHASE3 n’a plus rien à traiter. */
  private maybeAdvanceToPhase4EndOfRaid() {
    const g = this.game;
    if (!g || g.phase !== 'PHASE3') return;
    if (this.phase4AdvanceSent) return;

    const noCurrent = !g.currentCombat && g.currentCombatIndex == null;
    const queueEmpty = !g.combatsQueue || g.combatsQueue.length === 0;

    if (noCurrent || queueEmpty) {
      this.phase4AdvanceSent = true;
      setTimeout(() => {
        this.api.advancePhase(this.gameId, 'PHASE4').subscribe({
          error: e => { this.phase4AdvanceSent = false; this.showError(e); }
        });
      }, this.SPECTATE_HOLD_MS); // petit temps de lecture du dernier breakdown
    }
  }

  /** Patch “flip” local des cartes (utilisé quand on reçoit CENTER_REVEALED). */
  private flipCenterFaceUpLocally() {
    if (!this.game) return;
    this.game.center = this.game.center.map(c => ({ ...c, faceUp: true }));
    this.game = { ...(this.game as any) };
  }

  private scheduleActionAutoClose() {
    if (this.actionTimeoutId) {
      clearTimeout(this.actionTimeoutId);
    }

    this.actionTimeoutId = setTimeout(() => {
      this.showActionModal = false;
      this.actionTimeoutId = null;
      this.maybeAutoAdvanceToPhase3();
    }, 5000);
  }

  // assets card style
  private diceToTier(d?: string): 0 | 1 | 2 | 3 {
    const s = (d || 'D6').toUpperCase();
    if (s.includes('12')) return 3;
    if (s.includes('8')) return 2;
    if (s.includes('6')) return 1;
    return 0;
  }

  // Hunters: T1+ => BLEED / STUN / RANGE selon player.weapon
  private hunterWeaponType(weapon?: string): 'BLEED' | 'STUN' | 'RANGE' {
    const w = (weapon || '').toUpperCase();
    // stun
    if (w.includes('MACE') || w.includes('HAMMER') || w.includes('FLAIL')) return 'STUN';
    // range
    if (w.includes('SPEAR') || w.includes('CROSSBOW') || w.includes('PISTOL')) return 'RANGE';
    // bleed par défaut (SWORD / HALBERD / WRIST_BLADES / ou inconnu)
    return 'BLEED';
  }

  weaponImg(p: any): string {
    const tier = this.diceToTier(p?.attackDice);
    const isVamp = p?.role === 'VAMPIRE' || p?.role === 'SERVANT'

    if (isVamp) {
      return `/assets/cards/stuff/W_T${tier}_VAMP.png`;
    }

    // Hunter (et SERVANT si jamais): T0 n’a pas de type
    if (tier === 0) {
      return `/assets/cards/stuff/W_T0_HUNTER.png`;
    }

    const type = this.hunterWeaponType(p?.weapon);
    return `/assets/cards/stuff/W_T${tier}_${type}_HUNTER.png`;
  }

  armorImg(p: any): string {
    const tier = this.diceToTier(p?.defenseDice);
    const isVamp = p?.role === 'VAMPIRE' || p?.role === 'SERVANT'
    return isVamp
      ? `/assets/cards/stuff/A_T${tier}_VAMP.png`
      : `/assets/cards/stuff/A_T${tier}_HUNTER.png`;
  }

  private readonly HUNTER_ACTIONS_DIR = '/assets/cards/hunter_actions/';
  private readonly VAMP_ACTIONS_DIR = '/assets/cards/vampire_actions/';
  private readonly POTIONS_DIR = '/assets/cards/potions/';

  /** Action -> image (dossier dépend du rôle du joueur courant) */
  actionImg(code: string | null | undefined, role: string | null | undefined = undefined): string {
    if (!code) return '';
    let base;

    if (role === 'HUNTER') {
      base = this.VAMP_ACTIONS_DIR;
    } else if (role === 'VAMPIRE' || role === 'SERVANT') {
      base = this.HUNTER_ACTIONS_DIR;
    } else {
      base = (this.me?.role === 'VAMPIRE')
        ? this.VAMP_ACTIONS_DIR
        : this.HUNTER_ACTIONS_DIR;
    }


    return base + this.actionFile(code);
  }

  /** Map des exceptions + fallback auto */
  private actionFile(code: string): string {
    switch (code) {
      // --- HUNTER ---
      case 'EAU_BENITE': return 'eau_benite.png';
      case 'FUMIGATION_AIL': return 'fumigation_ail.png';
      case 'PISTEUR': return 'pistage.png';
      case 'FEU_DE_CAMP': return 'feu_de_camp.png';
      case 'NET': return 'net.png';
      case 'PIT': return 'pit.png';
      case 'PROVOCATION': return 'provocation.png';
      case 'INCENDIAIRE': return 'incendiaire.png';
      case 'AMBUSH': return 'ambush.png';
      case 'LONELY': return 'lonely.png';
      case 'BLESSED_STAKE': return 'blessed_stake.png';
      case 'SACRED_ROSARY': return 'sacred_rosary.png';
      case 'CHARISMATIQUE': return 'charismatique.png';
      case 'MARCHAND_ITINERANT':
      case 'MARCHAND_BONUS_BUY':
        return 'marchand_itinerant.png';

      // --- VAMPIRE ---
      case 'AFFAIBLISSEMENT_OCCULTE': return 'affaiblissement_occulte.png';
      case 'CLONES_OMBRE': return 'clones_ombre.png';
      case 'MARQUE_TENEBREUSE': return 'marque_tenebreuse.png';
      case 'AVIDITE_NOCTURNE': return 'avidite_nocturne.png';
      case 'ECLIPSE': return 'eclipse.png';
      case 'PASSAGE_SECRET': return 'passage_secret.png';
      case 'BLOOD_MOON': return 'blood_moon.png';
      case 'FAIM_IRREPRESSIBLE': return 'faim_irrepressible.png';
      case 'PRESENCE_ECRASANTE': return 'presence_ecrasante.png';
      case 'CATACLYSME': return 'cataclysme.png';
      case 'VOILE_DE_BRUME': return 'voile_de_brume.png';

      case 'IMAGE_MIROIR':
      case 'IMAGE_MIROIR_SETUP':
      case 'IMAGE_MIROIR_RESOLVE':
        return 'image_miroir.png';

      default:
        // fallback auto: "PRESENCE_ECRASANTE" -> "presence_ecrasante.png"
        return code.toLowerCase() + '.png';
    }
  }

  /** Potion/Elixir -> image */
  potionImg(code: string | null | undefined): string {
    if (!code) return '';
    return this.POTIONS_DIR + code.toLowerCase() + '.png';
  }

  potionBackSrc = '/assets/cards/potion_verso.png';

  actionBackSrc(p: any): string {
    // Servant -> verso hunter (tu peux changer si tu veux)
    return p?.role === 'VAMPIRE'
      ? '/assets/cards/vampire_verso.png'
      : '/assets/cards/hunter_verso.png';
  }

  actionCount(p: any): number {
    return (p?.actions?.length ?? 0);
  }

  consumablesCount(p: any): number {
    const pot = (p?.potions?.length ?? 0);
    const eli = (p?.elixirs?.length ?? 0);
    return pot + eli;
  }

  isHunterId(playerId: string | null | undefined): boolean {
    if (!playerId) return false;
    const ps = this.game?.players;
    if (!ps) return false;

    for (const p of ps) {
      if (p.id === playerId) return p.role === 'HUNTER';
    }
    return false;
  }

  deckCount(pile: any): number {
    return pile?.deck ?? 0;
  }

  discardCards(pile: any): string[] {
    return Array.isArray(pile?.discardCards) ? pile.discardCards : [];
  }

  discardCount(pile: any): number {
    return this.discardCards(pile).length;
  }

  lastDiscardId(pile: any): string | null {
    const xs = this.discardCards(pile);
    return xs.length ? xs[xs.length - 1] : null;
  }

  private readonly ELIXIRS_DIR = '/assets/cards/elixirs/';

  elixirImg(code: string | null | undefined): string {
    if (!code) return '';
    return this.ELIXIRS_DIR + code.toLowerCase() + '.png';
  }

  discardImgFor(kind: 'HUNTER_ACTIONS' | 'VAMP_ACTIONS' | 'POTIONS' | 'ELIXIRS', pile: any): string {
    const id = this.lastDiscardId(pile);
    if (!id) return '';

    switch (kind) {
      case 'HUNTER_ACTIONS': return this.HUNTER_ACTIONS_DIR + this.actionFile(id);
      case 'VAMP_ACTIONS': return this.VAMP_ACTIONS_DIR + this.actionFile(id);
      case 'POTIONS': return this.potionImg(id);
      case 'ELIXIRS': return this.elixirImg(id);
    }
  }

  deckBackFor(kind: 'HUNTER_ACTIONS' | 'VAMP_ACTIONS' | 'POTIONS' | 'ELIXIRS'): string {
    switch (kind) {
      case 'HUNTER_ACTIONS': return '/assets/cards/hunter_verso.png';
      case 'VAMP_ACTIONS': return '/assets/cards/vampire_verso.png';
      case 'POTIONS': return this.potionBackSrc;
      case 'ELIXIRS': return this.potionBackSrc;
    }
  }

  // ---- BONUS (marchand) : coûts + image ----
  bonusResCost(): { water?: number; herbs?: number; wood?: number; iron?: number, silver?: number } | null {
    const kind = this.me?.shopBonusKind;
    if (!kind) return null;

    const t = this.me?.shopBonusEquipTier ?? 1;

    switch (kind) {
      case 'POTION': return { water: 1, herbs: 2 };
      case 'ELIXIR': return { water: 2, herbs: 4 };
      case 'EQUIP_WEAPON':
      case 'EQUIP_ARMOR':
        return (t >= 2) ? { wood: 3, iron: 3 } : { wood: 2, iron: 2 };
      default:
        return null;
    }
  }

  bonusGoldCost(): number | null {
    const g: any = this.game;
    const me: any = this.me;
    const kind = me?.shopBonusKind;
    if (!g || !me || !kind) return null;

    const greedy = !!g.shopPricesIncreasedThisRaid;
    const charismatic = !!me.charismaticThisRaid;

    const computeCost = (base: number) => {
      let c = base;
      if (greedy) c += 50;
      if (charismatic) c = Math.max(0, c - 20);
      return c;
    };

    const t = me.shopBonusEquipTier ?? 1;

    switch (kind) {
      case 'POTION': return computeCost(30);
      case 'ELIXIR': return computeCost(60);
      case 'EQUIP_WEAPON':
      case 'EQUIP_ARMOR':
        return computeCost(t >= 2 ? 150 : 100);
      default:
        return null;
    }
  }

  bonusBuyImgSrc(): string {
    const kind = this.me?.shopBonusKind;
    if (!kind) return '';

    switch (kind) {
      case 'POTION':
      case 'ELIXIR':
        return this.deckBackFor('POTIONS');

      case 'EQUIP_WEAPON':
      case 'EQUIP_ARMOR':
        return this.deckBackFor('HUNTER_ACTIONS');

      default:
        return this.deckBackFor('HUNTER_ACTIONS');
    }
  }

  // assets zoom
  zoomBadgeIsHunter: boolean | undefined = undefined;

  zoomOn = false;
  zoomStyle: any = {};
  zoomSrc = '';

  zoomBadgeOn = false;
  zoomBadgeText = '';

  private zoomW = 0;
  private zoomH = 0;

  // Zoom moyen (défausse / cartes normales) => inchangé
  private readonly zoomScale = 2.2;
  private readonly zoomMaxSide = 340;

  // ✅ Zoom large (défausse)
  private readonly zoomScaleLarge = 4.0;
  private readonly zoomMaxSideLarge = 420;

  // zoom info lieu
  zoomMediaW = 0;
  zoomMediaH = 0;

  zoomInfoOn = false;
  zoomInfoKey = '';
  zoomInfoLinesHunter: string[] = [];
  zoomInfoLinesVamp: string[] = [];
  zoomInfoNote = '';
  zoomInfoLines: string[] = [];

  private readonly zoomInfoW = 340; // largeur du panneau info
  private readonly zoomInfoGap = 10; // espace entre image et info

  zoomEnter(
    ev: MouseEvent,
    badge?: string | number,
    badgeIsHunter?: boolean,
    size: 'M' | 'L' = 'M',
    info?: { key: string; lines: string[] } | null
  ) {
    const host = ev.currentTarget as HTMLElement | null;
    if (!host) return;

    const img = (host.tagName === 'IMG'
      ? (host as HTMLImageElement)
      : (host.querySelector('img') as HTMLImageElement | null));

    if (!img?.src) return;

    this.zoomSrc = img.src;

    // badge (inchangé)
    if (badge !== undefined && badge !== null && String(badge).trim() !== '') {
      this.zoomBadgeOn = true;
      this.zoomBadgeText = String(badge);
      this.zoomBadgeIsHunter = badgeIsHunter;
    } else {
      this.zoomBadgeOn = false;
      this.zoomBadgeText = '';
      this.zoomBadgeIsHunter = undefined;
    }

    // info
    const key = (info as any)?.key || '';

    // On affiche le panneau si :
    // - forge (même si lines est vide)
    // - OU il y a au moins une ligne non vide
    const hasLines = Array.isArray(info?.lines) && info!.lines.some(l => !!(l ?? '').trim());
    const showInfo = !!info && (key === 'forge' || hasLines);

    if (showInfo) {
      this.zoomInfoOn = true;
      this.zoomInfoKey = key;
      this.zoomInfoLines = info?.lines ?? [];

      if (this.zoomInfoKey === 'altar' && this.zoomInfoLines.length >= 5) {
        this.zoomInfoLinesHunter = this.zoomInfoLines.slice(0, 2);
        this.zoomInfoLinesVamp = this.zoomInfoLines.slice(2, 4);
        this.zoomInfoNote = this.zoomInfoLines[4] || '';
      } else {
        this.zoomInfoLinesHunter = [];
        this.zoomInfoLinesVamp = [];
        this.zoomInfoNote = '';
      }

    } else {
      this.zoomInfoOn = false;
      this.zoomInfoKey = '';
      this.zoomInfoLines = [];
      this.zoomInfoLinesHunter = [];
      this.zoomInfoLinesVamp = [];
      this.zoomInfoNote = '';
    }

    const rect = img.getBoundingClientRect();

    let scale = this.zoomScale;
    let maxSide = this.zoomMaxSide;
    if (size === 'L') { scale = this.zoomScaleLarge; maxSide = this.zoomMaxSideLarge; }

    let w = rect.width * scale;
    let h = rect.height * scale;

    const max = Math.max(w, h);
    if (max > maxSide) {
      const k = maxSide / max;
      w *= k;
      h *= k;
    }

    this.zoomW = Math.round(w);
    this.zoomH = Math.round(h);

    // exposé au template
    this.zoomMediaW = this.zoomW;
    this.zoomMediaH = this.zoomH;

    this.zoomOn = true;
    this.zoomMove(ev);
  }

  zoomMove(ev: MouseEvent) {
    if (!this.zoomOn) return;

    const mediaW = this.zoomW;
    const mediaH = this.zoomH;

    // largeur "réelle" à protéger à l'écran (image + panneau si présent)
    const totalW = mediaW + (this.zoomInfoOn ? (this.zoomInfoGap + this.zoomInfoW) : 0);
    const totalH = mediaH;

    let left = ev.clientX + 12;
    let top = ev.clientY + 12;

    const vw = window.innerWidth;
    const vh = window.innerHeight;

    // clamp avec totalW/totalH
    if (left + totalW + 8 > vw) left = vw - totalW - 8;
    if (top + totalH + 8 > vh) top = vh - totalH - 8;

    // IMPORTANT : on n'agrandit plus la box, elle reste à la taille de l'image
    this.zoomStyle = {
      left: left + 'px',
      top: top + 'px',
      width: mediaW + 'px',
      height: mediaH + 'px',
    };
  }


  zoomLeave() {
    this.zoomOn = false;
    this.zoomSrc = '';
    this.zoomBadgeOn = false;
    this.zoomBadgeText = '';
    this.zoomBadgeIsHunter = undefined;
    this.zoomInfoOn = false;
    this.zoomInfoOn = false;
    this.zoomInfoKey = '';
    this.zoomInfoLinesHunter = [];
    this.zoomInfoLinesVamp = [];
    this.zoomInfoNote = '';
    this.zoomInfoLines = [];
  }

  // === Helpers center history ===
  @ViewChild('historyBox') historyBox?: ElementRef<HTMLDivElement>;
  historyHover = false;
  private lastHistorySize = 0;

  /** Groupement dynamique par (raid, phase) */
  historyGroups() {
    const hist = this.game?.history || [];
    interface Group { raid: number; phase: string; phaseNum: string; items: typeof hist; }
    const out: Group[] = [];
    let curKey = '';
    let cur: Group | null = null;

    const phaseNum = (p: string) => p.startsWith('PHASE') ? p.substring(5) : p;

    for (const it of hist) {
      const key = `${it.raid}|${it.phase}`;
      if (key !== curKey) {
        curKey = key;
        cur = { raid: it.raid, phase: it.phase, phaseNum: phaseNum(it.phase), items: [] as any };
        out.push(cur);
      }
      cur!.items.push(it);
    }
    return out;
  }

  private bumpHistoryScroll() {
    const newSize = this.game?.history?.length || 0;
    const grew = newSize > this.lastHistorySize;
    this.lastHistorySize = newSize;
    if (!grew) return;

    // Laisse Angular peindre puis scroll seulement le conteneur
    requestAnimationFrame(() => {
      const box = this.historyBox?.nativeElement;
      if (box) box.scrollTop = box.scrollHeight;
    });
  }

  // === Helpers buff/debuff ===
  // mêmes règles que modsForDisplay, mais en filtrant aussi par STAT
  modsForStat(p: SPlayer | undefined, stat: 'ATTACK' | 'DEFENSE'): RawStatMod[] {
    if (!p || !this.game?.raidMods) return [];
    const list = this.game.raidMods[p.id] || [];

    const weatherActive = this.isWeatherActive();
    const weatherCancelled = this.isWeatherCancelledForPlayer(p);

    const out: RawStatMod[] = list.filter(m => {
      if (m.stat !== stat) return false;

      const src = m.source || '';
      const isWeather = src.startsWith('WEATHER:');
      const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');

      // on masque les mods CORRUPTION:...:ENG (comme avant)
      if (isCorruptEng) return false;

      // on masque les mods météo :
      // - si aucune météo active
      // - OU si la météo est annulée pour ce joueur (Feu de camp)
      if (isWeather) {
        if (!weatherActive) return false;
        if (weatherCancelled) return false;
      }

      return true;
    });

    // 1) Corruption DSP : puce d’état
    const dsp = list.filter(mm =>
      mm.source?.startsWith('CORRUPTION:') && mm.source?.includes(':DSP')
    );
    if (dsp.length) {
      out.push(dsp[0]); // une seule puce d’état
    } else {
      // 2) Sinon, fallback sur ta puce synthétique front (si le niveau est dispo)
      const statusChip = this.corruptionDisplayChip(p);
      if (statusChip) out.push(statusChip);
    }

    // 3) Potions DSP (FOCALISATION + nouvelles)
    const dspPotions = list.filter(mm =>
      mm.source?.startsWith('POTION:') &&
      mm.source?.includes(':DSP')
    );

    for (const pm of dspPotions) {
      const src = (pm as any).source as string;

      const isFoca = src.includes('FOCALISATION');
      const isLeech = src.includes('SANGSUE');
      const isRage = src.includes('RAGE');
      const isResi = src.includes('RESILIENCE');
      const isRap = src.includes('RAPIDITE');
      const isInv = src.includes('INVISIBILITE');
      const isInvul = src.includes('INVULNERABILITE');

      let shouldShow = false;

      if (isFoca) {
        // Focalisation : visible sur ATTACK + DEFENSE
        shouldShow = true;
      } else if (stat === 'ATTACK' && (isLeech || isRage || isRap)) {
        // Effets d’attaque
        shouldShow = true;
      } else if (stat === 'DEFENSE' && (isResi || isInv || isInvul)) {
        // Effets de défense
        shouldShow = true;
      }

      if (shouldShow && !out.includes(pm)) {
        out.push(pm);
      }
    }

    return out;
  }

  modsForEntityStat(id: string, stat: 'ATTACK' | 'DEFENSE'): RawStatMod[] {
    const p = this.getPlayer(id);
    if (p) return this.modsForStat(p, stat);

    // Monstre
    if (!this.game?.raidMods) return [];
    const list = this.game.raidMods[id] || [];

    return list.filter(m => {
      if (m.stat !== stat) return false;

      const src = m.source || '';
      const isWeather = src.startsWith('WEATHER:');
      const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');

      // pas de météo sur les monstres, ni CORRUPTION:...:ENG
      if (isWeather) return false;
      if (isCorruptEng) return false;

      return true;
    });
  }

  chipOf(m: UiStatMod): string {
    if (m.labelFr) return m.labelFr;
    if (m.stat === 'MULTIPLE') return 'affaibli'; // fallback
    if (m.stat === 'INSTABLE') return 'instable'; // fallback

    const short = m.stat === 'ATTACK' ? 'ATK' : 'DEF';
    const sign = m.amount > 0 ? `+${m.amount}` : `${m.amount}`;
    return `${short}${sign}`;
  }

  // Mods à AFFICHER (tous stats confondues)
  modsForDisplay(p?: SPlayer): RawStatMod[] {
    if (!p || !this.game?.raidMods) return [];
    const list = this.game.raidMods[p.id] || [];

    const weatherActive = this.isWeatherActive();
    const weatherCancelled = this.isWeatherCancelledForPlayer(p);

    const filtered = list.filter(m => {
      const src = m.source || '';
      const isWeather = src.startsWith('WEATHER:');
      const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');

      if (isCorruptEng) return false;

      if (isWeather) {
        if (!weatherActive) return false;
        if (weatherCancelled) return false;
      }

      return true;
    });

    return filtered;
  }

  private totalModForDisplay(pId: string, stat: 'ATTACK' | 'DEFENSE'): number {
    const p = this.getPlayer(pId);
    if (p) {
      const mods = this.modsForDisplay(p);
      return mods.reduce((sum, m) => sum + (m.stat === stat ? m.amount : 0), 0);
    }

    // Monstre : somme simple des mods pertinents (sans météo ni CORRUPTION:...:ENG)
    if (!this.game?.raidMods) return 0;
    const list = this.game.raidMods[pId] || [];

    return list.reduce((sum, m) => {
      if (m.stat !== stat) return sum;
      const src = m.source || '';
      const isWeather = src.startsWith('WEATHER:');
      const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');
      if (isWeather || isCorruptEng) return sum;
      return sum + m.amount;
    }, 0);
  }

  // Construit UN chip d’affichage : MULTIPLE (L1), INSTABLE (L2) ou SERVITEUR (L3)
  private corruptionDisplayChip(p?: SPlayer): UiStatMod | null {
    const lvl = p?.corruption;
    if (lvl === 1) {
      return {
        stat: 'MULTIPLE',
        amount: 0,
        source: 'CORRUPTION:L1:DSP',
        labelFr: 'affaibli',
        displayOnly: true
      };
    }
    if (lvl === 2) {
      return {
        stat: 'INSTABLE',
        amount: 0,
        source: 'CORRUPTION:L2:DSP',
        labelFr: 'instable',
        displayOnly: true
      };
    }
    if (lvl === 3) {
      return {
        stat: 'SERVITEUR',
        amount: 0,
        source: 'CORRUPTION:L3:DSP',
        labelFr: 'serviteur',
        displayOnly: true
      };
    }
    return null;
  }

  labelOrChip(m: RawStatMod): string {
    const s = (m as any).source || '';

    // DSP Corruption
    if (s.startsWith('CORRUPTION:') && s.includes(':DSP')) {
      if (s.includes(':MARK:')) {
        return 'marqué';
      }
      switch ((m as any).stat) {
        case 'MULTIPLE': return 'affaibli';
        case 'INSTABLE': return 'instable';
        case 'SERVITEUR': return 'serviteur';
      }
    }

    // Potions DSP
    if (s.startsWith('POTION:') && s.includes(':DSP')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'FOCALISATION': return 'focalisation';
        case 'SANGSUE': return 'sangsue';
        case 'RESILIENCE': return 'résilience';
        case 'RAGE': return 'rage';
        case 'RAPIDITE': return 'rapidité';
        case 'INVISIBILITE': return 'invisibilité';
        case 'INVULNERABILITE': return 'invulnérabilité';
      }
    }

    // ACTION DSP
    if (s.startsWith('ACTION:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'PROVOCATION': return 'provoqué';
        case 'BLESSED_STAKE': return 'pieu béni';
        case 'SACRED_ROSARY': return 'chapelet sacré';
      }
    }

    // Équipement DSP / ENG (EQUIP:...)
    if (s.startsWith('EQUIP:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'BLEED_WEAPON': return 'arme tranchante';
        case 'STUN_WEAPON': return 'arme contondante';
        case 'RANGED_WEAPON': return 'arme à distance';
        case 'HUNTER_ARMOR': return 'armure sacrée';
        case 'VAMPIRE_WEAPON': return 'arme vampirique';
        case 'VAMPIRE_ARMOR_T3': return 'armure nocturne';
      }
    }

    // Effets de coup (HIT:...)
    if (s.startsWith('HIT:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'BLEED_WEAPON': return 'saigne';
        case 'RANGED_WEAPON': return 'tenu à distance';
      }
    }

    if (s.startsWith('WEATHER:WIND:')) {
      return 'cyclone';
    }


    return (m as any).labelFr || this.chipOf(m);
  }

  titleFor(m: RawStatMod): string | null {
    const s = (m as any).source || '';

    // Corruption (DSP)
    if (s.startsWith('CORRUPTION:') && s.endsWith(':DSP')) {
      if (s.includes(':L1:')) return 'Attaque et défense diminuées de 1 (persiste entre les raids).';
      if (s.includes(':L2:')) return 'Peut se retourner contre ses alliés sur un jet défavorable.';
      if (s.includes(':L3:')) return 'Ce chasseur est un serviteur du vampire';
      if (s.includes(':MARK:')) {
        return 'Marque ténébreuse: ce chasseur gagne +1 corruption à chaque raid où il croise le vampire, jusqu’à purification.';
      }
      return 'Effet de corruption';
    }

    if (s.startsWith('WEATHER:')) {
      // Cas spé : Vent violent DSP avec distinction côté chasseur / domaine
      if (s.startsWith('WEATHER:WIND:HUNTER')) {
        return 'Réparations du village: ce chasseur perd 1 ressource aléatoire (bois, fer ou pierre).';
      }
      if (s.startsWith('WEATHER:WIND:VAMP')) {
        return 'Réparations du domaine: perte de ressources aléatoires pour chaque construction.';
      }

      // Fallback générique (comme avant)
      const g = this.game;
      const w = g?.weather;
      if (!w) return 'Effets météo';

      const parts = s.split(':');
      const code = parts.length >= 2 ? parts[1] : null; // "RAIN", "FULL_MOON", ...

      // Si on a une météo secondaire (Cataclysme)
      if (code && w.secondaryStatus) {
        // 1) Statut principal
        if (code === w.status && w.nameFr) {
          return w.descFr
            ? `${w.nameFr} — ${w.descFr}`
            : w.nameFr;
        }

        // 2) Statut secondaire
        if (code === w.secondaryStatus && w.secondaryNameFr) {
          return w.secondaryDescFr
            ? `${w.secondaryNameFr} — ${w.secondaryDescFr}`
            : w.secondaryNameFr;
        }
      }

      // Fallback MONO météo (comportement ancien)
      if (w.nameFr && w.descFr) {
        return `${w.nameFr} — ${w.descFr}`;
      }
      if (w.nameFr) return w.nameFr;

      return 'Effets météo';
    }

    if (s.startsWith('POTION:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltips: Record<string, string> = {
        FORCE: 'Augmente de +1 le dé d’attaque.',
        ENDURANCE: 'Augmente de +1 le dé de défense.',
        VIE: 'Se soigner de +10 PV.',
        FOCALISATION: 'Lancer 2 dés lors des combat et garder le meilleur.',
        SANGSUE: 'Se soigner d’un montant égal aux dégats infligés.',
        RESILIENCE: 'Double la défense.',
        RAGE: 'Double l’attaque.',
        RAPIDITE: 'Attaque x2.',
        INVISIBILITE: 'L\'adversaire ne jette pas de dé de défense.',
        INVULNERABILITE: 'Insensible aux dégâts.',
      };
      return tooltips[type] ?? null;
    }

    if (s.startsWith('ACTION:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltips: Record<string, string> = {
        PROVOCATION: 'Forcé d\'attaquer un seul chasseur.',
        BLESSED_STAKE: 'Arme secondaire sacrée à utilisation unique.',
        SACRED_ROSARY: 'Objet à utilisation unique qui annule une morsure réussie.'
      };
      return tooltips[type] ?? null;
    }

    // Effets d'équipement (EQUIP:...)
    if (s.startsWith('EQUIP:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltipsEquip: Record<string, string> = {
        BLEED_WEAPON: 'Cette arme provoque un saignement lorsqu’elle inflige des dégâts.',
        STUN_WEAPON: 'Cette arme peut étourdir sa cible et réduire son attaque au prochain tour.',
        RANGED_WEAPON: 'Cette arme peut tenir le vampire à distance si le chasseur réalise un succès critique.',
        HUNTER_ARMOR: 'Cette armure réduit les risques liés aux morsures du vampire.',
        VAMPIRE_WEAPON: 'Cette arme peut soigner le vampire lorsqu’il inflige des dégâts.',
        VAMPIRE_ARMOR_T3: 'Cette armure permet de se dématerialiser et esquiver une attaque critique.'
      };
      return tooltipsEquip[type] ?? 'Effet d’équipement';
    }

    // Effets déclenchés par un coup (HIT:...)
    if (s.startsWith('HIT:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltipsHit: Record<string, string> = {
        BLEED_WEAPON: 'Ce personnage saigne et subira des dégâts supplémentaires en phase 4.',
        RANGED_WEAPON: 'Cette attaque a été repoussée par une arme à distance.',
        STUN_WEAPON: 'Ce personnage est étourdi et sa prochaine attaque est réduite.'
      };
      return tooltipsHit[type] ?? 'Effet de coup spécial';
    }

    // (Garde le reste de tes cas, ex. météo si tu l’avais déjà ajouté)
    return null;
  }

  private isWeatherActive(): boolean {
    const g = this.game;
    return !!g && g.weather?.roll != null && !!g.weather.status;
  }

  // === Helpers combat ===
  nameOrId(id: string): string {
    return this.entityDisplayName(id);
  }

  /** SPECTATE & ROLL: affiche le nom joueur dans sa colonne */
  modalTitle(r: any): string {
    const atkPlayer = this.getPlayer(r.attackerId);
    const defPlayer = this.getPlayer(r.defenderId);

    const atkMonster = this.getMonster(r.attackerId);
    const defMonster = this.getMonster(r.defenderId);

    const isClone = !!r.cloneAttack; // 🔹 flag envoyé par le back

    // --- CAS CLONE DES OMBRES ---
    if (isClone && atkPlayer && defPlayer) {
      const atkIsVamp = atkPlayer.role === 'VAMPIRE';
      const defIsHunter = defPlayer.role === 'HUNTER';

      if (atkIsVamp && defIsHunter) {
        const hunterName = this.entityDisplayName(r.defenderId);
        return `Clone d'ombre vs ${hunterName}`;
      }
    }

    // Cas 1 : EXACTEMENT un monstre dans le duel → on met le joueur à gauche
    if (atkMonster && !defMonster && defPlayer) {
      return `${this.entityDisplayName(r.defenderId)} vs ${this.entityDisplayName(r.attackerId)}`;
    }
    if (defMonster && !atkMonster && atkPlayer) {
      return `${this.entityDisplayName(r.attackerId)} vs ${this.entityDisplayName(r.defenderId)}`;
    }

    // Cas 2 : duel 100% joueurs → logique vampire/hunter comme avant
    if (atkPlayer && defPlayer) {
      const vampireLeft = atkPlayer.role === 'VAMPIRE';
      const vampireName = vampireLeft
        ? this.entityDisplayName(r.attackerId)
        : this.entityDisplayName(r.defenderId);
      const hunterName = vampireLeft
        ? this.entityDisplayName(r.defenderId)
        : this.entityDisplayName(r.attackerId);

      return vampireLeft
        ? `${vampireName} vs ${hunterName}`
        : `${hunterName} vs ${vampireName}`;
    }

    // Fallback : juste "A vs B"
    return `${this.entityDisplayName(r.attackerId)} vs ${this.entityDisplayName(r.defenderId)}`;
  }

  /* non utilisé pour le moment 
  /*
  get readyGauge(): string {
    const g: any = this.game || {};
    const ready = (typeof g.readyCount === 'number') ? g.readyCount : (g.readyForPhase3?.length || 0);
    const total = (typeof g.readyTotal === 'number') ? g.readyTotal : (g.players?.length || 0);
    return `${ready}/${total}`;
  }
  */

  trackById(_i: number, p: SPlayer) { return p.id; }

  get centerHasAnything(): boolean {
    const g: any = this.game;
    return ((g?.center?.length || 0)
      + (g?.clonesLocations?.length || 0)
      + (g?.trackerHunters?.length || 0)) > 0;
  }

  get currentCombat() {
    return this.game?.currentCombat || null;
  }
  get waitingForMyRoll(): 'ATTACK' | 'DEFENSE' | null {
    const r = this.currentCombat; if (!r) return null;
    if (r.attackerId === this.meId && (r.attackerRoll == null)) return 'ATTACK';
    if (r.defenderId === this.meId && (r.defenderRoll == null)) return 'DEFENSE';
    return null;
  }
  get showRollModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.waitingForMyRoll && !!this.currentCombat;
  }
  get showSpectatorModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.currentCombat && !this.waitingForMyRoll;
  }
  getPlayer(id: string): SPlayer | undefined {
    return this.game?.players.find(p => p.id === id);
  }
  roleColorOf(p?: SPlayer): 'red' | 'blue' {
    return (p?.role === 'VAMPIRE' || p?.role === 'SERVANT') ? 'red' : 'blue';
  }
  getRole(p?: SPlayer): 'VAMPIRE' | 'HUNTER' | 'SERVANT' | undefined {
    return p?.role;
  }
  diceAsset(dice: string | undefined, color: 'red' | 'blue' | 'purple'): string {
    const d = (dice || 'D6').toLowerCase();

    // Monstre => dossier monster + dés violets
    if (color === 'purple') {
      return `/assets/monster/${d}-purple.png`;
    }

    // comportement existant
    return `/assets/dices/${d}-${color}.png`;
  }
  roleIcon(role?: 'VAMPIRE' | 'HUNTER' | 'SERVANT' | undefined, name?: 'sword' | 'armor'): string {
    return role === 'SERVANT' ? `/assets/icons/HUNTER-${name}.png` : `/assets/icons/${role}-${name}.png`;
  }

  getMonster(id: string): SMonster | undefined {
    const g = this.game as GameSnapshot | undefined;
    const list = g?.monsters ?? [];
    return list.find(m => m.id === id);
  }

  entityDisplayName(id: string): string {
    const p = this.getPlayer(id);
    if (p) return p.username;

    const m = this.getMonster(id);
    if (m) {
      switch (m.type) {
        case 'REVENANT': return 'Revenant';
        case 'GARGOYLE': return 'Gargouille';
        case 'ABERRATION': return 'Aberration';
        default: return 'Créature';
      }
    }
    return id;
  }

  entityAttackDice(id: string): string | undefined {
    const p = this.getPlayer(id);
    if (p) return p.attackDice;
    const m = this.getMonster(id);
    return m?.attackDice;
  }

  entityDefenseDice(id: string): string | undefined {
    const p = this.getPlayer(id);
    if (p) return p.defenseDice;
    const m = this.getMonster(id);
    return m?.defenseDice;
  }

  entityColor(id: string): 'red' | 'blue' | 'purple' {
    const p = this.getPlayer(id);
    if (p) return this.roleColorOf(p);

    return 'purple';
  }

  private servantEquipSide(p: SPlayer, name: 'sword' | 'armor'): 'HUNTER' | 'VAMPIRE' {
    const code = (name === 'sword') ? p.weapon : p.armor;
    if (!code) return 'VAMPIRE'; // pas d'équipement -> servant utilise équipement de base vampire

    const c = code.toUpperCase();

    // tes codes back sont du style H_WEAPON..., V_ARMOR..., etc.
    if (c.startsWith('V_') || c.includes('V_WEAPON') || c.includes('V_ARMOR')) return 'VAMPIRE';
    if (c.startsWith('H_') || c.includes('H_WEAPON') || c.includes('H_ARMOR')) return 'HUNTER';

    // fallback : servant = côté vampire
    return 'VAMPIRE';
  }

  entityRoleIcon(id: string, name: 'sword' | 'armor'): string {
    const p = this.getPlayer(id);
    if (p) {
      console.log("player", p);
      if (p.role === 'SERVANT') {
        const side = this.servantEquipSide(p, name);
        console.log("servantEquipSide", side);
        return `/assets/icons/${side}-${name}.png`;
      }
      return `/assets/icons/${p.role}-${name}.png`;
    }

    if (this.getMonster(id)) {
      return `/assets/monster/MONSTER-${name}.png`;
    }

    return `/assets/icons/VAMPIRE-${name}.png`;
  }

  entityHaloIcon(id: string, type: 'attack' | 'defense') {
    const p = this.getPlayer(id);

    if (p?.role === 'VAMPIRE') {
      if (type === 'attack') return 'round';
      if (type === 'defense') return 'oval';
    }
    if (p?.role === 'SERVANT') {
      // Servants use round halo for attack (like vampires)
      if (type === 'attack') return 'round';
      return 'oval'
    }
    if (p?.role === 'HUNTER') return 'oval';

    // Monstre
    if (this.getMonster(id)) return 'oval';

    // fallback
    return 'round';
  }

  private locationOf(playerId?: string): string | null {
    if (!playerId || !this.game) return null;

    // this.game.center = tableau des cartes posées au centre
    const entry = this.game.center?.find(c => c.playerId === playerId);
    return entry ? entry.card : null;
  }

  private playersOnLocation(loc: string): SPlayer[] {
    const g = this.game;
    if (!g) return [];
    return g.players.filter(p => this.locationOf(p.id) === loc);
  }

  // quantité d'une ressource 'res' pour un joueur p
  resOf(p: SPlayer | undefined, res: string): number {
    if (!p) return 0;
    switch (res) {
      case 'gold': return p.gold;
      case 'silver': return p.silver;
      case 'souls': return p.souls;
      case 'wood': return p.wood;
      case 'herbs': return p.herbs;
      case 'stone': return p.stone;
      case 'iron': return p.iron;
      case 'water': return p.water;
      default: return 0;
    }
  }

  // quantité déjà proposée dans mon offre pour 'res'
  offerQty(res: string): number {
    return this.myOffer && this.myOffer[res] ? this.myOffer[res] : 0;
  }

  rollNow() {
    if (!this.waitingForMyRoll || this.isRolling) return;

    this.isRolling = true;

    if (!this.game) return;
    this.api.rollDice(this.game.id).subscribe({
      next: () => this.isRolling = false,
      error: e => {
        this.isRolling = false;

        const msg = e?.error?.message || e?.message || '';
        if (msg === 'bite pending') {

          this.api.getGame(this.gameId).subscribe({
            next: g => this.game = g,
            error: err => this.showError(err)
          });
          return;
        }

        this.showError(e);
      }
    });
  }

  // --- Assets helpers (cœurs + cartes équipement) ---
  heartIconFor(p?: SPlayer): string {
    const role = p?.role.toUpperCase();
    if (role === 'SERVANT') return `/assets/icons/VAMPIRE-hearth.png`;
    else return `/assets/icons/${role}-hearth.png`;
  }

  // Image de fond une fois la météo tirée
  setImageBackground(modal: 'weather' | 'location' | 'bite' | 'corruption' | 'construction' | 'construire'): string | null {

    if (modal === 'weather') {
      const ws = this.game?.weather?.status;
      if (!ws || this.game?.weather?.roll == null) return null;
      return `url('/assets/weather/bg-${ws.toLowerCase()}.png')`;
    }
    if (modal === 'location') {
      const loc = this.game?.currentCombat?.location?.toLowerCase();
      return loc ? `url('/assets/locations/${loc}.png')` : 'none';
    }
    if (modal === 'corruption') {
      return `url('/assets/corruption/corrupted.png')`;
    }
    if (modal === 'bite') {
      return `url('/assets/corruption/bite.png')`;
    }
    if (modal === 'construction') {
      if (this.buildChoice === 'SAWMILL') return "url('/assets/locations/forest.png')";
      if (this.buildChoice === 'MINE') return "url('/assets/locations/quarry.png')";
      else return "url('/assets/locations/manor.png')"
    }
    if (modal === 'construire') return "url('/assets/actions/build.png')";

    return 'none';
  }

  // chemin de l'icône météo
  weatherIconSrc(ws?: string | null): string {
    if (!ws) return '';

    if (ws.includes('WIND')) return `/assets/weather/icon-wind.png`;
    if (ws.includes('BLOOD_MOON')) return `/assets/weather/icon-red_moon.png`;
    return `/assets/weather/icon-${ws.toLowerCase()}.png`;
  }

  private weatherCodeFromSource(m: RawStatMod): string | null {
    const src = (m as any).source || '';
    if (!src.startsWith('WEATHER:')) return null;

    // "WEATHER:RAIN" -> "RAIN"
    const code = src.substring('WEATHER:'.length);
    return code || null;
  }


  weatherIconSrcForMod(m: RawStatMod): string {
    const code = this.weatherCodeFromSource(m) || this.game?.weather?.status || null;
    return this.weatherIconSrc(code);
  }

  modIconSrc(source: string): string {
    const fallback = '/assets/icons/action-hunter-icon.png';
    if (!source) return fallback;

    if (source.startsWith('ACTION:')) {
      const parts = source.split(':');
      const code = parts[1] || '';

      // Liste des actions qui sont forcément jouées par les chasseurs
      const hunterActions = [
        'NET',
        'PIT',
        'PROVOCATION',
        'AMBUSH',
        'LONELY'
      ];

      const isHunter = hunterActions.includes(code);

      if (code === 'BLESSED_STAKE') return '/assets/icons/HUNTER-sword.png';
      if (code === 'SACRED_ROSARY') return '/assets/icons/HUNTER-armor.png';

      return isHunter
        ? '/assets/icons/action-hunter-icon.png'
        : '/assets/icons/action-vampire-icon.png';
    }

    if (source.startsWith('EQUIP:')) {
      const parts = source.split(':');
      const type = (parts[1] || '').toUpperCase();

      if (type === 'BLEED_WEAPON'
        || type === 'STUN_WEAPON'
        || type === 'RANGED_WEAPON') {
        return '/assets/icons/HUNTER-sword.png';
      }

      if (type === 'HUNTER_ARMOR') {
        return '/assets/icons/HUNTER-armor.png';
      }

      if (type === 'VAMPIRE_WEAPON') {
        return '/assets/icons/VAMPIRE-sword.png';
      }

      if (type === 'VAMPIRE_ARMOR' || type === 'VAMPIRE_ARMOR_T3') {
        return '/assets/icons/VAMPIRE-armor.png';
      }

      return fallback;
    }

    if (source.startsWith('HIT:BLEED_WEAPON')) {
      return '/assets/icons/bleed.png';
    }
    if (source.startsWith('HIT:STUN_WEAPON')) {
      return '/assets/icons/stun.png';
    }
    if (source.startsWith('HIT:RANGED_WEAPON')) {
      return '/assets/icons/range.png';
    }

    return fallback;
  }

  actionBackgroundSrc(mode: 'EAU_BENITE' | 'NET' | 'PIT' | 'INCENDIAIRE' | 'PROVOCATION' | 'AMBUSH' | 'LONELY' | 'BLESSED_STAKE' | 'CHARISMATIQUE' | 'MARCHAND_ITINERANT' | 'MARCHAND_BONUS_BUY' | 'PRESENCE_ECRASANTE' | 'CATACLYSME' | 'CLONES_OMBRE' | 'IMAGE_MIROIR_SETUP' | 'IMAGE_MIROIR_RESOLVE' | 'ECLIPSE' | 'BLOOD_MOON' | 'VOILE_DE_BRUME' | 'FAIM_IRREPRESSIBLE' | 'MARQUE_TENEBREUSE' | 'AFFAIBLISSEMENT_OCCULTE' | 'PASSAGE_SECRET' | 'AVIDITE_NOCTURNE' | null): String {
    if (mode === 'NET') return 'url(/assets/actions/net.png)';
    if (mode === 'PIT') return 'url(/assets/actions/traphole.png)';
    if (mode === 'INCENDIAIRE') return 'url(/assets/actions/burn.png)';
    if (mode === 'PROVOCATION') return 'url(/assets/actions/taunt.png)';
    if (mode === 'AMBUSH') return 'url(/assets/actions/ambush.png)';
    if (mode === 'LONELY') return 'url(/assets/actions/lonely.png)';
    if (mode === 'BLESSED_STAKE') return 'url(/assets/actions/blessed_stake.png)';
    if (mode === 'CHARISMATIQUE') return 'url(/assets/actions/charismatic.png)';
    if (mode === 'MARCHAND_ITINERANT' || mode === 'MARCHAND_BONUS_BUY') return 'url(/assets/actions/traveling_merchant.png)';
    if (mode === 'PRESENCE_ECRASANTE') return 'url(/assets/actions/overwhelming_presence.png)';
    if (mode === 'CATACLYSME') return 'url(/assets/actions/cataclysm.png)';
    if (mode === 'CLONES_OMBRE') return 'url(/assets/actions/shadow_clones.png)';
    if (mode === 'IMAGE_MIROIR_SETUP' || mode === 'IMAGE_MIROIR_RESOLVE') return 'url(/assets/actions/miror_image.png';
    if (mode === 'ECLIPSE') return 'url(/assets/actions/eclipse.png';
    if (mode === 'BLOOD_MOON') return 'url(/assets/actions/redmoon.png';
    if (mode === 'VOILE_DE_BRUME') return 'url(/assets/actions/veil_of_mist.png';
    if (mode === 'FAIM_IRREPRESSIBLE') return 'url(/assets/actions/irrepressible_hunger.png';
    if (mode === 'MARQUE_TENEBREUSE') return 'url(/assets/actions/dark_mark.png';
    if (mode === 'AFFAIBLISSEMENT_OCCULTE') return 'url(/assets/actions/occult_weakening.png';
    if (mode === 'PASSAGE_SECRET') return 'url(/assets/actions/secret_passage.png';
    if (mode === 'AVIDITE_NOCTURNE') return 'url(/assets/actions/nocturnal_greed.png)';
    if (mode === 'EAU_BENITE') return 'url(/assets/actions/holy_water.png';
    return '';
  }

  // --- HP helpers (pour une jauge plus tard) ---
  maxHpOf(p: SPlayer): number {
    if (p.role === 'VAMPIRE') {
      const hunters = (this.game?.players ?? []).filter(x => x.role === 'HUNTER').length;
      return 20 + hunters * 10;
    }
    return 20;
  }
  hpPercent(p: SPlayer): number {
    const max = this.maxHpOf(p);
    const cur = Math.max(0, Math.min(p.hp ?? 0, max));
    return Math.round((cur / max) * 100);
  }

  // Helpers (lecture via players[])
  get me(): SPlayer | undefined {
    return this.game?.players.find(p => p.id === this.meId);
  }
  get isVampireSide(): boolean {
    return this.me?.role === 'VAMPIRE' || this.me?.role === 'SERVANT';
  }
  get isHunter() { return this.me?.role === 'HUNTER'; }
  get hasVampire(): boolean {
    return !!this.game && this.game.players.some(p => p.role === 'VAMPIRE');
  }
  get vampirePlayer(): SPlayer {
    return this.game!.players.find(p => p.role === 'VAMPIRE')!;
  }
  get hunterPlayers(): SPlayer[] {
    const list = (this.game?.players ?? []).filter(
      p => (p.role === 'HUNTER') && p.id !== this.meId
    );
    // garder les serviteurs en premier puis les chasseurs
    // return list.sort((a, b) => (a.role === b.role ? 0 : a.role === 'SERVANT' ? -1 : 1));
    return list;
  }
  get HunterAndServantPlayers(): SPlayer[] {
    const list = (this.game?.players ?? []).filter(
      p => (p.role === 'HUNTER' || p.role === 'SERVANT') && p.id !== this.meId
    );
    // garder les serviteurs en premier puis les chasseurs
    // return list.sort((a, b) => (a.role === b.role ? 0 : a.role === 'SERVANT' ? -1 : 1));
    return list;
  }
  get canPlaySelection(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;

    // Lieu sélectionné
    if (this.selectedLocation) {
      if (!this.canPlayLocation(this.selectedLocation)) return false;

      const phase = g.phase;
      if (me.role === 'HUNTER') {
        return phase === 'PHASE1';
      }
      if (me.role === 'VAMPIRE' || me.role === 'SERVANT') {
        return phase === 'PHASE2';
      }
      return false;
    }

    // Action sélectionnée
    if (this.selectedAction) {
      if (this.isMeHunterUnstablePending()) return false;
      return this.canUseActionNow(this.selectedAction);
    }

    // Potion / élixir sélectionné
    if (this.selectedPotion) {
      if (this.isMeHunterUnstablePending()) return false;
      return this.canUsePotionNow(this.selectedPotion);
    }

    return false;
  }

  get isMeVampire(): boolean {
    return this.me?.role === 'VAMPIRE';
  }

  usernameOf(id: string): string {
    if (!this.game) return id;
    const p = this.game.players.find(x => x.id === id);
    if (p?.username) return p.username;
    if (id === this.meId && this.username) return this.username;
    return id;
    // (plus tard, on pourra faire une vraie map id->username côté back si besoin)
  }

  isCurrent(_p: SPlayer) { return false; } // on branchera plus tard

  labelLocation(c: string) {
    switch (c) {
      case 'forest': return 'Forêt';
      case 'quarry': return 'Carrière';
      case 'lake': return 'Lac';
      case 'manor': return 'Manoir';
      case 'sawmill': return 'Scierie';
      case 'mine': return 'Mine';
      case 'library': return 'Bibliothèque';
      case 'laboratory': return 'Laboratoire';
      case 'ballroom': return 'Salle de bal';
      case 'altar': return 'Autel';
      case 'forge': return 'Forge';
      default: return c;
    }
  }

  /** INIT
   * fait 1 refresh au montage, puis s'abonne au WebSocket.
   */
  ngOnInit() {
    this.route.paramMap.subscribe(pm => {
      const id = pm.get('id');
      if (!id) { this.showError('Identifiant de game inconnu.'); return; }

      this.unsubscribeGameTopic?.();
      this.gameId = id;
      // 1) D’abord WS
      this.unsubscribeGameTopic = this.live.subscribeGame(this.gameId, ev => this.onLiveEvent(ev));

      // 2) Puis snapshot initial (autorité)
      this.api.getGame(this.gameId).subscribe({
        next: snap => {
          const previous = null;

          this.game = snap;

          if ((snap as any).status === 'CREATED' || (snap as any).status === 'STARTING') {
            this.router.navigate(['/lobby']);
            return;
          }

          // === Sync waitingDone sur reload ===
          const meId = this.meId;
          if (snap.phase === 'PHASE4') {
            this.waitingDone = !!(meId && snap.readyForNextRaid?.includes(meId));
          } else {
            // Dans toutes les autres phases, on reset proprement
            this.waitingDone = false;
          }

          this.handleWeatherReveal(snap);

          // Instables
          this.recomputeUnstableChoices();

          // Modale action
          this.syncActionFromSnapshot(snap);
          this.syncMerchantUiFromSnapshot(snap);

          // Effets de lieu (Bibliothèque, etc.)
          this.syncLocationEffectFromSnapshot(snap, previous);

          // Timer PREPHASE3 si on arrive "en cours de route" ET PAS d'effet de lieu en cours
          if (snap.phase === 'PREPHASE3') {
            this.prephase3AdvanceSent = false;
            if (!snap.locationEffectPending) {
              this.startPrephaseTimer();
            } else {
              this.stopPrephaseTimer();
            }
          } else {
            this.stopPrephaseTimer();
          }

          // ouvrir/fermer la modale + timer si on arrive en PHASE4
          this.syncShopVisibilityFromSnapshot();
        },
        error: err => { this.showError(err); }
      });
    });
  }

  /** DESTROY
   * on se désabonne du WS et on nettoie les timeouts météo.
   */
  ngOnDestroy() {
    this.unsubscribeGameTopic?.(); // <-- à la place du clearInterval
    if (this.weatherWaitTimer) clearTimeout(this.weatherWaitTimer);
    if (this.weatherTimer) clearTimeout(this.weatherTimer);
    if (this.weatherPostTimer) clearTimeout(this.weatherPostTimer);
    if (this.actionTimeoutId) clearTimeout(this.actionTimeoutId);
    this.stopPrephaseTimer();

  }

  /** onLiveEvent
   *  Reçoit les événements pushés par le backend.
   *  - WEATHER_ROLLED: on fait 1 GET (pour récupérer roll + messages) puis on lance la séquence météo côté front.
   *  - PHASE_CHANGED: on fait 1 GET pour refléter la nouvelle phase.
   *  - MESSAGE: on pousse le texte dans le flux local.
   *
   * l’info arrive en temps réel → 1 GET ponctuel → rendu UI.
   */
  private onLiveEvent(event: GameEvent) {
    // (optionnel) anti-doublon
    const key = this.stableEventKey(event);
    if (this.seenEventKeys.has(key)) return;
    this.seenEventKeys.add(key);

    switch (event.type) {
      case 'PHASE_CHANGED': {
        const next = (event?.payload?.phase as Phase | undefined) ?? undefined;

        // Gestion du timer PREPHASE3 AVANT le snapshot détaillé
        if (next === 'PREPHASE3') {
          if (this.game?.locationEffectPending) {
            this.stopPrephaseTimer();
            this.prephase3AdvanceSent = false;
          } else {
            this.prephase3AdvanceSent = false;
            this.startPrephaseTimer();
          }
        } else {
          this.stopPrephaseTimer();
        }

        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;

            const prevPhase = previous?.phase as Phase | undefined;
            this.emitPhaseBubble(prevPhase, g);

            const me = g?.players?.find(p => p.id === this.meId);
            const isDeadNow = !!me && !me.leftGame && me.hp <= 0;

            if (!g || g.status !== 'ACTIVE') {
              this.deathModalOpen = false;
              this.deathModalSeen = false;
            } else if (!isDeadNow) {
              // vivant => reset
              this.deathModalOpen = false;
              this.deathModalSeen = false;
            } else {
              // mort + game active
              if (!this.deathModalSeen) {
                this.deathModalOpen = true;   // open une seule fois
                this.deathModalSeen = true;   // verrouille immédiatement
              } else {
                // déjà “vu” => ne rien faire
              }
            }


            this.syncActionFromSnapshot(g);
            this.syncMerchantUiFromSnapshot(g);

            // recalculer les choix instables à partir du snapshot
            this.recomputeUnstableChoices();

            // Effets de lieu (Bibliothèque, etc.)
            this.syncLocationEffectFromSnapshot(g, previous);

            if (g.phase !== 'PHASE1') this.phase2AdvanceSent = false;

            // Début de raid → réarme météo et resets usuels
            if (g.phase === 'PHASE0') {
              this.weatherBgActive = false;
              this.weatherModalHold = false;
              this.lastWeatherRollSeen = null;
              this.weatherAdvanceSent = false;
              this.hasSkipped = false;
              this.prephase3AdvanceSent = false;
              this.phase4AdvanceSent = false;
              this.toPhase0AdvanceSent = false;
              this.game.currentBite = null;
              this.hadIncendiaireThisPrephase = false;

              this.handleWeatherReveal(g);
            } else {
              this.weatherModalHold = false; // ferme si on n’est plus en PHASE0
            }

            // Robustesse si 'next' manquait dans l'event
            if (!next) {
              if (g.phase === 'PREPHASE3') {
                if (g.locationEffectPending) {
                  this.stopPrephaseTimer();
                  this.prephase3AdvanceSent = false;
                } else {
                  this.prephase3AdvanceSent = false;
                  this.startPrephaseTimer();
                }
              } else {
                this.stopPrephaseTimer();
              }
              if (g.phase !== 'PHASE3') this.game.currentBite = null;
            }

            if (g.phase === 'PHASE3') {
              // Si aucun piège en cours (currentAction null),
              // tu laisses ta logique existante décider si on enchaîne.
              if (!g.currentAction) {
                this.maybeAdvanceToPhase4EndOfRaid();
              }
            }

            // Ouvrir/fermer la modale + timer si on bascule vers/depuis PHASE4
            this.syncShopVisibilityFromSnapshot();

            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'DRAFT_UPDATED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;
            this.syncLocationEffectFromSnapshot(g, previous);
          },
          error: e => this.showError(e),
        });
        break;
      }

      case 'LOBBY_UPDATED': {
        // si status == CREATED, refresh pour voir les pseudos/players en live
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'WEATHER_ROLLED': {
        const w = {
          roll: event.payload.roll,
          status: event.payload.status,
          nameFr: event.payload.nameFr,
          descFr: event.payload.descFr,
        };

        if (!this.game) {
          // Pas encore de snapshot → récupère tout et lance l’anim proprement
          this.api.getGame(this.gameId).subscribe({
            next: g => {
              this.game = g;
              // (optionnel) sécurité si le back a poussé WEATHER_ROLLED une micro-seconde avant d’écrire le roll
              if (!g.weather || g.weather.roll == null) {
                this.game = { ...g, weather: w } as GameSnapshot;
              }
              this.handleWeatherReveal(this.game!);
              this.bumpHistoryScroll();
            },
            error: e => this.showError(e)
          });
        } else {
          // Snapshot déjà présent → patch léger + anim
          this.game = { ...(this.game as any), weather: w } as GameSnapshot;
          this.handleWeatherReveal(this.game!);

          // Sync propre un peu après pour récupérer mods/messages exacts
          setTimeout(() => {
            this.api.getGame(this.gameId).subscribe({
              next: g => { this.game = g; this.bumpHistoryScroll(); },
              error: e => this.showError(e)
            });
          }, 200);
        }
        break;
      }

      case 'MESSAGE': {
        this.game?.messages?.push(event.payload.text);
        this.bumpHistoryScroll();
        break;
      }

      case 'LOCATION_SELECTED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'READY_UPDATED': {
        const g: any = this.game || {};
        g.readyForPhase3 = Array.isArray(g.readyForPhase3) ? g.readyForPhase3 : [];
        const pid = event.payload.playerId;
        if (!g.readyForPhase3.includes(pid)) g.readyForPhase3.push(pid);
        (g as any).readyCount = event.payload.ready;
        (g as any).readyTotal = event.payload.total;
        this.game = { ...(g as GameSnapshot) };

        this.maybeAutoAdvanceToPhase3();
        break;
      }

      case 'RAID_MODS_UPDATED': {
        // Simple: resynchronise l’état complet
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'CENTER_REVEALED': {
        // 1) Flip local immédiat (animation)
        this.flipCenterFaceUpLocally();

        // 2) Petit hold UX, puis sync complète
        setTimeout(() => {
          this.api.getGame(this.gameId).subscribe({
            next: g => this.game = g,
            error: e => this.showError(e)
          });
        }, this.CENTER_FLIP_HOLD_MS);

        break;
      }

      /*
      case 'DICE_ROLLED': {
        // patch léger si le roll concerne le round courant; sinon GET
        const r = this.game?.currentCombat;
        if (!this.game || !r || r.id !== event.payload.roundId) {
          this.api.getGame(this.gameId).subscribe({
            next: g => this.game = g,
            error: e => this.showError(e)
          });
          break;
        }
        const side = event.payload.side; // "ATTACK"|"DEFENSE"
        const val  = event.payload.roll as number;
        const patched = { ...(this.game as any) };
        if (side === 'ATTACK') patched.currentCombat.attackerRoll = val;
        if (side === 'DEFENSE') patched.currentCombat.defenderRoll = val;
        this.game = patched as GameSnapshot;
        break;
      }
      */

      case 'DICE_ROLLED': {
        // Toujours un GET : on récupère attackerFirstRoll / defenderFirstRoll / rolls finaux proprement
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'COMBAT_RESOLVED': {
        if (this.game) {
          const def = this.game.players.find(p => p.id === event.payload.defenderId);
          if (def) def.hp = event.payload.defenderHp;
          const r = (this.game as any).currentCombat;
          if (r) r.breakdownLines = event.payload.breakdown || [];
          this.game = { ...(this.game as GameSnapshot) };
        }
        setTimeout(() => {
          this.api.combatContinue(this.gameId).subscribe({
            next: _ => {
              // Resync propre :
              this.api.getGame(this.gameId).subscribe({
                next: g => {
                  const previous = this.game;
                  this.game = g;

                  this.resetHarvestBubbleIfPhaseChanged(previous, g);
                  this.maybeEmitHarvestBubble(g);

                  this.bumpHistoryScroll();
                  this.syncActionFromSnapshot(g);
                  this.maybeAdvanceToPhase4EndOfRaid();
                },
                error: e => this.showError(e)
              });
            },
            error: e => this.showError(e)
          });
        }, this.SPECTATE_HOLD_MS);
        break;
      }

      case 'BITE_STARTED': {
        // petit délai de lecture avant d’ouvrir la modale
        this.biteNotBeforeMillis = Date.now() + this.SPECTATE_HOLD_MS;
        // récupère currentBite depuis le snapshot
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'BITE_ROLLED': {
        this.isSacredRosaryUsed = event?.payload?.isSacredRosaryUsed;

        // Patch local immédiat du roll pour que le modal puisse l'afficher
        // même si le snapshot arrive avec currentBite=null
        if (this.game && event?.payload?.roll != null) {
          const gAny = this.game as any;
          const currentBite = gAny.currentBite ? { ...gAny.currentBite } : {};
          currentBite.roll = event.payload.roll;

          this.game = {
            ...gAny,
            currentBite
          } as GameSnapshot;
        }

        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;

            const b: any = (g as any)?.currentBite;
            const isResolved = !!b?.resolvedAtMillis; // en armure argent, c'est null

            if (isResolved) {
              setTimeout(() => {
                this.api.combatContinue(this.gameId).subscribe({
                  error: e => this.showError(e)
                });
              }, this.SPECTATE_HOLD_MS);
            }
            // sinon: on laisse la modale ouverte, la cible lancera le d4
          },
          error: e => this.showError(e)
        });

        break;
      }

      case 'BITE_RESOLVED': {
        // le back a déjà remis currentBite = null ; on resynchronise
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;

            this.resetHarvestBubbleIfPhaseChanged(previous, g);
            this.maybeEmitHarvestBubble(g);
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'UNSTABLE_ASSIGNED': {
        const kind = event?.payload?.kind as string;   // "TARGET" | "HARVEST" | "NOTHING"
        const unstableId = event?.payload?.unstableId as string | undefined;

        // Stratégie simple et robuste : on resynchronise entièrement
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.recomputeUnstableChoices(); // met à jour la modale
            // Optionnel: si plus aucun choix restant, la modale se ferme automatiquement via showUnstableModal()
          },
          error: e => this.showError(e)
        });

        break;
      }
      case 'POTION_BOUGHT':
      case 'ACTION_BOUGHT':
      case 'SILVER_BOUGHT':
      case 'STUFF_BOUGHT':
      case 'HOLY_WATER_BOUGHT':
      case 'TRACKING_BOUGHT':
      case 'RESOURCE_SOLD':
      case 'TRANSMUTED': {
        // stratégie simple: GET de synchro
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            // si on est en phase 4, s’assurer que la modale est bien ouverte et timer à jour
            this.syncShopVisibilityFromSnapshot();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'POTION_USED': {
        this.toastPotionUsed(event.payload);

        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'INFRA_BUILT': {
        this.toastInfraBuilt(event.payload);

        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'ACTION_USED': {
        this.toastActionUsed(event.payload);

        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;

            if (g.currentAction &&
              (g.currentAction.mode === 'PROVOCATION'
                || g.currentAction.mode === 'AMBUSH'
                || g.currentAction.mode === 'LONELY'
                || g.currentAction.mode === 'CHARISMATIQUE'
                || g.currentAction.mode === 'MARCHAND_ITINERANT'
                || g.currentAction.mode === 'MARCHAND_BONUS_BUY'
                || g.currentAction.mode === 'PRESENCE_ECRASANTE'
                || g.currentAction.mode === 'CATACLYSME'
                || g.currentAction.mode === 'CLONES_OMBRE'
                || g.currentAction.mode === 'IMAGE_MIROIR_SETUP'
                || g.currentAction.mode === 'IMAGE_MIROIR_RESOLVE'
                || g.currentAction.mode === 'ECLIPSE'
                || g.currentAction.mode === 'BLOOD_MOON'
                || g.currentAction.mode === 'VOILE_DE_BRUME'
                || g.currentAction.mode === 'FAIM_IRREPRESSIBLE'
                || g.currentAction.mode === 'MARQUE_TENEBREUSE'
                || g.currentAction.mode === 'AFFAIBLISSEMENT_OCCULTE'
                || g.currentAction.mode === 'PASSAGE_SECRET'
                || g.currentAction.mode === 'AVIDITE_NOCTURNE'
                || g.currentAction.mode === 'EAU_BENITE')) {
              this.syncActionFromSnapshot(g);
            }
            this.syncMerchantUiFromSnapshot(g);
            this.bumpHistoryScroll(); // optionnel, mais cohérent avec les autres
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'ACTION_STARTED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            // ouvre/MAJ la modale selon currentAction (NET/PIT)
            this.syncActionFromSnapshot(g);
            this.syncMerchantUiFromSnapshot(g);
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'ACTION_ROLLED': {
        // 1) refresh pour voir roll / breakdownLines dans currentAction
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.syncActionFromSnapshot(g); // met à jour actionRoll + breakdown dans la modale
            this.syncMerchantUiFromSnapshot(g);
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });

        // 2) laisse le résultat affiché, puis enchaîne (comme BITE_ROLLED)
        setTimeout(() => {
          this.api.combatContinue(this.gameId).subscribe({
            error: e => this.showError(e)
          });
        }, this.SPECTATE_HOLD_MS);

        break;
      }

      case 'ACTION_RESOLVED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.syncActionFromSnapshot(g); // ferme la modale si currentAction=null
            this.syncMerchantUiFromSnapshot(g);

            // si on vient juste de fermer la modale marchand après confirmation => play animation
            const merchantJustClosed =
              (this.actionMode === null || this.showActionModal === false); // modale fermée côté UI

            if (merchantJustClosed && this.shouldPlayBonusFlyAfterClose && this.pendingBonusFly) {
              const { src, rect } = this.pendingBonusFly;

              // important : jouer après le repaint du DOM (modale vraiment disparue)
              setTimeout(() => {
                this.flyDomToViewportBottom(src, rect);
              }, 0);

              // cleanup
              this.shouldPlayBonusFlyAfterClose = false;
              this.pendingBonusFly = null;
            }

            if (g.phase === 'PREPHASE3' && g.hasUpcomingCombat && !g.locationEffectPending) {
              // On (re)lance simplement le timer local.
              // Si tu veux éviter de le redémarrer toutes les 2s, tu peux ajouter un petit guard :
              if (!this.prephaseTicker) {
                this.startPrephaseTimer();
              }
            } else {
              this.stopPrephaseTimer();
            }
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'TRADE_SYNC': {
        const t = event.payload as STrade;

        // upsert
        const arr = (this.game as any).trades as STrade[] | undefined;
        if (!arr) (this.game as any).trades = [t];
        else {
          const i = arr.findIndex(x => x.id === t.id);
          if (i >= 0) arr[i] = t; else arr.push(t);
        }

        // auto-open si utile
        const meInvolved = t.aId === this.me?.id || t.bId === this.me?.id;
        const mySt = this.myStatus(t);
        const hasAnyOffer =
          (t.offerA && Object.keys(t.offerA).length > 0) ||
          (t.offerB && Object.keys(t.offerB).length > 0);

        if (meInvolved && this.shopOpen && !this.selectedTradeTargetId &&
          mySt !== 'CANCELLED' && mySt !== 'REFUSED' && hasAnyOffer) {
          const otherId = this.otherIdFromTrade(t);
          this.selectedTradeTargetId = otherId;
          this.myOffer = { ...(this.myOffersByTarget[otherId] || {}) };
        }

        this.game = { ...(this.game as GameSnapshot) };
        break;
      }

      case 'TRADE_DELETED': {
        const payload: any = event.payload || {};
        const id = payload.id as string | undefined;
        const aId = payload.aId as string | undefined;
        const bId = payload.bId as string | undefined;
        const result = payload.result as ('SUCCESS' | 'CLOSED' | undefined);
        if (!id || !aId || !bId) break;

        const me = this.me;
        if (!me) break;

        const success = (result === 'SUCCESS');
        const offerA = (payload.offerA || {}) as Record<string, number>;
        const offerB = (payload.offerB || {}) as Record<string, number>;

        const meInvolved = (me.id === aId || me.id === bId);

        // Déduire le camp de l’échange depuis les rôles dans le snapshot
        const pa = this.game?.players?.find(p => p.id === aId);
        const pb = this.game?.players?.find(p => p.id === bId);

        const tradeSide: 'HUNTERS' | 'VAMP_SIDE' | null =
          (pa && pb && pa.role === 'HUNTER' && pb.role === 'HUNTER') ? 'HUNTERS'
            : (pa && pb) ? 'VAMP_SIDE'
              : null;

        const mySide: 'HUNTERS' | 'VAMP_SIDE' =
          (me.role === 'HUNTER') ? 'HUNTERS' : 'VAMP_SIDE';

        // Si je ne suis pas impliqué et pas dans le bon camp => je ne vois rien
        if (!meInvolved && tradeSide && tradeSide !== mySide) {
          // (optionnel) on peut quand même nettoyer la liste locale des trades
          if (this.game?.trades) {
            this.game.trades = this.game.trades.filter((x: STrade) => x.id !== id);
            this.game = { ...(this.game as GameSnapshot) };
          }
          break;
        }

        // Animation “closing” (ok pour tout le monde du camp, ça ne casse rien)
        this.closingUntil[id] = Date.now() + 1500;
        this.closingKind[id] = success ? 'ok' : 'ko';
        this.game = { ...(this.game as GameSnapshot) };

        setTimeout(() => {
          // retirer le trade du snapshot local
          if (this.game?.trades) {
            this.game.trades = this.game.trades.filter((x: STrade) => x.id !== id);
          }

          // Si je suis impliqué => nettoyage UI + message détaillé
          if (meInvolved) {
            const otherId = (me.id === aId) ? bId : aId;

            // nettoyer mes offres / sélection seulement si concerné
            if (otherId) delete this.myOffersByTarget[otherId];
            if (this.selectedTradeTargetId === otherId) {
              this.selectedTradeTargetId = null;
              this.myOffer = {};
            }

            if (success) {
              const iAmA = (me.id === aId);
              const give = iAmA ? offerA : offerB;
              const recv = iAmA ? offerB : offerA;

              const txt = `Vous avez échangé ${this.packToText(give)} contre ${this.packToText(recv)} avec ${this.usernameOf(otherId)}.`;
              this.setFlashFor(otherId, txt, 'ok', 2500);

              // resync ressources
              this.api.getGame(this.gameId).subscribe({
                next: g => this.game = g,
                error: e => this.showError(e)
              });
            } else {
              this.setFlashFor(otherId, `Échange annulé avec ${this.usernameOf(otherId)}.`, 'ko', 2500);
              this.game = { ...(this.game as GameSnapshot) };
            }
            return;
          }

          // Sinon (spectateur mais même camp) => message neutre
          const aName = this.usernameOf(aId);
          const bName = this.usernameOf(bId);

          if (success) {
            this.setFlashFor('__camp__', `${aName} et ${bName} ont conclu un échange.`, 'ok', 2500);
          } else {
            this.setFlashFor('__camp__', `Échange fermé entre ${aName} et ${bName}.`, 'ko', 2500);
          }

          this.game = { ...(this.game as GameSnapshot) };
        }, 1500);

        break;
      }

      case 'LOCATION_STARTED': {
        if (!this.game) break;
        const { ownerId, infra } = event.payload;

        // Mise à jour minimale du snapshot local
        this.game.locationEffectPending = true;
        this.game.locationEffectOwnerId = ownerId;
        this.game.locationEffectInfra = infra as any;
        this.game.locationEffectChoice = null;

        // Le joueur qui choisit repart de zéro
        this.effectChoice = null;
        break;
      }

      case 'LOCATION_USED': {
        // On recharge toujours un snapshot frais après utilisation d'un effet de lieu
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;

            this.syncActionFromSnapshot(g);
            this.recomputeUnstableChoices();
            this.syncLocationEffectFromSnapshot(g, previous);
            this.syncShopVisibilityFromSnapshot();
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e),
        });
        break;
      }

      case 'BANK_UPDATED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;

            this.syncShopVisibilityFromSnapshot();
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e),
        });
        break;
      }

      case 'PHASE4_READY_UPDATED': {
        const pid = event?.payload?.playerId as string | undefined;
        if (pid && pid === this.meId) this.waitingDone = true;
        break;
      }
    }
  }

  //====== Select location ======/
  canPlayLocation(c: string): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.hp <= 0) return false;

    const isVampSide = me.role === 'VAMPIRE' || me.role === 'SERVANT';

    // On ne bloque que le camp vampire en PHASE2
    if (g.phase === 'PHASE2' && isVampSide && this.isGarlicBlockedLocation(c)) {
      return false;
    }

    return true;
  }

  onLocationClick(c: string) {
    if (!this.canPlayLocation(c)) return;
    this.selectedLocation = c;
    this.selectedAction = null;
    this.selectedPotion = null;
  }

  playSelected() {
    if (!this.game || !this.me) return;

    const g = this.game;
    const me = this.me;

    // 1) CAS LIEU SÉLECTIONNÉ
    if (this.selectedLocation) {
      const loc = this.selectedLocation;
      const hasPisteur = this.myActions().includes('PISTEUR');
      const isHunterPhase1 = (me.role === 'HUNTER' && g.phase === 'PHASE1');

      // Cas spécial : Fumigation + Pisteur (chasseur en PHASE1)
      if (isHunterPhase1 && this.preparedGarlicForThisRaid && hasPisteur) {
        const useTracker = window.confirm(
          "Voulez-vous également utiliser Pisteur pour traquer le vampire ?"
        );

        this.api.selectLocation(g.id, loc).subscribe({
          next: _ => {
            if (useTracker) {
              this.api.useAction(g.id, 'PISTEUR').subscribe({
                error: e => this.showError(e)
              });
            }

            this.selectedLocation = null;
            this.preparedGarlicForThisRaid = false;
          },
          error: e => this.showError(e)
        });

        return;
      }

      // Cas normal : jouer uniquement le lieu
      if (String(loc).toLowerCase() === 'forge') {
        // 1) Options possibles (tiers suivant) via ton code existant
        const tmpG = { ...g, locationEffectOwnerId: me.id } as GameSnapshot;
        const possible = this.computeForgeOptionsForOwner(tmpG);

        // Si aucune option => probablement full T3
        if (possible.length === 0) {
          const ok = window.confirm(
            "Attention: vous ne pouvez plus forger d’amélioration.\n" +
            "Raison: votre arme et votre armure semblent déjà au palier maximum (T3).\n\n" +
            "Voulez-vous quand même jouer cette carte ?"
          );
          if (!ok) {
            this.selectedLocation = null;
            return;
          }
        } else {
          // 2) Vérif ressources (copie des coûts back, en inline)
          const wood = (me as any).wood ?? 0;
          const iron = (me as any).iron ?? 0;
          const silver = (me as any).silver ?? 0;
          const souls = (me as any).souls ?? 0;

          const missingOf = (id: string) => {
            const c = this.forgeCostOf(id);
            if (!c) return { total: 999, parts: ['coût inconnu'] };

            const missWood = Math.max(0, (c.wood ?? 0) - wood);
            const missIron = Math.max(0, (c.iron ?? 0) - iron);
            const missSilver = Math.max(0, (c.silver ?? 0) - silver);
            const missSouls = Math.max(0, (c.souls ?? 0) - souls);

            const parts: string[] = [];
            if (missWood) parts.push(`${missWood} bois`);
            if (missIron) parts.push(`${missIron} fer`);
            if (missSilver) parts.push(`${missSilver} argent`);
            if (missSouls) parts.push(`${missSouls} âmes`);

            return { total: missWood + missIron + missSilver + missSouls, parts };
          };

          const affordable = possible.filter(o => missingOf(o.id).total === 0);

          if (affordable.length === 0) {
            // On prend l’option “la plus proche” pour expliquer clairement la raison
            const ranked = possible
              .map(o => ({ o, miss: missingOf(o.id) }))
              .sort((a, b) => a.miss.total - b.miss.total);

            const best = ranked[0];

            const reason =
              "Attention: aller à la Forge n’aura aucun effet pour l’instant.\n" +
              "Raison: vous n’avez pas assez de ressources pour forger un équipement.\n\n" +
              `Option la plus proche: ${best.o.label}\n` +
              `Il vous manque: ${best.miss.parts.join(', ')}\n\n` +
              `Vos ressources: ${wood} bois, ${iron} fer, ${silver} argent, ${souls} âmes.\n\n` +
              "Voulez-vous quand même jouer cette carte ?";

            const ok = window.confirm(reason);
            if (!ok) {
              this.selectedLocation = null;
              return;
            }
          }
        }
      }

      this.api.selectLocation(g.id, loc).subscribe({
        next: _ => {
          this.selectedLocation = null;
        },
        error: e => this.showError(e)
      });

      return;
    }

    // 2) CAS ACTION SÉLECTIONNÉE
    if (this.selectedAction) {
      const action = this.selectedAction;
      if (this.isMeHunterUnstablePending()) return;
      if (!this.canUseActionNow(action)) return;

      this.useAction(action);
      this.selectedAction = null;
      return;
    }

    // 3) CAS POTION / ELIXIR SÉLECTIONNÉ
    if (this.selectedPotion) {
      const potion = this.selectedPotion;
      if (this.isMeHunterUnstablePending()) return;
      if (!this.canUsePotionNow(potion)) return;

      this.usePotion(potion);
      this.selectedPotion = null;
      return;
    }
  }

  isMeHunterUnstablePending(): boolean {
    const g: any = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (g.phase !== 'PREPHASE3') return false;
    if (me.role !== 'HUNTER' || me.hp <= 0) return false;

    const id = me.id;

    const has = (obj: any, key: string) =>
      !!obj && Object.prototype.hasOwnProperty.call(obj, key);

    // "instable sous contrôle" = pending OU assigné
    return (
      has(g.unstableEligibleTargets, id) ||
      has(g.unstableEligibleLocations, id) ||
      has(g.unstableTargetByPlayer, id) ||
      has(g.unstableHarvestLocByPlayer, id)
    );
  }

  skipNow() {
    if (!this.game || this.hasSkipped) return; // évite le spam
    this.hasSkipped = true;

    this.api.skipPrePhase3(this.game.id).subscribe({
      // on ne touche PAS à this.game ici : READY_UPDATED fait foi
      error: e => {
        this.hasSkipped = false;     // on relâche le garde-fou si erreur
        this.showError(e);
      }
    });
  }

  isUnstableAlreadyDecided(unstableId: string): boolean {
    const g: any = this.game;
    if (!g) return false;
    const chosenT = g.unstableTargetByPlayer || {};
    const chosenH = g.unstableHarvestLocByPlayer || {};
    return !!(chosenT[unstableId] || chosenH[unstableId]);
  }

  //====== Météo ======/
  canShowWeatherModal(): boolean {
    // La modale n’existe que pendant PHASE0 et uniquement quand on a activé le hold local
    return this.game?.phase === 'PHASE0' && this.weatherModalHold === true;
  }

  rollWeather() {
    this.api.rollWeather(this.gameId).subscribe({
      error: e => this.showError(e)
    });
  }

  /** handleWeatherReveal
   * Rôle :
   *  - Piloter l'animation météo côté front : petite attente (PRE_BG), activer le fond,
   *    éventuellement un post-hold (POST_BG), puis relâcher la modale.
   *
   *  À la fin de l'animation locale, on appelle explicitement advance(PHASE1).
   *  On ajoute un flag local (weatherAdvanceSent) pour éviter d'appeler /advance plusieurs fois
   *  si plusieurs onglets sont ouverts.
   */
  private handleWeatherReveal(g: GameSnapshot) {
    const roll = g.weather?.roll ?? null;

    // ---- RESET si pas (encore) de tirage
    if (roll == null) {
      this.lastWeatherRollSeen = null;
      this.weatherBgActive = false;

      if (g.phase === 'PHASE0') {
        // Petit teaser avant d’ouvrir la modale “En attente du tirage…”
        if (!this.weatherWaitTimer) {
          this.weatherModalHold = false; // masquée pendant le teaser
          this.weatherWaitTimer = setTimeout(() => {
            this.weatherModalHold = true; // ouverture “En attente du tirage…”
            this.weatherWaitTimer = undefined;
          }, GameComponent.WEATHER_WAIT_BEFORE_MODAL_MS);
        }
      } else {
        this.weatherModalHold = false;
      }

      // nouveau cycle autorisé
      this.weatherAdvanceSent = false;

      // cleanup timers
      if (this.weatherTimer) { clearTimeout(this.weatherTimer); this.weatherTimer = undefined; }
      if (this.weatherPostTimer) { clearTimeout(this.weatherPostTimer); this.weatherPostTimer = undefined; }
      return;
    }

    // ---- NOUVEAU TIRAGE détecté → lance l’animation locale
    if (this.lastWeatherRollSeen !== roll) {
      if (this.weatherWaitTimer) { clearTimeout(this.weatherWaitTimer); this.weatherWaitTimer = undefined; }

      this.lastWeatherRollSeen = roll;
      this.weatherAdvanceSent = false; // nouveau cycle météo → on réautorise 1 avance

      this.weatherModalHold = true;    // on garde la modale ouverte le temps de l’anim
      this.weatherBgActive = false;

      if (this.weatherTimer) clearTimeout(this.weatherTimer);
      if (this.weatherPostTimer) clearTimeout(this.weatherPostTimer);

      // 1) roue/teaser
      this.weatherTimer = setTimeout(() => {
        this.weatherBgActive = true; // active le fond/visuel principal

        // 2) petit “hold de lecture”
        const post = this.WEATHER_HOLD_MS;
        if (post > 0) {
          this.weatherPostTimer = setTimeout(() => {
            this.weatherModalHold = false;       // on ferme la modale…
            this.advanceToPhase1IfNeeded();      // …et on avance PHASE1 exactement ici (une seule fois)
          }, post);
        } else {
          this.weatherModalHold = false;
          this.advanceToPhase1IfNeeded();
        }
      }, this.WEATHER_WHEEL_MS);
    }
  }

  // on reste sur la roue tant qu'on n'a pas activé le bg météo
  isWeatherPreReveal(): boolean {
    const hasRoll = this.game?.weather?.roll != null;
    return !hasRoll || !this.weatherBgActive;
  }

  // calculer la transform pour poser l'icône sur la pale correspondante
  weatherIconTransform(): string {
    const roll = Math.max(1, Math.min(12, this.game?.weather?.roll ?? 1));
    const angle = (roll - 1) * GameComponent.WHEEL_DEG_PER_FACE + GameComponent.WHEEL_BASE_OFFSET;
    const r = GameComponent.WEATHER_ICON_RADIUS;
    // centre ➜ rotation vers la pale ➜ translation radiale ➜ remise à l'horizontale
    return `translate(-50%, -50%) rotate(${angle}deg) translate(0, -${r}px) rotate(${-angle}deg)`;
  }

  //====== Action & potion ======/
  // afficher mes potions (IDs)
  myPotions(): string[] {
    const g = this.game;
    if (!g) return [];
    const me = g.players.find(p => p.id === this.meId);
    return me?.potions ?? [];
  }

  myElixirs(): string[] {
    const g = this.game;
    if (!g) return [];
    const me = g.players.find(p => p.id === this.meId);
    return me?.elixirs ?? [];
  }

  canUsePotionNow(_pot: string): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.hp <= 0) return false;

    const ws = g.weather?.status;
    const wss = g.weather?.secondaryStatus;

    if (this.isMeHunterUnstablePending()) return false;

    // Sous blizzard : potions inutilisables
    if (ws === 'BLIZZARD' || wss === 'BLIZZARD') {
      return false;
    }

    // Web app : fenêtre unique de préparation avant les duels
    if (g.phase === 'PREPHASE3' && g.hasUpcomingCombat) {
      return this.imInUpcomingCombat();
    }

    // Jamais en PHASE3
    return false;
  }

  // Suis-je (moi) sur un lieu face-up où il y aura un combat ?
  // Ennemi = vampire / serviteur / monstre / clone d'ombre.
  imInUpcomingCombat(): boolean {
    const game = this.game;
    const meId = this.meId;
    if (!game || !meId) return false;

    const faceUp = (game.center || []).filter(cb => cb.faceUp);
    if (faceUp.length === 0) return false;

    const harvestMap: Record<string, string> =
      (game as any).unstableHarvestLocByPlayer || {};

    const allLocs = Array.from(new Set(faceUp.map(cb => cb.card)));
    const combatLocs = new Set<string>();

    // Monstres du snapshot (vivants)
    const monsters = (game as any).monsters as SMonster[] | undefined;
    const aliveMonsters = (monsters || []).filter(m => m.hp > 0);

    // Lieux des clones
    const cloneLocs: string[] = (game as any).clonesLocations || [];

    for (const loc of allLocs) {
      const idsOnLoc = faceUp
        .filter(cb => cb.card === loc)
        .map(cb => cb.playerId);

      const playersOnLoc = idsOnLoc
        .map(id => game.players.find(p => p.id === id))
        .filter((p): p is SPlayer => !!p);

      const monstersOnLoc = aliveMonsters.filter(m => m.location === loc);
      const cloneHere = cloneLocs.includes(loc);

      // Ennemi = joueur ennemi OU monstre OU clone d'ombre présent sur ce lieu
      const hasEnemy =
        playersOnLoc.some(p => this.isEnemy(p)) ||
        monstersOnLoc.length > 0 ||
        cloneHere;

      // Hunters présents (non récolteurs instables)
      const hasHunter = playersOnLoc.some(p =>
        p.role === 'HUNTER' &&
        p.hp > 0 &&
        !harvestMap[p.id]
      );

      if (hasEnemy && hasHunter) {
        combatLocs.add(loc);
      }
    }

    const myFaceUpCard = faceUp.find(cb => cb.playerId === meId)?.card;

    // si je suis récolteur instable, jamais combat pour moi
    if (harvestMap[meId]) return false;

    return !!myFaceUpCard && combatLocs.has(myFaceUpCard);
  }

  private isEnemy(p?: SPlayer): boolean {
    return p?.role === 'VAMPIRE' || p?.role === 'SERVANT';
  }

  potionLabelFr(id: string): string {
    switch (id) {
      case 'FORCE': return 'Potion de force';
      case 'ENDURANCE': return 'Potion d’endurance';
      case 'VIE': return 'Potion de vie';
      default: return id;
    }
  }

  isElixirMod(mod: { source?: string | null } | null | undefined): boolean {
    const src = mod?.source || '';
    if (!src.startsWith('POTION:')) return false;

    // "POTION:RAGE" ou "POTION:RAGE:DSP" → on récupère "RAGE"
    const type = src.split(':')[1] || '';

    return type === 'RAGE'
      || type === 'RESILIENCE'
      || type === 'RAPIDITE'
      || type === 'INVISIBILITE'
      || type === 'INVULNERABILITE';
  }

  usePotion(type: string) {
    if (!this.game) return;
    this.api.usePotion(this.game.id, type).subscribe({
      error: e => this.showError(e)
    });
  }

  onPotionClick(potion: string, index: number) {
    if (!this.canUsePotionNow(potion)) return;
    this.selectedPotion = potion;
    this.selectedPotionIndex = index;

    this.selectedLocation = null;
    this.selectedAction = null;
    this.selectedActionIndex = null;
  }

  /** Est-ce que ce joueur a un effet de focalisation actif ce raid ? */
  hasFocus(playerId?: string | null): boolean {
    if (!playerId || !this.game?.raidMods) return false;
    const list = this.game.raidMods[playerId] || [];
    return list.some(m =>
      m.source?.startsWith('POTION:FOCALISATION') &&
      m.source?.includes(':DSP')
    );
  }

  /** Suis-je entre le 1er et le 2e dé de focalisation ? */
  get isMyFocusFirstStep(): boolean {
    const r = this.currentCombat;
    const side = this.waitingForMyRoll;
    if (!r || !side) return false;
    if (!this.hasFocus(this.meId)) return false;

    if (side === 'ATTACK' && r.attackerId === this.meId) {
      // J’ai déjà un premier jet, mais pas encore le jet final
      return r.attackerFirstRoll != null && r.attackerRoll == null;
    }
    if (side === 'DEFENSE' && r.defenderId === this.meId) {
      return r.defenderFirstRoll != null && r.defenderRoll == null;
    }
    return false;
  }

  /** Label du bouton dans la modale roll (Lancer / Relancer) */
  get rollButtonLabel(): string {
    return this.isMyFocusFirstStep ? 'Relancer le dé' : 'Lancer le dé';
  }

  showFocusSpectate(playerId?: string): boolean {
    if (!playerId) return false;
    if (!this.hasFocus(playerId)) return false;

    // Tant qu'on est AVANT l'ouverture de la modale morsure,
    // on garde l'affichage focalisation (2 dés).
    return this.isBeforeBiteModal;
  }

  get isBeforeBiteModal(): boolean {
    // Pas de morsure en cours => on considère qu'on est "avant" la morsure
    if (!this.game?.currentBite) return true;

    // Morsure posée mais pas de délai configuré => on est après
    if (this.biteNotBeforeMillis == null) return false;

    // Si l'heure actuelle est encore avant biteNotBeforeMillis,
    // on est toujours dans la fenêtre "spectate" avant affichage de la modale morsure
    return Date.now() < this.biteNotBeforeMillis;
  }

  myActions(): string[] {
    const g = this.game;
    if (!g) return [];
    const me = g.players.find(p => p.id === this.meId);
    return me?.actions ?? [];
  }

  myMaintenanceActions(): string[] {
    const g = this.game;
    if (!g) return [];

    const me = g.players.find(p => p.id === this.meId);
    if (!me || !me.actions) return [];

    return me.actions.filter(a => a === 'CHARISMATIQUE' || a === 'MARCHAND_ITINERANT' || a === 'AVIDITE_NOCTURNE');
  }

  canUseActionNow(_action: string): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.hp <= 0) return false;

    const ws = g.weather?.status;

    if (this.isMeHunterUnstablePending()) return false;

    // 1) Action vampire:
    if (_action === 'CATACLYSME' || _action === 'CLONES_OMBRE') {
      return g.phase === 'PHASE2' && me.role === 'VAMPIRE';
    }

    if (_action === 'IMAGE_MIROIR') {
      // Phase + rôle de base
      if (g.phase !== 'PHASE2' || me.role !== 'VAMPIRE') return false;

      const hand = me.hand || [];

      // Lieux non fumigés distincts dans la main du vampire
      const nonGarlicDistinct = Array.from(
        new Set(
          hand.filter(loc => !this.isGarlicBlockedLocation(loc))
        )
      );

      const nonGarlicCount = nonGarlicDistinct.length;

      // Combien d'Image miroir ont déjà été "préparées" ce raid ?
      // (côté back : mirrorAltLocations s'incrémente à chaque IMAGE_MIROIR_SETUP)
      const alreadyUsed = (g as any).mirrorAltLocations
        ? (g as any).mirrorAltLocations.length
        : 0;

      if (alreadyUsed === 0) {
        // 1ère utilisation : il faut au moins 2 lieux non fumigés
        return nonGarlicCount >= 2;
      } else if (alreadyUsed === 1) {
        // 2ème utilisation : il faut au moins 3 lieux non fumigés
        return nonGarlicCount >= 3;
      } else {
        // Tu n'as que 2 copies dans le deck → pas de 3ème utilisation logique
        return false;
      }
    }

    if (_action === 'PRESENCE_ECRASANTE'
      || _action === 'ECLIPSE'
      || _action === 'VOILE_DE_BRUME'
      || _action === 'MARQUE_TENEBREUSE') {
      return g.phase === 'PREPHASE3' && me.role === 'VAMPIRE';
    }

    if (_action === 'FAIM_IRREPRESSIBLE' || _action === 'AFFAIBLISSEMENT_OCCULTE') {
      return (
        g.phase === 'PREPHASE3' &&
        me.role === 'VAMPIRE' &&
        this.imInUpcomingCombat()
      );
    }

    if (_action === 'PASSAGE_SECRET') {
      if (g.phase !== 'PREPHASE3' || me.role !== 'VAMPIRE') return false;

      const loc = this.locationOf(me.id);
      if (loc !== 'manor') return false;

      // Optionnel : vérifier qu'il y a au moins une destination possible
      const choices = this.computeSecretPassageChoices(g, loc);
      return choices.length > 0;
    }

    if (_action === 'AVIDITE_NOCTURNE') {
      if (g.phase !== 'PHASE4' || me.role !== 'VAMPIRE') return false;


      const greedy = (g as any).shopPricesIncreasedThisRaid;
      if (greedy) return false;

      return true;
    }

    if (_action === 'BLOOD_MOON' && ws === 'FULL_MOON') {
      return g.phase === 'PREPHASE3' && me.role === 'VAMPIRE';
    }

    // 2) Actions PHASE1 (chasseurs)
    if (g.phase === 'PHASE1' && me.role === 'HUNTER') {
      if (_action === 'FUMIGATION_AIL' || _action === 'PISTEUR') {
        return true;
      }
    }

    // À partir d'ici : on parle des actions de raid des chasseurs
    if (me.role !== 'HUNTER') return false;

    // Si le vampire a joué Présence écrasante ce raid, les actions chasseurs sont bloquées
    // uniquement pour les chasseurs sur le même lieu que le vampire
    if (g.hunterActionsBlockedThisRaid &&
      (_action === 'FEU_DE_CAMP'
        || _action === 'NET'
        || _action === 'PIT'
        || _action === 'INCENDIAIRE'
        || _action === 'PROVOCATION'
        || _action === 'AMBUSH'
        || _action === 'LONELY'
        || _action === 'BLESSED_STAKE'
        || _action === 'SACRED_ROSARY'
        || _action === 'CHARISMATIQUE'
        || _action === 'MARCHAND_ITINERANT'
        || _action === 'EAU_BENITE')) {

      const me = this.me;
      const vamp = g.players?.find(p => p.role === 'VAMPIRE') || null;

      const myLoc = me ? this.locationOf(me.id) : null;
      const vampLoc = vamp ? this.locationOf(vamp.id) : null;

      if (myLoc && vampLoc && myLoc === vampLoc) {
        return false;
      }
    }

    // 3) Règles par action
    switch (_action) {
      case 'FEU_DE_CAMP':
        if (g.phase !== 'PREPHASE3') return false;
        return (ws === 'DUSK' || ws === 'NIGHT_DARK' || ws === 'NIGHT_CLEAR');

      case 'NET':
      case 'PIT': {
        // météo qui bloque (sauf si feu de camp sur mon lieu)
        const myLoc = this.locationOf(me.id);
        const campfires = (g as any)?.campfireLocations ?? [];
        const hasCampfireHere = Array.isArray(campfires) && myLoc ? campfires.includes(myLoc) : false;

        if (ws === 'NIGHT_DARK' && !hasCampfireHere) return false;

        // rôle + phase
        if (g.phase !== 'PREPHASE3') return false;
        if (me.role !== 'HUNTER') return false;

        // Il faut au moins un ennemi physique sur mon lieu
        return this.hasRealEnemyOnMyLocation();
      }


      case 'PROVOCATION': {
        if (g.phase !== 'PREPHASE3') return false;
        if (me.role !== 'HUNTER') return false;
        return this.canPlayProvocationHere();
      }

      case 'BLESSED_STAKE': {
        if (!me) return false;
        if (me?.isBlessedStake) return false;
        if (g.phase === 'PREPHASE3' && me.role === 'HUNTER') return true;
        return false;
      }

      case 'SACRED_ROSARY': {
        if (!me) return false;
        if (me.isSacredRosary) return false;
        if (g.phase === 'PREPHASE3' && me.role === 'HUNTER') return true;
        return false;
      }

      case 'INCENDIAIRE':
        if (g.phase !== 'PREPHASE3') return false;
        return this.canPlayIncendiaireHere();

      case 'AMBUSH': {
        if (g.phase !== 'PREPHASE3' || me.role !== 'HUNTER') return false;

        const loc = this.locationOf(me.id);
        if (!loc) return false;

        const onLoc = this.playersOnLocation(loc);
        const hunters = onLoc.filter(p => p.role === 'HUNTER' && p.hp > 0);
        const enemies = onLoc.filter(p =>
          (p.role === 'VAMPIRE' || p.role === 'SERVANT') && p.hp > 0
        );

        return hunters.length >= 2 && enemies.length >= 1;
      }

      case 'LONELY': {
        if (g.phase !== 'PREPHASE3') return false;
        if (me.role !== 'HUNTER') return false;
        return this.imInUpcomingCombat();
      }

      case 'CHARISMATIQUE': {
        if (!me) return false;
        if (me.role !== 'HUNTER') return false;
        if (g.phase !== 'PHASE4') return false;
        if (me.charismaticThisRaid) return false;

        // Bloqué par Présence écrasante
        if (g.hunterActionsBlockedThisRaid) return false;

        return true;
      }

      case 'MARCHAND_ITINERANT': {
        if (!me) return false;
        if (me.role !== 'HUNTER') return false;
        if (g.phase !== 'PHASE4') return false;

        // Bloqué par Présence écrasante
        if (g.hunterActionsBlockedThisRaid) return false;

        return true;
      }

      case 'EAU_BENITE':
        if (g.phase !== 'PREPHASE3' && g.phase !== 'PHASE1') return false;
        return this.canPlayHolyWaterkHere();

      default:
        return false;
    }
  }

  // Le joueur a-t-il encore au moins une action préphase HUNTER jouable maintenant ?
  canUseHunterPrephaseActions(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.role !== 'HUNTER') return false;

    const acts = me.actions || [];

    const canInc = acts.includes('INCENDIAIRE') && this.canPlayIncendiaireHere();
    const canHoly = acts.includes('EAU_BENITE') && this.canPlayHolyWaterkHere();
    const canProv = acts.includes('PROVOCATION') && this.canPlayProvocationHere();
    const canAmbush = acts.includes('AMBUSH') && this.canUseActionNow('AMBUSH');
    const canLonely = acts.includes('LONELY') && this.canUseActionNow('LONELY');
    const canStake = acts.includes('BLESSED_STAKE') && this.canUseActionNow('BLESSED_STAKE');
    const canRosary = acts.includes('SACRED_ROSARY') && this.canUseActionNow('SACRED_ROSARY');

    return (
      canInc ||
      this.hadIncendiaireThisPrephase ||
      canHoly ||
      canProv ||
      canAmbush ||
      canStake ||
      canRosary
    );
  }

  // Le joueur a-t-il encore au moins une action préphase VAMP jouable maintenant ?
  canUseVampPrephaseActions(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.role !== 'VAMPIRE') return false;

    const acts = me.actions || [];

    const canFog = acts.includes('VOILE_DE_BRUME') && this.canUseActionNow('VOILE_DE_BRUME');
    const canPresence = acts.includes('PRESENCE_ECRASANTE') && this.canUseActionNow('PRESENCE_ECRASANTE');
    const canEclipse = acts.includes('ECLIPSE') && this.canUseActionNow('ECLIPSE');
    const canBloodMoon = acts.includes('BLOOD_MOON') && this.canUseActionNow('BLOOD_MOON');
    const canHunger = acts.includes('FAIM_IRREPRESSIBLE') && this.canUseActionNow('FAIM_IRREPRESSIBLE');
    const canDarkMark = acts.includes('MARQUE_TENEBREUSE') && this.canUseActionNow('MARQUE_TENEBREUSE');
    const canWeakening = acts.includes('AFFAIBLISSEMENT_OCCULTE') && this.canUseActionNow('AFFAIBLISSEMENT_OCCULTE');
    const canSecret = acts.includes('PASSAGE_SECRET') && this.canUseActionNow('PASSAGE_SECRET');

    return (
      canFog ||
      canPresence ||
      canEclipse ||
      canBloodMoon ||
      canHunger ||
      canDarkMark ||
      canWeakening ||
      canSecret
    );
  }

  actionLabelFr(mode: string | null | undefined): string {
    if (!mode) return '';

    switch (mode) {
      case 'EAU_BENITE': return 'Eau bénite';
      case 'FUMIGATION_AIL': return 'Fumigation d\'ail';
      case 'PISTEUR': return 'Pisteur';
      case 'FEU_DE_CAMP': return 'Feu de camp';
      case 'NET': return 'Filet';
      case 'PIT': return 'Fosse';
      case 'PROVOCATION': return 'Provocation';
      case 'INCENDIAIRE': return 'Incendiaire';
      case 'AMBUSH': return 'Embuscade';
      case 'LONELY': return 'Solitaire';
      case 'BLESSED_STAKE': return 'Pieu béni';
      case 'SACRED_ROSARY': return 'Chapelet sacré';
      case 'CHARISMATIQUE': return 'Charismatique';
      case 'MARCHAND_ITINERANT':
      case 'MARCHAND_BONUS_BUY': return 'Marchand itinérant';
      case 'PRESENCE_ECRASANTE': return 'Présence écrasante';
      case 'CATACLYSME': return 'Cataclysme';
      case 'CLONES_OMBRE': return 'Clones d’ombre';
      case 'IMAGE_MIROIR':
      case 'IMAGE_MIROIR_SETUP':
      case 'IMAGE_MIROIR_RESOLVE': return 'Image miroir';
      case 'ECLIPSE': return 'Éclipse';
      case 'BLOOD_MOON': return 'Lune sanglante';
      case 'VOILE_DE_BRUME': return 'Voile de brume';
      case 'FAIM_IRREPRESSIBLE': return 'Faim irrépressible';
      case 'MARQUE_TENEBREUSE': return 'Marque ténébreuse';
      case 'AFFAIBLISSEMENT_OCCULTE': return 'Affaiblissement occulte';
      case 'PASSAGE_SECRET': return 'Passage secret';
      case 'AVIDITE_NOCTURNE': return 'Avidité nocturne';
      // etc si tu as d’autres modes
      default: return '';
    }
  }

  useAction(type: string) {
    if (!this.game) return;

    this.zoomLeave();

    this.api.useAction(this.game.id, type).subscribe({
      next: _g => {
        if (type === 'FUMIGATION_AIL') {
          // On mémorise qu’on a préparé une fumigation pour CE raid
          this.preparedGarlicForThisRaid = true;
        }
        if (type === 'INCENDIAIRE') {
          this.hadIncendiaireThisPrephase = true;
        }
      },
      error: e => this.showError(e)
    });
  }

  onActionClick(action: string, index: number) {
    if (!this.canUseActionNow(action)) return;
    this.selectedAction = action;
    this.selectedActionIndex = index;

    this.selectedLocation = null;
    this.selectedPotion = null;
    this.selectedPotionIndex = null;
  }

  isGarlicBlockedLocation(location: string): boolean {
    return !!this.game && this.game.garlicBlockedLocations.includes(location);
  }

  garlicTooltip = "Ce lieu est protégé par une fumigation d'ail";

  private isWeatherCancelledForPlayer(p?: SPlayer): boolean {
    if (!p || !this.game || !this.game.weather) return false;

    const status = this.game.weather.status;
    const cancellable =
      status === 'DUSK' ||
      status === 'NIGHT_DARK' ||
      status === 'NIGHT_CLEAR';

    if (!cancellable) return false;

    const loc = this.locationOf(p.id);
    if (!loc) return false;

    return this.game.campfireLocations?.includes(loc) ?? false;
  }

  onNetRoll() {
    if (!this.game || !this.actionSelectedTargetId || this.actionMode !== 'NET') {
      return;
    }
    if (!this.isActionActor) return;
    if (this.actionResolving) return;

    this.actionResolving = true;

    this.api.resolveNet(this.gameId, this.actionSelectedTargetId).subscribe({
      next: () => {
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onPitRoll() {
    if (!this.game || this.actionMode !== 'PIT') return;
    if (this.actionResolving) return;
    if (!this.isActionActor) return; // doit être la victime courante

    const current = this.currentPitTarget;
    if (!current) return;

    this.actionResolving = true;

    this.api.resolvePit(this.gameId).subscribe({
      next: () => {
        // Le résultat arrive via ACTION_ROLLED + getGame
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  private hasRealEnemyOnMyLocation(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    // Joueurs ennemis physiques sur ce lieu
    const playersHere = this.playersOnLocation(loc);
    const enemyPlayers = playersHere.filter(p =>
      p.hp > 0 && (p.role === 'VAMPIRE' || p.role === 'SERVANT')
    );

    // Monstres vivants sur ce lieu
    const monsters = (g as any).monsters as SMonster[] | undefined;
    const monstersHere = (monsters || []).filter(m => m.hp > 0 && m.location === loc);

    return enemyPlayers.length > 0 || monstersHere.length > 0;
  }

  get isActionActor(): boolean {
    const meId = this.me?.id;
    if (!meId) return false;

    if (this.actionMode === 'NET'
      || this.actionMode === 'PROVOCATION'
      || this.actionMode === 'INCENDIAIRE'
      || this.actionMode === 'AMBUSH'
      || this.actionMode === 'LONELY'
      || this.actionMode === 'BLESSED_STAKE'
      || this.actionMode === 'CHARISMATIQUE'
      || this.actionMode === 'MARCHAND_ITINERANT'
      || this.actionMode === 'MARCHAND_BONUS_BUY'
      || this.actionMode === 'CATACLYSME'
      || this.actionMode === 'CLONES_OMBRE'
      || this.actionMode === 'IMAGE_MIROIR_SETUP'
      || this.actionMode === 'IMAGE_MIROIR_RESOLVE'
      || this.actionMode === 'ECLIPSE'
      || this.actionMode === 'BLOOD_MOON'
      || this.actionMode === 'VOILE_DE_BRUME'
      || this.actionMode === 'MARQUE_TENEBREUSE'
      || this.actionMode === 'AFFAIBLISSEMENT_OCCULTE'
      || this.actionMode === 'PASSAGE_SECRET'
      || this.actionMode === 'AVIDITE_NOCTURNE'
      || this.actionMode === 'EAU_BENITE'
    ) {
      // Filet : acteur = chasseur propriétaire
      return this.actionOwnerId === meId;
    }
    if (this.actionMode === 'PIT') {
      // Fosse : acteur = victime courante
      const current = this.currentPitTarget;
      return !!current && current.id === meId;
    }

    return false;
  }

  // Nom de la cible (pour tout le monde)
  get ActionTargetName(): string | null {
    if (!this.game) return null;
    const targetId = this.actionSelectedTargetId;
    if (!targetId) return null;

    // INCENDIAIRE utilise un code d'infra, pas un joueur
    if (this.actionMode === 'INCENDIAIRE') {
      return this.labelFr(targetId.toLowerCase());
    }

    const p = this.game.players.find(pl => pl.id === targetId);
    return p?.username ?? targetId;
  }

  get currentPitTarget(): SPlayer | null {
    if (!this.trapEnemies || !this.trapEnemies.length) return null;

    // priorité à targetId venant du back
    if (this.actionSelectedTargetId) {
      const found = this.trapEnemies.find(p => p.id === this.actionSelectedTargetId);
      if (found) return found;
    }

    return this.trapEnemies[this.trapCurrentIndex] ?? null;
  }

  selectActionTarget(id: string) {
    // Filet uniquement, par design (Fosse n’a pas de ciblage manuel chez toi)
    if (this.actionMode !== 'NET') return;
    if (!this.isActionActor || this.actionResolving || this.actionRoll !== null) return;

    this.actionResolving = true; // on réutilise ce flag pour désactiver les boutons pendant l’appel

    this.api.setNetTarget(this.gameId, id).subscribe({
      next: () => {
        this.actionResolving = false;
        // On met aussi à jour localement pour feedback instantané
        this.actionSelectedTargetId = id;
        // Le snapshot “officiel” arrivera via l’event ACTION_STARTED
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  private canPlayProvocationHere(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me || me.role !== 'HUNTER') return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    const playersHere = this.playersOnLocation(loc);

    const huntersCount = playersHere.filter(p => p.role === 'HUNTER' && p.hp > 0).length;
    const enemiesCount = playersHere.filter(p =>
      (p.role === 'VAMPIRE' || p.role === 'SERVANT') && p.hp > 0
    ).length;

    return huntersCount >= 2 && enemiesCount >= 1;
  }

  onProvocationChoose(targetId: string) {
    if (!this.game || this.actionMode !== 'PROVOCATION') return;
    if (!this.isActionActor) return;
    if (this.actionResolving) return;

    this.actionResolving = true;

    this.api.resolveProvocation(this.game.id, targetId).subscribe({
      next: () => {
        this.actionResolving = false;
        // La modale se fermera quand currentAction repassera à null via snapshot
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  private computeIncendiaireChoices(g: GameSnapshot, loc: string | null) {
    const built = g.builtInfras || [];
    const pending = g.pendingConstructionInfra as InfraCode | undefined;

    const choices: InfraCode[] = [];
    const addIfPresent = (code: InfraCode) => {
      if (built.includes(code) || pending === code) {
        if (!choices.includes(code)) choices.push(code);
      }
    };

    switch (loc) {
      case 'forest':
      case 'sawmill':
        addIfPresent('SAWMILL');
        break;

      case 'quarry':
      case 'mine':
        addIfPresent('MINE');
        break;

      case 'manor':
        (['LIBRARY', 'LABORATORY', 'BALLROOM', 'ALTAR', 'FORGE'] as InfraCode[])
          .forEach(addIfPresent);
        break;

      case 'library':
        addIfPresent('LIBRARY');
        break;

      case 'laboratory':
        addIfPresent('LABORATORY');
        break;

      case 'ballroom':
        addIfPresent('BALLROOM');
        break;

      case 'altar':
        addIfPresent('ALTAR');
        break;

      case 'forge':
        addIfPresent('FORGE');
        break;

      default:
        // lac ou autre : aucun choix
        break;
    }

    return choices;
  }

  onIncendiaireRoll() {
    if (!this.game) return;
    if (this.actionMode !== 'INCENDIAIRE') return;
    if (!this.actionSelectedTargetId) return; // ici c'est le code infra
    if (!this.isActionActor) return;
    if (this.actionResolving || this.actionRoll !== null) return;

    this.actionResolving = true;

    this.api.resolveIncendiaire(
      this.gameId,
      this.actionSelectedTargetId as
      'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE'
    ).subscribe({
      next: () => {
        // le résultat (d20 + texte) arrive via le snapshot / websocket
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  canPlayIncendiaireHere(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;

    // Il doit avoir la carte Incendiaire en main
    if (!me.actions || !me.actions.includes('INCENDIAIRE')) return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    // On réutilise la logique de mapping loc -> infra jouables
    const snapshot = g as unknown as GameSnapshot;
    const choices = this.computeIncendiaireChoices(snapshot, loc);

    return choices.length > 0;
  }

  onAmbushChoose(targetId: string) {
    if (!this.game) return;
    this.actionResolving = true;

    this.api.resolveAmbush(this.game.id, targetId).subscribe({
      next: _g => {
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onBlessedStakeRoll() {
    if (!this.game || this.actionMode !== 'BLESSED_STAKE') {
      return;
    }
    if (!this.isActionActor) return;
    if (this.actionResolving) return;

    this.actionResolving = true;

    this.api.resolveBlessedStake(this.gameId).subscribe({
      next: () => {
        this.actionResolving = false;
        // la suite est gérée par syncActionFromSnapshot + combatContinue/ACTION_RESOLVED
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onMerchantRoll(): void {
    if (!this.game) return;

    this.actionResolving = true;
    this.api.rollMerchant(this.gameId).subscribe({
      next: g => {
        this.actionResolving = false;
        this.bumpHistoryScroll();
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  merchantResultText(): string {
    const v = this.actionRoll;
    if (v == null) return '';

    if (v === 1 || v === 2) return "une potion est disponible à la boutique.";
    if (v === 3 || v === 4) return "un élixir est disponible à la boutique.";

    const k = this.me?.shopBonusKind;
    if (k === 'EQUIP_WEAPON') return "une arme est disponible à la boutique.";
    if (k === 'EQUIP_ARMOR') return "une armure est disponible à la boutique.";
    return "un équipement est disponible à la boutique.";
  }

  merchantBuyText(): string {
    const kind = this.me?.shopBonusKind;
    if (!kind) return 'Marchand itinérant — offre spéciale disponible.';

    switch (kind) {
      case 'POTION':
        return 'Marchand itinérant — choisissez comment payer la potion (ressources ou or).';
      case 'ELIXIR':
        return 'Marchand itinérant — choisissez comment payer l\'élixir (ressources ou or).';
      case 'EQUIP_WEAPON':
        return 'Marchand itinérant — choisissez comment payer l\'arme (ressources ou or).';
      case 'EQUIP_ARMOR':
        return 'Marchand itinérant — choisissez comment payer l\'armure (ressources ou or).';
      default:
        return 'Marchand itinérant — choisissez un mode de paiement.';
    }
  }

  bonusTitle(): string {
    const kind = this.me?.shopBonusKind;
    if (!kind) return 'Objet bonus';

    switch (kind) {
      case 'POTION': return 'Potion';
      case 'ELIXIR': return 'Élixir';
      case 'EQUIP_WEAPON': return 'Arme du marchand';
      case 'EQUIP_ARMOR': return 'Armure du marchand';
      default: return 'Objet bonus';
    }
  }

  canBuyBonus(): boolean {
    const me: any = this.me;
    if (!me) return false;
    if (me.hp <= 0) return false;

    // si max atteint (équipement), on bloque
    if (!this.canReceiveBonusItem()) return false;

    return this.canPayBonusWithResource() || this.canPayBonusWithGold();
  }

  canPayBonusWithResource(): boolean {
    const me = this.me;
    const rc = this.bonusResCost();
    if (!me || me.role !== 'HUNTER' || !rc) return false;

    if (rc.water && me.water < rc.water) return false;
    if (rc.herbs && me.herbs < rc.herbs) return false;
    if (rc.wood && me.wood < rc.wood) return false;
    if (rc.iron && me.iron < rc.iron) return false;
    if (rc.silver && (me as any).silver < rc.silver) return false;

    return true;
  }


  canPayBonusWithGold(): boolean {
    const me = this.me;
    const gc = this.bonusGoldCost();
    if (!me || me.role !== 'HUNTER' || gc == null) return false;
    return me.gold >= gc;
  }

  onConfirmBonus(mode: 'RESOURCE' | 'GOLD'): void {
    if (!this.game) return;

    this.shouldPlayBonusFlyAfterClose = true;

    this.actionResolving = true;
    this.api.buyShopBonus(this.gameId, mode).subscribe({
      next: _g => {
        this.actionResolving = false;
        this.bumpHistoryScroll();
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
        // si erreur, on ne joue rien
        this.shouldPlayBonusFlyAfterClose = false;
      }
    });
  }

  onBuyBonus(ev: MouseEvent): void {
    if (!this.game) return;

    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    // on mémorise pour plus tard
    this.pendingBonusFly = { src: img.src, rect: img.getBoundingClientRect() };
    this.shouldPlayBonusFlyAfterClose = false; // pas encore confirmé

    this.api.StartShopBonus(this.gameId).subscribe({
      next: _g => {
        this.bumpHistoryScroll();
      },
      error: e => this.showError(e)
    });
  }

  onCancelBonus(): void {
    if (!this.game) return;

    this.actionResolving = true;
    this.api.cancelShopBonus(this.gameId).subscribe({
      next: () => {
        this.actionResolving = false;
        this.bumpHistoryScroll();

        // annulation => aucune animation
        this.shouldPlayBonusFlyAfterClose = false;
        this.pendingBonusFly = null;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  /** true si l'objet bonus est achetable (règles "max atteint" inclues) */
  private canReceiveBonusItem(): boolean {
    const me: any = this.me;
    const kind = (me as any)?.shopBonusKind;
    if (!me || me.role !== 'HUNTER' || !kind) return false;

    // potions / elixirs : pas de notion de max ici
    if (kind === 'POTION' || kind === 'ELIXIR') return true;

    // équipements : le marchand ne doit jamais donner T3, et si tu as déjà T2/T3 -> pas achetable
    if (kind === 'EQUIP_WEAPON') {
      const t = this.hunterWeaponTier(me.weapon);
      return t < 2; // T0/T1 -> OK ; T2/T3 -> NON
    }
    if (kind === 'EQUIP_ARMOR') {
      const t = this.hunterArmorTier(me.armor);
      return t < 2;
    }

    return false;
  }

  /** Tooltip explicite pour le bouton d'achat bonus */
  bonusBuyDisabledTitle(): string | null {
    const me: any = this.me;
    const kind = me?.shopBonusKind;
    if (!me || me.role !== 'HUNTER' || !kind) return null;

    // 1) max atteint ?
    if (!this.canReceiveBonusItem()) {
      if (kind === 'EQUIP_WEAPON') return "Impossible: tu as déjà une arme T2/T3 (le T3 s'achète uniquement à la forge).";
      if (kind === 'EQUIP_ARMOR') return "Impossible: tu as déjà une armure T2/T3 (le T3 s'achète uniquement à la forge).";
      return "Impossible: maximum atteint.";
    }

    // 2) peut payer ?
    const canRes = this.canPayBonusWithResource();
    const canGold = this.canPayBonusWithGold();
    if (canRes || canGold) return null;

    return "Impossible: pas assez de ressources ni d'or.";
  }

  onSelectWeather2(ws: string) {
    this.selectedWeather2 = ws;
    if (this.selectedWeather3 === ws) {
      this.selectedWeather3 = null;
    }
  }

  onSelectWeather3(ws: string) {
    this.selectedWeather3 = ws;
    if (this.selectedWeather2 === ws) {
      this.selectedWeather2 = null;
    }
  }

  onCataclysmeConfirm() {
    if (!this.game || !this.isActionActor) return;
    if (!this.selectedWeather2 || !this.selectedWeather3) return;
    if (this.selectedWeather2 === this.selectedWeather3) return;

    this.actionResolving = true;
    this.api.resolveCataclysme(this.game.id, this.selectedWeather2, this.selectedWeather3)
      .subscribe({
        next: () => { this.actionResolving = false; },
        error: e => { this.actionResolving = false; this.showError(e); }
      });
  }

  labelWeather(ws: string | null | undefined): string {
    if (!ws) return '';

    switch (ws) {
      case 'SUNNY':
        return 'Jour ensoleillé';
      case 'FOG':
        return 'Brouillard protecteur';
      case 'AURORA':
        return 'Aurore';
      case 'CLOUDY':
        return 'Ciel couvert';
      case 'WIND':
        return 'Cyclone';
      case 'STORM':
        return 'Orage';
      case 'RAIN':
        return 'Pluie diluvienne';
      case 'BLIZZARD':
        return 'Blizzard';
      case 'DUSK':
        return 'Crépuscule';
      case 'NIGHT_DARK':
        return 'Nuit obscure';
      case 'NIGHT_CLEAR':
        return 'Nuit claire';
      case 'FULL_MOON':
        return 'Pleine lune';
      default:
        // au cas où un nouveau statut arrive, on affiche la clé brute
        return ws;
    }
  }

  get cataclysmeLabelPair(): string | null {
    if (!this.actionSelectedTargetId) return null;
    const parts = this.actionSelectedTargetId.split(',');
    const w1 = parts[0]?.trim();
    const w2 = parts[1]?.trim();
    if (!w1 || !w2) return null;
    return `${this.labelWeather(w1)} & ${this.labelWeather(w2)}`;
  }

  get clonesIndexes(): number[] {
    if (this.actionRoll == null) return [];
    return Array.from({ length: this.actionRoll }, (_, i) => i);
  }

  onClonesRoll() {
    if (!this.game || this.actionMode !== 'CLONES_OMBRE') return;
    if (!this.isActionActor || this.actionResolving || this.actionRoll !== null) return;

    this.actionResolving = true;

    this.api.rollClones(this.game.id).subscribe({
      next: _g => {
        this.actionResolving = false;
        // le snapshot à jour arrive via websockets / GET
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onCloneLocationChange(idx: number, ev: Event) {
    const select = ev.target as HTMLSelectElement;
    const loc = select.value;

    const arr = [...(this.clonesSelectedLocations || [])];
    arr[idx] = loc || '';
    this.clonesSelectedLocations = arr;
  }

  onClonesConfirm() {
    if (!this.game || this.actionMode !== 'CLONES_OMBRE') return;
    if (!this.isActionActor || this.actionResolving) return;
    if (!this.canConfirmClones()) return;

    this.actionResolving = true;

    this.api.confirmClones(this.game.id, this.clonesSelectedLocations).subscribe({
      next: _g => {
        this.actionResolving = false;
        // snapshot + fermeture modale via syncActionFromSnapshot
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  canConfirmClones(): boolean {
    if (this.actionRoll == null) return false;
    if (!this.clonesSelectedLocations) return false;
    if (this.clonesSelectedLocations.length !== this.actionRoll) return false;

    // Chaque clone doit avoir un lieu non vide
    return this.clonesSelectedLocations.every(l => !!l);
  }

  get clonesLocationChoices(): string[] {
    const me = this.me;
    const g = this.game;
    if (!g || !me || !me.hand) return [];
    return me.hand.filter(loc => !this.isGarlicBlockedLocation(loc));
  }

  onMirrorSetupConfirm() {
    if (!this.game || !this.selectedMirrorLoc || !this.isActionActor || this.actionResolving) return;

    this.actionResolving = true;
    this.api.resolveImageMiroirSetup(this.game.id, this.selectedMirrorLoc).subscribe({
      next: () => {
        this.actionResolving = false;
        // La modale se fermera quand currentAction repassera à null côté snapshot
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onMirrorResolveChoose(loc: string) {
    if (!this.game || !this.isActionActor || this.actionResolving) return;

    this.actionResolving = true;
    this.api.resolveImageMiroirChoice(this.game.id, loc).subscribe({
      next: () => {
        this.actionResolving = false;
        // Là aussi, fermeture via snapshot (currentAction null)
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  get mirrorLocationChoices(): string[] {
    const me = this.me;
    const g = this.game;
    if (!g || !me || !me.hand) return [];

    const usedAlts: string[] = (g as any).mirrorAltLocations || [];

    return me.hand
      // pas fumigé
      .filter(loc => !this.isGarlicBlockedLocation(loc))
      // pas déjà choisi par une Image miroir précédente de CE raid
      .filter(loc => !usedAlts.includes(loc));
  }

  onDarkMarkChoose(targetId: string) {
    if (!this.game) return;

    this.actionResolving = true;
    this.api.resolveDarkMark(this.game.id, targetId).subscribe({
      next: () => { this.actionResolving = false; },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  isDarkMarked(p: SPlayer): boolean {
    if (!this.game?.raidMods) return false;
    const list = this.game.raidMods[p.id] || [];
    return list.some(m => {
      const src = (m as any).source as string | undefined;
      return src != null && src.startsWith('CORRUPTION:MARK') && src.includes(':DSP');
    });
  }

  isDarkMarkedMe(): boolean {
    const me = this.me;
    return !!(me && this.isDarkMarked(me));
  }

  onHolyWaterChoice(choice: 'REDUCE' | 'ATTACK' | 'CLEANSE'): void {
    if (!this.game) return;
    // (optionnel : sécurité côté front)
    if (choice === 'REDUCE' && !this.canHolyWaterReduce()) return;
    if (choice === 'ATTACK' && !this.canHolyWaterAttack()) return;
    if (choice === 'CLEANSE' && !this.canHolyWaterCleanse()) return;
    this.actionResolving = true;

    this.api.resolveHolyWater(this.game.id, choice).subscribe({
      next: () => {
        this.actionResolving = false;
        // Le WS ACTION_RESOLVED rafraîchira la game, fermera la modale
        // et relancera le timer PREPHASE3 côté front.
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  canHolyWaterReduce(): boolean {
    const me = this.me;
    if (!me) return false;
    return (me.corruption || 0) > 0;
  }

  canHolyWaterAttack(): boolean {
    const g = this.game as GameSnapshot | null;
    const me = this.me;
    if (!g || !me) return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    // Au moins un vampire / serviteur vivant sur le même lieu
    return this.playersOnLocation(loc).some(p =>
      (p.role === 'VAMPIRE' || p.role === 'SERVANT') && p.hp > 0
    );
  }

  canHolyWaterCleanse(): boolean {
    const g = this.game as GameSnapshot | null;
    const me = this.me;
    if (!g || !me || !g.raidMods) return false;

    const list = g.raidMods[me.id] || [];




    return list.some(m => {
      const src = (m as any).source as string | undefined;
      if (!src) return false;
      return src.startsWith('CORRUPTION:MARK') && src.endsWith(':DSP');
    });
  }

  // Holy water jouable ? (pour bouton "J’ai fini", canUseActionNow, etc.)
  canPlayHolyWaterkHere(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;

    if (!me.actions || !me.actions.includes('EAU_BENITE')) return false;

    return (
      this.canHolyWaterReduce() ||
      this.canHolyWaterAttack() ||
      this.canHolyWaterCleanse()
    );
  }

  onOccultWeakeningTarget(targetId: string) {
    if (!this.game) return;
    this.api.resolveOccultWeakening(this.game.id, targetId).subscribe({
      next: g => {
        this.game = g;
        this.syncActionFromSnapshot(g);
      },
      error: e => this.showError(e)
    });
  }

  computeSecretPassageChoices(g: GameSnapshot, currentLoc: string | null): string[] {
    const choices = new Set<string>();

    const baseLocs = ['forest', 'quarry', 'lac', 'manor'];
    for (const code of baseLocs) {
      if (code && code !== currentLoc) {
        choices.add(code);
      }
    }

    const built = g.builtInfras || [];
    for (const infra of built) {
      switch (infra) {
        case 'SAWMILL': choices.add('sawmill'); break;
        case 'MINE': choices.add('mine'); break;
        case 'LIBRARY': choices.add('library'); break;
        case 'LABORATORY': choices.add('laboratory'); break;
        case 'BALLROOM': choices.add('ballroom'); break;
        case 'ALTAR': choices.add('altar'); break;
        case 'FORGE': choices.add('forge'); break;
      }
    }

    return [...baseLocs, ...built]
      .filter(loc => loc !== currentLoc)                    // pas le même lieu
      .filter(loc => !this.isGarlicBlockedLocation(loc));   // pas fumigé
  }

  onSecretPassageConfirm() {
    if (!this.game || !this.selectedSecretPassageLoc || this.actionResolving) return;

    this.actionResolving = true;
    this.api.resolveSecretPassage(this.game.id, this.selectedSecretPassageLoc)
      .subscribe({
        next: _g => {
          // comme pour Marque / Affaiblissement / Holy
          this.actionResolving = false;
          // Le vrai refresh viendra via ACTION_RESOLVED, mais on peut aussi
          // mettre à jour tout de suite si tu veux.
        },
        error: e => {
          this.actionResolving = false;
          this.showError(e);
        }
      });
  }

  get actionOwner() {
    const g = this.game;
    if (!g || !g.currentAction) return null;
    return g.players.find(p => p.id === g.currentAction?.ownerId) ?? null;
  }

  get actionDiceColor(): 'red' | 'blue' {
    if (this.actionMode === 'NET' || this.actionMode === "INCENDIAIRE" || this.actionMode === 'BLESSED_STAKE') return 'blue';
    if (this.actionMode === 'PIT' || this.actionMode === "CLONES_OMBRE") return 'red';
    return 'blue';
  }

  private syncActionFromSnapshot(g: GameSnapshot) {
    const act = g.currentAction;
    const meId = this.me?.id;

    // ------------------------------------------------------------------
    // PHASE4 : popups "info" (perso) -> 1 seule fois, uniquement acteur
    // et surtout : ne jamais ré-ouvrir sur les refresh (buyBonus, etc.)
    // ------------------------------------------------------------------
    if (act && g.phase === 'PHASE4'
      && (act.mode === 'CHARISMATIQUE' || act.mode === 'AVIDITE_NOCTURNE')) {

      const isActor = !!meId && act.ownerId === meId;

      // 1) Pas l'acteur -> on n'affiche pas, et on n'écrase rien (Marchand etc.)
      if (!isActor) {
        // si jamais une vieille popup info traînait, on la ferme
        if (this.actionMode === 'CHARISMATIQUE' || this.actionMode === 'AVIDITE_NOCTURNE') {
          this.actionMode = null;
          this.actionOwnerId = null;
          this.actionLocation = null;
          this.actionSelectedTargetId = null;
          this.actionRoll = null;
          this.actionBreakdownLines = [];
          this.trapEnemies = [];
          this.trapCurrentIndex = 0;
          this.showActionModal = false;
          this.incendiaireChoices = [];
          this.selectedWeather2 = null;
          this.selectedWeather3 = null;
        }
        return;
      }

      // 2) Acteur : one-shot par raid
      if (act.mode === 'CHARISMATIQUE') {
        if (this.lastCharismRaid === g.raid) return; // déjà affiché -> ne rien faire
        this.lastCharismRaid = g.raid;
      } else { // AVIDITE_NOCTURNE
        if (this.lastGreedRaid === g.raid) return; // déjà affiché -> ne rien faire
        this.lastGreedRaid = g.raid;
      }

      // 3) Afficher 1 fois + autoclose
      this.actionMode = act.mode as any;
      this.actionOwnerId = act.ownerId;
      this.actionLocation = act.location;
      this.actionSelectedTargetId = act.targetId ?? null;
      this.actionRoll = act.roll ?? null;
      this.actionBreakdownLines = act.breakdownLines ?? [];
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
      this.selectedWeather2 = null;
      this.selectedWeather3 = null;

      this.showActionModal = true;
      this.scheduleActionAutoClose();
      return;
    }

    // 1) Pas d'action ou mode non géré -> on ferme
    if (!act || (act.mode !== 'NET'
      && act.mode !== 'PIT'
      && act.mode !== 'PROVOCATION'
      && act.mode !== 'INCENDIAIRE'
      && act.mode !== 'AMBUSH'
      && act.mode !== 'LONELY'
      && act.mode !== 'BLESSED_STAKE'
      && act.mode !== 'CHARISMATIQUE'
      && act.mode !== 'PRESENCE_ECRASANTE'
      && act.mode !== 'CATACLYSME'
      && act.mode !== 'CLONES_OMBRE'
      && act.mode !== 'IMAGE_MIROIR_SETUP'
      && act.mode !== 'IMAGE_MIROIR_RESOLVE'
      && act.mode !== 'ECLIPSE'
      && act.mode !== 'BLOOD_MOON'
      && act.mode !== 'VOILE_DE_BRUME'
      && act.mode !== 'FAIM_IRREPRESSIBLE'
      && act.mode !== 'MARQUE_TENEBREUSE'
      && act.mode !== 'AFFAIBLISSEMENT_OCCULTE'
      && act.mode !== 'PASSAGE_SECRET'
      && act.mode !== 'AVIDITE_NOCTURNE'
      && act.mode !== 'EAU_BENITE')) {

      this.actionMode = null;
      this.actionOwnerId = null;
      this.actionLocation = null;
      this.actionSelectedTargetId = null;
      this.actionRoll = null;
      this.actionBreakdownLines = [];
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.showActionModal = false;
      this.incendiaireChoices = [];
      this.selectedWeather2 = null;
      this.selectedWeather3 = null;
      return;
    }

    // 2) Base commune
    this.actionMode = act.mode as any;
    this.actionOwnerId = act.ownerId;
    this.actionLocation = act.location;
    this.actionSelectedTargetId = act.targetId ?? null;
    this.actionRoll = act.roll ?? null;
    this.actionBreakdownLines = act.breakdownLines ?? [];

    // 3) NET / PIT / PROVOCATION
    if (this.actionMode === 'NET' || this.actionMode === 'PIT' || this.actionMode === 'PROVOCATION') {
      if (this.actionLocation && g.players) {
        this.trapEnemies = this.playersOnLocation(this.actionLocation)
          .filter(p => p.role === 'VAMPIRE' || p.role === 'SERVANT');
      } else {
        this.trapEnemies = [];
      }

      if (this.actionMode === 'PIT') {
        if (this.actionSelectedTargetId && this.trapEnemies.length) {
          const idx = this.trapEnemies.findIndex(p => p.id === this.actionSelectedTargetId);
          this.trapCurrentIndex = idx >= 0 ? idx : 0;
        } else {
          this.trapCurrentIndex = 0;
        }
      } else {
        this.trapCurrentIndex = 0;
      }

      this.incendiaireChoices = [];
    }

    // 3bis) EMBUSCADE
    if (this.actionMode === 'AMBUSH') {
      if (this.actionLocation && g.players) {
        this.ambushEnemies = this.playersOnLocation(this.actionLocation)
          .filter(p => p.role === 'VAMPIRE' || p.role === 'SERVANT');
      } else {
        this.ambushEnemies = [];
      }

      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
    }

    // 4) INCENDIAIRE
    if (this.actionMode === 'INCENDIAIRE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = this.computeIncendiaireChoices(g, this.actionLocation);

      if (!this.actionSelectedTargetId && this.incendiaireChoices.length === 1) {
        this.actionSelectedTargetId = this.incendiaireChoices[0];
      }
    }

    // 5) actions "info" (hors PHASE4 one-shot géré au-dessus) + autres infos
    if (this.actionMode === 'CHARISMATIQUE'
      || this.actionMode === 'PRESENCE_ECRASANTE'
      || this.actionMode === 'ECLIPSE'
      || this.actionMode === 'BLOOD_MOON'
      || this.actionMode === 'VOILE_DE_BRUME'
      || this.actionMode === 'FAIM_IRREPRESSIBLE'
      || this.actionMode === 'AVIDITE_NOCTURNE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
    }

    // 6) CATACLYSME : choix météo uniquement
    if (this.actionMode === 'CATACLYSME') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
      this.selectedWeather2 = null;
      this.selectedWeather3 = null;
    }

    // 7) CLONES_OMBRE: D4 + choix de lieux
    if (this.actionMode === 'CLONES_OMBRE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
      if (this.actionRoll === null) {
        this.clonesSelectedLocations = [];
      }
    }

    // 8) IMAGE MIROIR
    if (this.actionMode === 'IMAGE_MIROIR_RESOLVE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];

      const ownerId = act.ownerId;
      const gSnap = g;

      this.mirrorAltLocations = gSnap.mirrorAltLocations || [];

      const cb = gSnap.center?.find(c => c.playerId === ownerId) || null;
      this.mirrorPrimaryLoc = cb ? cb.card : null;

      this.mirrorChoices = [];
      this.mirrorHuntersByLoc = {};

      if (this.mirrorPrimaryLoc) {
        this.mirrorChoices.push(this.mirrorPrimaryLoc);
      }
      for (const loc of this.mirrorAltLocations) {
        if (loc && !this.mirrorChoices.includes(loc)) {
          this.mirrorChoices.push(loc);
        }
      }

      for (const loc of this.mirrorChoices) {
        this.mirrorHuntersByLoc[loc] = this.playersOnLocation(loc).filter(p => p.role === 'HUNTER');
      }
    }

    // 9) MARQUE_TENEBREUSE / EAU_BENITE / AFFAIBLISSEMENT_OCCULTE
    if (this.actionMode === 'MARQUE_TENEBREUSE'
      || this.actionMode === 'EAU_BENITE'
      || this.actionMode === 'AFFAIBLISSEMENT_OCCULTE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
    }

    // 9B) pause timer pour les actions qui bloquent la préphase
    if (this.actionMode === 'MARQUE_TENEBREUSE'
      || this.actionMode === 'EAU_BENITE'
      || this.actionMode === 'AFFAIBLISSEMENT_OCCULTE'
      || this.actionMode === 'PROVOCATION'
      || this.actionMode === 'AMBUSH'
      || this.actionMode === 'PASSAGE_SECRET') {
      this.stopPrephaseTimer();
    }

    // 10) PASSAGE_SECRET
    if (this.actionMode === 'PASSAGE_SECRET') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];

      const me = this.me;
      const loc = me ? this.locationOf(me.id) : null;
      this.secretPassageChoices = this.computeSecretPassageChoices(g, loc);

      if (!this.selectedSecretPassageLoc && this.secretPassageChoices.length === 1) {
        this.selectedSecretPassageLoc = this.secretPassageChoices[0];
      }

      this.stopPrephaseTimer();
    }

    // ---- Affichage modales "info" : uniquement l'acteur (grâce au guard en haut) ----
    if (this.actionMode === 'CHARISMATIQUE') {
      const alreadyShown = this.lastCharismRaid === g.raid;
      if (!alreadyShown) {
        this.lastCharismRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();
      }
    }

    if (this.actionMode === 'AVIDITE_NOCTURNE') {
      const alreadyShown = this.lastGreedRaid === g.raid;
      if (!alreadyShown) {
        this.lastGreedRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();
      }
    }

    // 11) Afficher la modale / timers
    if (this.actionMode === 'PRESENCE_ECRASANTE') {
      const alreadyShown = this.lastPresenceRaid === g.raid;

      if (!alreadyShown) {
        this.lastPresenceRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }

    } else if (this.actionMode === 'ECLIPSE') {
      const alreadyShown = this.lastEclipseRaid === g.raid;

      if (!alreadyShown) {
        this.lastEclipseRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }
    } else if (this.actionMode === 'LONELY') {
      const ts = (act as any)?.resolvedAtMillis ?? 0;

      // évite de re-pop la même exécution (refresh / getGame répétée)
      if (ts && ts <= this.lastLonelyShownAt) return;
      this.lastLonelyShownAt = ts;

      this.showActionModal = true;
      this.scheduleActionAutoClose();

      if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
        this.startPrephaseTimer();
      }
    } else if (this.actionMode === 'BLOOD_MOON') {
      const alreadyShown = this.lastBloodMoonRaid === g.raid;

      if (!alreadyShown) {
        this.lastBloodMoonRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }

    } else if (this.actionMode === 'VOILE_DE_BRUME') {
      const alreadyShown = this.lastFogRaid === g.raid;

      if (!alreadyShown) {
        this.lastFogRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }

    } else if (this.actionMode === 'FAIM_IRREPRESSIBLE') {
      this.showActionModal = true;
      this.scheduleActionAutoClose();

      if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
        this.startPrephaseTimer();
      }

    } else if (this.actionMode === 'CATACLYSME') {
      const resolved = !!act.targetId;

      if (!resolved) {
        this.showActionModal = true;
      } else {
        const alreadyShown = this.lastCataclysmeRaid === g.raid;

        if (!alreadyShown) {
          this.lastCataclysmeRaid = g.raid;
          this.showActionModal = true;
          this.scheduleActionAutoClose();
        }
      }

    } else if (this.actionMode === 'MARQUE_TENEBREUSE'
      || this.actionMode === 'PROVOCATION'
      || this.actionMode === 'AMBUSH'
      || this.actionMode === 'BLESSED_STAKE'
      || this.actionMode === 'EAU_BENITE'
      || this.actionMode === 'PASSAGE_SECRET'
      || this.actionMode === 'AFFAIBLISSEMENT_OCCULTE') {

      this.showActionModal = true;

    } else {
      this.showActionModal = true;
    }
  }

  private syncMerchantUiFromSnapshot(g: GameSnapshot) {
    const me: any = this.me;
    if (!g || !me || me.role !== 'HUNTER') return;

    const act: any = (g as any).currentAction;

    // En PHASE4, CHARISMATIQUE/AVIDITE sont des popups "info" -> elles ne doivent JAMAIS bloquer Marchand
    const isP4Info =
      (g.phase === 'PHASE4')
      && (act?.mode === 'CHARISMATIQUE' || act?.mode === 'AVIDITE_NOCTURNE');

    // Si une vraie action serveur est en cours (non marchand, non info PHASE4) -> on ne touche pas
    if (act?.mode && !isP4Info && act.mode !== 'MARCHAND_ITINERANT' && act.mode !== 'MARCHAND_BONUS_BUY') {
      return;
    }

    // Si je suis en train de lire MA popup info PHASE4, ne pas l'écraser (2-5s),
    // Marchand s'ouvrira juste après quand la popup se ferme.
    if (this.showActionModal
      && (this.actionMode === 'CHARISMATIQUE' || this.actionMode === 'AVIDITE_NOCTURNE')
      && this.actionOwnerId === me.id) {
      return;
    }

    // 1) modale paiement (perso)
    if (g.phase === 'PHASE4' && !!me.shopBonusBuyPending) {
      this.actionMode = 'MARCHAND_BONUS_BUY' as any;
      this.actionOwnerId = me.id;
      this.actionLocation = null;
      this.actionSelectedTargetId = null;
      this.actionRoll = null;
      this.actionBreakdownLines = [];
      this.showActionModal = true;
      return;
    }

    // 2) modale jet D6 (perso)
    if (g.phase === 'PHASE4' && !!me.merchantPending) {
      this.actionMode = 'MARCHAND_ITINERANT' as any;
      this.actionOwnerId = me.id;
      this.actionLocation = null;
      this.actionSelectedTargetId = null;
      this.actionRoll = null;
      this.actionBreakdownLines = [];
      this.showActionModal = true;
      return;
    }

    // 3) afficher résultat une seule fois (5s)
    const roll = me.merchantRoll;
    const kind = me.shopBonusKind;
    const equip = me.shopBonusEquipId;

    if (g.phase === 'PHASE4' && roll != null && !!kind) {
      const key = `${g.raid}|${roll}|${kind}|${equip || ''}`;
      if (this.lastMerchantShownKey !== key) {
        this.lastMerchantShownKey = key;

        this.actionMode = 'MARCHAND_ITINERANT' as any;
        this.actionOwnerId = me.id;
        this.actionLocation = null;
        this.actionSelectedTargetId = null;
        this.actionRoll = roll;
        this.actionBreakdownLines = [];
        this.showActionModal = true;
        this.scheduleActionAutoClose();
        return;
      }
    }

    // 4) si on était sur une modale marchand mais plus rien -> fermer
    if (this.actionMode === 'MARCHAND_ITINERANT' || this.actionMode === 'MARCHAND_BONUS_BUY') {
      this.actionMode = null;
      this.actionOwnerId = null;
      this.actionLocation = null;
      this.actionSelectedTargetId = null;
      this.actionRoll = null;
      this.actionBreakdownLines = [];
      this.showActionModal = false;
    }
  }

  // ====== Corruption / morsure ======/
  get showBiteModal(): boolean {
    const g = this.game as any;
    return g?.phase === 'PHASE3' && !!g?.currentBite && Date.now() >= this.biteNotBeforeMillis;
  }

  biteAttacker(): SPlayer | undefined {
    const id = (this.game as any)?.currentBite?.attackerId;
    return id ? this.getPlayer(id) : undefined;
  }

  biteTarget(): SPlayer | undefined {
    const id = (this.game as any)?.currentBite?.targetId;
    return id ? this.getPlayer(id) : undefined;
  }

  rollCorruption() {
    if (!this.game) return;
    this.api.rollCorruption(this.game.id).subscribe({
      error: e => this.showError(e)
    });
  }

  // 1) Détermine la "phase" actuelle de la morsure : D6, D4, terminé
  biteStage(): 'BITE' | 'ARMOR' | 'DONE' {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b) return 'DONE';

    // Pas encore de jet de morsure → D20
    if (b.roll == null) return 'BITE';

    // Jet de morsure fait : voir si armure spéciale intervient
    const target = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(target);

    if (b.roll > 8 && hasArmor && b.armorRoll == null) {
      return 'ARMOR';
    }

    return 'DONE';
  }

  // 2) Est-ce qu’on affiche le D4 d’armure ?
  get showArmorDice(): boolean {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b) return false;
    const target = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(target);
    return hasArmor && b.roll != null;
  }

  // 3) Qui peut lancer le D20 de morsure ?
  canRollBite(): boolean {
    const g = this.game;
    const b = g?.currentBite;
    if (!g || !b) return false;
    if (b.roll != null) return false; // déjà lancé

    const meId = this.me?.id;
    if (!meId) return false;

    // Seul le vampire lance le D20
    const me = this.getPlayer(meId);
    return me?.role === 'VAMPIRE';
  }

  // 4) Qui peut lancer le D4 d’armure ?
  canRollArmor(): boolean {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!g || !b) return false;

    if (b.roll == null) return false;           // pas encore de morsure
    if (b.armorRoll != null) return false;      // déjà fait

    const meId = this.me?.id;
    if (!meId || meId !== b.targetId) return false;

    const target = this.getPlayer(meId);
    return this.hasSilverPlate(target);
  }

  // 5) Action : jet d’armure (appel le même endpoint back)
  rollArmor() {
    if (!this.game) return;
    this.api.rollCorruption(this.game.id).subscribe({
      error: e => this.showError(e)
    });
  }

  // 6) Résumé texte du résultat final
  biteResultText(): string {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b || b.roll == null) return '';

    const attacker = this.getPlayer?.(b.attackerId)?.username ?? 'Le vampire';
    const target = this.getPlayer?.(b.targetId)?.username ?? 'le chasseur';

    const targetPlayer = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(targetPlayer);

    // Échec direct du D6
    if (b.roll <= 8) {
      return `${attacker} échoue sa tentative de morsure.`;
    }

    if (this.isSacredRosaryUsed) {
      return `${attacker} est repoussé par un chapelet sacré.`;
    }

    // Pas d'armure spéciale → morsure réussie
    if (!hasArmor || b.armorRoll == null) {
      // Check if player became servant
      if (b.becameServant) {
        return `${target} est mordu et succombe à la corruption. ${target} devient un serviteur du vampire !`;
      }
      return `${target} est mordu. Le sang versé nourrit le vampire: +50 âmes déchues.`;
    }

    if (b.armorRoll <= 3) {
      // Check if player became servant
      if (b.becameServant) {
        return `${target} est mordu malgré son armure d'argent et succombe à la corruption. ${target} devient un serviteur du vampire !`;
      }
      return `${target} est mordu malgré son armure d'argent.`;
    } else {
      return `L'armure d'argent de ${target} le protège de la morsure.`;
    }
  }

  altarRitualText(): string {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b || b.roll == null) return '';

    const isAltarFight = g.currentCombat?.location === 'altar';
    if (!isAltarFight || g.altarCorrupted) return '';

    const target = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(target);

    let success = false;
    if (b.roll > 8) {
      if (!hasArmor || b.armorRoll == null) {
        success = true;
      } else {
        // même hypothèse que ci-dessus : <= 3 = morsure réussie
        success = b.armorRoll <= 3;
      }
    }

    if (success) {
      return `Le rituel est accompli: l'autel est profané.`;
    }
    return '';
  }

  // 7) Test d’armure argent (à adapter au champ réel du snapshot)
  private hasSilverPlate(p: SPlayer | undefined): boolean {
    if (!p) return false;
    // Adapte ce test au nom réel de l’armure dans ton snapshot :
    // ex : (p as any).armor === 'SILVER_PLATE'
    return (p as any).armor === 'H_ARMOR_T3_PLATE_SILVER';
  }

  // Conditions d’ouverture de la modale (seulement vampire + PREPHASE3 + choix restants)
  showUnstableModal(): boolean {
    return !!this.game
      && this.game.phase === 'PREPHASE3'
      && this.isMeVampire
      && this.pendingUnstable();
  }
  unstableEntries() {
    const g = this.game as any;
    if (!g) return [];
    const eligT = g.unstableEligibleTargets || {};
    const eligL = g.unstableEligibleLocations || {};
    const ids = new Set<string>([...Object.keys(eligT), ...Object.keys(eligL)]);
    return [...ids].map(uId => ({
      unstableId: uId,
      targets: eligT[uId] || [],
      locations: eligL[uId] || []
    }));
  }
  pendingUnstable(): boolean {
    const g = this.game as any;
    if (!g) return false;
    const t = g.unstableEligibleTargets || {};
    const l = g.unstableEligibleLocations || {};
    return Object.keys(t).length > 0 || Object.keys(l).length > 0;
  }

  assignUnstableTarget(unstableId: string, targetId: string) {
    if (!this.isMeVampire || this.isUnstableLocked(unstableId)) return;

    this.lockUnstable(unstableId);
    this.api.assignUnstableTarget(this.gameId, unstableId, targetId).subscribe({
      next: () => { },
      error: e => {
        // 409 "no pending unstable choice" => le serveur a déjà pris une décision : on laisse lock
        if (!(e?.status === 409 || e?.error?.message === 'no pending unstable choice')) {
          this.unlockUnstable(unstableId); // vraie erreur -> permettre un retry
        }
        this.showError(e);
      }
    });
  }

  assignUnstableHarvest(unstableId: string, loc: string) {
    if (!this.isMeVampire || this.isUnstableLocked(unstableId)) return;

    this.lockUnstable(unstableId);
    this.api.assignUnstableHarvest(this.gameId, unstableId, loc).subscribe({
      next: () => { },
      error: e => {
        if (!(e?.status === 409 || e?.error?.message === 'no pending unstable choice')) {
          this.unlockUnstable(unstableId);
        }
        this.showError(e);
      }
    });
  }

  assignUnstableNothing(unstableId: string) {
    if (!this.isMeVampire || !this.isPendingUnstable(unstableId)) return;

    this.api.assignUnstableNothing(this.gameId, unstableId).subscribe({
      next: _ => {
        // Resync — garantit que la modale se met à jour tout de suite
        this.api.getGame(this.gameId).subscribe({
          next: g => { this.game = g; this.recomputeUnstableChoices(); },
          error: e => this.showError(e)
        });
      },
      error: e => this.showError(e)
    });
  }

  // Garder l’array stable et ne le remplacer que si le contenu change
  unstableChoices: Array<{ unstableId: string; targets: string[]; locations: string[] }> = [];
  trackByUnstable = (_i: number, it: { unstableId: string }) => it.unstableId;

  private recomputeUnstableChoices() {
    const g: any = this.game;
    if (!g) { this.unstableChoices = []; return; }

    const t: Record<string, string[]> = g.unstableEligibleTargets || {};
    const L: Record<string, string[]> = g.unstableEligibleLocations || {};

    const next: Array<{ unstableId: string; targets: string[]; locations: string[] }> = [];
    const ids = new Set([...Object.keys(t || {}), ...Object.keys(L || {})]);
    for (const uid of ids) {
      next.push({
        unstableId: uid,
        targets: (t?.[uid] ?? []).slice(),
        locations: (L?.[uid] ?? []).slice(),
      });
    }

    if (JSON.stringify(this.unstableChoices) !== JSON.stringify(next)) {
      this.unstableChoices = next;
    }

    // ➜ purge des locks pour les IDs qui ne sont plus éligibles (ou déjà décidés)
    const present = new Set(this.unstableChoices.map(x => x.unstableId));
    const decidedIds = new Set<string>([
      ...Object.keys(g.unstableTargetByPlayer || {}),
      ...Object.keys(g.unstableHarvestLocByPlayer || {}),
    ]);
    for (const id of Array.from(this.unstableLockedIds)) {
      if (!present.has(id) || decidedIds.has(id)) this.unstableLockedIds.delete(id);
    }
  }

  vampireName(): string {
    const v = this.game?.players.find(p => p.role === 'VAMPIRE');
    return v?.username || v?.id || 'vampire';
  }

  // Texte d’avertissement si l’instable va combattre par défaut (même lieu qu’un ennemi)
  // Retourne null sinon (aucun avertissement à afficher)
  getUnstableDefaultFight(unstableId: string): DefaultFightInfo {
    const g = this.game;
    if (!g || g.phase !== 'PREPHASE3') return { willFight: false };

    const unstable = g.players.find(p => p.id === unstableId);
    if (!unstable || unstable.role !== 'HUNTER' || unstable.hp <= 0) return { willFight: false };

    // lieu face-up de l’instable
    const cb = (g.center || []).find(c => c.playerId === unstableId && c.faceUp);
    if (!cb) return { willFight: false };
    const loc = cb.card;

    // joueurs présents (face-up) sur ce lieu
    const idsOnLoc = (g.center || []).filter(c => c.faceUp && c.card === loc).map(c => c.playerId);
    const ppl = idsOnLoc
      .map(id => g.players.find(p => p.id === id))
      .filter((p): p is SPlayer => !!p);

    // adversaire prioritaire : vampire, sinon 1er serviteur
    const opponent = ppl.find(p => p.role === 'VAMPIRE') || ppl.find(p => p.role === 'SERVANT');
    if (!opponent) return { willFight: false };

    const opponentName = opponent.username || (opponent.role === 'VAMPIRE' ? 'vampire' : 'serviteur');
    return { willFight: true, loc, opponentName };
  }

  unstableHarvestWarning(unstableId: string): string | null {
    const info = this.getUnstableDefaultFight(unstableId);
    if (!info.willFight) return null;

    const unstableName = this.usernameOf(unstableId);
    const locName = this.labelLocation(info.loc!);

    return `${unstableName} va bientôt combattre sur ${locName} contre ${info.opponentName}. ` +
      `Si vous lui ordonnez une récolte, cela annulera son combat !`;
  }

  // encore éligible ? (présent dans l’un des deux maps serveur)
  isPendingUnstable(id: string): boolean {
    const g: any = this.game; if (!g) return false;
    const t = g.unstableEligibleTargets || {};
    const l = g.unstableEligibleLocations || {};
    return !!(t[id] || l[id]);
  }

  //====== Maintenance ======/
  // Helpers Maintenance:
  private openShop() {
    document.body.classList.add('modal-open');
    this.shopOpen = true;
    this.waitingDone = false;
    this.myOffer = {};
  }

  private closeShop() {
    document.body.classList.remove('modal-open');
    this.shopOpen = false;
    this.waitingDone = false;
    this.stopPhase4Timer();
  }

  private syncShopVisibilityFromSnapshot(): void {
    const inPhase4 = this.game?.phase === 'PHASE4';

    // ouvrir/fermer la modale
    if (inPhase4 && !this.shopOpen) this.openShop();
    if (!inPhase4 && this.shopOpen) this.closeShop();

    // (re)lancer le timer local (comme pour préphase)
    const deadline = (this.game as any)?.phase4DeadlineMillis as number | undefined;
    if (inPhase4) this.startPhase4Timer(deadline ?? null);

    // IMPORTANT : garder l’état “En attente…” après reload
    const ready = (this.game as any)?.readyForNextRaid as string[] | undefined;
    this.waitingDone = Array.isArray(ready) ? ready.includes(this.meId) : false;
  }

  // Retourne MON statut (A ou B selon que je suis aId ou bId)
  public myStatus(t: STrade): TradeStatus {
    const iAmA = (t.aId === this.me?.id);
    return iAmA ? t.statusA : t.statusB;
  }

  // Retourne le statut de l’AUTRE
  public otherStatus(t: STrade): TradeStatus {
    const iAmA = (t.aId === this.me?.id);
    return iAmA ? t.statusB : t.statusA;
  }

  statusClassFrom(st: TradeStatus): string {
    if (st === 'CONFIRMED') return 'ok';
    if (st === 'REFUSED' || st === 'CANCELLED') return 'ko';
    return ''; // PENDING
  }

  deckSize(pile: Pile | null | undefined): number {
    return pile?.deck ?? 0;
  }

  discardSize(pile: Pile | null | undefined): number {
    return pile?.discard ?? 0;
  }

  get canBuyPotion() {
    const me = this.me;
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.potions);
    if (left <= 0) return false;

    return me.water >= 4 && me.herbs >= 3;
  }

  get canBuyElixir() {
    const me = this.me;
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.elixirs);
    if (left <= 0) return false;

    return me.water >= 4 && me.herbs >= 3;
  }

  get canBuyVampAction() {
    const me = this.me;
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.actionsVamp);
    if (left <= 0) return false;

    return me.souls >= 50;
  }

  get canBuyHunterAction() {
    const me = this.me;
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.actionsHunters);
    if (left <= 0) return false;

    return me.gold >= this.actionPrice;
  }

  get canBuySilver() {
    const me = this.me;
    if (!me || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;
    return me.gold >= this.silverPrice;
  }

  canBuySilverQty(qty: number): boolean {
    const me = this.me;
    if (!me || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;
    const unit = this.silverPrice;
    const cost = unit * qty;
    return me.gold >= cost;
  }

  get canBuyHolyWaterAction(): boolean {
    const me = this.me;
    const g = this.game;
    if (!me || !g || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;

    const goldCost = this.holyWaterGoldPrice;
    const waterCost = 3;

    if (me.water < waterCost) return false;
    if (me.gold < goldCost) return false;

    return true;
  }

  get canBuyTrackingAction(): boolean {
    const me = this.me;
    const g = this.game;
    if (!me || !g || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;

    const goldCost = this.trackingGoldPrice;

    if (me.gold < goldCost) return false;

    return true;
  }


  iAmA(t: STrade): boolean { return t.aId === this.me?.id; }
  otherIdFromTrade(t: STrade): string { return this.iAmA(t) ? t.bId : t.aId; }

  tradeResources(): string[] {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return this.allResources;

    if (me.role === 'HUNTER') {
      return this.allResources.filter(r => r !== 'souls');
    }

    if (me.role === 'VAMPIRE' || me.role === 'SERVANT') {
      return this.allResources.filter(r => r !== 'gold');
    }

    return this.allResources;
  }

  get actionPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 50;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  get silverPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 50;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  get holyWaterGoldPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 150;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  get trackingGoldPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 100;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  get salesAmount(): number {
    const g = this.game;
    const me = this.me;
    let amount = 10;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      amount = Math.max(0, amount + 10);
    }
    return amount;
  }

  // UI Maintenance actions
  // --- Boutique --- //
  onBuyAction(ev: MouseEvent) {
    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyAction(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }

  onBuyPotion(ev: MouseEvent) {
    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyPotion(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }
  onBuySilver(qty: number) {
    this.api.buySilver(this.gameId, qty).subscribe({
      next: () => { },
      error: e => this.showError(e)
    });
  }
  onBuyHolyWaterAction(ev: MouseEvent): void {
    if (!this.game) return;

    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyHolyWaterAction(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }
  onBuyTrackingAction(ev: MouseEvent): void {
    if (!this.game) return;

    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyTrackingAction(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }
  onSell(res: 'wood' | 'herbs' | 'stone' | 'iron' | 'water', qty: number) {
    this.api.sellResource(this.gameId, res, qty).subscribe({
      next: () => { },
      error: e => this.showError(e)
    });
  }
  onTransmute(recipe: 'WOOD_TO_IRON' | 'IRON_TO_WOOD' | 'TRINITY_TO_SOULS') {
    this.api.transmute(this.gameId, recipe).subscribe({
      next: () => { },
      error: e => this.showError(e)
    });
  }
  onFinishPhase4() {
    this.waitingDone = true;
    this.api.finishPhase4(this.gameId).subscribe({
      next: () => { },
      error: e => this.showError(e)
    });
  }

  // --- Échanges --- //
  get eligibleTradeTargets(): SPlayer[] {
    if (!this.game || !this.me) return [];
    if (this.isHunter) {
      return this.game.players.filter((p: SPlayer) => p.role === 'HUNTER' && p.id !== this.me!.id && p.hp > 0);
    }
    if (this.me!.role === 'VAMPIRE') {
      return this.game.players.filter((p: SPlayer) => p.role === 'SERVANT' && p.hp > 0);
    }
    return this.game.players.filter((p: SPlayer) => p.role === 'VAMPIRE' && p.hp > 0);
  }

  // quand tu changes de cible : sauvegarde l’ancienne, recharge la nouvelle
  selectTradeTarget(targetId: string): void {
    if (this.selectedTradeTargetId) {
      this.myOffersByTarget[this.selectedTradeTargetId] = { ...this.myOffer };
    }
    this.selectedTradeTargetId = targetId;
    this.myOffer = { ...(this.myOffersByTarget[targetId] || {}) };
  }



  // quand on modifie l’offre, maj aussi de la mémoire et push au back
  bumpOffer(res: string, delta: number): void {
    const cur = this.myOffer[res] ?? 0;
    const next = Math.max(0, cur + delta);
    if (next === 0) delete this.myOffer[res];
    else this.myOffer[res] = next;

    if (this.selectedTradeTargetId) {
      this.myOffersByTarget[this.selectedTradeTargetId] = { ...this.myOffer };
      this.api.tradeOffer(this.gameId, this.selectedTradeTargetId, this.myOffer).subscribe({
        next: () => { },
        error: e => this.showError(e)
      });
    }
  }

  tradeForSelected(): STrade | undefined {
    if (!this.game?.trades || !this.selectedTradeTargetId || !this.me) return undefined;
    const meId = this.me.id;
    return this.game.trades.find((t: STrade) =>
      (t.aId === meId && t.bId === this.selectedTradeTargetId) ||
      (t.bId === meId && t.aId === this.selectedTradeTargetId)
    );
  }

  myTradesSorted(): STrade[] {
    if (!this.game?.trades || !this.me) return [];
    const meId = this.me.id;

    return this.game.trades
      .filter(t => t.aId === meId || t.bId === meId)
      // je ne montre pas un trade si MON statut est cancel/refuse
      .filter(t => {
        const st = this.myStatus(t);
        return st !== 'CANCELLED' && st !== 'REFUSED';
      })
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  }

  onTradeAction(action: 'confirm' | 'refuse' | 'cancel'): void {
    const targetId = this.selectedTradeTargetId; // capture
    if (!targetId) return;

    if (action === 'cancel') {
      this.selectedTradeTargetId = null; // UI immédiate locale
      this.myOffer = {};
    }

    this.api.tradeAction(this.gameId, action, targetId).subscribe({
      next: () => { },
      error: e => this.showError(e)
    });
  }

  onTradeActionFor(action: 'confirm' | 'refuse' | 'cancel', targetId: string): void {
    if (action === 'cancel') {
      // 2.1 ferme "Mes ressources"
      if (this.selectedTradeTargetId === targetId) {
        delete this.myOffersByTarget[targetId];
        this.selectedTradeTargetId = null;
        this.myOffer = {};
      }

      // 2.2 pose MON statut à CANCELLED en local => le filtre ci-dessus masquera le bloc tout de suite
      const meId = this.me?.id;
      if (this.game?.trades && meId) {
        const t = this.game.trades.find(x =>
          (x.aId === meId && x.bId === targetId) ||
          (x.bId === meId && x.aId === targetId)
        );
        if (t) {
          if (t.aId === meId) t.statusA = 'CANCELLED';
          else t.statusB = 'CANCELLED';
          t.updatedAt = Date.now();
        }
      }

      // tick UI
      this.game = { ...(this.game as GameSnapshot) };
    }

    // appel serveur (confirmera l’état et/ou supprimera plus tard)
    this.api.tradeAction(this.gameId, action, targetId).subscribe({
      next: () => { },
      error: e => this.showError(e)
    });
  }

  isClosing(id: string): boolean { return Date.now() < (this.closingUntil[id] || 0); }
  isClosingOk(id: string): boolean { return this.isClosing(id) && this.closingKind[id] === 'ok'; }
  isClosingKo(id: string): boolean { return this.isClosing(id) && this.closingKind[id] === 'ko'; }

  // --- Toasts (2,5 s) par cible ---
  private flashByTarget: Record<string, { text: string; kind: 'ok' | 'ko'; until: number }> = {};

  activeFlashes() {
    const now = Date.now();
    return Object.entries(this.flashByTarget)
      .filter(([, v]) => v.until > now)
      .map(([targetId, v]) => ({ targetId, ...v }));
  }
  private setFlashFor(targetId: string, text: string, kind: 'ok' | 'ko', ms = 2500) {
    this.flashByTarget[targetId] = { text, kind, until: Date.now() + ms };
  }

  // --- Format ressources pour le message succès ---
  private labelFr(k: string): string {
    switch (k) {
      case 'wood': return 'bois'; case 'herbs': return 'herbes'; case 'stone': return 'pierre';
      case 'iron': return 'fer'; case 'water': return 'eau'; case 'gold': return 'or';
      case 'souls': return 'âmes'; case 'silver': return 'argent'; default: return k;
    }
  }
  private packToText(pack?: Record<string, number>): string {
    if (!pack) return 'rien';
    const entries = Object.entries(pack).filter(([_, q]) => (q || 0) > 0);
    if (!entries.length) return 'rien';
    return entries.map(([k, q]) => `${this.labelFr(k)} x${q}`).join(', ');
  }

  // animation pioche
  private flyDomToViewportBottom(src: string, rect: DOMRect) {
    const el = document.createElement('img');
    el.src = src;

    // style de base
    Object.assign(el.style, {
      position: 'fixed',
      left: `${rect.left}px`,
      top: `${rect.top}px`,
      width: `${rect.width}px`,
      height: `${rect.height}px`,
      borderRadius: '10px',
      boxShadow: '0 12px 30px rgba(0,0,0,.35)',
      zIndex: '2147483647',
      pointerEvents: 'none',
      transform: 'translate3d(0,0,0) scale(1)',
      opacity: '1'
    } as Partial<CSSStyleDeclaration>);

    document.body.appendChild(el);

    const startX = rect.left + rect.width / 2;
    const startY = rect.top + rect.height / 2;

    const endX = window.innerWidth / 2;
    const endY = window.innerHeight - 24;

    const dx = endX - startX;
    const dy = endY - startY;

    const anim = el.animate(
      [
        { transform: 'translate3d(0,0,0) scale(1)', opacity: 1 },
        { transform: `translate3d(${dx}px, ${dy}px, 0) scale(0.30)`, opacity: 0.15 }
      ],
      { duration: 1500, easing: 'cubic-bezier(.2,.8,.2,1)', fill: 'forwards' }
    );

    anim.onfinish = () => el.remove();
    anim.oncancel = () => el.remove();
  }

  // --- TIERS (miroir back) ---
  private hunterWeaponTier(weaponId?: string | null): number {
    if (!weaponId) return 0;
    if (weaponId.startsWith("H_WEAPON_T1_")) return 1;
    if (weaponId.startsWith("H_WEAPON_T2_")) return 2;
    if (weaponId.startsWith("H_WEAPON_T3_")) return 3;
    return 0;
  }
  private hunterArmorTier(armorId?: string | null): number {
    if (!armorId) return 0;
    if (armorId.startsWith("H_ARMOR_T1_")) return 1;
    if (armorId.startsWith("H_ARMOR_T2_")) return 2;
    if (armorId.startsWith("H_ARMOR_T3_")) return 3;
    return 0;
  }

  // --- OFFRE : tier+1, cap à 2 ---
  private nextWeaponTier(): number | null {
    const me = this.me;
    if (!me || me.role !== 'HUNTER') return null;
    const cur = this.hunterWeaponTier((me as any).weapon);
    if (cur >= 2) return null;
    return cur + 1;
  }
  private nextArmorTier(): number | null {
    const me = this.me;
    if (!me || me.role !== 'HUNTER') return null;
    const cur = this.hunterArmorTier((me as any).armor);
    if (cur >= 2) return null;
    return cur + 1;
  }

  // --- ASSETS ---
  private stuffImg(file: string): string {
    return `/assets/cards/stuff/${file}`;
  }

  // Armure : pas random
  private armorOfferFile(): string | null {
    const t = this.nextArmorTier();
    if (t == null) return null;
    return `A_T${t}_HUNTER.png`;
  }

  // Offre canon (ce que tu affiches ET ce que tu envoies à l’achat)
  private myWeaponOffer(): { tier: number; type: 'BLEED' | 'RANGE' | 'STUN' } | null {
    const t = this.nextWeaponTier();
    if (t == null) return null;

    const g: any = this.game;
    const meId = this.me?.id;
    if (!g || !meId) return { tier: t, type: 'BLEED' };

    const mapTier = g.shopWeaponOfferTierByHunter as Record<string, number> | undefined;
    const mapType = g.shopWeaponOfferTypeByHunter as Record<string, 'BLEED' | 'RANGE' | 'STUN'> | undefined;

    const srvTier = mapTier?.[meId];
    const srvType = mapType?.[meId];

    const type: 'BLEED' | 'RANGE' | 'STUN' = (srvTier === t && srvType) ? srvType : 'BLEED';
    return { tier: t, type };
  }

  // Image affichée = offre canon
  private weaponOfferFile(): string | null {
    const offer = this.myWeaponOffer();
    if (!offer) return null;
    return `W_T${offer.tier}_${offer.type}_HUNTER.png`;
  }

  weaponUpgradeImgSrc(): string {
    const file = this.weaponOfferFile();
    return file ? this.stuffImg(file) : '/assets/icons/lock.png';
  }
  armorUpgradeImgSrc(): string {
    const file = this.armorOfferFile();
    return file ? this.stuffImg(file) : '/assets/icons/lock.png';
  }

  weaponUpgradeTitle(): string {
    const t = this.nextWeaponTier();
    if (t == null) return "Arme (max)";
    return `Acheter une arme T${t}`;
  }
  armorUpgradeTitle(): string {
    const t = this.nextArmorTier();
    if (t == null) return "Armure (max)";
    return `Acheter une armure T${t}`;
  }

  weaponUpgradeCost(): { wood: number; iron: number } | null {
    const t = this.nextWeaponTier();
    if (t == null) return null;
    return t === 1 ? { wood: 3, iron: 3 } : { wood: 4, iron: 4 };
  }
  armorUpgradeCost(): { wood: number; iron: number } | null {
    const t = this.nextArmorTier();
    if (t == null) return null;
    return t === 1 ? { wood: 3, iron: 3 } : { wood: 4, iron: 4 };
  }

  canBuyUpgradeWeapon(): boolean {
    const me = this.me;
    const c = this.weaponUpgradeCost();
    if (!me || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;
    if (!c) return false;
    return me.wood >= c.wood && me.iron >= c.iron;
  }
  canBuyUpgradeArmor(): boolean {
    const me = this.me;
    const c = this.armorUpgradeCost();
    if (!me || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;
    if (!c) return false;
    return me.wood >= c.wood && me.iron >= c.iron;
  }

  onBuyUpgradeWeapon(ev: MouseEvent): void {
    const offer = this.myWeaponOffer();
    if (!offer) return;

    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;

    const src = img?.src;
    const rect = img?.getBoundingClientRect();

    this.api.buyUpgradeWeapon(this.gameId, offer).subscribe({
      next: () => {
        if (src && rect) this.flyDomToViewportBottom(src, rect);
      },
      error: e => this.showError(e)
    });
  }

  onBuyUpgradeArmor(ev: MouseEvent): void {
    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;

    const src = img?.src;
    const rect = img?.getBoundingClientRect();

    this.api.buyUpgradeArmor(this.gameId).subscribe({
      next: () => {
        if (src && rect) this.flyDomToViewportBottom(src, rect);
      },
      error: e => this.showError(e)
    });
  }


  // Construction
  openBuildModal() {
    if (!this.game || !this.me) return;
    if (this.game.phase !== 'PHASE2') return;
    if (this.me.role !== 'VAMPIRE') return;

    this.buildChoice = null;
    this.buildConfirmModalOpen = false;
    this.buildModalOpen = true;
  }

  closeBuildModal() {
    this.buildModalOpen = false;
  }

  onChooseInfra(infra: 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE') {
    this.buildChoice = infra;
    this.buildModalOpen = false;
    this.buildConfirmModalOpen = true;
    this.zoomLeave();
  }

  cancelBuild() {
    this.buildConfirmModalOpen = false;
    this.buildChoice = null;
  }

  getInfraConfirmText(infra: 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE'): string {
    if (infra === 'SAWMILL') {
      return 'Se déplacer à la Forêt pour construire la Scierie ?';
    }
    if (infra === 'MINE') {
      return 'Se déplacer à la Carrière pour construire la Mine ?';
    }
    if (infra === 'LIBRARY') {
      return 'Se déplacer au Manoir pour construire la Bibliothèque ?';
    }
    if (infra === 'LABORATORY') {
      return 'Se déplacer au Manoir pour construire le Laboratoire ?';
    }
    if (infra === 'BALLROOM') {
      return 'Se déplacer au Manoir pour construire la salle de bal ?';
    }
    if (infra === 'ALTAR') {
      return 'Se déplacer au Manoir pour construire l\'autel ?';
    }
    if (infra === 'FORGE') {
      return 'Se déplacer au Manoir pour construire la forge ?';
    }
    return '';
  }

  get isWindActive(): boolean {
    const w = this.game?.weather;
    if (!w) return false;
    return w.status === 'WIND' || w.secondaryStatus === 'WIND';
  }

  doBuild() {
    if (!this.game || !this.buildChoice) return;

    this.api.planConstruction(this.game.id, this.buildChoice).subscribe({
      next: () => {
        this.buildConfirmModalOpen = false;
        this.buildChoice = null;
      },
      error: (err) => {
        console.error('Erreur planConstruction', err);
        alert(err.error?.message ?? 'Construction impossible');
        // on laisse la modale ouverte pour que le joueur puisse réessayer/changer
      },
    });
  }

  get isLocationEffectOwner(): boolean {
    return !!this.game && !!this.me && this.game.locationEffectOwnerId === this.me.id;
  }

  get canChooseLocationEffect(): boolean {
    const g = this.game;
    return !!g
      && g.locationEffectPending
      && this.isLocationEffectOwner
      && !g.locationEffectChoice; // le serveur n’a pas encore figé le choix
  }

  translateLocationEffect(
    choice: GameSnapshot['locationEffectChoice'] | undefined
  ): string {
    switch (choice) {
      case 'STUDY':
        return 'Étude des grimoires';
      case 'THEFT':
        return 'Subtilisation de manuscrit';
      case 'OMEN':
        return 'Prédiction occulte';
      case 'EXPERIMENT':
        return 'Expérimentation';
      case 'ALCHEMY':
        return 'Alchimie';
      case 'RARE_ALCHEMY':
        return 'Alchimie rare';
      case 'EXPLOSION':
        return 'Explosion alchimique';
      case 'DEATH_DANCE':
        return 'Danse macabre';
      case 'SNEAK_ATTACK':
        return 'Attaque sournoise';
      case 'LOOTING':
        return 'Pillage';
      case 'HEAL':
        return 'Purifier un chasseur';
      case 'CORRUPT_SOULS':
        return 'Corrompre le lieu';
      case 'CORRUPT':
        return 'Corrompre un chasseur';
      case 'PURIFY_WATER':
        return 'Purifier le lieu';
      case 'FORGE':
        return 'Fabriquer de l\'équipement';
      default:
        return '';
    }
  }

  locationEffectBackground(): string {
    const infra = this.game?.locationEffectInfra;
    if (infra === 'LIBRARY') {
      return "url('/assets/locations/library.png')";
    }
    if (infra === 'LABORATORY') {
      return "url('/assets/locations/laboratory.png')";
    }
    if (infra === 'BALLROOM') {
      return "url('/assets/locations/ballroom.png')";
    }
    if (infra === 'ALTAR') {
      if (this.game?.altarCorrupted) return "url('/assets/locations/altar.png')"
      else return "url('/assets/locations/sanctuary.png')"
    }
    if (infra === 'FORGE') {
      return "url('/assets/locations/forge.png')"
    }
    return 'none';
  }

  private syncLocationEffectFromSnapshot(g: GameSnapshot, previous?: GameSnapshot | null) {
    // 0) Aucun effet de lieu → on vide tout
    if (!g.locationEffectPending || !g.locationEffectInfra) {
      this.effectChoice = null;
      this.resetLocationActionUi();
      return;
    }

    const prevOwner = previous?.locationEffectOwnerId;
    const ownerChanged = !!prevOwner && prevOwner !== g.locationEffectOwnerId;

    // 1) Si le serveur a déjà figé un choix → on s'aligne
    if (g.locationEffectChoice) {
      this.effectChoice = g.locationEffectChoice;
    } else if (ownerChanged) {
      // Nouveau joueur qui résout un effet → reset la sélection locale
      this.effectChoice = null;
    }

    // 2) À chaque appel, on repart d'un état d'action "propre"
    // (la modale de choix d'effet est gérée par le template via locationEffectPending/Choice)
    this.resetLocationActionUi();

    // ============================================================
    // ===  LIBRARY : logique existante (THEFT / OMEN)          ===
    // ============================================================
    if (g.locationEffectInfra === 'LIBRARY') {

      // --- THEFT : modale d'action pour choisir cible + slot ---
      if (g.locationEffectChoice === 'THEFT') {
        this.locationActionKind = 'THEFT';
        this.locationActionModalOpen = true;

        // Seul le propriétaire a besoin des cibles/slots
        if (this.isLocationEffectOwner) {
          const targets = this.computeTheftTargets(g);
          this.theftTargets = targets;
        } else {
          // Spectateurs : texte uniquement
          this.theftTargets = [];
        }

        return;
      }

      // --- OMEN : modale d'action si des cartes sont préparées ---
      if (g.locationEffectChoice === 'OMEN') {
        const cards = g.libraryOmenCards ?? [];

        if (cards.length > 0) {
          this.locationActionKind = 'OMEN';
          this.locationActionModalOpen = true;

          if (this.isLocationEffectOwner) {
            this.omenCards = cards;

            // reset si nouvelle séquence ou taille différente
            if (!this.omenPlacements || this.omenPlacements.length !== cards.length) {
              this.omenPlacements = cards.map(() => null);
            }
          } else {
            // Observateurs : pas de détail des cartes
            this.omenCards = [];
            this.omenPlacements = [];
          }
        }

        return;
      }

      // STUDY (et autres futurs non interactifs côté LIBRARY) : rien à faire ici.
      return;
    }

    // ============================================================
    // ===  LABORATORY : nouvel effet EXPERIMENT                ===
    // ============================================================
    if (g.locationEffectInfra === 'LABORATORY') {

      if (g.locationEffectChoice === 'EXPERIMENT') {
        this.locationActionKind = 'EXPERIMENT';
        this.locationActionModalOpen = true;

        this.experimentPossibleLocations = this.computeExperimentLocations(g);
        this.experimentMonsterType = (g.laboratoryDraftMonsterType as any) ?? null;
        this.experimentLocation = g.laboratoryDraftLocation ?? null;
        return;
      }

      // EXPLOSION : modale d'action pour le jet de d20
      if (g.locationEffectChoice === 'EXPLOSION') {
        this.locationActionKind = 'EXPLOSION';
        this.locationActionModalOpen = true;

        // optionnel: reset local si tu as des champs UI dédiés à l'explosion
        // (sinon rien)
        return;
      }

      return;
    }

    // ============================================================
    // ===  ALTAR : effets interactifs HEAL / CORRUPT           ===
    // ============================================================
    if (g.locationEffectInfra === 'ALTAR') {

      // HEAL : choisir un chasseur vivant avec corruption > 0 et < 3 (verrou max)
      if (g.locationEffectChoice === 'HEAL') {
        this.locationActionKind = 'HEAL';
        this.locationActionModalOpen = true;

        if (this.isLocationEffectOwner) {
          this.altarTargets = g.players
            .filter(p => p.role === 'HUNTER' && p.hp > 0 && p.corruption > 0 && p.corruption < 3)
            .map(p => ({
              id: p.id,
              username: p.username,
              hp: p.hp,
              corruption: p.corruption,
            }));
        } else {
          this.altarTargets = [];
        }

        this.altarSelectedTargetId = null;
        return;
      }

      // CORRUPT : choisir un chasseur vivant avec corruption < 3
      if (g.locationEffectChoice === 'CORRUPT') {
        this.locationActionKind = 'CORRUPT';
        this.locationActionModalOpen = true;

        if (this.isLocationEffectOwner) {
          this.altarTargets = g.players
            .filter(p => p.role === 'HUNTER' && p.hp > 0 && p.corruption < 3)
            .map(p => ({
              id: p.id,
              username: p.username,
              hp: p.hp,
              corruption: p.corruption,
            }));
        } else {
          this.altarTargets = [];
        }

        this.altarSelectedTargetId = null;
        return;
      }

      // CORRUPT_SOULS / PURIFY_WATER : pas d’UI interactive côté front,
      // le serveur gère tout lors du choix d’effet.
      return;
    }

    // ============================================================
    // ===  FORGE : choix d’un équipement à fabriquer            ===
    // ============================================================
    if (g.locationEffectInfra === 'FORGE') {
      if (g.locationEffectChoice === 'FORGE') {
        this.locationActionKind = 'FORGE';
        this.locationActionModalOpen = true;

        if (this.isLocationEffectOwner) {
          // Calcul local des options possibles pour le propriétaire
          this.forgeOptions = this.computeForgeOptionsForOwner(g);
        } else {
          this.forgeOptions = [];
        }

        this.forgeSelectedId = null;
        this.forgeResolvedLabel = null;
      }

      return;
    }
  }

  // Lieux où on peut envoyer un monstre créé par le Laboratoire
  private computeExperimentLocations(g: GameSnapshot): string[] {
    const locs = new Set<string>();

    // 1) 4 lieux de base toujours proposés
    [
      'forest',
      'quarry',
      'lake',
      'manor',
    ].forEach(l => locs.add(l));

    // 2) Ajouter les lieux correspondant aux infrastructures construites
    for (const infra of g.builtInfras ?? []) {
      switch (infra) {
        case 'SAWMILL':
          locs.add('sawmill');
          break;
        case 'MINE':
          locs.add('mine');
          break;
        case 'LIBRARY':
          locs.add('library');
          break;
        case 'LABORATORY':
          locs.add('laboratory');
          break;
        case 'BALLROOM':
          locs.add('ballroom');
          break;
        case 'ALTAR':
          locs.add('altar');
          break;
        case 'FORGE':
          locs.add('forge');
          break;
      }
    }

    return Array.from(locs);
  }

  onEffectOptionClick(choice: 'STUDY' | 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'ALCHEMY' | 'RARE_ALCHEMY' | 'EXPLOSION' | 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING' | 'HEAL' | 'CORRUPT_SOULS' | 'CORRUPT' | 'PURIFY_WATER' | 'FORGE') {
    if (!this.canChooseLocationEffect) return;

    // Experiment : pas de chasseur
    if (choice === 'EXPERIMENT' && !this.canUseExperiment()) {
      return;
    }

    // Garde spécifique rare alchimie : si pas les ressources, on ignore le clic
    if (choice === 'RARE_ALCHEMY' && !this.canUseRareAlchemy()) {
      return;
    }

    // Explosion : juste un chasseur
    if (choice === 'EXPLOSION' && !this.canUseExplosion()) {
      return;
    }

    // Garde Ballroom : cohérent avec isBallroomChoiceDisabled
    if (
      (choice === 'DEATH_DANCE' || choice === 'SNEAK_ATTACK' || choice === 'BLOOD_WALTZ') &&
      !this.isVampireSide
    ) {
      return;
    }

    if (choice === 'LOOTING' && !this.isHunter) {
      return;
    }

    // ALTAR : garde role + état du lieu
    if (this.game?.locationEffectInfra === 'ALTAR') {
      if (choice === 'HEAL' || choice === 'CORRUPT' || choice === 'PURIFY_WATER' || choice === 'CORRUPT_SOULS') {
        if (this.isAltarChoiceDisabled(choice)) {
          return;
        }
      }
    }

    this.effectChoice = choice;
  }

  chooseLocationEffect() {
    if (!this.game || !this.effectChoice || !this.canChooseLocationEffect) return;

    if (this.effectChoice === 'RARE_ALCHEMY' && !this.canUseRareAlchemy()) {
      alert("Vous n'avez pas assez d'ingrédients pour une alchimie rare (6 eau, 6 herbes).");
      return;
    }

    this.api.chooseLocationEffect(this.game.id, this.effectChoice).subscribe({
      next: () => {
        // On ne ferme pas la modale :
        // - le serveur mettra à jour locationEffectChoice
        // - puis enchaînera l’effet suivant ou passera en PHASE3.
        // La vue se resynchronise via events + snapshot.
      },
      error: (err) => {
        console.error('Erreur chooseLocationEffect', err);
        alert(err.error?.message ?? 'Erreur Bibliothèque');
      },
    });
  }

  onCancelLocationEffect() {
    // Annule juste la sélection locale, l’effet reste en attente côté serveur
    this.effectChoice = null;
  }

  get isLocationActionOwner(): boolean {
    return this.isLocationEffectOwner; // même logique que pour la modale de choix
  }

  canUseExperiment(): boolean {
    const me = this.me;
    if (!me) return false;
    return (me.role === 'VAMPIRE' || me.role === 'SERVANT') && me.souls >= 100;
  }

  canUseRareAlchemy(): boolean {
    const g = this.game;
    const meId = this.me?.id;
    if (!g || !meId) return false;

    const me = g.players.find(p => p.id === meId);
    if (!me) return false;

    const water = (me as any).water ?? 0;
    const herbs = (me as any).herbs ?? 0;

    return water >= 6 && herbs >= 6;
  }

  canUseExplosion(): boolean {
    const me = this.me;
    if (!me) return false;
    return me.role === 'HUNTER';
  }

  get theftCanSubmit(): boolean {
    return this.locationActionKind === 'THEFT'
      && this.isLocationActionOwner
      && !!this.theftSelectedTargetId
      && this.theftSelectedSlotIndex !== null
      && this.theftSelectedSlotIndex >= 0;
  }

  get omenCanSubmit(): boolean {
    return this.locationActionKind === 'OMEN'
      && this.isLocationActionOwner
      && this.omenPlacements.length > 0
      && this.omenPlacements.every(p => p === 'TOP' || p === 'BOTTOM');
  }

  private resetLocationActionUi() {
    this.locationActionModalOpen = false;
    this.locationActionKind = null;

    // OMEN
    this.omenCards = [];
    this.omenPlacements = [];
    this.omenSubmitting = false;

    // THEFT
    this.theftTargets = [];
    this.theftSelectedTargetId = null;
    this.theftSlots = [];
    this.theftSelectedSlotIndex = null;
    this.theftSubmitting = false;

    // EXPERIMENT
    this.experimentMonsterType = null;
    this.experimentLocation = null;
    this.experimentPossibleLocations = [];

    // ALTAR
    this.altarTargets = [];
    this.altarSelectedTargetId = null;
    this.altarSubmitting = false;

    // FORGE
    this.forgeOptions = [];
    this.forgeSelectedId = null;
    this.forgeSubmitting = false;
    this.forgeResolvedLabel = null;
  }

  private computeTheftTargets(g: GameSnapshot): { id: string; username: string; actionsCount: number }[] {
    const ownerId = g.locationEffectOwnerId;
    if (!ownerId) return [];

    const owner = g.players.find(p => p.id === ownerId);
    if (!owner) return [];

    if (owner.role === 'VAMPIRE') {
      // Vampire → peut cibler n'importe quel chasseur vivant avec ≥1 carte Action
      return g.players
        .filter(p => p.role === 'HUNTER' && p.hp > 0 && p.actions && p.actions.length > 0)
        .map(p => ({
          id: p.id,
          username: p.username,
          actionsCount: p.actions.length,
        }));
    }

    if (owner.role === 'HUNTER') {
      // Chasseur → cible unique = vampire, s'il a des cartes
      const vamp = g.players.find(p => p.role === 'VAMPIRE' && p.hp > 0 && p.actions && p.actions.length > 0);
      return vamp
        ? [{
          id: vamp.id,
          username: vamp.username,
          actionsCount: vamp.actions.length,
        }]
        : [];
    }

    return [];
  }

  onSelectTheftTarget(targetId: string) {
    if (!this.isLocationActionOwner) return;
    this.theftSelectedTargetId = targetId;

    const t = this.theftTargets.find(tt => tt.id === targetId);
    const count = t ? t.actionsCount : 0;

    this.theftSlots = Array.from({ length: count }, (_, i) => i);
    this.theftSelectedSlotIndex = null;
  }

  onSelectTheftSlot(index: number) {
    if (!this.isLocationActionOwner) return;
    if (index < 0 || index >= this.theftSlots.length) return;

    this.theftSelectedSlotIndex = index;
  }

  confirmTheftSelection() {
    if (!this.game) return;
    if (!this.theftCanSubmit) return;
    if (!this.theftSelectedTargetId && this.theftSelectedTargetId !== '') return;
    if (this.theftSelectedSlotIndex === null) return;

    this.theftSubmitting = true;

    this.api.resolveLibraryTheft(
      this.game.id,
      this.theftSelectedTargetId!,
      this.theftSelectedSlotIndex
    ).subscribe({
      next: () => {
        // Le serveur va :
        //  - retirer la carte de la main de la cible
        //  - la remettre dans le bon deck + shuffle
        //  - loguer dans l'historique
        //  - émettre LOCATION_USED(THEFT) puis enchaîner la file d'effets
        //
        // La fermeture de la modale se fera via LOCATION_USED + nouveau snapshot
        // → syncLocationEffectFromSnapshot() la refermera.
        this.theftSubmitting = false;
      },
      error: (err) => {
        console.error('Erreur resolveLibraryTheft', err);
        alert(err.error?.message ?? 'Erreur Subtilisation de manuscrit');
        this.theftSubmitting = false;
      }
    });
  }

  onOmenPlacementClick(index: number, where: 'TOP' | 'BOTTOM') {
    if (!this.isLocationActionOwner) return;
    if (!this.omenPlacements || index < 0 || index >= this.omenPlacements.length) return;

    this.omenPlacements = this.omenPlacements.map((p, i) =>
      i === index ? where : p
    );
  }

  confirmOmenPlacements() {
    if (!this.game || !this.isLocationActionOwner || !this.omenCanSubmit) return;

    this.omenSubmitting = true;

    this.api.resolveLibraryOmen(
      this.game.id,
      this.omenPlacements as ('TOP' | 'BOTTOM')[]
    ).subscribe({
      next: () => {
        // Le serveur va :
        //  - remettre les cartes dans le deck (TOP/BOTTOM)
        //  - effacer libraryOmenState
        //  - émettre LOCATION_USED(OMEN) et enchaîner ensuite sur PHASE3 ou effet suivant
        // La fermeture de la modale se fera via snapshot + syncLocationEffectFromSnapshot.
        this.omenSubmitting = false;
      },
      error: (err) => {
        console.error('Erreur resolveLibraryOmen', err);
        alert(err.error?.message ?? 'Erreur Prédiction occulte');
        this.omenSubmitting = false;
      }
    });
  }

  get experimentAvailableLocations(): string[] {
    return this.experimentPossibleLocations;
  }

  get experimentCanSubmit(): boolean {
    return this.locationActionKind === 'EXPERIMENT'
      && this.isLocationActionOwner
      && !!this.experimentMonsterType
      && !!this.experimentLocation;
  }

  monsterHpInCombat(r: any): number | undefined {
    const m = this.getMonster(r.attackerId) || this.getMonster(r.defenderId);
    return m?.hp;
  }

  onExperimentMonsterClick(type: 'REVENANT' | 'GARGOYLE' | 'ABERRATION') {
    if (!this.isLocationActionOwner || !this.game) return;

    this.experimentMonsterType = type;

    this.api.updateExperimentDraft(this.game.id, {
      type,
      location: this.experimentLocation
    }).subscribe();
  }

  onExperimentLocationClick(loc: string) {
    if (!this.isLocationActionOwner || !this.game) return;

    this.experimentLocation = loc;

    this.api.updateExperimentDraft(this.game.id, {
      type: this.experimentMonsterType,
      location: loc
    }).subscribe();
  }

  confirmExperiment() {
    if (!this.game) return;
    if (!this.experimentCanSubmit || !this.experimentMonsterType || !this.experimentLocation) return;

    this.experimentSubmitting = true;

    this.api.resolveLaboratoryExperiment(
      this.game.id,
      this.experimentMonsterType,
      this.experimentLocation
    ).subscribe({
      next: () => {
        // Le serveur fera LOCATION_USED + snapshot → la modale se fermera via syncLocationEffectFromSnapshot
      },
      error: (err) => {
        console.error('Erreur resolveLaboratoryExperiment', err);
        alert(err.error?.message ?? 'Erreur Expérience occulte');
        this.experimentSubmitting = false;
      }
    });
  }

  rollLabExplosion() {
    if (!this.game) return;
    this.api.resolveLaboratoryExplosion(this.game.id).subscribe({
      error: e => this.showError(e)
    });
  }

  isBallroomChoiceDisabled(
    choice: 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING'
  ): boolean {
    const g = this.game;
    if (!g) return true;

    // conditions globales (comme canChooseLocationEffect)
    if (!g.locationEffectPending || !!g.locationEffectChoice) return true;
    if (!this.isLocationEffectOwner) return true;

    // Pillage : chasseurs uniquement → disabled si PAS chasseur
    if (choice === 'LOOTING') {
      return !this.isHunter;
    }

    // Effets vampiriques : vampire ou serviteur uniquement → disabled si PAS vampire-side
    if (choice === 'DEATH_DANCE' || choice === 'SNEAK_ATTACK' || choice === 'BLOOD_WALTZ') {
      return !this.isVampireSide;
    }

    // Par défaut, pas de désactivation spécifique
    return false;
  }


  isBallroomFight(r: RoundFightView): boolean {
    if (!this.game?.builtInfras?.includes('BALLROOM')) return false;
    return r.location === 'ballroom';
  }

  /** Duel où la Valse s'applique : effet actif + duel sur Ballroom + vampire vs chasseur + ≥2 chasseurs sur Ballroom */
  isBallroomWaltzFight(r: RoundFightView): boolean {
    if (!this.game) return false;

    // Effet Valse activé sur ce raid
    if (!this.game.ballroomBloodWaltz) return false;

    // Ballroom construite
    if (!this.game.builtInfras?.includes('BALLROOM')) return false;

    const att = this.getPlayer(r.attackerId);
    const def = this.getPlayer(r.defenderId);
    if (!att || !def) return false;

    // Conditions de rôles (adapte si, en fait, c'est le chasseur qui attaque le vampire)
    if (att.role !== 'VAMPIRE' || def.role !== 'HUNTER') return false;

    // Le duel doit être sur la salle de bal
    const locKey = 'ballroom';
    if (r.location !== locKey) return false;

    // On réutilise la même logique que getHuntersOnBallroomCount()
    if (!this.playersOnLocation) return false;
    const playersOnLoc = this.playersOnLocation(locKey) || [];
    const huntersOnLoc = playersOnLoc.filter(p => p.role === 'HUNTER' && p.hp > 0).length;

    // Valse seulement s'il y a au moins 2 chasseurs sur Ballroom
    return huntersOnLoc > 1;
  }



  /** Est-ce que CE joueur (moi) est le vampire attaquant sur un duel Valse ? */
  isBallroomWaltzForMe(r: RoundFightView): boolean {
    return this.meId === r.attackerId && this.isBallroomWaltzFight(r);
  }

  getHuntersOnBallroomCount(): number {
    if (!this.game?.builtInfras?.includes('BALLROOM')) return 0;

    const locKey = 'ballroom';

    if (!this.playersOnLocation) {
      return 0; // sécurité
    }

    const playersOnLoc = this.playersOnLocation(locKey);

    return playersOnLoc.filter(p => p.role === 'HUNTER' && p.hp > 0).length;
  }

  /** Placeholders avant que les dés de Valse soient connus */
  waltzPlaceholderDice(): number[] {
    const n = this.getHuntersOnBallroomCount();
    return n > 1 ? new Array(n).fill(0) : [];
  }

  /** Dés de Valse (globaux pour le raid) */
  get waltzRolls(): number[] {
    return this.game?.ballroomWaltzRolls || [];
  }

  get waltzBest(): number | null {
    return this.game?.ballroomWaltzBest ?? null;
  }

  get altarCanSubmit(): boolean {
    return (this.locationActionKind === 'HEAL'
      || this.locationActionKind === 'CORRUPT')
      && this.isLocationActionOwner
      && !!this.altarSelectedTargetId;
  }

  isAltarChoiceDisabled(
    choice: 'HEAL' | 'CORRUPT' | 'PURIFY_WATER' | 'CORRUPT_SOULS'
  ): boolean {
    const g = this.game;
    if (!g) return true;

    // Conditions globales : même logique que canChooseLocationEffect
    if (!g.locationEffectPending || !!g.locationEffectChoice) return true;
    if (!this.isLocationEffectOwner) return true;

    // Règles de rôle demandées :
    // 1) Si vampire (ou serviteur), les 2 "purifier" doivent être désactivés
    if ((choice === 'HEAL' || choice === 'PURIFY_WATER') && !this.isHunter) {
      return true;
    }

    // 2) Si chasseur, les 2 "corrompre" doivent être désactivés
    if ((choice === 'CORRUPT' || choice === 'CORRUPT_SOULS') && !this.isVampireSide) {
      return true;
    }

    // Optionnel (mais logique) : éviter les choix absurdes côté lieu
    if (choice === 'PURIFY_WATER' && !g.altarCorrupted) {
      // Sanctuaire déjà pur → rien à purifier
      return true;
    }
    if (choice === 'CORRUPT_SOULS' && g.altarCorrupted) {
      // Sanctuaire déjà corrompu → rien à corrompre en plus
      return true;
    }

    return false;
  }

  onSelectAltarTarget(targetId: string) {
    if (!this.isLocationActionOwner) return;
    this.altarSelectedTargetId = targetId;
  }

  confirmAltarHeal() {
    if (!this.game || !this.altarCanSubmit || !this.altarSelectedTargetId) return;

    this.altarSubmitting = true;
    this.api.resolveAltarHeal(this.game.id, this.altarSelectedTargetId).subscribe({
      next: () => {
        this.altarSubmitting = false;
        // La modale se fermera via snapshot + syncLocationEffectFromSnapshot
      },
      error: (err) => {
        console.error('Erreur resolveAltarHeal', err);
        alert(err.error?.message ?? 'Erreur Purification de chasseur');
        this.altarSubmitting = false;
      },
    });
  }

  confirmAltarCorrupt() {
    if (!this.game || !this.altarCanSubmit || !this.altarSelectedTargetId) return;

    this.altarSubmitting = true;
    this.api.resolveAltarCorrupt(this.game.id, this.altarSelectedTargetId).subscribe({
      next: () => {
        this.altarSubmitting = false;
      },
      error: (err) => {
        console.error('Erreur resolveAltarCorrupt', err);
        alert(err.error?.message ?? 'Erreur Corruption de chasseur');
        this.altarSubmitting = false;
      },
    });
  }

  // Forge
  get forgeWeaponOptions(): ForgeOption[] {
    return this.forgeOptions.filter(o => o.type === 'WEAPON');
  }
  get forgeArmorOptions(): ForgeOption[] {
    return this.forgeOptions.filter(o => o.type === 'ARMOR');
  }
  get forgeCanSubmit(): boolean {
    return this.isLocationActionOwner
      && !!this.game
      && !!this.forgeSelectedId
      && !this.forgeSubmitting;
  }

  // Renvoie le nombre de faces max trouvé dans une chaîne de dés (ex: "2D6+1" → 6)
  private maxDiceFaces(dice: string | null | undefined): number {
    if (!dice) return 0;
    const matches = [...dice.matchAll(/D(\d+)/gi)];
    if (matches.length === 0) return 0;
    return matches
      .map(m => parseInt(m[1], 10))
      .filter(n => !Number.isNaN(n))
      .reduce((a, b) => Math.max(a, b), 0);
  }

  private getWeaponTierFromPlayer(p: GameSnapshot['players'][number]): 0 | 1 | 2 | 3 {
    const faces = this.maxDiceFaces(p.attackDice);
    if (faces >= 12) return 3;
    if (faces >= 8) return 2;
    if (faces >= 6) return 1;
    return 0;
  }

  private getArmorTierFromPlayer(p: GameSnapshot['players'][number]): 0 | 1 | 2 | 3 {
    const faces = this.maxDiceFaces(p.defenseDice);
    if (faces >= 12) return 3;
    if (faces >= 8) return 2;
    if (faces >= 6) return 1;
    return 0;
  }

  private forgeCostOf(id: string): ForgeCost | null {
    return FORGE_COSTS[id] ?? null;
  }


  private buildHunterWeaponOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'H_WEAPON_T1_SWORD',
            type: 'WEAPON',
            tier: 1,
            label: 'Épée de fer',
            desc: 'Arme de chasseur — Saignement +1.',
            cost: this.forgeCostOf('H_WEAPON_T1_SWORD')!
          },
          {
            id: 'H_WEAPON_T1_MACE',
            type: 'WEAPON',
            tier: 1,
            label: 'Masse de fer',
            desc: 'Arme de chasseur — Étourdissement -1.',
            cost: this.forgeCostOf('H_WEAPON_T1_MACE')!
          },
          {
            id: 'H_WEAPON_T1_SPEAR',
            type: 'WEAPON',
            tier: 1,
            label: 'Lance de fer',
            desc: 'Arme de chasseur — annule riposte vampire si 6 au dé.',
            cost: this.forgeCostOf('H_WEAPON_T1_SPEAR')!
          },
        ];
      case 2:
        return [
          {
            id: 'H_WEAPON_T2_HALBERD',
            type: 'WEAPON',
            tier: 2,
            label: 'Hallebarde',
            desc: 'Arme de chasseur — Saignement +2.',
            cost: this.forgeCostOf('H_WEAPON_T2_HALBERD')!
          },
          {
            id: 'H_WEAPON_T2_HAMMER',
            type: 'WEAPON',
            tier: 2,
            label: 'Marteau de guerre',
            desc: 'Arme de chasseur — Étourdissement -2.',
            cost: this.forgeCostOf('H_WEAPON_T2_HAMMER')!
          },
          {
            id: 'H_WEAPON_T2_CROSSBOW',
            type: 'WEAPON',
            tier: 2,
            label: 'Arbalète',
            desc: 'Arme de chasseur — annule riposte vampire si 7 ou 8 au dé.',
            cost: this.forgeCostOf('H_WEAPON_T2_CROSSBOW')!
          },
        ];
      case 3:
        return [
          {
            id: 'H_WEAPON_T3_WRIST_BLADES',
            type: 'WEAPON',
            tier: 3,
            label: 'Lames de poignet',
            desc: 'Arme de chasseur — Saignement +3.',
            cost: this.forgeCostOf('H_WEAPON_T3_WRIST_BLADES')!
          },
          {
            id: 'H_WEAPON_T3_FLAIL',
            type: 'WEAPON',
            tier: 3,
            label: 'Fléau',
            desc: 'Arme de chasseur — Étourdissement -3.',
            cost: this.forgeCostOf('H_WEAPON_T3_FLAIL')!
          },
          {
            id: 'H_WEAPON_T3_PISTOL',
            type: 'WEAPON',
            tier: 3,
            label: 'Pistolet',
            desc: 'Arme de chasseur — annule riposte vampire si 10 ou + au dé.',
            cost: this.forgeCostOf('H_WEAPON_T3_PISTOL')!
          },
        ];
    }
  }

  private buildHunterArmorOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'H_ARMOR_T1_BRIGANDINE',
            type: 'ARMOR',
            tier: 1,
            label: 'Brigandine',
            desc: 'Armure de chasseur — Dé de défense D8.',
            cost: this.forgeCostOf('H_ARMOR_T1_BRIGANDINE')!
          },
        ];
      case 2:
        return [
          {
            id: 'H_ARMOR_T2_HAUBERT',
            type: 'ARMOR',
            tier: 2,
            label: 'Haubert',
            desc: 'Armure de chasseur — Dé de défense D12.',
            cost: this.forgeCostOf('H_ARMOR_T2_HAUBERT')!
          },
        ];
      case 3:
        return [
          {
            id: 'H_ARMOR_T3_PLATE_SILVER',
            type: 'ARMOR',
            tier: 3,
            label: 'Armure de plates en argent',
            desc: 'Armure de chasseur — D20 + effet spécial contre la morsure.',
            cost: this.forgeCostOf('H_ARMOR_T3_PLATE_SILVER')!
          },
        ];
    }
  }

  private buildVampireWeaponOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'V_WEAPON_T1_SCYTHE',
            type: 'WEAPON',
            tier: 1,
            label: 'Faux maudite',
            desc: 'Arme vampirique — D6, régénération 1 pv sur 6.',
            cost: this.forgeCostOf('V_WEAPON_T1_SCYTHE')!
          },
        ];
      case 2:
        return [
          {
            id: 'V_WEAPON_T2_SWORD',
            type: 'WEAPON',
            tier: 2,
            label: 'Épée vampirique',
            desc: 'Arme vampirique — D8, régénération 2 pv sur 7 ou 8.',
            cost: this.forgeCostOf('V_WEAPON_T2_SWORD')!
          },
        ];
      case 3:
        return [
          {
            id: 'V_WEAPON_T3_CLAWS',
            type: 'WEAPON',
            tier: 3,
            label: 'Griffes maudites',
            desc: 'Arme vampirique — D12, régénération 3 pv sur 10 ou +.',
            cost: this.forgeCostOf('V_WEAPON_T3_CLAWS')!
          },
        ];
    }
  }

  private buildVampireArmorOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'V_ARMOR_T1_CARAPACE',
            type: 'ARMOR',
            tier: 1,
            label: 'Carapace d’ombre',
            desc: 'Armure vampirique — D6 +1 DEF.',
            cost: this.forgeCostOf('V_ARMOR_T1_CARAPACE')!
          },
        ];
      case 2:
        return [
          {
            id: 'V_ARMOR_T2_HAUBERT',
            type: 'ARMOR',
            tier: 2,
            label: 'Haubert de nuit',
            desc: 'Armure vampirique — D8 +1 DEF.',
            cost: this.forgeCostOf('V_ARMOR_T2_HAUBERT')!
          },
        ];
      case 3:
        return [
          {
            id: 'V_ARMOR_T3_ECORCE',
            type: 'ARMOR',
            tier: 3,
            label: 'Écorce impie',
            desc: 'Armure vampirique — D12 + esquive sur 12.',
            cost: this.forgeCostOf('V_ARMOR_T3_ECORCE')!
          },
        ];
    }
  }

  private computeForgeOptionsForOwner(g: GameSnapshot): ForgeOption[] {
    const ownerId = g.locationEffectOwnerId;
    if (!ownerId) return [];

    const owner = g.players.find(p => p.id === ownerId);
    if (!owner) return [];

    const role = owner.role; // 'HUNTER' | 'VAMPIRE' | 'SERVANT'

    const weaponTier = this.getWeaponTierFromPlayer(owner);
    const armorTier = this.getArmorTierFromPlayer(owner);

    const nextWeaponTier = (weaponTier < 3 ? (weaponTier + 1) as 1 | 2 | 3 : null);
    const nextArmorTier = (armorTier < 3 ? (armorTier + 1) as 1 | 2 | 3 : null);

    const options: ForgeOption[] = [];

    const isHunterCamp = role === 'HUNTER';
    const isVampCamp = role === 'VAMPIRE' || role === 'SERVANT';

    if (nextWeaponTier) {
      if (isHunterCamp) {
        options.push(...this.buildHunterWeaponOptions(nextWeaponTier));
      } else if (isVampCamp) {
        options.push(...this.buildVampireWeaponOptions(nextWeaponTier));
      }
    }

    if (nextArmorTier) {
      if (isHunterCamp) {
        options.push(...this.buildHunterArmorOptions(nextArmorTier));
      } else if (isVampCamp) {
        options.push(...this.buildVampireArmorOptions(nextArmorTier));
      }
    }

    return options;
  }

  onSelectForgeOption(opt: ForgeOption) {
    if (!this.isLocationActionOwner) return;
    this.forgeSelectedId = opt.id;
  }

  confirmForge() {
    if (!this.game || !this.forgeCanSubmit || !this.forgeSelectedId) return;

    this.forgeSubmitting = true;

    const chosen = this.forgeOptions.find(o => o.id === this.forgeSelectedId) || null;

    this.api.resolveForge(
      this.game.id,
      this.forgeSelectedId
    ).subscribe({
      next: () => {
        this.forgeSubmitting = false;

        if (chosen) {
          this.forgeResolvedLabel = chosen.label;
        }
      },
      error: (err) => {
        console.error('Erreur resolveForgeEffect', err);
        alert(err.error?.message ?? 'Erreur Forge');
        this.forgeSubmitting = false;
      },
    });
  }

  buildOptions: Array<{ code: InfraCode; title: string; where: string }> = [
    { code: 'SAWMILL', title: 'Scierie', where: 'Forêt' },
    { code: 'MINE', title: 'Mine', where: 'Carrière' },
    { code: 'LIBRARY', title: 'Bibliothèque', where: 'Manoir' },
    { code: 'LABORATORY', title: 'Laboratoire occulte', where: 'Manoir' },
    { code: 'BALLROOM', title: 'Salle de bal', where: 'Manoir' },
    { code: 'ALTAR', title: 'Sanctuaire / Autel', where: 'Manoir' },
    { code: 'FORGE', title: 'Forge', where: 'Manoir' },
  ];

  isInfraBuilt(code: InfraCode): boolean {
    return (this.game?.builtInfras ?? []).includes(code);
  }

  infraImg(code: InfraCode): string {
    // ex: "FORGE" -> "/assets/cards/locations/forge.png"
    return `/assets/cards/locations/${code.toLowerCase()}.png`;
  }

  private readonly INFRA_COSTS: Record<InfraCode, Partial<Record<string, number>>> = {
    SAWMILL: { stone: 2, iron: 2 },
    MINE: { wood: 3, iron: 1 },
    LIBRARY: { wood: 4, stone: 2, iron: 1 },
    LABORATORY: { water: 2, herbs: 2, stone: 3, souls: 50 },
    BALLROOM: { stone: 5, iron: 2, souls: 50 },
    ALTAR: { stone: 4, wood: 1, iron: 2, souls: 50 },
    FORGE: { iron: 5, stone: 4, wood: 2 },
  };

  private readonly RES_META: Record<string, { icon: string; label: string }> = {
    wood: { icon: '/assets/icons/wood.png', label: 'Bois' },
    stone: { icon: '/assets/icons/stone.png', label: 'Pierre' },
    iron: { icon: '/assets/icons/iron.png', label: 'Fer' },
    water: { icon: '/assets/icons/water.png', label: 'Eau pure' },
    herbs: { icon: '/assets/icons/medical_grass.png', label: 'Herbe médicinale' },
    souls: { icon: '/assets/icons/souls.png', label: 'Âmes déchues' },
  };

  infraCostList(code: InfraCode): Array<{ qty: number; icon: string; label: string }> {
    const costs = this.INFRA_COSTS[code] ?? {};
    const order: string[] = ['wood', 'stone', 'iron', 'water', 'herbs', 'souls']; // ordre stable
    const out: Array<{ qty: number; icon: string; label: string }> = [];

    for (const k of order) {
      const qty = costs[k];
      if (!qty) continue;
      const meta = this.RES_META[k];
      out.push({ qty, icon: meta.icon, label: meta.label });
    }
    return out;
  }

  private readonly LOCATION_INFO: Partial<Record<string, string[]>> = {
    library: [
      'Étude des grimoires: piocher une carte action.',
      'Subtilisation de manuscrit: prendre une carte action aléatoire de l’adversaire (un chasseur au choix si vampire) et la mélanger dans la pioche.',
      'Prédiction occulte: révéler la prochaine carte Action de l’adversaire (sans la montrer) et choisir de la mettre au-dessus ou au-dessous de la pioche.',
    ],
    ballroom: [
      'Danse macabre: quand le vampire réussit une attaque sur ce lieu, le chasseur visé subit aussi +1 corruption.',
      'Charme du vampire: vole une ressource au hasard à chaque chasseur présent sur ce lieu (en plus d’attaquer).',
      'Valse sanguinaire: jette autant de dés d’attaque que de chasseurs présents, garde le meilleur et applique l’attaque à tous les chasseurs sur ce lieu.',
      'Les chasseurs sur ce lieu ont deux fois la récolte d’or sur ce lieu.',
    ],

    altar: [
      'Autel purifié: un chasseur peut réduire de 1 la corruption.',
      'Autel corrompu: dépenser eau bénite OU repousser le vampire lors des combats sur ce lieu pour PURIFIER l’autel.',
      'Autel corrompu: le vampire peut augmenter de 1 la corruption d’un chasseur.',
      'Autel purifié: morsure réussie OU sacrifier des âmes corrompues pour CORROMPRE l’autel.',
      'Si le vampire a gagné au moins un affrontement sur ce lieu: le lieu n’est pas purifié.',
    ],

    laboratory: [
      'Expérimentation: dépenser des âmes déchues pour créer un monstre (carte “monstre” jouée pour défendre un lieu).',
      'Explosion alchimique: un chasseur peut tenter de détruire le labo si D20 >= 15. S’il échou → le chasseur perd 2pv. S’il réussi → le vampire perd immédiatement 1 ressource au hasard et 20 âmes déchues.',
      'Fabriquer une potion / un élixir: dépenser eau pure + herbes médicinales pour piocher une potion ou un élixir.',
    ],
  };

  forgeCostGroups = {
    V: {
      1: [
        { id: 'V_WEAPON_T1_SCYTHE', label: 'Faux maudite', cost: FORGE_COSTS['V_WEAPON_T1_SCYTHE'] ?? {} },
        { id: 'V_ARMOR_T1_CARAPACE', label: 'Carapace d’ombre', cost: FORGE_COSTS['V_ARMOR_T1_CARAPACE'] ?? {} },
      ],
      2: [
        { id: 'V_WEAPON_T2_SWORD', label: 'Épée vampirique', cost: FORGE_COSTS['V_WEAPON_T2_SWORD'] ?? {} },
        { id: 'V_ARMOR_T2_HAUBERT', label: 'Haubert de nuit', cost: FORGE_COSTS['V_ARMOR_T2_HAUBERT'] ?? {} },
      ],
      3: [
        { id: 'V_WEAPON_T3_CLAWS', label: 'Griffes maudites', cost: FORGE_COSTS['V_WEAPON_T3_CLAWS'] ?? {} },
        { id: 'V_ARMOR_T3_ECORCE', label: 'Écorce impie', cost: FORGE_COSTS['V_ARMOR_T3_ECORCE'] ?? {} },
      ],
    },
    H: {
      1: [
        { id: 'H_WEAPON_T1_SWORD', label: 'Épée de fer', cost: FORGE_COSTS['H_WEAPON_T1_SWORD'] ?? {} },
        { id: 'H_WEAPON_T1_MACE', label: 'Masse de fer', cost: FORGE_COSTS['H_WEAPON_T1_MACE'] ?? {} },
        { id: 'H_WEAPON_T1_SPEAR', label: 'Lance de fer', cost: FORGE_COSTS['H_WEAPON_T1_SPEAR'] ?? {} },
        { id: 'H_ARMOR_T1_BRIGANDINE', label: 'Brigandine', cost: FORGE_COSTS['H_ARMOR_T1_BRIGANDINE'] ?? {} },
      ],
      2: [
        { id: 'H_WEAPON_T2_HALBERD', label: 'Hallebarde', cost: FORGE_COSTS['H_WEAPON_T2_HALBERD'] ?? {} },
        { id: 'H_WEAPON_T2_HAMMER', label: 'Marteau de guerre', cost: FORGE_COSTS['H_WEAPON_T2_HAMMER'] ?? {} },
        { id: 'H_WEAPON_T2_CROSSBOW', label: 'Arbalète', cost: FORGE_COSTS['H_WEAPON_T2_CROSSBOW'] ?? {} },
        { id: 'H_ARMOR_T2_HAUBERT', label: 'Haubert', cost: FORGE_COSTS['H_ARMOR_T2_HAUBERT'] ?? {} },
      ],
      3: [
        { id: 'H_WEAPON_T3_WRIST_BLADES', label: 'Lames de poignet', cost: FORGE_COSTS['H_WEAPON_T3_WRIST_BLADES'] ?? {} },
        { id: 'H_WEAPON_T3_FLAIL', label: 'Fléau', cost: FORGE_COSTS['H_WEAPON_T3_FLAIL'] ?? {} },
        { id: 'H_WEAPON_T3_PISTOL', label: 'Pistolet', cost: FORGE_COSTS['H_WEAPON_T3_PISTOL'] ?? {} },
        { id: 'H_ARMOR_T3_PLATE_SILVER', label: 'Armure de plates en argent', cost: FORGE_COSTS['H_ARMOR_T3_PLATE_SILVER'] ?? {} },
      ],
    },
  } as const;

  get hasZoomInfoLines(): boolean {
    return Array.isArray(this.zoomInfoLines) && this.zoomInfoLines.some(l => !!(l ?? '').trim());
  }

  locationInfo(code: string | null | undefined): { key: string; lines: string[] } | null {
    if (!code) return null;

    const key = String(code).toLowerCase();

    // Forge : on veut afficher le panneau (coûts), même sans phrase d’intro
    if (key === 'forge') return { key, lines: [] };

    const lines = this.LOCATION_INFO[key];
    if (!lines?.length) return null;

    return { key, lines };
  }

  bankLevel(): number {
    return (this.game as any)?.bankLevel ?? 0;
  }

  bankStoneProgress(): number {
    return (this.game as any)?.bankStoneProgress ?? 0;
  }

  bankNextCost(): number | null {
    const lvl = this.bankLevel();
    if (lvl >= 3) return null;
    if (lvl === 0) return 10;
    if (lvl === 1) return 15;
    return 20;
  }

  bankBonusText(level: number): string {
    switch (level) {
      case 0: return 'Aucun bonus';
      case 1: return 'Donne 50 pièces d’or aux chasseurs en début de Phase 4';
      case 2: return 'Donne 50 pièces d’or et 1 ressource aléatoire aux chasseurs en début de Phase 4';
      case 3: return 'Donne 100 pièces d’or et 1 ressource aléatoire aux chasseurs en début de Phase 4';
      default: return 'Aucun bonus';
    }
  }

  canContributeBankStone(): boolean {
    if (!this.game || !this.me) return false;
    if (!this.isHunter) return false;
    if (this.isMeDead) return false;
    if (this.bankLevel() >= 3) return false;
    return (this.me.stone ?? 0) >= 1;
  }

  onContributeBankStone() {
    if (!this.game) return;
    this.api.contributeBankStone(this.game.id).subscribe({
      error: e => this.showError(e)
    });
  }
}
