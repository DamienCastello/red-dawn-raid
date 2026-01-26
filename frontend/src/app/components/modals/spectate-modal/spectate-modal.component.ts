import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpectateModalVm } from './spectate-modal.vm';

@Component({
    selector: 'app-spectate-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './spectate-modal.component.html',
    styleUrls: ['./spectate-modal.component.scss']
})
export class SpectateModalComponent {
    @Input({ required: true }) vm!: SpectateModalVm;
}
