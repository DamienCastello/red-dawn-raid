import { Component, Input, ViewChild, ElementRef, AfterViewChecked, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameAssetsService } from '../../../services/game-assets.service';
import { ZoomHandlers } from '../board.types';
import { CenterBoardVm, CenterBoardActions, CenterBoardHelpers } from './center-board.vm';

/**
 * Plateau central (mid) : fil en direct (bandeau préphase + messages + cartes
 * jouées au centre : lieux, clones, pisteurs) et historique.
 *
 * Composant présentationnel via vm/actions/helpers. Gère lui-même l'auto-scroll
 * de son historique (ViewChild local) : plus besoin que le parent le pilote.
 */
@Component({
    selector: 'app-center-board',
    standalone: true,
    imports: [CommonModule],
    template: `
    <section class="panel panel-center">
      <div class="center-grid">
        <!-- GAUCHE : Fil en direct -->
        <div class="center-col">
          <h3 class="center-title">Fil en direct</h3>

          <!-- Bandeau PREPHASE3 -->
          <div *ngIf="vm.game.phase==='PREPHASE3' && vm.game.hasUpcomingCombat" class="prephase3">
            <b>Préparation des actions</b>
            <span *ngIf="vm.remainingPrePhaseSeconds > 0"> ({{ vm.remainingPrePhaseSeconds }}s)</span>

            <div *ngIf="!vm.isMeDead && vm.me && vm.game.hasUpcomingCombat" style="margin-top:.5rem">
              <button *ngIf="!helpers.pendingUnstable() &&
                  (helpers.imInUpcomingCombat()
                  || helpers.canUseHunterPrephaseActions()
                  || helpers.canUseVampPrephaseActions())" (click)="actions.skipNow()" [disabled]="vm.hasSkipped"
                title="Signaler que vous avez fini vos actions">
                J'ai fini
              </button>

              <small *ngIf="vm.hasSkipped ||
                  !(helpers.imInUpcomingCombat()
                    || helpers.canUseHunterPrephaseActions()
                    || helpers.canUseVampPrephaseActions())" style="margin-left:.5rem; color:#666">
                En attente des autres…
              </small>
            </div>
          </div>

          <!-- Messages "live" -->
          <div *ngIf="(vm.game.messages?.length || 0) > 0" class="live-box">
            <div *ngFor="let m of vm.game.messages" class="live-line">{{ m }}</div>
          </div>

          <!-- Centre (cartes) -->
          <ng-container *ngIf="vm.centerHasAnything; else emptyCenter">
            <div class="center-cards">
              <!-- Cartes jouées -->
              <div class="center-card" *ngFor="let cp of vm.game.center">
                <div class="center-card-name" [ngClass]="helpers.isHunterId(cp.playerId) ? 'name-hunter' : 'name-vamp'">
                  {{ helpers.usernameOf(cp.playerId) }}
                </div>
                <img class="center-card-img"
                  [src]="cp.faceUp ? ('/assets/cards/locations/' + cp.card + '.png') : '/assets/cards/zone_verso.png'"
                  [alt]="cp.faceUp ? helpers.labelLocation(cp.card) : 'Carte face cachée'"
                  (mouseenter)="zoom.enter($event, helpers.usernameOf(cp.playerId), helpers.isHunterId(cp.playerId))"
                  (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
              </div>

              <!-- Clones -->
              <div class="center-card" *ngFor="let loc of vm.game.clonesLocations; let i = index">
                <div class="center-card-name name-vamp">clone d'ombre {{ i + 1 }}</div>
                <img class="center-card-img"
                  [src]="vm.game.clonesFaceUp ? ('/assets/cards/locations/' + loc + '.png') : '/assets/cards/zone_verso.png'"
                  [alt]="vm.game.clonesFaceUp ? helpers.labelLocation(loc) : 'Carte face cachée'"
                  (mouseenter)="zoom.enter($event, 'clone d\\'ombre ' + (i + 1), false)"
                  (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
              </div>

              <!-- Pisteur -->
              <div class="center-card" *ngFor="let pid of (vm.game.trackerHunters || [])">
                <div class="center-card-name name-hunter">{{ helpers.usernameOf(pid) }}</div>
                <img class="center-card-img" [src]="assets.actionImg('PISTEUR')" alt="Pisteur"
                  (mouseenter)="zoom.enter($event, helpers.usernameOf(pid), true)"
                  (mousemove)="zoom.move($event)" (mouseleave)="zoom.leave()" />
              </div>
            </div>
          </ng-container>

          <ng-template #emptyCenter>
            <div style="color:#999">Aucune carte jouée pour l'instant</div>
          </ng-template>
        </div>

        <!-- DROITE : Historique -->
        <div class="center-col">
          <h3 class="center-title">Historique</h3>
          <div #historyBox class="history-box">
            <ng-container *ngFor="let g of vm.historyGroups">
              <div class="history-head">
                RAID {{ g.raid }}
                {{ g.phase.startsWith('PHASE') ? ('PHASE ' + g.phaseNum) : g.phase }}:
              </div>
              <div *ngFor="let it of g.items" class="history-line">{{ it.text }}</div>
            </ng-container>
          </div>
        </div>
      </div>
    </section>
  `,
  styleUrls: ['../../../game.component.scss'],
  styles: [':host { display: contents; }'],
})
export class CenterBoardComponent implements AfterViewChecked {
    @Input({ required: true }) vm!: CenterBoardVm;
    @Input({ required: true }) actions!: CenterBoardActions;
    @Input({ required: true }) helpers!: CenterBoardHelpers;
    @Input({ required: true }) zoom!: ZoomHandlers;

    readonly assets = inject(GameAssetsService);

    @ViewChild('historyBox') historyBox?: ElementRef<HTMLDivElement>;
    private lastHistorySize = -1;

    ngAfterViewChecked() {
        const size = (this.vm?.historyGroups || []).reduce((n, g) => n + g.items.length, 0);
        if (size === this.lastHistorySize) return;
        this.lastHistorySize = size;
        const box = this.historyBox?.nativeElement;
        if (box) box.scrollTop = box.scrollHeight;
    }
}
