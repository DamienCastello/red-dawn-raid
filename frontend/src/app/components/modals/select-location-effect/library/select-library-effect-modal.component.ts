import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectLibraryEffectModalVm, SelectLibraryEffectModalActions } from './select-library-effect-modal.vm';

@Component({
    selector: 'app-select-library-effect-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './select-library-effect-modal.component.html',
    styleUrls: ['./select-library-effect-modal.component.scss']
})
export class SelectLibraryEffectModalComponent {
    @Input() vm!: SelectLibraryEffectModalVm;
    @Input() actions!: SelectLibraryEffectModalActions;
}
