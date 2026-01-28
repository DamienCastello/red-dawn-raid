export interface SelectAltarEffectModalVm {
    show: boolean;
    backgroundImage: string | null;
    altarCorrupted: boolean;
    title: string;
    ownerText: string;
    isOwner: boolean;
    canChoose: boolean;
    choice: string | null;
    serverChoiceLabel: string | null;
    options: {
        id: string;
        title: string;
        desc: string;
        disabled: boolean;
    }[];
}

export interface SelectAltarEffectModalActions {
    select: (id: string) => void;
    validate: () => void;
    cancel: () => void;
}
