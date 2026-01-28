export interface SelectLaboratoryEffectModalVm {
    show: boolean;
    backgroundImage: string | null;
    title: string;
    ownerText: string;
    isOwner: boolean;
    canChoose: boolean;
    choice: string | null;
    serverChoiceLabel: string | null;
    showRareAlchemyWarning: boolean;
    options: {
        id: string;
        title: string;
        desc: string;
        disabled: boolean;
    }[];
}

export interface SelectLaboratoryEffectModalActions {
    select: (id: string) => void;
    validate: () => void;
    cancel: () => void;
}
