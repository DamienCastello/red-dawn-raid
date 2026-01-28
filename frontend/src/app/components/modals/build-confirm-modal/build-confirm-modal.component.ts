import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BuildConfirmModalVm, BuildConfirmModalActions } from './build-confirm-modal.vm';

@Component({
    selector: 'app-build-confirm-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './build-confirm-modal.component.html',
    styleUrls: ['./build-confirm-modal.component.scss']
})
export class BuildConfirmModalComponent {
    @Input() vm!: BuildConfirmModalVm;
    @Input() actions!: BuildConfirmModalActions;
}
