import { GameSnapshot } from '../../../services/api.service';

export type SPlayer = GameSnapshot['players'][number];

export interface ActionModalVm {
    show: boolean;
    mode: 'NET' | 'PIT' | 'INCENDIAIRE' | 'PROVOCATION' | 'AMBUSH' | 'LONELY' | 'BLESSED_STAKE' | 'CHARISMATIQUE' | 'MARCHAND_ITINERANT' | 'MARCHAND_BONUS_BUY' | 'PRESENCE_ECRASANTE' | 'CATACLYSME' | 'CLONES_OMBRE' | 'IMAGE_MIROIR_SETUP' | 'IMAGE_MIROIR_RESOLVE' | 'ECLIPSE' | 'BLOOD_MOON' | 'VOILE_DE_BRUME' | 'FAIM_IRREPRESSIBLE' | 'MARQUE_TENEBREUSE' | 'AFFAIBLISSEMENT_OCCULTE' | 'PASSAGE_SECRET' | 'AVIDITE_NOCTURNE' | 'EAU_BENITE' | 'CRATE_LAKE' | 'CRATE_MANOR' | null;
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
    weatherSecondChoices: string[];
    weatherThirdChoices: string[];
    selectedWeather2: string | null;
    selectedWeather3: string | null;
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
    onCrateRoll: () => void;
    onCrateResolve: () => void;
    onConfirmBonus: (mode: any) => void;
    onCancelBonus: () => void;
    onSelectWeather2: (ws: any) => void;
    onSelectWeather3: (ws: any) => void;
    onCataclysmeConfirm: () => void;
    onClonesRoll: () => void;
    onCloneLocationChange: (idx: number, ev: any) => void;
    onCloneBiteChange: (idx: number, ev: any) => void;
    onClonesConfirm: () => void;
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
    labelWeather: (ws: any) => string;
    diceAsset: (type: any, color: any) => string;
    canConfirmClones: () => boolean;
    clonesTotalCost: () => number;
}
