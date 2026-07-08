import { GameSnapshot, Player } from '../../../services/api.service';

/** Un groupe d'historique (par raid+phase) prêt à afficher. */
export interface HistoryGroup {
    raid: number;
    phase: string;
    phaseNum: string;
    items: { text: string }[];
}

/** Données affichées par le plateau central (fil live + cartes + historique). */
export interface CenterBoardVm {
    game: GameSnapshot;
    me: Player | undefined;
    isMeDead: boolean;
    remainingPrePhaseSeconds: number;
    hasSkipped: boolean;
    centerHasAnything: boolean;
    historyGroups: HistoryGroup[];
}

/** Callbacks du plateau central. */
export interface CenterBoardActions {
    skipNow: () => void;
}

/** Prédicats/libellés fournis par le parent. */
export interface CenterBoardHelpers {
    pendingUnstable: () => boolean;
    imInUpcomingCombat: () => boolean;
    canUseHunterPrephaseActions: () => boolean;
    canUseVampPrephaseActions: () => boolean;
    isHunterId: (playerId: string) => boolean;
    usernameOf: (id: string) => string;
    labelLocation: (c: string) => string;
}
