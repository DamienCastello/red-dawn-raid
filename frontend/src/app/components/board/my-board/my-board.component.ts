import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameAssetsService } from '../../../services/game-assets.service';
import { ZoomHandlers } from '../board.types';
import { ModChipsComponent } from '../mod-chips/mod-chips.component';
import { MyBoardVm, MyBoardActions, MyBoardHelpers } from './my-board.vm';

/**
 * Board du bas (bottom) : mon plateau joueur — ressources, PV, main (lieux,
 * actions, potions, élixirs) et boutons "Jouer / Construire".
 *
 * Composant présentationnel : l'état de sélection et les prédicats restent
 * dans game.component (ils pilotent les modales et la résolution) ; ce composant
 * les reçoit via vm/helpers et remonte les clics via actions.
 */
@Component({
    selector: 'app-my-board',
    standalone: true,
    imports: [CommonModule, ModChipsComponent],
    template: `
    <section class="board-wide my-board">
      <div class="player-strip">
        <div class="name">
          {{ vm.me.username || 'anonyme' }} — {{ vm.me.role === 'VAMPIRE' ? 'Vampire' : (vm.me.role === 'SERVANT' ? 'Serviteur' : 'Chasseur') }}
        </div>

        <div class="res-board">
          <span class="res-title">Ressources:</span>

          <span *ngIf="vm.me.role==='HUNTER'" class="res-item" title="L'or est utile pour obtenir des actions et de l'eau bénite">
            <span class="res-ico-box"><img class="res-ico res-ico-s" src="/assets/icons/gold.png" alt="Or"></span>
            <span class="res-val">{{ vm.me.gold || 0 }}</span>
          </span>

          <span *ngIf="vm.me.role==='VAMPIRE' || vm.me.role==='SERVANT'" class="res-item" title="Les âmes déchues sont utiles pour obtenir des actions">
            <span class="res-ico-box"><img class="res-ico res-ico-s" src="/assets/icons/souls.png" alt="Âmes déchues"></span>
            <span class="res-val">{{ vm.me.souls || 0 }}</span>
          </span>

          <span class="res-item" title="L'eau pure est utile pour l'alchimie et obtenir de l'eau bénite">
            <span class="res-ico-box"><img class="res-ico res-ico-s" src="/assets/icons/water.png" alt="Eau pure"></span>
            <span class="res-val">{{ vm.me.water || 0 }}</span>
          </span>

          <span class="res-item" title="L'herbe médicinale est utile pour l'alchimie">
            <span class="res-ico-box"><img class="res-ico res-ico-s" src="/assets/icons/medical_grass.png" alt="Herbe médicinale"></span>
            <span class="res-val">{{ vm.me.herbs || 0 }}</span>
          </span>

          <span class="res-item" title="Le bois est utile pour la fabrication d'équipement">
            <span class="res-ico-box"><img class="res-ico res-ico-l" src="/assets/icons/wood.png" alt="Bois"></span>
            <span class="res-val">{{ vm.me.wood || 0 }}</span>
          </span>

          <span class="res-item" title="Le fer est utile pour la fabrication d'équipement">
            <span class="res-ico-box"><img class="res-ico res-ico-m" src="/assets/icons/iron.png" alt="Fer"></span>
            <span class="res-val">{{ vm.me.iron || 0 }}</span>
          </span>

          <span class="res-item" title="La pierre est utile pour les constructions du vampire ou pour vendre a la ville et gagner de l'or">
            <span class="res-ico-box"><img class="res-ico res-ico-s" src="/assets/icons/stone.png" alt="Pierre"></span>
            <span class="res-val">{{ vm.me.stone || 0 }}</span>
          </span>

          <span *ngIf="vm.me.role==='HUNTER'" class="res-item" title="L'argent est utile late game pour fabriquer de l'équipement sacré">
            <span class="res-ico-box"><img class="res-ico res-ico-s" src="/assets/icons/silver.png" alt="Argent"></span>
            <span class="res-val">{{ vm.me.silver || 0 }}</span>
          </span>
        </div>

        <div class="hp res-item">
          <img class="hp-heart" [src]="assets.heartIconFor(vm.me)" alt="HP" />
          <span class="hp-value">{{ vm.me.hp }}</span>
        </div>
      </div>

      <div class="hand">
        <div class="hand-labels">
          <div class="hand-label equip-title">Équipements</div>
          <div class="hand-label">Votre main</div>
        </div>

        <div class="hand-cards">
          <!-- ÉQUIPEMENT -->
          <span class="equip-slot">
            <img class="my-equip-item" [src]="assets.weaponImg(vm.me)" alt="arme"
              (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
          </span>
          <span class="equip-slot">
            <img class="my-equip-item" [src]="assets.armorImg(vm.me)" alt="armure"
              (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
          </span>

          <!-- Lieux -->
          <button *ngFor="let c of (vm.me.hand || [])" class="card-btn" (click)="actions.onLocationClick(c)"
            [class.selected]="vm.selectedLocation === c" [class.is-disabled]="!helpers.canPlayLocation(c)"
            [attr.aria-disabled]="!helpers.canPlayLocation(c) ? true : null"
            [attr.title]="!helpers.canPlayLocation(c) ? vm.garlicTooltip : null" type="button">
            <span class="card-frame"><span class="card-surface">
              <img class="card-img" [src]="'/assets/cards/locations/' + c + '.png'" [alt]="helpers.labelLocation(c)"
                (mouseenter)="zoom.enter($event, undefined, undefined, 'L', helpers.locationInfo(c))"
                (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
            </span></span>
          </button>

          <!-- Actions -->
          <button *ngFor="let action of (vm.me.actions || []); let i = index" class="card-btn"
            [class.is-disabled]="!helpers.canUseActionNow(action)" [attr.aria-disabled]="!helpers.canUseActionNow(action) ? true : null"
            [class.selected]="vm.selectedAction === action && vm.selectedActionIndex === i" (click)="actions.onActionClick(action, i)"
            [attr.title]="helpers.isMeHunterUnstablePending()
              ? 'Impossible tant que vous succombez à la corruption.'
              : (!helpers.canUseActionNow(action) ? 'Disponible uniquement en PREPHASE3' : null)" type="button">
            <span class="card-frame"><span class="card-surface">
              <img class="card-img" [src]="assets.actionImg(action, vm.me.role)" [alt]="assets.actionLabelFr(action)"
                (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
            </span></span>
          </button>

          <!-- Potions -->
          <button *ngFor="let potion of (vm.me.potions || []); let i = index" class="card-btn"
            [class.is-disabled]="!helpers.canUsePotionNow(potion)" [attr.aria-disabled]="!helpers.canUsePotionNow(potion) ? true : null"
            [class.selected]="vm.selectedPotion === potion && vm.selectedPotionIndex === i" (click)="actions.onPotionClick(potion, i)"
            [attr.title]="potionTitle(potion)" type="button">
            <span class="card-frame"><span class="card-surface">
              <img class="card-img" [src]="assets.potionImg(potion)" [alt]="assets.potionLabelFr(potion)"
                (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
            </span></span>
          </button>

          <!-- Élixirs -->
          <button *ngFor="let potion of (vm.me.elixirs || []); let i = index" class="card-btn"
            [class.is-disabled]="!helpers.canUsePotionNow(potion)" [attr.aria-disabled]="!helpers.canUsePotionNow(potion) ? true : null"
            [class.selected]="vm.selectedPotion === potion && vm.selectedPotionIndex === i" (click)="actions.onPotionClick(potion, i)"
            [attr.title]="potionTitle(potion)" type="button">
            <span class="card-frame"><span class="card-surface">
              <img class="card-img" [src]="assets.potionImg(potion)" [alt]="assets.potionLabelFr(potion)"
                (mouseenter)="zoom.enter($event)" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
            </span></span>
          </button>
        </div>

        <app-mod-chips [chips]="vm.chips"></app-mod-chips>

        <div style="margin-top:.5rem">
          <button (click)="actions.playSelected()" [disabled]="!vm.canPlaySelection">Jouer cette carte</button>
          <button style="margin-left:.5rem" *ngIf="vm.me.role === 'VAMPIRE'"
            [disabled]="vm.game.phase !== 'PHASE2' || vm.isWindActive || vm.isMeDead" (click)="actions.openBuildModal()">
            Construire un lieu
          </button>
        </div>
      </div>
    </section>
  `,
  styles: [':host { display: contents; }'],
})
export class MyBoardComponent {
    @Input({ required: true }) vm!: MyBoardVm;
    @Input({ required: true }) actions!: MyBoardActions;
    @Input({ required: true }) helpers!: MyBoardHelpers;
    @Input({ required: true }) zoom!: ZoomHandlers;

    readonly assets = inject(GameAssetsService);

    /** Tooltip d'une potion non utilisable (corruption / blizzard / mauvaise phase). */
    potionTitle(potion: string): string | null {
        if (this.helpers.canUsePotionNow(potion)) return null;
        if (this.helpers.isMeHunterUnstablePending()) {
            return 'Impossible tant que vous succombez à la corruption.';
        }
        const w = this.vm.game.weather;
        if (w?.status === 'BLIZZARD' || w?.secondaryStatus === 'BLIZZARD') {
            return 'Potions gelées';
        }
        return 'Disponible uniquement en PREPHASE3';
    }
}
