import { GameSnapshot, Player } from '../../../services/api.service';
import { ChipVm } from '../board.types';

/** Données affichées par mon board (bas) — état de sélection inclus. */
export interface MyBoardVm {
    me: Player;
    game: GameSnapshot;
    chips: ChipVm[];
    selectedLocation: string | null;
    selectedAction: string | null;
    selectedActionIndex: number | null;
    selectedPotion: string | null;
    selectedPotionIndex: number | null;
    canPlaySelection: boolean;
    isWindActive: boolean;
    isMeDead: boolean;
    garlicTooltip: string;
}

/** Callbacks déclenchés par mes clics (le parent orchestre l'appel API). */
export interface MyBoardActions {
    onLocationClick: (c: string) => void;
    onActionClick: (action: string, i: number) => void;
    onPotionClick: (potion: string, i: number) => void;
    playSelected: () => void;
    openBuildModal: () => void;
}

/** Prédicats et libellés fournis par le parent (dépendent de l'état de partie). */
export interface MyBoardHelpers {
    canPlayLocation: (c: string) => boolean;
    canUseActionNow: (action: string) => boolean;
    canUsePotionNow: (potion: string) => boolean;
    isMeHunterUnstablePending: () => boolean;
    labelLocation: (c: string) => string;
    locationInfo: (c: string) => { key: string; lines: string[] } | null;
}
