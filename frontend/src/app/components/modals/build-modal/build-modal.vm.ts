import { GameSnapshot } from "../../../api.service";

export type InfraCode = 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE';
export type SPlayer = GameSnapshot['players'][number];

export interface BuildOption {
    code: InfraCode;
    title: string;
    where: string;
}

export interface InfraCost {
    qty: number;
    icon: string;
    label: string;
}

export interface BuildModalVm {
    show: boolean;
    backgroundImage: string;
    title: string;
    me: SPlayer | null;
    buildOptions: BuildOption[];
}

export interface BuildModalActions {
    chooseInfra: (code: InfraCode) => void;
    close: () => void;
}

export interface BuildModalHelpers {
    isInfraBuilt: (code: InfraCode) => boolean;
    infraImg: (code: InfraCode) => string;
    infraCostList: (code: InfraCode) => InfraCost[];
    locationInfo: (code: string | null | undefined) => { key: string; lines: string[] } | null;

    zoomEnter: (ev: MouseEvent, badge?: string | number, badgeIsHunter?: boolean, size?: 'M' | 'L', info?: { key: string; lines: string[] } | null) => void;
    zoomMove: (ev: MouseEvent) => void;
    zoomLeave: () => void;
}
