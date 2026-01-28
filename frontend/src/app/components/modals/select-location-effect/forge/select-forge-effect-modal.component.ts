import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectForgeEffectModalVm, SelectForgeEffectModalActions } from './select-forge-effect-modal.vm';

@Component({
    selector: 'app-select-forge-effect-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './select-forge-effect-modal.component.html',
    styleUrls: ['./select-forge-effect-modal.component.scss']
})
export class SelectForgeEffectModalComponent {
    @Input() vm!: SelectForgeEffectModalVm;
    @Input() actions!: SelectForgeEffectModalActions;
}
