import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Player } from '../../../services/api.service';
import { GameAssetsService } from '../../../services/game-assets.service';
import { ChipVm, ZoomHandlers } from '../board.types';

/**
 * Panneau de gauche (mid-left) : stats du vampire vues par les chasseurs
 * (nom, PV, barre d'équipement, chips de modificateurs).
 *
 * Composant d'affichage pur : reçoit le joueur vampire et ses chips déjà
 * calculées, injecte GameAssetsService pour les images, et remonte le survol
 * via le bundle `zoom`. Aucune règle ni appel API ici.
 */
@Component({
    selector: 'app-vampire-panel',
    standalone: true,
    imports: [CommonModule],
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

      <!-- BARRE D'ÉQUIPEMENT -->
      <div class="equip-bar">
        <img class="equip-item" [src]="assets.weaponImg(vampire)" alt="arme"
          (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
        <img class="equip-item" [src]="assets.armorImg(vampire)" alt="armure"
          (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />

        <div class="mini-stack">
          <div class="mini-card mini-card-img" [title]="'Actions: ' + actionCount"
            (mouseenter)="zoom.enter($event, actionCount)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
            <img class="mini-back-img" [src]="assets.actionBackSrc(vampire)" alt="actions" />
            <span class="mini-badge">{{ actionCount }}</span>
          </div>

          <div class="mini-card mini-card-img" [title]="'Potions/Élixirs: ' + consumablesCount"
            (mouseenter)="zoom.enter($event, consumablesCount)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
            <img class="mini-back-img" [src]="assets.potionBackSrc" alt="potions" />
            <span class="mini-badge">{{ consumablesCount }}</span>
          </div>
        </div>
      </div>

      <!-- CHIPS -->
      <div class="mods-row" *ngIf="chips.length">
        <span class="mod-chip" *ngFor="let c of chips" [title]="c.title">
          <div class="mods-badge-weather" *ngIf="c.kind === 'WEATHER'">
            <img class="weather-ico" [src]="c.iconSrc" alt="icône météo" />
          </div>
          <div class="mods-badge-potion" *ngIf="c.kind === 'POTION'">
            <img class="mod-ico" [src]="c.iconSrc" alt="potion_or_elixir" />
          </div>
          <div class="mods-badge-action" *ngIf="c.kind === 'ACTION'">
            <img class="mod-ico" [src]="c.iconSrc" alt="action" />
          </div>
          <div class="mods-badge-equip" *ngIf="c.kind === 'EQUIP'">
            <img class="mod-ico" [src]="c.iconSrc" alt="équipement" />
          </div>
          <div class="mods-badge-hit" *ngIf="c.kind === 'HIT'">
            <img class="mod-ico" [src]="c.iconSrc" alt="effet de coup" />
          </div>
          <span class="chip-val">{{ c.label }}</span>
        </span>
      </div>
    </section>
  `,
  // Réutilise les styles du plateau (règles top-level, variables CSS héritées
  // du host de game.component via le DOM). Un board.shared.scss dédié pourra
  // remplacer cet import lors des finitions.
  styleUrls: ['../../../game.component.scss'],
  // Le host <app-vampire-panel> ne doit pas s'insérer comme boîte dans la grille
  // parente (.boards-row) : display:contents le rend transparent pour que son
  // .panel-left interne (grid-area: left) redevienne l'enfant direct de la grille.
  styles: [':host { display: contents; }'],
})
export class VampirePanelComponent {
    @Input({ required: true }) vampire!: Player;
    @Input() chips: ChipVm[] = [];
    @Input({ required: true }) zoom!: ZoomHandlers;

    readonly assets = inject(GameAssetsService);

    get actionCount(): number {
        return this.vampire?.actions?.length ?? 0;
    }

    get consumablesCount(): number {
        const pot = this.vampire?.potions?.length ?? 0;
        const eli = this.vampire?.elixirs?.length ?? 0;
        return pot + eli;
    }
}
