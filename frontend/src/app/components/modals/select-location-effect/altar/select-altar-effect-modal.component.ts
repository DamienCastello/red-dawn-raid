import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectAltarEffectModalVm, SelectAltarEffectModalActions } from './select-altar-effect-modal.vm';

@Component({
    selector: 'app-select-altar-effect-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './select-altar-effect-modal.component.html',
    styleUrls: ['./select-altar-effect-modal.component.scss']
})
export class SelectAltarEffectModalComponent {
    @Input() vm!: SelectAltarEffectModalVm;
    @Input() actions!: SelectAltarEffectModalActions;
}
