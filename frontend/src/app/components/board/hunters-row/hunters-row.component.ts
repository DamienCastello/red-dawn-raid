import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameAssetsService } from '../../../services/game-assets.service';
import { HunterCardVm, ZoomHandlers } from '../board.types';
import { PlayerEquipBarComponent } from '../player-equip-bar/player-equip-bar.component';
import { ModChipsComponent } from '../mod-chips/mod-chips.component';

/**
 * Rangée du haut (top) : une carte par chasseur/serviteur adverse (nom, PV,
 * barre d'équipement, chips). Composant d'affichage pur composé des feuilles
 * partagées equip-bar + mod-chips ; reçoit les cartes déjà calculées.
 */
@Component({
    selector: 'app-hunters-row',
    standalone: true,
    imports: [CommonModule, PlayerEquipBarComponent, ModChipsComponent],
    template: `
    <section class="board-wide players-grid">
      <div *ngFor="let card of cards; trackBy: trackByPlayerId" class="player-card"
        [style.opacity]="card.isCurrent ? 1 : .9"
        style="padding:.5rem; border:1px dashed #bbb; background:#f7f7ff; border-radius:8px">

        <div class="player-strip">
          <div class="name">{{ card.player.username || card.player.id }}</div>
          <div class="hp">
            <img class="hp-heart" [src]="assets.heartIconFor(card.player)" alt="HP" />
            <span class="hp-value">{{ card.player.hp }}</span>
          </div>
        </div>

        <app-player-equip-bar [player]="card.player" [zoom]="zoom"></app-player-equip-bar>
        <app-mod-chips [chips]="card.chips"></app-mod-chips>
      </div>
    </section>
  `,
  styles: [':host { display: contents; }'],
})
export class HuntersRowComponent {
    @Input() cards: HunterCardVm[] = [];
    @Input({ required: true }) zoom!: ZoomHandlers;

    readonly assets = inject(GameAssetsService);

    trackByPlayerId(_i: number, card: HunterCardVm) {
        return card.player.id;
    }
}
