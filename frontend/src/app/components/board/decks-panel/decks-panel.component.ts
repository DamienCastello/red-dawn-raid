import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DecksView } from '../../../services/api.service';
import { GameAssetsService } from '../../../services/game-assets.service';
import { ZoomHandlers } from '../board.types';

/**
 * Panneau de droite (mid-right) : pioches et défausses des quatre decks
 * (actions vampire, actions chasseur, potions, élixirs).
 *
 * Composant d'affichage pur : reçoit l'objet `decks` du snapshot, injecte
 * GameAssetsService pour les images et comptages, remonte le survol via `zoom`.
 */
@Component({
    selector: 'app-decks-panel',
    standalone: true,
    imports: [CommonModule],
    template: `
    <section class="panel panel-right">
      <h3 style="margin:0 0 10px 0">Decks</h3>

      <div class="decks-grid">
        <!-- 1) VAMPIRE -->
        <div class="deck-block">
          <div class="deck-block-title name-vamp">Vampire</div>
          <div class="deck-piles">
            <div class="mini-card deck-pile"
              (mouseenter)="zoom.enter($event, assets.deckCount(decks?.actionsVamp), false)"
              (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img class="mini-back-img deck-img" [src]="assets.deckBackFor('VAMP_ACTIONS')" alt="pioche actions vampire" />
              <span class="mini-badge badge-vamp">{{ assets.deckCount(decks?.actionsVamp) }}</span>
            </div>
            <div class="mini-card deck-pile" [class.deck-empty]="assets.discardCount(decks?.actionsVamp) === 0"
              (mouseenter)="zoom.enter($event, undefined, undefined, 'L')" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img *ngIf="assets.discardCount(decks?.actionsVamp) > 0" class="mini-back-img deck-img"
                [src]="assets.discardImgFor('VAMP_ACTIONS', assets.lastDiscardId(decks?.actionsVamp))" alt="défausse actions vampire" />
              <span class="mini-badge badge-vamp">{{ assets.discardCount(decks?.actionsVamp) }}</span>
            </div>
          </div>
        </div>

        <!-- 2) HUNTER -->
        <div class="deck-block">
          <div class="deck-block-title name-hunter">Chasseur</div>
          <div class="deck-piles">
            <div class="mini-card deck-pile"
              (mouseenter)="zoom.enter($event, assets.deckCount(decks?.actionsHunters), true)"
              (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img class="mini-back-img deck-img" [src]="assets.deckBackFor('HUNTER_ACTIONS')" alt="pioche actions chasseur" />
              <span class="mini-badge badge-hunter">{{ assets.deckCount(decks?.actionsHunters) }}</span>
            </div>
            <div class="mini-card deck-pile" [class.deck-empty]="assets.discardCount(decks?.actionsHunters) === 0"
              (mouseenter)="zoom.enter($event, undefined, undefined, 'L')" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img *ngIf="assets.discardCount(decks?.actionsHunters) > 0" class="mini-back-img deck-img"
                [src]="assets.discardImgFor('HUNTER_ACTIONS', assets.lastDiscardId(decks?.actionsHunters))" alt="défausse actions chasseur" />
              <span class="mini-badge badge-hunter">{{ assets.discardCount(decks?.actionsHunters) }}</span>
            </div>
          </div>
        </div>

        <!-- 3) POTIONS -->
        <div class="deck-block">
          <div class="deck-block-title">Potions</div>
          <div class="deck-piles">
            <div class="mini-card deck-pile"
              (mouseenter)="zoom.enter($event, assets.deckCount(decks?.potions), undefined)"
              (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img class="mini-back-img deck-img" [src]="assets.deckBackFor('POTIONS')" alt="pioche potions" />
              <span class="mini-badge">{{ assets.deckCount(decks?.potions) }}</span>
            </div>
            <div class="mini-card deck-pile deck-discard-common" [class.deck-empty]="assets.discardCount(decks?.potions) === 0"
              (mouseenter)="zoom.enter($event, undefined, undefined, 'L')" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img *ngIf="assets.discardCount(decks?.potions) > 0" class="mini-back-img deck-img"
                [src]="assets.discardImgFor('POTIONS', assets.lastDiscardId(decks?.potions))" alt="défausse potions" />
              <span class="mini-badge">{{ assets.discardCount(decks?.potions) }}</span>
            </div>
          </div>
        </div>

        <!-- 4) ELIXIRS -->
        <div class="deck-block">
          <div class="deck-block-title">Élixirs</div>
          <div class="deck-piles">
            <div class="mini-card deck-pile"
              (mouseenter)="zoom.enter($event, assets.deckCount(decks?.elixirs), undefined)"
              (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img class="mini-back-img deck-img" [src]="assets.deckBackFor('ELIXIRS')" alt="pioche élixirs" />
              <span class="mini-badge">{{ assets.deckCount(decks?.elixirs) }}</span>
            </div>
            <div class="mini-card deck-pile deck-discard-common" [class.deck-empty]="assets.discardCount(decks?.elixirs) === 0"
              (mouseenter)="zoom.enter($event, undefined, undefined, 'L')" (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()">
              <img *ngIf="assets.discardCount(decks?.elixirs) > 0" class="mini-back-img deck-img"
                [src]="assets.discardImgFor('POTIONS', assets.lastDiscardId(decks?.elixirs))" alt="défausse élixirs" />
              <span class="mini-badge">{{ assets.discardCount(decks?.elixirs) }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  `,
  styleUrls: ['../../../game.component.scss'],
  styles: [':host { display: contents; }'],
})
export class DecksPanelComponent {
    @Input() decks: DecksView | null | undefined;
    @Input({ required: true }) zoom!: ZoomHandlers;

    readonly assets = inject(GameAssetsService);
}
