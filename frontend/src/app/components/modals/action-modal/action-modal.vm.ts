import { GameSnapshot } from '../../../services/api.service';

export type SPlayer = GameSnapshot['players'][number];

export interface ActionModalVm {
    show: boolean;
    mode: 'NET' | 'PIT' | 'INCENDIAIRE' | 'PROVOCATION' | 'AMBUSH' | 'LONELY' | 'BLESSED_STAKE' | 'CHARISMATIQUE' | 'MARCHAND_ITINERANT' | 'MARCHAND_BONUS_BUY' | 'ADVANCED_TRANSMUTATION' | 'ADVANCED_TRANSMUTATION_BUY' | 'PRESENCE_ECRASANTE' | 'CATACLYSME' | 'CLONES_OMBRE' | 'IMAGE_MIROIR_SETUP' | 'IMAGE_MIROIR_RESOLVE' | 'ECLIPSE' | 'BLOOD_MOON' | 'VOILE_DE_BRUME' | 'FAIM_IRREPRESSIBLE' | 'MARQUE_TENEBREUSE' | 'AFFAIBLISSEMENT_OCCULTE' | 'PASSAGE_SECRET' | 'AVIDITE_NOCTURNE' | 'EAU_BENITE' | 'CRATE_LAKE' | 'CRATE_MANOR' | 'PORTAL_INVOCATION_REVENANT' | 'PORTAL_INVOCATION_BAT' | null;
    ownerId: string | null;
    location: string | null;
    trapEnemies: SPlayer[];
    selectedTargetId: string | null;
    trapCurrentIndex: number;
    roll: number | null;
    breakdownLines: string[];
    resolving: boolean;
    isActor: boolean;
    targetName: string | null;
    currentPitTarget: SPlayer | null;
    incendiaireChoices: string[];
    ambushEnemies: SPlayer[];
    clonesIndexes: number[];
    clonesLocationChoices: string[];
    clonesSelectedLocations: string[];
    clonesBiteParams: boolean[];
    portalLocationChoices: string[];
    portalSelectedLocation: string | null;
    weatherChoices: string[];
    selectedWeather1: string | null;
    selectedWeather2: string | null;
    cataclysmeLabelPair: string | null;
    mirrorLocationChoices: string[];
    selectedMirrorLoc: string | null;
    mirrorChoices: string[];
    mirrorHuntersByLoc: Record<string, SPlayer[]>;
    hunterPlayers: SPlayer[];
    secretPassageChoices: string[];
    selectedSecretPassageLoc: string | null;
    voileLocationChoices: string[];
    voileHuntersByLoc: Record<string, SPlayer[]>;
    secretPassageHuntersByLoc: Record<string, SPlayer[]>;
    diceColor: string;
    isGameEnded: boolean;
    game: GameSnapshot | undefined;
}

export interface ActionModalActions {
    onHolyWaterChoice: (choice: any) => void;
    onNetRoll: () => void;
    selectActionTarget: (id: string) => void;
    onPitRoll: () => void;
    onProvocationChoose: (id: string) => void;
    onIncendiaireRoll: () => void;
    onAmbushChoose: (id: string) => void;
    onBlessedStakeRoll: () => void;
    onMerchantRoll: () => void;
    onAdvancedTransmutationRoll: () => void;
    onCrateRoll: () => void;
    onCrateResolve: () => void;
    onConfirmBonus: (mode: any) => void;
    onConfirmVampireBonus: (mode: any) => void;
    onCancelBonus: () => void;
    onSelectWeather1: (ws: any) => void;
    onSelectWeather2: (ws: any) => void;
    onCataclysmeConfirm: () => void;
    onClonesRoll: () => void;
    onCloneLocationChange: (idx: number, ev: any) => void;
    onCloneBiteChange: (idx: number, ev: any) => void;
    onClonesConfirm: () => void;
    onPortalConfirm: () => void;
    setSelectedPortalLocation: (loc: string | null) => void;
    onMirrorSetupConfirm: () => void;
    onMirrorResolveChoose: (loc: any) => void;
    onDarkMarkChoose: (id: string) => void;
    onOccultWeakeningTarget: (id: string) => void;
    onSecretPassageConfirm: () => void;
    onVoileChoose: (loc: string) => void;
    setSelectedActionTargetId: (id: string | null) => void;
    setSelectedMirrorLoc: (loc: string | null) => void;
    setSelectedSecretPassageLoc: (loc: string | null) => void;
}

export interface ActionModalHelpers {
    actionBackgroundSrc: (mode: any) => any;
    actionLabelFr: (mode: any) => string;
    canHolyWaterReduce: () => boolean;
    canHolyWaterAttack: () => boolean;
    canHolyWaterCleanse: () => boolean;
    labelLocation: (loc: any) => string;
    merchantResultText: () => string;
    merchantBuyText: () => string;
    canPayBonusWithResource: () => boolean;
    canPayBonusWithGold: () => boolean;
    vampireBonusResultText: () => string;
    vampireBonusBuyText: () => string;
    canPayVampireBonusWithResource: () => boolean;
    canPayVampireBonusWithSouls: () => boolean;
    bonusResourceCostVampire: () => string;
    bonusSoulsCost: () => string;
    labelWeather: (ws: any) => string;
    diceAsset: (type: any, color: any) => string;
    canConfirmClones: () => boolean;
    clonesTotalCost: () => number;
}
