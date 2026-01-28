import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectBallroomEffectModalVm, SelectBallroomEffectModalActions } from './select-ballroom-effect-modal.vm';

@Component({
    selector: 'app-select-ballroom-effect-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './select-ballroom-effect-modal.component.html',
    styleUrls: ['./select-ballroom-effect-modal.component.scss']
})
export class SelectBallroomEffectModalComponent {
    @Input() vm!: SelectBallroomEffectModalVm;
    @Input() actions!: SelectBallroomEffectModalActions;
}
