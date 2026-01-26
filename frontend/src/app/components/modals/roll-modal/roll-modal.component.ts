import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RollModalVm, RollModalActions } from './roll-modal.vm';

@Component({
    selector: 'app-roll-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './roll-modal.component.html',
    styleUrls: ['./roll-modal.component.scss']
})
export class RollModalComponent {
    @Input() vm!: RollModalVm;
    @Input() actions!: RollModalActions;
}
