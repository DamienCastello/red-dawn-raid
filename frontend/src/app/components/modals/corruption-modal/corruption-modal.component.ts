import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface UnstableChoice {
    unstableId: string;
    targets?: string[];
    locations?: string[];
}

export interface UnstableDefaultFight {
    willFight: boolean;
    loc?: string;
    opponentName?: string;
}

@Component({
    selector: 'app-corruption-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './corruption-modal.component.html',
    styleUrls: ['./corruption-modal.component.scss']
})
export class CorruptionModalComponent {
    @Input() show = false;
    @Input() backgroundImage: string | null = null;

    // Unstable choices data
    @Input() unstableChoices: UnstableChoice[] = [];
    @Input() isMeVampire = false;
    @Input() vampireName = '';

    // Helper functions passed as inputs (to avoid duplicating logic)
    @Input() usernameOf: (id: string) => string = () => '';
    @Input() labelLocation: (loc: string) => string = () => '';
    @Input() getUnstableDefaultFight: (id: string) => UnstableDefaultFight = () => ({ willFight: false });
    @Input() isUnstableLocked: (id: string) => boolean = () => false;
    @Input() isUnstableAlreadyDecided: (id: string) => boolean = () => false;
    @Input() isPendingUnstable: (id: string) => boolean = () => false;
    @Input() trackByUnstable: (index: number, item: UnstableChoice) => any = (i) => i;

    // Events
    @Output() assignTarget = new EventEmitter<{ unstableId: string, targetId: string }>();
    @Output() assignHarvest = new EventEmitter<{ unstableId: string, location: string }>();
    @Output() assignNothing = new EventEmitter<string>();
}
