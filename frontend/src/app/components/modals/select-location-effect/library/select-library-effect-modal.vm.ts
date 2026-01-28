export interface SelectLibraryEffectModalVm {
    show: boolean;
    backgroundImage: string | null;
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

export interface SelectLibraryEffectModalActions {
    select: (id: string) => void;
    validate: () => void;
    cancel: () => void;
}
