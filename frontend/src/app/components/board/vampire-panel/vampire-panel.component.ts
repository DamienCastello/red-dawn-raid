import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Player } from '../../../services/api.service';
import { GameAssetsService } from '../../../services/game-assets.service';
import { ChipVm, ZoomHandlers } from '../board.types';
import { PlayerEquipBarComponent } from '../player-equip-bar/player-equip-bar.component';
import { ModChipsComponent } from '../mod-chips/mod-chips.component';

/**
 * Panneau de gauche (mid-left) : stats du vampire vues par les chasseurs
 * (nom, PV, barre d'équipement, chips de modificateurs). Composant d'affichage
 * pur composé des feuilles partagées equip-bar + mod-chips.
 */
@Component({
    selector: 'app-vampire-panel',
    standalone: true,
    imports: [CommonModule, PlayerEquipBarComponent, ModChipsComponent],
    template: `
    <section class="panel panel-left">
      <h3 style="margin:0 0 10px 0">Vampire</h3>

      <div class="player-strip">
        <div class="name">{{ vampire.username || vampire.id }}</div>
        <div class="hp">
          <img class="hp-heart" [src]="assets.heartIconFor(vampire)" alt="HP" />
          <span class="hp-value">{{ vampire.hp }}</span>
        </div>
      </div>

      <app-player-equip-bar [player]="vampire" [zoom]="zoom"></app-player-equip-bar>
      <app-mod-chips [chips]="chips"></app-mod-chips>
    </section>
  `,
  styleUrls: ['../../../game.component.scss'],
  styles: [':host { display: contents; }'],
})
export class VampirePanelComponent {
    @Input({ required: true }) vampire!: Player;
    @Input() chips: ChipVm[] = [];
    @Input({ required: true }) zoom!: ZoomHandlers;

    readonly assets = inject(GameAssetsService);
}
