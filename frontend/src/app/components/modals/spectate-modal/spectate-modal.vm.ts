import { GameSnapshot, RawStatMod } from "../../../api.service";

export type RoundFightView = GameSnapshot['combatsQueue'][number];
export type UiStatMod = RawStatMod & { labelFr?: string; displayOnly?: boolean };

export interface SpectateModalHelpers {
    nameOrId: (id: string) => string;
    modsForEntityStat: (id: string, stat: 'ATTACK' | 'DEFENSE') => UiStatMod[];
    titleFor: (mod: UiStatMod) => string | null;
    labelOrChip: (mod: UiStatMod) => string;
    weatherIconSrcForMod: (mod: UiStatMod) => string;
    isElixirMod: (mod: UiStatMod) => boolean;
    modIconSrc: (source: string) => string;
    entityHaloIcon: (id: string, side: 'attack' | 'defense') => any;
    entityRoleIcon: (id: string, kind: "sword" | "armor") => string;
    diceAsset: (dice: string | undefined, color: "red" | "blue" | "purple") => string;
    entityAttackDice: (id: string) => any;
    entityDefenseDice: (id: string) => any;
    entityColor: (id: string) => "red" | "blue" | "purple";
    isBallroomWaltzFight: (r: RoundFightView) => boolean;
    hasFocus: (id: string) => boolean;
    showFocusSpectate: (id: string) => boolean;
    waltzPlaceholderDice: () => number[];
    getPlayer: (id: string) => any;
    roleColorOf: (player: any) => "red" | "blue" | "purple";
}

export interface SpectateModalVm {
    show: boolean;
    title: string;
    hp: number | null;
    backgroundImage: string | null;
    hasFocus: boolean;
    biteActive: boolean;
    combat: RoundFightView | null;
    waltzRolls: number[];
    helpers: SpectateModalHelpers;
}
