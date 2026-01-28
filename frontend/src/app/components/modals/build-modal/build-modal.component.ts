import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BuildModalVm, BuildModalActions, BuildModalHelpers } from './build-modal.vm';

@Component({
    selector: 'app-build-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './build-modal.component.html',
    styleUrls: ['./build-modal.component.scss']
})
export class BuildModalComponent {
    @Input() vm!: BuildModalVm;
    @Input() actions!: BuildModalActions;
    @Input() helpers!: BuildModalHelpers;
}
