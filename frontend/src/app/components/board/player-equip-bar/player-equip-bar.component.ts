import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Player } from '../../../services/api.service';
import { GameAssetsService } from '../../../services/game-assets.service';
import { ZoomHandlers } from '../board.types';

/**
 * Barre d'équipement d'un joueur : arme, armure, dos du deck d'actions (avec
 * compteur), dos des potions/élixirs (avec compteur). Feuille d'affichage
 * partagée par les panneaux vampire / chasseurs / mon board.
 */
@Component({
    selector: 'app-player-equip-bar',
    standalone: true,
    imports: [CommonModule],
    template: `
    <div class="equip-bar">
      <img class="equip-item" [src]="assets.weaponImg(player)" alt="arme"
        (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
      <img class="equip-item" [src]="assets.armorImg(player)" alt="armure"
        (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />

      <div class="mini-stack">
        <div class="mini-card mini-card-img" [title]="'Actions: ' + actionCount"
          (mouseenter)="zoom.enter($event, actionCount)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
          <img class="mini-back-img" [src]="assets.actionBackSrc(player)" alt="actions" />
          <span class="mini-badge">{{ actionCount }}</span>
        </div>

        <div class="mini-card mini-card-img" [title]="'Potions/Élixirs: ' + consumablesCount"
          (mouseenter)="zoom.enter($event, consumablesCount)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
          <img class="mini-back-img" [src]="assets.potionBackSrc" alt="potions" />
          <span class="mini-badge">{{ consumablesCount }}</span>
        </div>
      </div>
    </div>
  `,
  styleUrls: ['../../../game.component.scss'],
  styles: [':host { display: contents; }'],
})
export class PlayerEquipBarComponent {
    @Input({ required: true }) player!: Player;
    @Input({ required: true }) zoom!: ZoomHandlers;

    readonly assets = inject(GameAssetsService);

    get actionCount(): number {
        return this.player?.actions?.length ?? 0;
    }

    get consumablesCount(): number {
        const pot = this.player?.potions?.length ?? 0;
        const eli = this.player?.elixirs?.length ?? 0;
        return pot + eli;
    }
}
