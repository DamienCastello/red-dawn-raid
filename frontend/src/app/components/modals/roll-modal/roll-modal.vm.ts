export interface RollModalVm {
    show: boolean;
    title: string;
    backgroundImage: string | null;
    waitingForMyRoll: 'ATTACK' | 'DEFENSE' | null;
    isRolling: boolean;
    canRoll: boolean;
    rollButtonLabel: string;
    isMyFocusFirstStep: boolean;
    combat: {
        monsterHp: number | null;
        currentCombat: any;
    };
    dice?: {
        main?: { value?: number | null; src?: string | null };
        armor?: { value?: number | null; src?: string | null };
    };
    mods?: Array<{
        label: string;
        iconSrc?: string | null;
        title?: string | null;
        kind?: string | null;
    }>;

    helpers: {
        entityHaloIcon: (id: string, type: 'attack' | 'defense') => any;
        entityRoleIcon: (id: string, role: 'sword' | 'armor') => string;
        isBallroomWaltzFight: (combat: any) => boolean;
        hasFocus: (id: string) => boolean;
        waltzPlaceholderDice: () => any[];
        diceAsset: (dice: any, color: any) => string;
        getPlayer: (id: string) => any;
        roleColorOf: (player: any) => any;
        getRole: (player: any) => any;
        roleIcon: (role: any, icon: 'sword' | 'armor') => string;
        waltzRolls: number[];
    };
}

export interface RollModalActions {
    rollNow: () => void;
}
