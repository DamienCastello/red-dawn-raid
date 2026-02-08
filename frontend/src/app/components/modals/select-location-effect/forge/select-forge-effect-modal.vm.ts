export interface SelectForgeEffectModalVm {
    show: boolean;
    backgroundImage: string | null;
    title: string;
    ownerText: string;
    isOwner: boolean;
    canChoose: boolean;
    choice: string | null;
    serverChoiceLabel: string | null;
    insufficientResources: boolean; // true si le joueur n'a pas assez de ressources pour forger
    options: {
        id: string;
        title: string;
        desc: string;
        disabled: boolean;
    }[];
}

export interface SelectForgeEffectModalActions {
    select: (id: string) => void;
    validate: () => void;
    cancel: () => void;
}
