export interface UseLocationEffectModalVm {
    show: boolean;
    backgroundImage: string | null;
    title: string;
    ownerText: string;
    infra: string | null;
    kind: string | null;
    isOwner: boolean;
    choiceLabel: string | null;

    omen?: {
        cards: { id: string; img: string; label: string }[];
        placements: ('TOP' | 'BOTTOM' | null)[];
        canSubmit: boolean;
        submitting: boolean;
    };

    theft?: {
        targets: { id: string; username: string; actionsCount: number }[];
        selectedTargetId: string | null;
        slots: number[];
        selectedSlotIndex: number | null;
        canSubmit: boolean;
        submitting: boolean;
    };

    experiment?: {
        monsterMeta: any;
        selectedMonsterType: string | null;
        availableLocations: { id: string; label: string }[];
        selectedLocation: string | null;
        canSubmit: boolean;
        submitting: boolean;
    };

    explosion?: {
        roll: number | null;
    };

    altar?: {
        targets: { id: string; username: string; hp: number; corruption: number }[];
        selectedTargetId: string | null;
        canSubmit: boolean;
        submitting: boolean;
    };

    forge?: {
        weaponOptions: any[];
        armorOptions: any[];
        selectedId: string | null;
        submitting: boolean;
        canSubmit: boolean;
        resolvedLabel: string | null;
    };
}

export interface UseLocationEffectModalActions {
    // OMEN
    onOmenPlacementClick: (index: number, placement: 'TOP' | 'BOTTOM') => void;
    confirmOmenPlacements: () => void;

    // THEFT
    onSelectTheftTarget: (targetId: string) => void;
    onSelectTheftSlot: (index: number) => void;
    confirmTheftSelection: () => void;

    // EXPERIMENT
    onExperimentMonsterClick: (type: string) => void;
    onExperimentLocationClick: (loc: string) => void;
    confirmExperiment: () => void;

    // EXPLOSION
    rollLabExplosion: () => void;

    // ALTAR
    onSelectAltarTarget: (targetId: string) => void;
    confirmAltarHeal: () => void;
    confirmAltarCorrupt: () => void;

    // FORGE
    onSelectForgeOption: (id: string) => void;
    confirmForge: () => void;

    // UI / ZOOM
    zoomEnter: (event: any, cardId?: string, isHunter?: boolean, side?: 'L' | 'R') => void;
    zoomMove: (event: any) => void;
    zoomLeave: () => void;
}
