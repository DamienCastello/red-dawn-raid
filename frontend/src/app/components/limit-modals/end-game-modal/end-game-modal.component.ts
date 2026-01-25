import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
    selector: 'app-end-game-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './end-game-modal.component.html',
    styleUrls: ['./end-game-modal.component.scss']
})
export class EndGameModalComponent {
    @Input() show = false;
    @Input() title = '';
    @Input() subtitle = '';
    @Output() leave = new EventEmitter<void>();
}
