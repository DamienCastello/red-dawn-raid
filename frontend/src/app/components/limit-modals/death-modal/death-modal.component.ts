import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
    selector: 'app-death-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './death-modal.component.html',
    styleUrls: ['./death-modal.component.scss']
})
export class DeathModalComponent {
    @Input() show = false;
    @Output() observe = new EventEmitter<void>();
    @Output() leave = new EventEmitter<void>();
}
