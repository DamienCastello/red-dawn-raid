export interface BuildConfirmModalVm {
    show: boolean;
    backgroundImage: string;
    text: string;
}

export interface BuildConfirmModalActions {
    confirm: () => void;
    cancel: () => void;
}
