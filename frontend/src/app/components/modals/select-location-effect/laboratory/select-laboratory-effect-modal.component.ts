import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectLaboratoryEffectModalVm, SelectLaboratoryEffectModalActions } from './select-laboratory-effect-modal.vm';

@Component({
    selector: 'app-select-laboratory-effect-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './select-laboratory-effect-modal.component.html',
    styleUrls: ['./select-laboratory-effect-modal.component.scss']
})
export class SelectLaboratoryEffectModalComponent {
    @Input() vm!: SelectLaboratoryEffectModalVm;
    @Input() actions!: SelectLaboratoryEffectModalActions;
}
