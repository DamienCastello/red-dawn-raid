/**
 * Types partagés par les composants de board (régions du plateau).
 */

import { Player } from '../../services/api.service';

/** Une "chip" de modificateur prête à afficher (données pures, calculées par le parent). */
export interface ChipVm {
    label: string;
    title: string | null;
    iconSrc: string;
    /** Préfixe de la source ("WEATHER", "POTION", "ACTION", "EQUIP", "HIT", "CORRUPTION"). */
    kind: string | undefined;
}

/** Carte d'un chasseur/serviteur dans la rangée du haut (données pré-calculées). */
export interface HunterCardVm {
    player: Player;
    chips: ChipVm[];
    isCurrent: boolean;
}

/**
 * Handlers de zoom (survol pour agrandir une carte). Le mécanisme reste dans
 * game.component (état + géométrie souris) ; les composants de board reçoivent
 * ce petit bundle et l'appellent sur les événements de survol.
 */
export interface ZoomHandlers {
    enter: (ev: MouseEvent, badge?: string | number, badgeIsHunter?: boolean,
        size?: 'M' | 'L', info?: { key: string; lines: string[] } | null) => void;
    move: (ev: MouseEvent) => void;
    leave: () => void;
}
