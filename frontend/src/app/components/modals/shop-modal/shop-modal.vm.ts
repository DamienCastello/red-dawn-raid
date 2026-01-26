import { GameSnapshot, Player, TradeView, Pile } from "../../../api.service";

export type SPlayer = Player;
export type STrade = TradeView;

export interface ShopModalHelpers {
    actionImg: (action: string) => string;
    actionLabelFr: (action: string) => string;
    deckBackFor: (kind: any) => string;
    deckCount: (pile: Pile | null | undefined) => number;
    usernameOf: (id: string) => string;
    resOf: (p: SPlayer | undefined, res: string) => number;
    bankLevel: () => number;
    canContributeBankStone: () => boolean;
    bankNextCost: () => number | null;
    bankStoneProgress: () => number;
    bankBonusText: (lvl: number) => string;
    weaponUpgradeCost: () => { wood: number; iron: number } | null;
    weaponUpgradeImgSrc: () => string;
    canBuyUpgradeWeapon: () => boolean;
    armorUpgradeCost: () => { wood: number; iron: number } | null;
    armorUpgradeImgSrc: () => string;
    canBuyUpgradeArmor: () => boolean;
    canBuySilverQty: (qty: number) => boolean;
    myMaintenanceActions: () => string[];
    canUseActionNow: (action: string) => boolean;
    tradeResources: () => string[];
    offerQty: (res: string) => number;
    activeFlashes: () => any[];
    myTradesSorted: () => STrade[];
    otherIdFromTrade: (t: STrade) => string;
    iAmA: (t: STrade) => boolean;
    otherStatus: (t: STrade) => any;
    myStatus: (t: STrade) => any;
    statusClassFrom: (st: any) => string;
    isClosing: (id: string) => boolean;
    isClosingOk: (id: string) => boolean;
    isClosingKo: (id: string) => boolean;
}

export interface ShopModalActions {
    onFinishPhase4: () => void;
    onBuyAction: (ev: MouseEvent) => void;
    onBuyPotion: (ev: MouseEvent) => void;
    onBuySilver: (qty: number) => void;
    onBuyHolyWaterAction: (ev: MouseEvent) => void;
    onBuyTrackingAction: (ev: MouseEvent) => void;
    onSell: (res: any, qty: number) => void;
    onTransmute: (recipe: any) => void;
    selectTradeTarget: (id: string) => void;
    bumpOffer: (res: string, delta: number) => void;
    onTradeActionFor: (action: 'confirm' | 'refuse' | 'cancel', targetId: string) => void;
    onContributeBankStone: () => void;
    onBuyUpgradeWeapon: (ev: MouseEvent) => void;
    onBuyUpgradeArmor: (ev: MouseEvent) => void;
    onBuyBonus: (ev: MouseEvent) => void;
    useAction: (action: string) => void;
    zoomEnter: (ev: MouseEvent, cardOrTitle?: string | number, isHunter?: boolean, side?: any) => void;
    zoomMove: (ev: MouseEvent) => void;
    zoomLeave: () => void;
}

export interface ShopModalVm {
    show: boolean;
    isHunter: boolean;
    isVampireSide: boolean;
    isMeVampire: boolean;
    isMeDead: boolean;
    waitingDone: boolean;
    meId: string;
    me: SPlayer | undefined;
    phase4LeftSec: number;
    actionPrice: number;
    silverPrice: number;
    holyWaterGoldPrice: number;
    trackingGoldPrice: number;
    salesAmount: number;
    canBuyPotion: boolean;
    canBuyHunterAction: boolean;
    canBuyVampAction: boolean;
    canBuyTrackingAction: boolean;
    canBuyHolyWaterAction: boolean;
    canBuySilver: boolean;
    selectedTradeTargetId: string | null;
    eligibleTradeTargets: SPlayer[];
    myOffer: Record<string, number>;
    game: GameSnapshot | undefined;
    sellableResources: readonly ('wood' | 'herbs' | 'stone' | 'iron' | 'water')[];

    // Bonus/Forge
    bonusKind: string | null;
    bonusTitle: string;
    bonusResCost: any | null;
    bonusGoldCost: number | null;
    canBuyBonus: boolean;
    bonusBuyDisabledTitle: string | null;
    bonusBuyImgSrc: string;

    helpers: ShopModalHelpers;
}
