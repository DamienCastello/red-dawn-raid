import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ShopModalVm, ShopModalActions, ShopModalHelpers } from './shop-modal.vm';

@Component({
    selector: 'app-shop-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './shop-modal.component.html',
    styleUrl: './shop-modal.component.scss'
})
export class ShopModalComponent {
    @Input({ required: true }) vm!: ShopModalVm;
    @Input({ required: true }) actions!: ShopModalActions;
    @Input({ required: true }) helpers!: ShopModalHelpers;
}
