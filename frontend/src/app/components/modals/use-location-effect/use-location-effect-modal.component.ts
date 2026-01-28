import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { UseLocationEffectModalVm, UseLocationEffectModalActions } from './use-location-effect-modal.vm';

@Component({
    selector: 'app-use-location-effect-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './use-location-effect-modal.component.html',
    styleUrls: ['./use-location-effect-modal.component.scss']
})
export class UseLocationEffectModalComponent {
    @Input() vm!: UseLocationEffectModalVm;
    @Input() actions!: UseLocationEffectModalActions;

    // Helpers for template if needed, but VM should ideally handle labels
}
