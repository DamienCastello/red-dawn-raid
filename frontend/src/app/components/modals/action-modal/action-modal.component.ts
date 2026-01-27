import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActionModalVm, ActionModalActions, ActionModalHelpers } from './action-modal.vm';

@Component({
    selector: 'app-action-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './action-modal.component.html',
    styleUrls: ['./action-modal.component.scss']
})
export class ActionModalComponent {
    @Input() vm!: ActionModalVm;
    @Input() actions!: ActionModalActions;
    @Input() helpers!: ActionModalHelpers;
}
