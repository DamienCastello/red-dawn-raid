import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ChipVm } from '../board.types';

/**
 * Rangée de "chips" de modificateurs d'un joueur (météo, potion, action,
 * corruption, équipement, effet de coup). Feuille d'affichage partagée par
 * les panneaux vampire / chasseurs / mon board.
 *
 * Reçoit des chips DÉJÀ calculées (label + title + iconSrc + kind) ; ne fait
 * qu'aiguiller l'icône vers le bon conteneur selon `kind`.
 */
@Component({
    selector: 'app-mod-chips',
    standalone: true,
    imports: [CommonModule],
    template: `
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
        <div class="mods-badge-corruption" *ngIf="c.kind === 'CORRUPTION'">
          <img class="mod-ico" [src]="c.iconSrc" alt="corruption" />
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
  `,
  styleUrls: ['../../../game.component.scss'],
  styles: [':host { display: contents; }'],
})
export class ModChipsComponent {
    @Input() chips: ChipVm[] = [];
}
