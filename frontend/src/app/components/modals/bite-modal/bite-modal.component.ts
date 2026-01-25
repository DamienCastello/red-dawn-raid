import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
    selector: 'app-bite-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './bite-modal.component.html',
    styleUrls: ['./bite-modal.component.scss']
})
export class BiteModalComponent {
    @Input() show = false;
    @Input() backgroundImage: string | null = null;

    // Bite data
    @Input() biteRoll: number | null | undefined;
    @Input() armorRoll: number | null | undefined;
    @Input() showArmorDice = false;

    // Stage and state
    @Input() biteStage: string | null = null;
    @Input() canRollBite = false;
    @Input() canRollArmor = false;
    @Input() biteResultText = '';
    @Input() altarRitualText = '';

    // Events
    @Output() rollCorruption = new EventEmitter<void>();
    @Output() rollArmor = new EventEmitter<void>();
}
