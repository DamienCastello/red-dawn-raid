import { Component, inject, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, GameSnapshot, RawStatMod, Phase, Pile } from './api.service';
import { LiveService, GameEvent } from './live.service';
import { AssetPreloaderService } from './services/asset-preloader.service';
import { NotifyService } from './services/notif.service';
import { ChangeDetectorRef } from '@angular/core';

import { PhaseBubbleComponent } from './phase-buble.component';
import { ToastComponent } from './toast.component';

type InfraCode =
      'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE';
type RoundFightView = GameSnapshot['combatsQueue'][number];
type SPlayer = GameSnapshot['players'][number];
type SMonster = NonNullable<GameSnapshot['monsters']>[number];
type UiStatMod = RawStatMod & { labelFr?: string; displayOnly?: boolean };
type DefaultFightInfo = { willFight: boolean; loc?: string; opponentName?: string };
type TradeStatus = 'PENDING'|'CONFIRMED'|'REFUSED'|'CANCELLED';
type TradeSide = 'HUNTERS'|'VAMP_SIDE';
interface STrade {
  id: string; side: TradeSide; aId: string; bId: string;
  offerA: Record<string,number>; offerB: Record<string,number>;
  statusA: TradeStatus; statusB: TradeStatus; updatedAt: number;
}
interface ForgeOption {
  id: string;
  type: 'WEAPON' | 'ARMOR';
  tier: 1 | 2 | 3;
  label: string;
  desc: string;
}

@Component({
  standalone: true,
  selector: 'app-game',
  imports: [CommonModule, PhaseBubbleComponent, ToastComponent],
  template: `
  <app-phase-bubble *ngIf="!isGameEnded || !isMeDead"></app-phase-bubble>
  <app-toast *ngIf="!isGameEnded || !isMeDead"></app-toast>
  <main class="container">
    <button class="lobby-btn" *ngIf="!isGameEnded && !isMeDead" (click)="back()">← Retour Lobby</button>
    <button class="leave-btn"
            *ngIf="!isGameEnded && !isMeDead"
            (click)="leave()">
      Abandonner
    </button>

    <button class="leave-btn"
            *ngIf="!isGameEnded && isMeDead"
            (click)="leaveAfterDeath()">
      Quitter
    </button>
    <h2 style="margin: 0px;">Raid {{ game?.raid }} — {{ game?.phase || '...' }}</h2>

    <div *ngIf="errorMsg" style="background:#fee;border:1px solid #f99;padding:.5rem;margin:.5rem 0">
      {{ errorMsg }}
    </div>

    <!-- BOARD HAUT: chasseurs -->
    <section class="board-wide players-grid">

      <div *ngFor="let p of HunterAndServantPlayers; trackBy: trackById"
          class="player-card"
          [style.opacity]="isCurrent(p) ? 1 : .9"
          style="padding:.5rem; border:1px dashed #bbb; background:#f7f7ff; border-radius:8px">

        <div class="player-strip">
          <div class="name">{{ p.username || p.id }}</div>
          <div class="hp">
            <img class="hp-heart" [src]="heartIconFor(p)" alt="HP"/>
            <span class="hp-value">{{ p.hp }}</span>
          </div>
        </div>

        <!-- BARRE D’ÉQUIPEMENT -->
        <div class="equip-bar">
          <img class="equip-item"
              [src]="weaponImg(p)"
              alt="arme"
              (mouseenter)="zoomEnter($event)"
              (mousemove)="zoomMove($event)"
              (mouseleave)="zoomLeave()" />

          <img class="equip-item"
              [src]="armorImg(p)"
              alt="armure"
              (mouseenter)="zoomEnter($event)"
              (mousemove)="zoomMove($event)"
              (mouseleave)="zoomLeave()" />

          <div class="mini-stack">
            <!-- Actions -->
            <div class="mini-card mini-card-img"
                [title]="'Actions: ' + actionCount(p)"
                (mouseenter)="zoomEnter($event, actionCount(p))"
                (mousemove)="zoomMove($event)"
                (mouseleave)="zoomLeave()">
              <img class="mini-back-img" [src]="actionBackSrc(p)" alt="actions" />
              <span class="mini-badge">{{ actionCount(p) }}</span>
            </div>

            <!-- Potions + élixirs -->
            <div class="mini-card mini-card-img"
                [title]="'Potions/Élixirs: ' + consumablesCount(p)"
                (mouseenter)="zoomEnter($event, consumablesCount(p))"
                (mousemove)="zoomMove($event)"
                (mouseleave)="zoomLeave()">
              <img class="mini-back-img" [src]="potionBackSrc" alt="potions" />
              <span class="mini-badge">{{ consumablesCount(p) }}</span>
            </div>
          </div>
        </div>

        <!-- CHIPS -->
        <ng-container *ngIf="modsForDisplay(p) as mods">
          <div class="mods-row" *ngIf="mods.length">
            <span class="mod-chip" *ngFor="let m of mods" [title]="titleFor(m)">
              <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                <img class="weather-ico" [src]="weatherIconSrcForMod(m)" alt="icône météo" />
              </div>

              <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                <img class="mod-ico" [src]="isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png'" 
                alt="potion_or_elixir"/>
              </div>

              <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                <img class="mod-ico" [src]="modIconSrc(m.source)" alt="action"/>
              </div>

              <div class="mods-badge-corruption"
                  *ngIf="m.source?.startsWith('CORRUPTION')">
                <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
              </div>

              <div class="mods-badge-equip" *ngIf="m.source?.startsWith('EQUIP:')">
                <img class="mod-ico" [src]="modIconSrc(m.source)" alt="équipement"/>
              </div>

              <div class="mods-badge-hit" *ngIf="m.source?.startsWith('HIT:')">
                <img class="mod-ico" [src]="modIconSrc(m.source)" alt="effet de coup"/>
              </div>
              <span class="chip-val">{{ labelOrChip(m) }}</span>
            </span>
          </div>
        </ng-container>
      </div>
    </section>

    <!-- LIGNE MILIEU -->
    <section class="board-wide-center boards-row"
        [class.no-left]="!hasVampire || isMeVampire">
      <!-- gauche: stats vampire -->
      <section *ngIf="hasVampire && !isMeVampire" class="panel panel-left">
        <h3 style="margin:0 0 10px 0">Vampire</h3>

        <div class="player-strip">
          <div class="name">{{ vampirePlayer.username || vampirePlayer.id }}</div>
          <div class="hp">
            <img class="hp-heart" [src]="heartIconFor(vampirePlayer)" alt="HP"/>
            <span class="hp-value">{{ vampirePlayer.hp }}</span>
          </div>
        </div>

        <!-- BARRE D’ÉQUIPEMENT-->
        <div class="equip-bar">
          <img class="equip-item" 
              [src]="weaponImg(vampirePlayer)" 
              alt="arme" 
              (mouseenter)="zoomEnter($event)"
              (mousemove)="zoomMove($event)"
              (mouseleave)="zoomLeave()" />
          <img class="equip-item" 
              [src]="armorImg(vampirePlayer)" 
              alt="armure" 
              (mouseenter)="zoomEnter($event)"
              (mousemove)="zoomMove($event)"
              (mouseleave)="zoomLeave()"/>

          <div class="mini-stack">
            <div class="mini-card mini-card-img"
                [title]="'Actions: ' + actionCount(vampirePlayer)"
                (mouseenter)="zoomEnter($event, actionCount(vampirePlayer))"
                (mousemove)="zoomMove($event)"
                (mouseleave)="zoomLeave()">
              <img class="mini-back-img" [src]="actionBackSrc(vampirePlayer)" alt="actions" />
              <span class="mini-badge">{{ actionCount(vampirePlayer) }}</span>
            </div>

            <div class="mini-card mini-card-img"
                [title]="'Potions/Élixirs: ' + consumablesCount(vampirePlayer)"
                (mouseenter)="zoomEnter($event, consumablesCount(vampirePlayer))"
                (mousemove)="zoomMove($event)"
                (mouseleave)="zoomLeave()">
              <img class="mini-back-img" [src]="potionBackSrc" alt="potions" />
              <span class="mini-badge">{{ consumablesCount(vampirePlayer) }}</span>
            </div>
          </div>
        </div>

        <!-- CHIPS -->
        <div class="mods-row" *ngIf="modsForDisplay(vampirePlayer).length">
          <span class="mod-chip" *ngFor="let m of modsForDisplay(vampirePlayer)" [title]="titleFor(m)">
            <div
              class="mods-badge-weather"
              *ngIf="m.source?.startsWith('WEATHER:')"
            >
              <img
                class="weather-ico"
                [src]="weatherIconSrcForMod(m)"
                alt="icône météo"
              />
            </div>

            <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
              <img class="mod-ico" [src]="isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png'" 
              alt="potion_or_elixir"/>
            </div>

            <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
              <img class="mod-ico" [src]="modIconSrc(m.source)" alt="action"/>
            </div>

            <div class="mods-badge-equip" *ngIf="m.source?.startsWith('EQUIP:')">
              <img class="mod-ico" [src]="modIconSrc(m.source)" alt="équipement"/>
            </div>

            <div class="mods-badge-hit" *ngIf="m.source?.startsWith('HIT:')">
              <img class="mod-ico" [src]="modIconSrc(m.source)" alt="effet de coup"/>
            </div>
            <span class="chip-val">{{ labelOrChip(m) }}</span>
          </span>
        </div>
      </section>


      <!-- centre: plateau -->
      <section class="panel panel-center">

        <div class="center-grid">
          <!-- GAUCHE : Fil en direct -->
          <div class="center-col">
            <h3 class="center-title">Fil en direct</h3>

            <!-- Bandeau PREPHASE3 -->
            <div *ngIf="game?.phase==='PREPHASE3' && game?.hasUpcomingCombat" class="prephase3">
              <b>Préparation des actions</b>
              <span *ngIf="remainingPrePhaseSeconds > 0"> ({{ remainingPrePhaseSeconds }}s)</span>

              <div *ngIf="!isMeDead && me && game?.hasUpcomingCombat" style="margin-top:.5rem">
                <button *ngIf="!pendingUnstable() && 
                  (imInUpcomingCombat() 
                  || canUseHunterPrephaseActions()
                  || canUseVampPrephaseActions())"
                  (click)="skipNow()" 
                  [disabled]="hasSkipped" 
                  title="Signaler que vous avez fini vos actions">
                  J’ai fini
                </button>

                <small *ngIf="hasSkipped || 
                  !(imInUpcomingCombat() 
                    || canUseHunterPrephaseActions()
                    || canUseVampPrephaseActions())"
                  style="margin-left:.5rem; color:#666">
                  En attente des autres…
                </small>
              </div>
            </div>

            <!-- Messages “live” -->
            <div *ngIf="(game?.messages?.length || 0) > 0" class="live-box">
              <div *ngFor="let m of game?.messages" class="live-line">{{ m }}</div>
            </div>

            <!-- Centre (cartes) -->
            <ng-container *ngIf="(game?.center?.length || 0) + (game?.clonesLocations?.length || 0) > 0; else emptyCenter">
              <div class="center-cards">

                <!-- Cartes jouées -->
                <div class="center-card" *ngFor="let cp of game?.center">
                  <div class="center-card-name"
                      [ngClass]="isHunterId(cp.playerId) ? 'name-hunter' : 'name-vamp'">
                    {{ usernameOf(cp.playerId) }}
                  </div>

                  <img class="center-card-img"
                      [src]="cp.faceUp ? ('/assets/cards/locations/' + cp.card + '.png') : '/assets/cards/zone_verso.png'"
                      [alt]="cp.faceUp ? labelLocation(cp.card) : 'Carte face cachée'"
                      (mouseenter)="zoomEnter($event, usernameOf(cp.playerId), isHunterId(cp.playerId))"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()" />
                </div>

                <!-- Clones -->
                <div class="center-card" *ngFor="let loc of game?.clonesLocations; let i = index">
                  <div class="center-card-name name-vamp">clone d'ombre {{ i + 1 }}</div>

                  <img class="center-card-img"
                      [src]="game?.clonesFaceUp ? ('/assets/cards/locations/' + loc + '.png') : '/assets/cards/zone_verso.png'"
                      [alt]="game?.clonesFaceUp ? labelLocation(loc) : 'Carte face cachée'"
                      (mouseenter)="zoomEnter($event, 'clone d\\'ombre ' + (i + 1), false)"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()" />
                </div>

              </div>
            </ng-container>

            <ng-template #emptyCenter>
              <div style="color:#999">Aucune carte jouée pour l’instant</div>
            </ng-template>
          </div>

          <!-- DROITE : Historique -->
          <div class="center-col">
            <h3 class="center-title">Historique</h3>
            <div #historyBox class="history-box">
              <ng-container *ngFor="let g of historyGroups()">
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

    

      <!-- droite: decks actions + potions -->
      <section class="panel panel-right">
        <h3 style="margin:0 0 10px 0">Decks</h3>

        <div class="decks-grid">
          <!-- 1) VAMPIRE (deck + discard) -->
          <div class="deck-block">
            <div class="deck-block-title name-vamp">Vampire</div>

            <div class="deck-piles">
              <!-- Pioche -->
              <div class="mini-card deck-pile"
                  (mouseenter)="zoomEnter($event, deckCount(game?.decks?.actionsVamp), false)"
                  (mousemove)="zoomMove($event)"
                  (mouseleave)="zoomLeave()">
                <img class="mini-back-img deck-img"
                    [src]="deckBackFor('VAMP_ACTIONS')"
                    alt="pioche actions vampire" />
                <span class="mini-badge badge-vamp">
                  {{ deckCount(game?.decks?.actionsVamp) }}
                </span>
              </div>

              <!-- Défausse -->
              <div class="mini-card deck-pile"
                  [class.deck-empty]="discardCount(game?.decks?.actionsVamp) === 0"
                  (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                  (mousemove)="zoomMove($event)"
                  (mouseleave)="zoomLeave()">

                <img *ngIf="discardCount(game?.decks?.actionsVamp) > 0"
                    class="mini-back-img deck-img"
                    [src]="discardImgFor('VAMP_ACTIONS', game?.decks?.actionsVamp)"
                    alt="défausse actions vampire" />

                <span class="mini-badge badge-vamp">
                  {{ discardCount(game?.decks?.actionsVamp) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 2) HUNTER (deck + discard) -->
          <div class="deck-block">
            <div class="deck-block-title name-hunter">Chasseur</div>

            <div class="deck-piles">
              <!-- Pioche -->
              <div class="mini-card deck-pile"
                  (mouseenter)="zoomEnter($event, deckCount(game?.decks?.actionsHunters), true)"
                  (mousemove)="zoomMove($event)"
                  (mouseleave)="zoomLeave()">
                <img class="mini-back-img deck-img"
                    [src]="deckBackFor('HUNTER_ACTIONS')"
                    alt="pioche actions chasseur" />
                <span class="mini-badge badge-hunter">
                  {{ deckCount(game?.decks?.actionsHunters) }}
                </span>
              </div>

              <!-- Défausse -->
            <div class="mini-card deck-pile"
                [class.deck-empty]="discardCount(game?.decks?.actionsHunters) === 0"
                (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                (mousemove)="zoomMove($event)"
                (mouseleave)="zoomLeave()">

              <img *ngIf="discardCount(game?.decks?.actionsHunters) > 0"
                  class="mini-back-img deck-img"
                  [src]="discardImgFor('HUNTER_ACTIONS', game?.decks?.actionsHunters)"
                  alt="défausse actions chasseur" />

              <span class="mini-badge badge-hunter">
                {{ discardCount(game?.decks?.actionsHunters) }}
              </span>
            </div>
            </div>
          </div>

          <!-- 3) POTIONS (deck + discard) -->
          <div class="deck-block">
            <div class="deck-block-title">Potions</div>

            <div class="deck-piles">
              <!-- Pioche -->
              <div class="mini-card deck-pile"
                  (mouseenter)="zoomEnter($event, deckCount(game?.decks?.potions), undefined)"
                  (mousemove)="zoomMove($event)"
                  (mouseleave)="zoomLeave()">
                <img class="mini-back-img deck-img"
                    [src]="deckBackFor('POTIONS')"
                    alt="pioche potions" />
                <span class="mini-badge">
                  {{ deckCount(game?.decks?.potions) }}
                </span>
              </div>

              <!-- Défausse -->
              <div class="mini-card deck-pile deck-discard-common"
                  [class.deck-empty]="discardCount(game?.decks?.potions) === 0"
                  (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                  (mousemove)="zoomMove($event)"
                  (mouseleave)="zoomLeave()">

                <img *ngIf="discardCount(game?.decks?.potions) > 0"
                    class="mini-back-img deck-img"
                    [src]="discardImgFor('POTIONS', game?.decks?.potions)"
                    alt="défausse potions" />

                <span class="mini-badge">
                  {{ discardCount(game?.decks?.potions) }}
                </span>
              </div>
            </div>
          </div>

          <!-- 4) ELIXIRS (deck + discard) -->
          <div class="deck-block">
            <div class="deck-block-title">Élixirs</div>

            <div class="deck-piles">
              <!-- Pioche -->
              <div class="mini-card deck-pile"
                  (mouseenter)="zoomEnter($event, deckCount(game?.decks?.elixirs), undefined)"
                  (mousemove)="zoomMove($event)"
                  (mouseleave)="zoomLeave()">
                <img class="mini-back-img deck-img"
                    [src]="deckBackFor('ELIXIRS')"
                    alt="pioche élixirs" />
                <span class="mini-badge">
                  {{ deckCount(game?.decks?.elixirs) }}
                </span>
              </div>

              <!-- Défausse -->
              <div class="mini-card deck-pile deck-discard-common"
                  [class.deck-empty]="discardCount(game?.decks?.elixirs) === 0"
                  (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                  (mousemove)="zoomMove($event)"
                  (mouseleave)="zoomLeave()">

                <img *ngIf="discardCount(game?.decks?.elixirs) > 0"
                    class="mini-back-img deck-img"
                    [src]="discardImgFor('ELIXIRS', game?.decks?.elixirs)"
                    alt="défausse élixirs" />

                <span class="mini-badge">
                  {{ discardCount(game?.decks?.elixirs) }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </section>
    </section>

    <!-- BOARD BAS: moi -->
    <section *ngIf="me && game" class="board-wide my-board">
      <div class="player-strip">
        <div class="name">
          {{ me.username || 'anonyme' }} — {{ me.role === 'VAMPIRE' ? 'Vampire' : (me.role === 'SERVANT' ? 'Serviteur' : 'Chasseur') }}
        </div>
        <div class="res-board" *ngIf="me as m">
          <span class="res-title">Ressources:</span>

          <span *ngIf="m.role==='HUNTER'" class="res-item" title="L'or est utile pour obtenir des actions et de l'eau bénite">
            <span class="res-ico-box">
              <img class="res-ico res-ico-s" src="/assets/icons/gold.png" alt="Or">
            </span>
            <span class="res-val">{{ m.gold || 0 }}</span>
          </span>

          <span *ngIf="m.role==='VAMPIRE' || m.role==='SERVANT'" class="res-item" title="Les âmes déchues sont utiles pour obtenir des actions">
            <span class="res-ico-box">
              <img class="res-ico res-ico-s" src="/assets/icons/souls.png" alt="Âmes déchues">
            </span>
            <span class="res-val">{{ m.souls || 0 }}</span>
          </span>

          <span class="res-item" title="L'eau pure est utile pour l'alchimie et obtenir de l'eau bénite">
            <span class="res-ico-box">
              <img class="res-ico res-ico-s" src="/assets/icons/water.png" alt="Eau pure">
            </span>
            <span class="res-val">{{ m.water || 0 }}</span>
          </span>

          <span class="res-item" title="L'herbe médicinale est utile pour l'alchimie">
            <span class="res-ico-box">
              <img class="res-ico res-ico-s" src="/assets/icons/medical_grass.png" alt="Herbe médicinale">
            </span>
            <span class="res-val">{{ m.herbs || 0 }}</span>
          </span>

          <span class="res-item" title="Le bois est utile pour la fabrication d'équipement">
            <span class="res-ico-box">
              <img class="res-ico res-ico-l" src="/assets/icons/wood.png" alt="Bois">
            </span>
            <span class="res-val">{{ m.wood || 0 }}</span>
          </span>

          <span class="res-item" title="Le fer est utile pour la fabrication d'équipement">
            <span class="res-ico-box">
              <img class="res-ico res-ico-m" src="/assets/icons/iron.png" alt="Fer">
            </span>
            <span class="res-val">{{ m.iron || 0 }}</span>
          </span>

          <span class="res-item" title="La pierre est utile pour les constructions du vampire ou pour vendre a la ville et gagner de l'or">
            <span class="res-ico-box">
              <img class="res-ico res-ico-s" src="/assets/icons/stone.png" alt="Pierre">
            </span>
            <span class="res-val">{{ m.stone || 0 }}</span>
          </span>

          <span *ngIf="m.role==='HUNTER'" class="res-item" title="L'argent est utile late game pour fabriquer de l'équipement sacré">
            <span class="res-ico-box">
              <img class="res-ico res-ico-s" src="/assets/icons/silver.png" alt="Argent">
            </span>
            <span class="res-val">{{ m.silver || 0 }}</span>
          </span>
        </div>
        <div class="hp res-item">
          <img class="hp-heart" [src]="heartIconFor(me)" alt="HP"/>
          <span class="hp-value">{{ me.hp }}</span>
        </div>
      </div>

      <div class="hand">
        <!-- Labels -->
        <div class="hand-labels">
          <div class="hand-label equip-title">Équipements</div>
          <div class="hand-label">Votre main</div>
        </div>

        <div class="hand-cards">
          <!-- ÉQUIPEMENT -->
          <span class="equip-slot">
            <img class="my-equip-item"
                [src]="weaponImg(me)"
                alt="arme"
                (mouseenter)="zoomEnter($event)"
                (mousemove)="zoomMove($event)"
                (mouseleave)="zoomLeave()" />
          </span>

          <span class="equip-slot">
            <img class="my-equip-item"
                [src]="armorImg(me)"
                alt="armure"
                (mouseenter)="zoomEnter($event)"
                (mousemove)="zoomMove($event)"
                (mouseleave)="zoomLeave()" />
          </span>

          <!-- Lieux -->
          <button *ngFor="let c of (me?.hand || [])"
                  class="card-btn"
                  (click)="onLocationClick(c)"
                  [class.selected]="selectedLocation === c"
                  [class.is-disabled]="!canPlayLocation(c)"
                  [attr.aria-disabled]="!canPlayLocation(c) ? true : null"
                  [attr.title]="!canPlayLocation(c) ? garlicTooltip : null"
                  type="button">
            <span class="card-frame">
              <span class="card-surface">
                <img class="card-img"
                    [src]="'/assets/cards/locations/' + c + '.png'"
                    [alt]="labelLocation(c)"
                    (mouseenter)="zoomEnter($event, undefined, undefined, 'L', locationInfo(c))"
                    (mousemove)="zoomMove($event)"
                    (mouseleave)="zoomLeave()" />
              </span>
            </span>
          </button>

          <!-- Actions / Potions -->
          <button *ngFor="let action of myActions(); let i = index"
                  class="card-btn"
                  [class.is-disabled]="!canUseActionNow(action)"
                  [attr.aria-disabled]="!canUseActionNow(action) ? true : null"
                  [class.selected]="selectedAction === action && selectedActionIndex === i"
                  (click)="onActionClick(action, i)"
                  [attr.title]="canUseActionNow(action) ? null : 'Disponible uniquement en PREPHASE3'"
                  type="button">
            <span class="card-frame">
              <span class="card-surface">
                <img class="card-img"
                    [src]="actionImg(action)"
                    [alt]="actionLabelFr(action)"
                    (mouseenter)="zoomEnter($event)"
                    (mousemove)="zoomMove($event)"
                    (mouseleave)="zoomLeave()" />
              </span>
            </span>
          </button>

          <button *ngFor="let potion of myPotions(); let i = index"
                  class="card-btn"
                  [class.is-disabled]="!canUsePotionNow(potion)"
                  [attr.aria-disabled]="!canUsePotionNow(potion) ? true : null"
                  [class.selected]="selectedPotion === potion && selectedPotionIndex === i"
                  (click)="onPotionClick(potion, i)"
                  [attr.title]="canUsePotionNow(potion)
                    ? null
                    : ((game.weather?.status === 'BLIZZARD'
                        || game.weather?.secondaryStatus === 'BLIZZARD')
                        ? 'Potions gelées'
                        : 'Disponible uniquement en PREPHASE3')"
                  type="button">
            <span class="card-frame">
              <span class="card-surface">
                <img class="card-img"
                    [src]="potionImg(potion)"
                    [alt]="potionLabelFr(potion)"
                    (mouseenter)="zoomEnter($event)"
                    (mousemove)="zoomMove($event)"
                    (mouseleave)="zoomLeave()" />
              </span>
            </span>
          </button>

          <button *ngFor="let potion of myElixirs(); let i = index"
                  class="card-btn"
                  [class.is-disabled]="!canUsePotionNow(potion)"
                  [attr.aria-disabled]="!canUsePotionNow(potion) ? true : null"
                  [class.selected]="selectedPotion === potion && selectedPotionIndex === i"
                  (click)="onPotionClick(potion, i)"
                  [attr.title]="canUsePotionNow(potion)
                    ? null
                    : ((game.weather?.status === 'BLIZZARD'
                        || game.weather?.secondaryStatus === 'BLIZZARD')
                        ? 'Potions gelées'
                        : 'Disponible uniquement en PREPHASE3')"
                  type="button">
            <span class="card-frame">
              <span class="card-surface">
                <img class="card-img"
                    [src]="potionImg(potion)"
                    [alt]="potionLabelFr(potion)"
                    (mouseenter)="zoomEnter($event)"
                    (mousemove)="zoomMove($event)"
                    (mouseleave)="zoomLeave()" />
              </span>
            </span>
          </button>
        </div>

        <!-- CHIPS -->
        <ng-container *ngIf="modsForDisplay(me) as myMods">
          <div class="mods-row" *ngIf="myMods.length">
            <span class="mod-chip" *ngFor="let m of myMods" [title]="titleFor(m)">
              <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                <img class="weather-ico" [src]="weatherIconSrcForMod(m)" alt="icône météo" />
              </div>

              <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                <img class="mod-ico" [src]="isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png'" 
                alt="potion_or_elixir"/>
              </div>

              <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                <img class="mod-ico" [src]="modIconSrc(m.source)" alt="action"/>
              </div>

              <div class="mods-badge-corruption"
                  *ngIf="m.source?.startsWith('CORRUPTION')">
                <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
              </div>
              
              <div class="mods-badge-equip" *ngIf="m.source?.startsWith('EQUIP:')">
                <img class="mod-ico" [src]="modIconSrc(m.source)" alt="équipement"/>
              </div>

              <div class="mods-badge-hit" *ngIf="m.source?.startsWith('HIT:')">
                <img class="mod-ico" [src]="modIconSrc(m.source)" alt="effet de coup"/>
              </div>
              <span class="chip-val">{{ labelOrChip(m) }}</span>
            </span>
          </div>
        </ng-container>

        <div style="margin-top:.5rem">
          <button (click)="playSelected()" [disabled]="!canPlaySelection">Jouer cette carte</button>
          <button
            style="margin-left:.5rem"
            *ngIf="me?.role === 'VAMPIRE'"
            [disabled]="game.phase !== 'PHASE2' || isWindActive || isMeDead" 
            (click)="openBuildModal()"
          >
            Construire un lieu
          </button>
        </div>
      </div>
    </section>
  </main>

  <!-- === MODALE METEO (PHASE0) === -->
  <div *ngIf="canShowWeatherModal()" class="modal-backdrop">
    <div
      class="modal weather-modal"
      [ngClass]="{ 'with-bg': weatherBgActive }"
      [style.backgroundImage]="weatherBgActive ? setImageBackground('weather') : null"
    >
      <h3 style="margin-top:0; color:white;">{{'Tirage météo'}}</h3>

      <!-- AVANT affichage du fond météo : roue + dé (avec overlay résultat) -->
      <ng-container *ngIf="isWeatherPreReveal(); else weatherResult">
        <div class="content weather">
          <div class="wheel-wrap">
            <img class="wheel" src="/assets/weather/meteo_wheel.png" alt="roue météo"/>
            <!-- icône météo posée sur la pale tirée -->
            <div
              class="badge-weather"
              *ngIf="game?.weather?.status && game?.weather?.roll != null"
              [style.transform]="weatherIconTransform()"
            >
              <img
                class="weather-ico"
                [src]="weatherIconSrc(game?.weather?.status)"
                alt="icône météo"
              />
            </div>
          </div>

          <div class="dice-wrap" style="margin-top:1rem"
              [attr.data-digits]="(game?.weather?.roll ?? 0) > 9 ? 2 : 1">
            <img class="dice" src="/assets/dices/d12-red.png" alt="d12"/>
            <div class="dice-overlay" *ngIf="game?.weather?.roll != null">
              {{ game?.weather?.roll }}
            </div>
          </div>
        </div>

        <div class="footer">
          <ng-container *ngIf="isMeVampire && game?.weather?.roll == null; else waitWeather">
            <button (click)="rollWeather()" class="btn-primary">Jeter le dé</button>
          </ng-container>
          <ng-template #waitWeather>
            <span *ngIf="game?.weather?.roll == null">En attente du tirage…</span>
            <span class="weather-footer" *ngIf="game?.weather?.roll != null">{{ game?.weather?.nameFr }}</span>
          </ng-template>
        </div>
      </ng-container>

      <!-- APRES le délai : fond météo + texte -->
      <ng-template #weatherResult>
        <div class="content weather result">
          <div class="weather-badge">{{ game?.weather?.nameFr }}</div>
          <p class="weather-desc">{{ game?.weather?.descFr }}</p>
        </div>
      </ng-template>
    </div>
  </div>
  <!-- === MODALE ROLL (joueur concerné) === -->
  <div *ngIf="showRollModal && !isGameEnded && currentCombat as r" class="modal-backdrop">
    <div class="modal location-modal" [style.backgroundImage]="setImageBackground('location')">
      <h3 style="margin-top:0" class="bg-badge">
        {{ modalTitle(r) }}
        <ng-container *ngIf="monsterHpInCombat(r) as hp">
          &nbsp;— <span>{{ hp }} PV</span>
        </ng-container>
      </h3>
      <div class="roll-side" *ngIf="waitingForMyRoll as side">
        <ng-container *ngIf="side === 'ATTACK'; else defenseSide">
          <h2 class="bg-badge">Vous attaquez !</h2> 
        </ng-container>
        <ng-template #defenseSide>
          <h2 class="bg-badge">Vous défendez !</h2>
        </ng-template>
      </div>
      <div class="bg-badge" *ngIf="isMyFocusFirstStep">
        Potion de focalisation : vous pouvez relancer ce dé et garder le meilleur.
      </div>
      <div class="content action" style="margin-top: 30px;">
        <!-- On affiche le dé du joueur courant, avec icône -->
        
        <ng-container *ngIf="waitingForMyRoll as side">
          <ng-container *ngIf="side === 'ATTACK'; else defenseSide">
            <!-- ===================== -->
            <!--        ATTAQUANT      -->
            <!-- ===================== -->
            <div class="roll-row" style="margin-top: 30px;">
              <!-- Icône (à gauche) -->
              <div class="icon-bubble oval">
                <div class="icon-halo"
                    [ngClass]="entityHaloIcon(r.attackerId, 'attack')">
                  <img class="icon-side"
                      [src]="entityRoleIcon(r.attackerId,'sword')"
                      alt="attaque"/>
                </div>
              </div>

              <!-- CAS 1 : Valse + Foca -->
              <ng-container *ngIf="isBallroomWaltzFight(r) && hasFocus(r.attackerId); else noWaltzFocusCombo">

                <div class="dice-column">
                  <!-- Valse -->
                  <div class="dice-section">
                    <div class="dice-label global bg-badge">Dés de valse sanguinaire</div>
                    <div class="waltz-dice-grid">badge-weather
                      <div class="dice-with-label valse-die"
                          *ngFor="let _ of waltzPlaceholderDice(); let i = index">
                        <div class="dice-wrap waltz-verysmall">
                          <img class="dice-verysmall"
                              [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                                roleColorOf(getPlayer(r.attackerId)))"
                              alt="dé valse"/>
                          <div class="dice-overlay-small" *ngIf="waltzRolls.length">
                            {{ waltzRolls[i] }}
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- Focalisation -->
                  <div class="dice-section focus-single">
                    <div class="dice-label bg-badge">Dé de focalisation</div>
                    <div class="dice-wrap waltz-small">
                      <img class="dice"
                          [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                            roleColorOf(getPlayer(r.attackerId)))"
                          alt="dé de focalisation"/>
                      <div class="dice-overlay-small" *ngIf="r.attackerReroll != null">
                        {{ r.attackerReroll }}
                      </div>
                    </div>
                  </div>
                </div>

              </ng-container>

              <!-- Pas combo Valse+Foca -->
              <ng-template #noWaltzFocusCombo>

                <!-- CAS 2 : Valse seule -->
                <ng-container *ngIf="isBallroomWaltzFight(r); else noWaltz">

                  <div class="dice-column">
                    <div class="dice-section">
                      <div class="dice-label global">Dés de valse sanguinaire</div>
                      <div class="waltz-dice-grid">
                        <div class="dice-with-label valse-die"
                            *ngFor="let _ of waltzPlaceholderDice(); let i = index">
                          <div class="dice-wrap waltz-verysmall">
                            <img class="dice-verysmall"
                                [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                                  roleColorOf(getPlayer(r.attackerId)))"
                                alt="dé valse"/>
                            <div class="dice-overlay-small" *ngIf="waltzRolls.length">
                              {{ waltzRolls[i] }}
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                </ng-container>

                <!-- CAS 3 : pas de Valse -->
                <ng-template #noWaltz>

                  <!-- Focalisation seule : 2 petits dés -->
                  <ng-container *ngIf="hasFocus(r.attackerId); else singleBigDice">
                    <div class="dice-pair">
                      <!-- Dé de base -->
                      <div class="dice-with-label">
                        <div class="dice-label bg-badge">Dé de base</div>
                        <div class="dice-wrap">
                          <img class="dice"
                              [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                                roleColorOf(getPlayer(r.attackerId)))"
                              alt="dé de base"/>
                          <div class="dice-overlay" *ngIf="r.attackerFirstRoll != null">
                            {{ r.attackerFirstRoll }}
                          </div>
                        </div>
                      </div>

                      <!-- Dé de Focalisation -->
                      <div class="dice-with-label">
                        <div class="dice-label bg-badge">Dé de focalisation</div>
                        <div class="dice-wrap">
                          <img class="dice"
                              [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                                roleColorOf(getPlayer(r.attackerId)))"
                              alt="dé de focalisation"/>
                          <div class="dice-overlay" *ngIf="r.attackerReroll != null">
                            {{ r.attackerReroll }}
                          </div>
                        </div>
                      </div>
                    </div>
                  </ng-container>

                  <!-- CAS 4 : ni Valse ni Foca → gros dé classique -->
                  <ng-template #singleBigDice>
                    <div class="dice-wrap">
                      <img class="dice-big"
                          [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                            roleColorOf(getPlayer(r.attackerId)))"
                          alt="dice"/>
                      <div class="dice-overlay" *ngIf="r.attackerRoll != null">
                        {{ r.attackerRoll }}
                      </div>
                    </div>
                  </ng-template>

                </ng-template>
              </ng-template>
            </div>
            <ng-container *ngIf="modsForStat(getPlayer(r.attackerId), 'ATTACK') as atkMods">
              <div class="mods-row" *ngIf="atkMods.length">
                <span class="mod-chip" *ngFor="let m of atkMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrcForMod(m)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" [src]="isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png'" alt="potion_or_elixir"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
                  </div>

                  <div class="mods-badge-equip" *ngIf="m.source?.startsWith('EQUIP:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="équipement"/>
                  </div>

                  <div class="mods-badge-hit" *ngIf="m.source?.startsWith('HIT:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="effet de coup"/>
                  </div>
                  <span class="chip-val">{{ labelOrChip(m) }}</span>
                </span>
              </div>
            </ng-container>
          </ng-container>
          <ng-template #defenseSide>
            <!-- ===================== -->
            <!--       DEFENSEUR       -->
            <!-- ===================== -->
            <div class="roll-row">
              <!-- Icône à gauche -->
              <div class="icon-bubble oval">
                <div class="icon-halo oval"
                    [ngClass]="entityHaloIcon(r.defenderId, 'defense')">
                  <img class="icon-side"
                      [src]="roleIcon(getRole(getPlayer(r.defenderId)),'armor')"
                      alt="défense"/>
                </div>
              </div>

              <!-- Focalisation seule (défenseur) -->
              <ng-container *ngIf="hasFocus(r.defenderId); else defSingleBig">
                <div class="dice-pair">
                  <!-- Dé de base -->
                  <div class="dice-with-label">
                    <div class="dice-label bg-badge">Dé de base</div>
                    <div class="dice-wrap">
                      <img class="dice"
                          [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                            roleColorOf(getPlayer(r.defenderId)))"
                          alt="dé de base"/>
                      <div class="dice-overlay" *ngIf="r.defenderFirstRoll != null">
                        {{ r.defenderFirstRoll }}
                      </div>
                    </div>
                  </div>

                  <!-- Dé de focalisation -->
                  <div class="dice-with-label">
                    <div class="dice-label bg-badge">Dé de focalisation</div>
                    <div class="dice-wrap">
                      <img class="dice"
                          [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                            roleColorOf(getPlayer(r.defenderId)))"
                          alt="dé de focalisation"/>
                      <div class="dice-overlay" *ngIf="r.defenderReroll != null">
                        {{ r.defenderReroll }}
                      </div>
                    </div>
                  </div>
                </div>
              </ng-container>

              <!-- Pas de Foca : gros dé comme avant -->
              <ng-template #defSingleBig>
                <div class="dice-wrap">
                  <img class="dice-big"
                      [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                        roleColorOf(getPlayer(r.defenderId)))"
                      alt="dice"/>
                  <div class="dice-overlay" *ngIf="r.defenderRoll != null">
                    {{ r.defenderRoll }}
                  </div>
                </div>
              </ng-template>
            </div>

            <ng-container *ngIf="modsForStat(getPlayer(r.defenderId), 'DEFENSE') as defMods">
              <div class="mods-row" *ngIf="defMods.length">
                <span class="mod-chip" *ngFor="let m of defMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrcForMod(m)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" [src]="isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png'" alt="potion_or_elixir"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
                  </div>

                  <div class="mods-badge-equip" *ngIf="m.source?.startsWith('EQUIP:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="équipement"/>
                  </div>

                  <div class="mods-badge-hit" *ngIf="m.source?.startsWith('HIT:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="effet de coup"/>
                  </div>
                  <span class="chip-val">{{ labelOrChip(m) }}</span>
                </span>
              </div>
            </ng-container>
          </ng-template>

        </ng-container>
      </div>

      <div class="footer">
        <button (click)="rollNow()" [disabled]="!waitingForMyRoll || isRolling">
          {{ rollButtonLabel }}
        </button>
      </div>
    </div>
  </div>

  <!-- === MODALE SPECTATEUR === -->
  <div *ngIf="showSpectatorModal && !isGameEnded && currentCombat as r" class="modal-backdrop">
    <div class="modal location-modal spectate" 
        [class.has-focus]="hasFocus(r.attackerId) || hasFocus(r.defenderId)"
        [class.bite-active]="!!game?.currentBite && !isBeforeBiteModal"
        [style.backgroundImage]="setImageBackground('location')">
      <h3 style="margin-top:0" class="bg-badge">
        {{ modalTitle(r) }}
        <ng-container *ngIf="monsterHpInCombat(r) as hp">
          &nbsp;— <span>{{ hp }} PV</span>
        </ng-container>
      </h3>
      <div class="title-side-row">
        <h2 class="attack-title-side bg-badge">{{nameOrId(r.attackerId)}} attaque !</h2>
        <h2 class="defense-title-side bg-badge">{{nameOrId(r.defenderId)}} défend !</h2>
      </div>

      <div class="content spectate">
        <!-- Côté attaquant -->
        <div class="side">
          <!-- Colonne mods à GAUCHE -->
          <ng-container *ngIf="modsForEntityStat(r.attackerId, 'ATTACK') as atkMods">
            <div class="mods-col left" *ngIf="atkMods.length">
              <div class="mods-row">
                <span class="mod-chip" *ngFor="let m of atkMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrcForMod(m)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" [src]="isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png'" alt="potion_or_elixir"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
                  </div>

                  <div class="mods-badge-equip" *ngIf="m.source?.startsWith('EQUIP:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="équipement"/>
                  </div>

                  <div class="mods-badge-hit" *ngIf="m.source?.startsWith('HIT:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="effet de coup"/>
                  </div>
                  <span class="chip-val">{{ labelOrChip(m) }}</span>
                </span>
              </div>
            </div>
          </ng-container>

          <div class="icon-halo oval"
              [ngClass]="entityHaloIcon(r.attackerId, 'attack')">
            <img class="icon-side"
                [src]="entityRoleIcon(r.attackerId,'sword')"
                alt="attaque"/>
          </div>

          <div class="dice-row">
            <!-- CAS 1 : duel de Valse -->
            <ng-container *ngIf="isBallroomWaltzFight(r); else spectateNoWaltz">

              <!-- Valse + Foca ? -->
              <ng-container *ngIf="hasFocus(r.attackerId); else spectateValseOnly">
                <div class="dice-column">
                  <!-- Valse -->
                  <div class="dice-section">
                    <div class="dice-label global bg-badge">Dés de valse sanguinaire</div>
                    <div class="waltz-dice-grid">
                      <div class="dice-with-label valse-die"
                          *ngFor="let _ of waltzPlaceholderDice(); let i = index">
                        <div class="dice-wrap waltz-verysmall">
                          <img class="dice-verysmall"
                              [src]="diceAsset(entityAttackDice(r.attackerId),
                                                entityColor(r.attackerId))"
                              alt="dé valse"/>
                          <div class="dice-overlay-small" *ngIf="waltzRolls.length">
                            {{ waltzRolls[i] }}
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- Foca sous les dés de Valse -->
                  <div class="dice-section focus-single">
                    <div class="dice-label bg-badge">Dé de focalisation</div>
                    <div class="dice-wrap waltz-verysmall">
                      <img class="dice-verysmall"
                          [src]="diceAsset(entityAttackDice(r.attackerId),
                                            entityColor(r.attackerId))"
                          alt="dé focalisation"/>
                      <div class="dice-overlay-small" *ngIf="r.attackerReroll != null">
                        {{ r.attackerReroll }}
                      </div>
                    </div>
                  </div>
                </div>
              </ng-container>

              <!-- Valse seule -->
              <ng-template #spectateValseOnly>
                <div class="dice-column">
                  <div class="dice-section">
                    <div class="dice-label global">Dés de valse sanguinaire</div>
                    <div class="waltz-dice-grid">
                      <div class="dice-with-label valse-die"
                          *ngFor="let _ of waltzPlaceholderDice(); let i = index">
                        <div class="dice-wrap waltz-verysmall">
                          <img class="dice-verysmall"
                              [src]="diceAsset(entityAttackDice(r.attackerId),
                                                entityColor(r.attackerId))"
                              alt="dé valse"/>
                          <div class="dice-overlay-small" *ngIf="waltzRolls.length">
                            {{ waltzRolls[i] }}
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </ng-template>

            </ng-container>

            <!-- PAS VALSE -->
            <ng-template #spectateNoWaltz>
              <!-- Foca seule : 2 petits dés avec labels -->
              <ng-container *ngIf="showFocusSpectate(r.attackerId); else attackerSingleDice">
                <div class="dice-pair">
                  <div class="dice-with-label">
                    <div class="dice-label bg-badge">Dé de base</div>
                    <div class="dice-wrap waltz-verysmall">
                      <img class="dice-verysmall"
                          [src]="diceAsset(entityAttackDice(r.attackerId),
                                            entityColor(r.attackerId))"
                          alt="premier dé"/>
                      <div class="dice-overlay-small" *ngIf="r.attackerFirstRoll != null">
                        {{ r.attackerFirstRoll }}
                      </div>
                    </div>
                  </div>

                  <div class="dice-with-label">
                    <div class="dice-label bg-badge">Dé de focalisation</div>
                    <div class="dice-wrap waltz-verysmall">
                      <img class="dice-verysmall"
                          [src]="diceAsset(entityAttackDice(r.attackerId),
                                            entityColor(r.attackerId))"
                          alt="dé de focalisation"/>
                      <div class="dice-overlay-small" *ngIf="r.attackerReroll != null">
                        {{ r.attackerReroll }}
                      </div>
                    </div>
                  </div>
                </div>
              </ng-container>

              <!-- Ni Foca ni Valse : un seul dé comme avant -->
              <ng-template #attackerSingleDice>
                <div class="dice-wrap">
                  <img class="dice-big"
                      [src]="diceAsset(entityAttackDice(r.attackerId),
                                        entityColor(r.attackerId))"
                      alt="dice"/>
                  <div class="dice-overlay"
                      *ngIf="(r.attackerRoll ?? r.attackerFirstRoll) != null">
                    {{ r.attackerRoll ?? r.attackerFirstRoll }}
                  </div>
                </div>
              </ng-template>
            </ng-template>
          </div>
        </div>

        <!-- Côté défenseur -->
        <div class="side">
          <!-- Colonne mods à DROITE -->
          <ng-container *ngIf="modsForEntityStat(r.defenderId, 'DEFENSE') as defMods">
            <div class="mods-col right" *ngIf="defMods.length">
              <div class="mods-row">
                <span class="mod-chip" *ngFor="let m of defMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrcForMod(m)" alt="icône météo" />
                  </div>
                  
                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" [src]="isElixirMod(m) ? 'assets/icons/elixir-icon.png' : 'assets/icons/potion-icon.png'" alt="potion_or_elixir"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
                  </div>

                  <div class="mods-badge-equip" *ngIf="m.source?.startsWith('EQUIP:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="équipement"/>
                  </div>

                  <div class="mods-badge-hit" *ngIf="m.source?.startsWith('HIT:')">
                    <img class="mod-ico" [src]="modIconSrc(m.source)" alt="effet de coup"/>
                  </div>
                  <span class="chip-val">{{ labelOrChip(m) }}</span>
                </span>
              </div>
            </div>
          </ng-container>
          <div class="icon-halo oval"
              [ngClass]="entityHaloIcon(r.defenderId, 'defense')">
            <img class="icon-side"
                [src]="entityRoleIcon(r.defenderId,'armor')"
                alt="défense"/>
          </div>

          <div class="dice-row">
            <!-- Focalisation seule (défenseur) -->
            <ng-container *ngIf="hasFocus(r.defenderId); else defSingleBig">
              <div class="dice-pair">
                <!-- Dé de base -->
                <div class="dice-with-label">
                  <div class="dice-label bg-badge">Dé de base</div>
                  <div class="dice-wrap waltz-verysmall">
                    <img class="dice-verysmall"
                        [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                          roleColorOf(getPlayer(r.defenderId)))"
                        alt="dé de base"/>
                    <div class="dice-overlay-small" *ngIf="r.defenderFirstRoll != null">
                      {{ r.defenderFirstRoll }}
                    </div>
                  </div>
                </div>

                <!-- Dé de focalisation -->
                <div class="dice-with-label">
                  <div class="dice-label bg-badge">Dé de focalisation</div>
                  <div class="dice-wrap waltz-verysmall">
                    <img class="dice-verysmall"
                        [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                          roleColorOf(getPlayer(r.defenderId)))"
                        alt="dé de focalisation"/>
                    <div class="dice-overlay-small" *ngIf="r.defenderReroll != null">
                      {{ r.defenderReroll }}
                    </div>
                  </div>
                </div>
              </div>
            </ng-container>

            <!-- Pas de Foca : gros dé comme avant -->
            <ng-template #defSingleBig>
              <div class="dice-wrap">
                <img class="dice-big"
                    [src]="diceAsset(entityDefenseDice(r.defenderId),
                                      entityColor(r.defenderId))"
                    alt="dice"/>
                <div class="dice-overlay" *ngIf="r.defenderRoll != null">
                  {{ r.defenderRoll }}
                </div>
              </div>
            </ng-template>
          </div>
        </div>
      </div>

      <div class="breakdown bg-badge" *ngIf="currentCombat?.breakdownLines?.length">
        <div *ngFor="let line of currentCombat!.breakdownLines">{{ line }}</div>
      </div>
    </div>
  </div>
  <!-- === MODALE MORSURE (PHASE3, quand currentBite actif) === -->
  <div *ngIf="showBiteModal && !isGameEnded" class="modal-backdrop">
    <div class="modal bite-modal" [style.backgroundImage]="setImageBackground('bite')">
      <h3 class="bg-badge">Tentative de morsure</h3>

      <div class="content action">
        <div class="icon-bubble round">
          <div class="icon-halo round">
            <img class="icon-side" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
          </div>
        </div>

        <div class="dice-row">
          <!-- D6 de morsure -->
          <div class="dice-wrap" [attr.data-digits]="1">
            <img class="dice-big" src="/assets/dices/d6-red.png" alt="d6"/>
            <div class="dice-overlay" *ngIf="game?.currentBite?.roll != null">
              {{ game?.currentBite?.roll }}
            </div>
          </div>

          <!-- D4 d’armure (si armure d’argent concernée) -->
          <div class="dice-wrap"
              *ngIf="showArmorDie"
              [attr.data-digits]="1">
            <!-- adapte le visuel si tu n’as pas de d4 -->
            <img class="dice-big" src="/assets/dices/d4-white.png" alt="d4"/>
            <div class="dice-overlay" *ngIf="game?.currentBite?.armorRoll != null">
              {{ game?.currentBite?.armorRoll }}
            </div>
          </div>
        </div>
      </div>

      <div class="footer">
        <!-- Étape 1 : jet de morsure (D6) -->
        <ng-container *ngIf="biteStage() === 'BITE'; else afterFirstRoll">
          <ng-container *ngIf="canRollBite(); else waitBite">
            <button (click)="rollCorruption()" class="btn-primary">Jeter le dé</button>
          </ng-container>
          <ng-template #waitBite>
            <span class="bg-badge">En attente du jet…</span>
          </ng-template>
        </ng-container>

        <!-- Étape 2 : jet d’armure (D4) ou résultat final -->
        <ng-template #afterFirstRoll>
          <!-- Jet d’armure en attente -->
          <ng-container *ngIf="biteStage() === 'ARMOR'; else finalResult">
            <ng-container *ngIf="canRollArmor(); else waitArmor">
              <button (click)="rollArmor()" class="btn-primary">Jet d’armure (d4)</button>
            </ng-container>
            <ng-template #waitArmor>
              <span class="bg-badge">En attente du jet d’armure…</span>
            </ng-template>
          </ng-container>

          <!-- Résultat final -->
          <ng-template #finalResult>
            <span class="bg-badge">{{ biteResultText() }}</span>
            <span class="bg-badge">{{ altarRitualText() }}</span>
          </ng-template>
        </ng-template>
      </div>
    </div>
  </div>
  <!-- === MODALE CHOIX INSTABLE (PREPHASE3) === -->
  <div *ngIf="showUnstableModal()" class="modal-backdrop">
    <div class="modal corruption-modal with-bg" [style.backgroundImage]="setImageBackground('corruption')">
      <h3 class="bg-badge" style="margin-top:0;">
        Contrôle d’un chasseur instable
      </h3>

      <div class="scrim-bottom"></div>

      <div class="content corruption">
        <div class="bg-box">

          <ng-container *ngFor="let it of unstableChoices; trackBy: trackByUnstable">
            <ng-container *ngIf="getUnstableDefaultFight(it.unstableId) as info">
              <div class="bg-badge warn" *ngIf="info.willFight">
                {{ usernameOf(it.unstableId) }} va bientôt combattre sur {{ labelLocation(info.loc!) }}
                contre {{ info.opponentName }}. Si vous lui ordonnez une récolte, cela annulera son combat !
              </div>
            </ng-container>

            <div *ngIf="it.targets?.length">
              <div class="label-choices">
                Choisir un chasseur pris pour cible par {{ usernameOf(it.unstableId) }}
              </div>
              <div class="choices">
                <button *ngFor="let tid of it.targets"
                        (click)="assignUnstableTarget(it.unstableId, tid)"
                        [disabled]="!isMeVampire || isUnstableLocked(it.unstableId)"
                        [class.is-disabled]="isUnstableAlreadyDecided(it.unstableId)"
                        [attr.aria-disabled]="isUnstableAlreadyDecided(it.unstableId) ? true : null">>
                  {{ usernameOf(tid) }}
                </button>
              </div>
            </div>

            <div>
              <div class="label-choices">
                Choisir un lieu où {{ usernameOf(it.unstableId) }} récoltera pour {{ vampireName() }}
              </div>
              <div class="choices">
                <button *ngFor="let loc of it.locations"
                        (click)="assignUnstableHarvest(it.unstableId, loc)"
                        [disabled]="!isMeVampire || isUnstableLocked(it.unstableId)">
                  {{ labelLocation(loc) }}
                </button>

                <!-- Bouton "Ne rien faire" seulement si combat par défaut ET choix encore possible -->
                <button *ngIf="getUnstableDefaultFight(it.unstableId).willFight && isPendingUnstable(it.unstableId)"
                        (click)="assignUnstableNothing(it.unstableId)"
                        [disabled]="!isMeVampire || isUnstableLocked(it.unstableId)">
                  Ne rien faire
                </button>
              </div>
            </div>
          </ng-container>
        </div>
      </div>
    </div>
  </div>
  <!-- ===== MODALE BOUTIQUE / TRANSMUTATION (Phase 4) ===== -->
  <div *ngIf="shopOpen && !isGameEnded" class="modal-backdrop">
    <div class="modal trade-modal"
        [class.hunters]="isHunter"
        [class.vampires]="isVampireSide">

      <header class="modal-head">
        <h3 *ngIf="isHunter">Boutique</h3>
        <h3 *ngIf="isVampireSide">Transmutation</h3>

        <div class="inventory" *ngIf="me as p">
          <span class="inv-item" title="Bois">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-l" src="/assets/icons/wood.png" alt="Bois"></span>
            <span class="inv-val">{{ p.wood }}</span>
          </span>

          <span class="inv-item" title="Herbe médicinale">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-s" src="/assets/icons/medical_grass.png" alt="Herbe médicinale"></span>
            <span class="inv-val">{{ p.herbs }}</span>
          </span>

          <span class="inv-item" title="Pierre">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-s" src="/assets/icons/stone.png" alt="Pierre"></span>
            <span class="inv-val">{{ p.stone }}</span>
          </span>

          <span class="inv-item" title="Fer">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-m" src="/assets/icons/iron.png" alt="Fer"></span>
            <span class="inv-val">{{ p.iron }}</span>
          </span>

          <span class="inv-item" title="Eau pure">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-s" src="/assets/icons/water.png" alt="Eau pure"></span>
            <span class="inv-val">{{ p.water }}</span>
          </span>

          <span *ngIf="p.role==='HUNTER'" class="inv-item" title="Or">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-s" src="/assets/icons/gold.png" alt="Or"></span>
            <span class="inv-val">{{ p.gold }}</span>
          </span>

          <span *ngIf="p.role==='HUNTER'" class="inv-item" title="Argent">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-s" src="/assets/icons/silver.png" alt="Argent"></span>
            <span class="inv-val">{{ p.silver }}</span>
          </span>

          <span *ngIf="p.role==='VAMPIRE' || p.role==='SERVANT'" class="inv-item" title="Âmes déchues">
            <span class="inv-ico-box"><img class="inv-ico inv-ico-s" src="/assets/icons/souls.png" alt="Âmes déchues"></span>
            <span class="inv-val">{{ p.souls }}</span>
          </span>
        </div>

        <div class="head-actions">
          <div *ngIf="!isMeDead && !waitingDone; else waitingTpl">
            <button class="finish" (click)="onFinishPhase4()">Ne rien faire</button>
          </div>
          <ng-template #waitingTpl>
            <div class="muted">En attente des autres joueurs…</div>
          </ng-template>
        </div>

        <div class="timer" *ngIf="phase4LeftSec>0">⏱ {{ phase4LeftSec }}s</div>
      </header>

      <section class="modal-body" [class.has-mid]="isHunter">

        <!-- =========================
            COLONNE 1 : SHOP (sans vendre/argent, sans bonus -> Pisteur)
            ========================= -->
        <div class="col col-shop-a">

          <div class="card" *ngIf="myMaintenanceActions().length">
            <h4>Actions de maintenance</h4>
            <small>utiliser depuis la main</small>
            <div class="shop-actions-grid">
              <button *ngFor="let action of myMaintenanceActions(); let i = index"
                      class="shop-card-action-btn"
                      [class.is-disabled]="!canUseActionNow(action)"
                      [attr.aria-disabled]="!canUseActionNow(action) ? true : null"
                      [attr.title]="actionLabelFr(action)"
                      (click)="useAction(action)"
                      (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()">
                <img class="shop-card-img"
                    [src]="actionImg(action)"
                    [alt]="actionLabelFr(action)" />
              </button>
            </div>
          </div>

          <!-- ACTIONS + POTIONS sur la même ligne (2 cards) -->
          <div class="card-row row-2">
            <!-- Actions vampire -->
            <div class="card" *ngIf="isMeVampire">
              <div class="card-header-line">
                <h4>Actions</h4>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">50</span>
                    <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/souls.png" alt="Âmes"></span>
                  </span>
                </span>
              </div>

              <button class="shop-btn buy-card-btn"
                      (click)="onBuyAction($event)"
                      [disabled]="!canBuyVampAction"
                      aria-label="Acheter une carte action vampire"
                      (mouseenter)="zoomEnter($event, deckCount(game?.decks?.actionsVamp), false, 'L')"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()">
                <img class="buy-img" [src]="deckBackFor('VAMP_ACTIONS')" alt="Deck actions vampire">
                <span class="buy-badge badge-vamp">{{ deckCount(game?.decks?.actionsVamp) }}</span>
              </button>
            </div>

            <!-- Actions chasseur -->
            <div class="card" *ngIf="isHunter">
              <div class="card-header-line">
                <h4>Actions</h4>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">{{ actionPrice }}</span>
                    <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/gold.png" alt="Or"></span>
                  </span>
                </span>
              </div>

              <button class="shop-btn buy-card-btn"
                      (click)="onBuyAction($event)"
                      [disabled]="!canBuyHunterAction"
                      aria-label="Acheter une carte action chasseur"
                      (mouseenter)="zoomEnter($event, deckCount(game?.decks?.actionsHunters), true, 'L')"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()">
                <img class="buy-img" [src]="deckBackFor('HUNTER_ACTIONS')" alt="Deck actions chasseur">
                <span class="buy-badge badge-hunter">{{ deckCount(game?.decks?.actionsHunters) }}</span>
              </button>
            </div>

            <!-- Potions -->
            <div class="card">
              <div class="card-header-line">
                <h4>Potions</h4>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">4</span>
                    <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/water.png" alt="Eau"></span>
                    <span class="cost-val">3</span>
                    <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/medical_grass.png" alt="Herbes"></span>
                  </span>
                </span>
              </div>

              <button class="shop-btn buy-card-btn"
                      (click)="onBuyPotion($event)"
                      [disabled]="!canBuyPotion"
                      aria-label="Acheter une potion"
                      (mouseenter)="zoomEnter($event, deckCount(game?.decks?.potions), undefined, 'L')"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()">
                <img class="buy-img" [src]="deckBackFor('POTIONS')" alt="Deck potions">
                <span class="buy-badge">{{ deckCount(game?.decks?.potions) }}</span>
              </button>
            </div>
          </div>

          <!-- PISTEUR + EAU BÉNITE sur la même ligne (2 cards) -->
          <div class="card-row row-2" *ngIf="isHunter">

            <!-- Pisteur -->
            <div class="card card-buy-bottom">
              <div class="card-header-line">
                <h4>Espion</h4>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">{{ trackingGoldPrice }}</span>
                    <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/gold.png" alt="Or"></span>
                  </span>
                </span>
              </div>

              <button class="shop-btn buy-card-btn shop-card-action-btn"
                      (click)="onBuyTrackingAction($event)"
                      [disabled]="!canBuyTrackingAction"
                      aria-label="Acheter Pisteur"
                      (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()">
                <img class="buy-img" [src]="actionImg('PISTEUR')" alt="Pisteur">
              </button>
            </div>

            <!-- Eau bénite -->
            <div class="card card-buy-bottom">
              <div class="card-header-line">
                <h4>Eau bénite</h4>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">3</span>
                    <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/water.png" alt="Eau"></span>
                    <span class="cost-val">{{ holyWaterGoldPrice }}</span>
                    <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/gold.png" alt="Or"></span>
                  </span>
                </span>
              </div>

              <button class="shop-btn buy-card-btn shop-card-action-btn"
                      (click)="onBuyHolyWaterAction($event)"
                      [disabled]="!canBuyHolyWaterAction"
                      aria-label="Acheter Eau bénite"
                      (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                      (mousemove)="zoomMove($event)"
                      (mouseleave)="zoomLeave()">
                <img class="buy-img" [src]="actionImg('EAU_BENITE')" alt="Eau bénite">
              </button>
            </div>
          </div>

          <!-- Transmutation vampire -->
          <div class="card" *ngIf="isVampireSide">
            <h4>Recettes de transmutation</h4>

            <ul class="recipes">

              <!-- 2 wood + 1 water -> +2 iron -->
              <li>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">2</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/wood.png" alt="Bois">
                    </span>

                    <span class="cost-plus">+</span>

                    <span class="cost-val">1</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/water.png" alt="Eau">
                    </span>

                    <span class="cost-or">→</span>

                    <span class="cost-plus">+</span>
                    <span class="cost-val">2</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/iron.png" alt="Fer">
                    </span>
                  </span>
                </span>

                <button
                  (click)="onTransmute('WOOD_TO_IRON')"
                  [disabled]="me?.wood! < 2 || me?.water! < 1 || isMeDead">
                  Transmuter
                </button>
              </li>

              <!-- 2 iron + 1 water -> +2 wood -->
              <li>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">2</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/iron.png" alt="Fer">
                    </span>

                    <span class="cost-plus">+</span>

                    <span class="cost-val">1</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/water.png" alt="Eau">
                    </span>

                    <span class="cost-or">→</span>

                    <span class="cost-plus">+</span>
                    <span class="cost-val">2</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/wood.png" alt="Bois">
                    </span>
                  </span>
                </span>

                <button
                  (click)="onTransmute('IRON_TO_WOOD')"
                  [disabled]="me?.iron! < 2 || me?.water! < 1 || isMeDead">
                  Transmuter
                </button>
              </li>

              <!-- 1 wood + 1 iron + 1 water -> +30 souls -->
              <li>
                <span class="price price-icons">
                  <span class="cost-item">
                    <span class="cost-val">1</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/wood.png" alt="Bois">
                    </span>

                    <span class="cost-plus">+</span>

                    <span class="cost-val">1</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/iron.png" alt="Fer">
                    </span>

                    <span class="cost-plus">+</span>

                    <span class="cost-val">1</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/water.png" alt="Eau">
                    </span>

                    <span class="cost-or">→</span>

                    <span class="cost-plus">+</span>
                    <span class="cost-val">30</span>
                    <span class="cost-ico-box">
                      <img class="cost-ico cost-ico-s" src="/assets/icons/souls.png" alt="Âmes">
                    </span>
                  </span>
                </span>

                <button
                  (click)="onTransmute('TRINITY_TO_SOULS')"
                  [disabled]="me?.wood! < 1 || me?.iron! < 1 || me?.water! < 1 || isMeDead">
                  Transmuter
                </button>
              </li>

            </ul>
          </div>
        </div>

        <!-- =========================
            COLONNE 2 : Vendre + Acheter argent + Bonus (hunter)
            ========================= -->
        <div class="col col-shop-b" *ngIf="isHunter">

          <div class="card">
            <div class="card-header-line">
              <h4>Vendre des ressources</h4>

              <!-- +10 + icône gold (comme achat silver) -->
              <span class="price price-icons">
                <span class="cost-item" title="Or gagné">
                  <span class="cost-plus">+</span>
                  <span class="cost-val">10</span>
                  <span class="cost-ico-box">
                    <img class="cost-ico cost-ico-s" src="/assets/icons/gold.png" alt="Or">
                  </span>
                </span>
              </span>
            </div>

            <!-- Grille icônes -->
            <div class="grid sell-grid sell-grid-icons">
              <div class="sell" *ngFor="let r of sellableResources" [title]="r + ' (x' + resOf(me, r) + ')'">
                <div class="sell-ico">
                  <img
                    class="sell-res-ico"
                    [src]="'/assets/icons/' + (
                      r === 'wood'  ? 'wood.png' :
                      r === 'herbs' ? 'medical_grass.png' :
                      r === 'stone' ? 'stone.png' :
                      r === 'iron'  ? 'iron.png' :
                      r === 'water' ? 'water.png' :
                      r
                    )"
                    [alt]="r"
                  />
                </div>

                <div class="actions">
                  <button (click)="onSell(r, 1)" [disabled]="resOf(me, r) < 1 || isMeDead">-1</button>
                </div>
              </div>
            </div>
          </div>

          <div class="card">
            <div class="card-header-line">
              <h4>Acheter de l’argent</h4>
              <span class="price price-icons">
                <span class="cost-item" title="Or">
                  <span class="cost-ico-box">
                    <img class="cost-ico cost-ico-s" src="/assets/icons/gold.png" alt="Or">
                  </span>
                  <span class="cost-val">{{ silverPrice }}</span>
                </span>
              </span>
            </div>

            <div class="row" style="justify-content:center; align-items:center;">
              <button (click)="onBuySilver(1)" [disabled]="!canBuySilver">+1</button>
              <button (click)="onBuySilver(5)" [disabled]="!canBuySilverQty(5)">+5</button>
            </div>
          </div>


          <div class="card card-buy-bottom" *ngIf="game?.shopBonusKind">
            <div class="card-header-line">
              <h4>{{ bonusTitle() }}</h4>

              <span class="price price-icons">
                <ng-container *ngIf="bonusResCost() as rc">
                  <span class="cost-item"> 
                    <span *ngIf="rc.water" class="cost-val">{{ rc.water }}</span>
                    <span *ngIf="rc.water" class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/water.png" alt="Eau"></span>
                    <span *ngIf="rc.herbs" class="cost-val">{{ rc.herbs }}</span>
                    <span *ngIf="rc.herbs" class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/medical_grass.png" alt="Herbes"></span>
                    <span *ngIf="rc.wood" class="cost-val">{{ rc.wood }}</span>
                    <span *ngIf="rc.wood" class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/wood.png" alt="Bois"></span>
                    <span *ngIf="rc.iron" class="cost-val">{{ rc.iron }}</span>
                    <span *ngIf="rc.iron" class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/iron.png" alt="Fer"></span>
                    <span *ngIf="rc.silver" class="cost-val">{{ rc.silver }}</span>
                    <span *ngIf="rc.silver" class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/silver.png" alt="Argent"></span>
                  </span>              
                </ng-container>

                <span class="cost-or">ou</span>

                <span class="cost-item" title="Or" *ngIf="bonusGoldCost() as gc">
                  <span class="cost-val">{{ gc }}</span>
                  <span class="cost-ico-box"><img class="cost-ico cost-ico-s" src="/assets/icons/gold.png" alt="Or"></span>
                </span>
              </span>
            </div>

            <button class="shop-btn buy-card-btn shop-card-action-btn"
                    (click)="onBuyBonus($event)"
                    [disabled]="!canBuyBonus()"
                    aria-label="Acheter objet bonus"
                    (mouseenter)="zoomEnter($event, bonusTitle(), true, 'L')"
                    (mousemove)="zoomMove($event)"
                    (mouseleave)="zoomLeave()">
              <img class="buy-img" [src]="bonusBuyImgSrc()" [alt]="bonusTitle()">
            </button>
          </div>
        </div>

        <!-- =========================
            COLONNE 3 : ÉCHANGES
            ========================= -->
        <div class="col col-trades">
          <div class="card">
            <h4>Proposer un échange</h4>

            <div class="targets" *ngIf="!isMeDead">
              <button *ngFor="let p of eligibleTradeTargets"
                      (click)="selectTradeTarget(p.id)"
                      [class.active]="p.id===selectedTradeTargetId"
                      class="shop-btn">
                {{ p.username }}
              </button>
            </div>

            <div *ngIf="selectedTradeTargetId as tgtId" class="trade-area">
              <div class="card">
                <h4>Mes ressources</h4>
                <div class="grid">
                  <div class="res" *ngFor="let r of tradeResources()">
                    <div class="label">{{ r }} <small>(x{{ resOf(me, r) }})</small></div>

                    <div class="actions" *ngIf="r !== 'gold'; else goldActions">
                      <button (click)="bumpOffer(r, +1)" [disabled]="resOf(me, r) < (offerQty(r)+1)">+1</button>
                      <button (click)="bumpOffer(r, -1)" [disabled]="offerQty(r) <= 0">-1</button>
                    </div>

                    <ng-template #goldActions>
                      <div class="actions">
                        <button (click)="bumpOffer(r, +10)" [disabled]="resOf(me, r) < (offerQty(r)+10)">+10</button>
                        <button (click)="bumpOffer(r, -10)" [disabled]="offerQty(r) < 10">-10</button>
                      </div>
                    </ng-template>
                  </div>
                </div>
              </div>
            </div>

            <div class="card">
              <h4>Échanges en cours</h4>

              <div class="trade-toast"
                  *ngFor="let f of activeFlashes()"
                  [class.ok]="f.kind==='ok'"
                  [class.ko]="f.kind==='ko'">
                {{ f.text }}
              </div>

              <div class="trade-list">
                <div class="trade-block"
                    *ngFor="let T of myTradesSorted()"
                    [class.active]="otherIdFromTrade(T)===selectedTradeTargetId"
                    [class.closing]="isClosing(T.id)"
                    [class.closing-ok]="isClosingOk(T.id)"
                    [class.closing-ko]="isClosingKo(T.id)">

                  <div class="row" style="justify-content:space-between;">
                    <strong>{{ usernameOf(otherIdFromTrade(T)) }}</strong>
                    <small class="muted">maj {{ T.updatedAt | date:'shortTime' }}</small>
                  </div>

                  <div class="card sub"
                      [class.ok]="statusClassFrom(otherStatus(T))==='ok'"
                      [class.ko]="statusClassFrom(otherStatus(T))==='ko'">
                    <h5>Proposition d’échange de {{ usernameOf(T.aId===me?.id ? T.bId : T.aId) }}</h5>
                    <div class="pill" *ngFor="let k of ((iAmA(T)?T.offerB:T.offerA) | keyvalue)">
                      {{k.key}} x{{k.value}}
                    </div>
                    <div *ngIf="!((iAmA(T)?T.offerB:T.offerA) | keyvalue).length" class="muted">En attente…</div>
                  </div>

                  <div class="card sub"
                      [class.ok]="statusClassFrom(myStatus(T))==='ok'"
                      [class.ko]="statusClassFrom(myStatus(T))==='ko'">
                    <h5>Proposition d’échange pour {{ usernameOf(otherIdFromTrade(T)) }}</h5>
                    <div class="pill" *ngFor="let k of ((iAmA(T)?T.offerA:T.offerB) | keyvalue)">
                      {{k.key}} x{{k.value}}
                    </div>
                    <div *ngIf="!((iAmA(T)?T.offerA:T.offerB) | keyvalue).length" class="muted">Rien.</div>
                  </div>

                  <div class="row buttons">
                    <button class="confirm" (click)="onTradeActionFor('confirm', otherIdFromTrade(T))" [disabled]="isClosing(T.id)">Confirmer</button>
                    <button class="refuse"  (click)="onTradeActionFor('refuse',  otherIdFromTrade(T))" [disabled]="isClosing(T.id)">Refuser</button>
                    <button class="cancel"  (click)="onTradeActionFor('cancel',  otherIdFromTrade(T))" [disabled]="isClosing(T.id)">Annuler</button>
                  </div>
                </div>
              </div>
            </div>

          </div>
        </div>

      </section>
    </div>
  </div>

  <!-- ===== MODALE ACTION ===== -->
  <div class="modal-backdrop" *ngIf="showActionModal && !isGameEnded">
    <div
      class="modal action-modal with-bg"
      [ngStyle]="{'background-image': actionBackgroundSrc(actionMode)}"
    >
      <div class="content action">
        <div class="action-box">
          <h2>{{ actionLabelFr(actionMode) }}</h2>

          <p *ngIf="actionLocation">
            Lieu : {{ actionLocation }}
          </p>

          <!-- ========== EAU BENITE (PREPHASE3 : choix d'effet) ========== -->
          <ng-container *ngIf="actionMode === 'EAU_BENITE'">
            <ng-container *ngIf="isActionActor; else holyWaterSpectate">
              <p class="breakdown">
                Votre eau bénite peut être utilisée de trois façons pour ce raid&nbsp;:
              </p>

              <ul class="breakdown">
                <li>Purifier une partie de votre corruption.</li>
                <li>Consacrer votre arme contre le vampire (+4 dégâts).</li>
                <li *ngIf="isDarkMarkedMe()">
                  Dissiper la Marque ténébreuse qui pèse sur vous.
                </li>
              </ul>

              <div class="holy-water-choices">
                <button type="button"
                        (click)="onHolyWaterChoice('REDUCE')"
                        [disabled]="!canHolyWaterReduce() || actionResolving">
                  Réduire ma corruption
                </button>

                <button type="button"
                        (click)="onHolyWaterChoice('ATTACK')"
                        [disabled]="!canHolyWaterAttack() || actionResolving">
                  Utiliser en attaque
                </button>

                <button type="button"
                        *ngIf="isDarkMarkedMe()"
                        (click)="onHolyWaterChoice('CLEANSE')"
                        [disabled]="!canHolyWaterCleanse() || actionResolving">
                  Purifier la marque
                </button>
              </div>
            </ng-container>

            <ng-template #holyWaterSpectate>
              <p class="breakdown">
                Le chasseur décide comment utiliser son eau bénite...
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== FILET ========== -->
          <ng-container *ngIf="actionMode === 'NET'">

            <!-- Vue ACTEUR : choix de cible + bouton de dé -->
            <ng-container *ngIf="isActionActor; else netSpectate">

              <p>Choisis une cible :</p>

              <div class="action-targets">
                <button
                  type="button"
                  *ngFor="let p of trapEnemies"
                  (click)="selectActionTarget(p.id)"
                  [disabled]="actionResolving || actionRoll !== null"
                  [class.selected]="actionSelectedTargetId === p.id">
                  {{ p.username }} ({{ p.role }})
                </button>
              </div>

              <button
                type="button"
                (click)="onNetRoll()"
                [disabled]="!actionSelectedTargetId || actionResolving || actionRoll !== null">
                Lancer le dé
              </button>
            </ng-container>

            <!-- Vue SPECTATEUR : on montre seulement la cible choisie / en attente -->
            <ng-template #netSpectate>
              <ng-container *ngIf="actionSelectedTargetId; else netWaitTarget">
                <p>Cible : {{ ActionTargetName }}</p>
              </ng-container>
              <ng-template #netWaitTarget>
                <p>En attente du choix de la cible...</p>
              </ng-template>
            </ng-template>
          </ng-container>

          <!-- ========== FOSSE ========== -->
          <ng-container *ngIf="actionMode === 'PIT'">

            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else pitSpectate">

              <p *ngIf="currentPitTarget">
                Cible : {{ currentPitTarget.username }} ({{ currentPitTarget.role }})
              </p>

              <button
                type="button"
                (click)="onPitRoll()"
                [disabled]="actionResolving || actionRoll !== null || !currentPitTarget">
                Lancer le dé
              </button>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #pitSpectate>
              <ng-container *ngIf="currentPitTarget; else pitWaitTarget">
                <p>Cible : {{ currentPitTarget.username }} ({{ currentPitTarget.role }})</p>
              </ng-container>
              <ng-template #pitWaitTarget>
                <p>En attente du jet pour la fosse...</p>
              </ng-template>
            </ng-template>
          </ng-container>

          <!-- ========== PROVOCATION ========== -->
          <ng-container *ngIf="actionMode === 'PROVOCATION'">
            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else provocationSpectate">
              <p class="breakdown">
                Choisissez un ennemi (vampire ou serviteur) à provoquer : il devra vous cibler
                en priorité ce raid et ses autres attaques seront annulées.
              </p>

              <div class="action-targets">
                <button
                  type="button"
                  *ngFor="let p of trapEnemies"
                  (click)="onProvocationChoose(p.id)"
                  [disabled]="actionResolving">
                  {{ p.username }} ({{ p.role }})
                </button>
              </div>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #provocationSpectate>
              <p class="breakdown">
                Un chasseur utilise Provocation et choisit sa cible...
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== INCENDIAIRE ========== -->
          <ng-container *ngIf="actionMode === 'INCENDIAIRE'">
            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else incendiaireSpectate">

              <p *ngIf="!incendiaireChoices.length">
                Aucune construction à incendier sur cette zone.
              </p>

              <!-- Cas classique (1 ou plusieurs choix) -->
              <ng-container *ngIf="incendiaireChoices.length === 1">
                <p>
                  Construction ciblée :
                  {{ labelLocation(incendiaireChoices[0].toLowerCase()) }}
                </p>

                <button
                  type="button"
                  (click)="onIncendiaireRoll()"
                  [disabled]="actionResolving || actionRoll !== null">
                  Lancer le dé
                </button>
              </ng-container>

              <ng-container *ngIf="incendiaireChoices.length > 1">
                <p>Choisis une construction à incendier :</p>

                <div class="action-targets">
                  <button
                    type="button"
                    *ngFor="let infra of incendiaireChoices"
                    (click)="actionSelectedTargetId = infra"
                    [disabled]="actionResolving || actionRoll !== null"
                    [class.selected]="actionSelectedTargetId === infra">
                    {{ labelLocation(infra.toLowerCase()) }}
                  </button>
                </div>

                <button
                  type="button"
                  (click)="onIncendiaireRoll()"
                  [disabled]="!actionSelectedTargetId || actionResolving || actionRoll !== null">
                  Lancer le dé
                </button>
              </ng-container>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #incendiaireSpectate>
              <ng-container *ngIf="actionSelectedTargetId; else incendiaireWait">
                <p>Cible : {{ ActionTargetName }}</p>
              </ng-container>
              <ng-template #incendiaireWait>
                <p>En attente du choix de la construction...</p>
              </ng-template>
            </ng-template>
          </ng-container>

          <!-- ========== EMBUSCADE ========== -->
          <ng-container *ngIf="actionMode === 'AMBUSH'">
            <ng-container *ngIf="isActionActor; else ambushSpectate">
              <p class="breakdown">
                Choisissez un ennemi (vampire ou serviteur) : tous les chasseurs sur ce lieu
                gagnent +1 ATK par chasseur présent et cette cible ne pourra pas riposter
                contre eux ce raid.
              </p>

              <div class="target-list">
                <button
                  *ngFor="let e of ambushEnemies"
                  type="button"
                  (click)="onAmbushChoose(e.id)"
                  [disabled]="actionResolving">
                  {{ e.username }} (PV : {{ e.hp }})
                </button>
              </div>
            </ng-container>

            <ng-template #ambushSpectate>
              <p class="breakdown">
                Un chasseur prépare une embuscade et choisit sa cible...
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== PIEU BÉNI ========== -->
          <ng-container *ngIf="actionMode === 'BLESSED_STAKE'">

            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else blessedStakeSpectate">

              <p>
                Épieu béni —
                <ng-container *ngIf="ActionTargetName as tn">
                  {{ tn }} subit un coup sacré supplémentaire.
                </ng-container>
              </p>

              <button
                type="button"
                (click)="onBlessedStakeRoll()"
                [disabled]="actionResolving || actionRoll !== null">
                Lancer le d4
              </button>

              <div *ngIf="actionRoll !== null" class="result-line">
                Résultat : {{ actionRoll }} dégâts sacrés supplémentaires.
              </div>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #blessedStakeSpectate>
              <p>
                Épieu béni —
                <ng-container *ngIf="ActionTargetName as tn; else blessedStakeWait">
                  {{ tn }} attend le verdict du d4...
                </ng-container>
                <ng-template #blessedStakeWait>
                  Résolution en cours…
                </ng-template>
              </p>

              <div *ngIf="actionRoll !== null" class="result-line">
                Résultat : {{ actionRoll }} dégâts sacrés supplémentaires.
              </div>
            </ng-template>
          </ng-container>

          <!-- ========== CHARISMATIQUE ========== -->
          <ng-container *ngIf="actionMode === 'CHARISMATIQUE'">
            <p>
              <ng-container *ngIf="isActionActor; else charismGeneric">
                Les marchands sont sous le charme: les achats coûtent 20 or de moins
                et les ventes rapportent 20 or de plus par ressource ce raid.
              </ng-container>
              <ng-template #charismGeneric>
                Un chasseur charme les marchands: ses prix à la boutique sont améliorés ce raid.
              </ng-template>
            </p>
          </ng-container>

          <!-- ========== MARCHAND ITINERANT ========== -->
          <ng-container *ngIf="actionMode === 'MARCHAND_ITINERANT'">
            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else merchantSpectate">
              <ng-container *ngIf="actionRoll === null">
                <p class="breakdown">
                  Marchand itinérant — lance un D6 pour déterminer l’offre spéciale de ce tour.
                </p>

                <button
                  type="button"
                  (click)="onMerchantRoll()"
                  [disabled]="actionResolving">
                  Lancer le dé
                </button>
              </ng-container>

              <ng-container *ngIf="actionRoll !== null">
                <p class="result">
                  Marchand itinérant — {{ merchantResultText() }}
                </p>
              </ng-container>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #merchantSpectate>
              <ng-container *ngIf="actionRoll === null">
                <p class="breakdown">
                  Marchand itinérant — en attente du jet de D6...
                </p>
              </ng-container>
              <ng-container *ngIf="actionRoll !== null">
                <p class="result">
                  Marchand itinérant — {{ merchantResultText() }}
                </p>
              </ng-container>
            </ng-template>
          </ng-container>

          <!-- ========== MARCHAND_BONUS_BUY ========== -->
          <ng-container *ngIf="actionMode === 'MARCHAND_BONUS_BUY'">
            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else merchantBuySpectate">
              <p class="breakdown">
                {{ merchantBuyText() }}
              </p>

              <div class="row" style="margin-top:.5rem; justify-content:center;">
                <button
                  type="button"
                  (click)="onConfirmBonus('RESOURCE')"
                  [disabled]="actionResolving || !canPayBonusWithResource()">
                  Payer en ressources
                </button>
                <button
                  type="button"
                  (click)="onConfirmBonus('GOLD')"
                  [disabled]="actionResolving || !canPayBonusWithGold()">
                  Payer en or
                </button>
              </div>

                  <div class="row" style="margin-top:.5rem; justify-content:center;">
                    <button
                      type="button"
                      (click)="onCancelBonus()"
                      [disabled]="actionResolving">
                      Annuler
                    </button>
                  </div>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #merchantBuySpectate>
              <p class="breakdown">
                {{ merchantBuyText() }}
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== CATACLYSME ========== -->
          <ng-container *ngIf="actionMode === 'CATACLYSME'">
            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else cataclysmeSpectate">

              <ng-container *ngIf="!cataclysmeLabelPair; else cataclysmeDone">

                <p><b>Second statut</b>:</p>
                <div class="action-targets">
                  <button
                    type="button"
                    *ngFor="let ws of weatherSecondChoices"
                    (click)="onSelectWeather2(ws)"
                    [class.selected]="selectedWeather2 === ws"
                    [disabled]="actionResolving || selectedWeather3 === ws">
                    {{ labelWeather(ws) }}
                  </button>
                </div>

                <p style="margin-top:.5rem"><b>Troisième statut</b>:</p>
                <div class="action-targets">
                  <button
                    type="button"
                    *ngFor="let ws of weatherThirdChoices"
                    (click)="onSelectWeather3(ws)"
                    [class.selected]="selectedWeather3 === ws"
                    [disabled]="actionResolving || selectedWeather2 === ws">
                    {{ labelWeather(ws) }}
                  </button>
                </div>

                <button
                  type="button"
                  style="margin-top:.75rem"
                  (click)="onCataclysmeConfirm()"
                  [disabled]="
                    !selectedWeather2 ||
                    !selectedWeather3 ||
                    selectedWeather2 === selectedWeather3 ||
                    actionResolving
                  ">
                  Valider cette combinaison
                </button>
              </ng-container>

              <ng-template #cataclysmeDone>
                <p class="breakdown">Vous avez choisi: {{ cataclysmeLabelPair }}</p>
              </ng-template>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #cataclysmeSpectate>
              <ng-container *ngIf="cataclysmeLabelPair; else catWait">
                <p class="result">Le vampire a choisi: {{ cataclysmeLabelPair }}</p>
              </ng-container>
              <ng-template #catWait>
                <p class="breakdown">Le vampire choisit deux statuts météo pour ce raid...</p>
              </ng-template>
            </ng-template>
          </ng-container>

          <!-- ========== CLONES DES OMBRES ========== -->
          <ng-container *ngIf="actionMode === 'CLONES_OMBRE'">
            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else clonesSpectate">

              <!-- Étape 1 : lancer le D4 -->
              <ng-container *ngIf="actionRoll === null">
                <p class="breakdown">Invoquez les clones des ombres et lancez un D4.</p>

                <button
                  type="button"
                  (click)="onClonesRoll()"
                  [disabled]="actionResolving">
                  Lancer le dé
                </button>
              </ng-container>

              <!-- Étape 2 : choix des lieux (un par clone) -->
              <ng-container *ngIf="actionRoll !== null">
                <p class="breakdown">
                  Tu contrôles {{ actionRoll }} clone<span *ngIf="actionRoll > 1">s</span>.
                  Choisis pour chacun le lieu qu’il va attaquer.
                </p>

                <div class="clones-assignment" *ngIf="clonesLocationChoices.length > 0">
                  <div class="clone-row" *ngFor="let idx of clonesIndexes">
                    <label>Clone {{ idx + 1 }}</label>
                    <select
                      [value]="clonesSelectedLocations[idx] || ''"
                      (change)="onCloneLocationChange(idx, $event)"
                    >
                      <option value="">-- Choisir un lieu --</option>
                      <option *ngFor="let loc of clonesLocationChoices" [value]="loc">
                        {{ labelLocation(loc) }}
                      </option>
                    </select>
                  </div>
                </div>

                <button
                  type="button"
                  style="margin-top:.75rem"
                  (click)="onClonesConfirm()"
                  [disabled]="actionResolving || !canConfirmClones()">
                  Valider ces attaques
                </button>
              </ng-container>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #clonesSpectate>
              <ng-container *ngIf="actionRoll === null">
                <p class="breakdown">Le vampire invoque des clones des ombres...</p>
              </ng-container>
              <ng-container *ngIf="actionRoll !== null">
                <p class="result">
                  Le vampire contrôle {{ actionRoll }} clone<span *ngIf="actionRoll > 1">s</span> des ombres.
                </p>
              </ng-container>
            </ng-template>
          </ng-container>

          <!-- ========== IMAGE MIROIR (PHASE2 : choix du 2e lieu) ========== -->
          <ng-container *ngIf="actionMode === 'IMAGE_MIROIR_SETUP'">
            <ng-container *ngIf="isActionActor; else miroirSetupSpectate">

              <p class="breakdown">
                Choisissez un lieu supplémentaire où projeter votre reflet :
              </p>

              <div class="action-targets">
                <button
                  type="button"
                  *ngFor="let loc of mirrorLocationChoices"
                  (click)="selectedMirrorLoc = loc"
                  [class.selected]="selectedMirrorLoc === loc"
                  [disabled]="actionResolving">
                  {{ labelLocation(loc) }}
                </button>
              </div>

              <button
                type="button"
                style="margin-top:.75rem"
                (click)="onMirrorSetupConfirm()"
                [disabled]="!selectedMirrorLoc || actionResolving">
                Valider ce lieu
              </button>
            </ng-container>

            <ng-template #miroirSetupSpectate>
              <p class="breakdown">Le vampire prépare une Image miroir...</p>
            </ng-template>
          </ng-container>

          <!-- ========== IMAGE MIROIR (PREPHASE3 : choix du lieu final) ========== -->
          <ng-container *ngIf="actionMode === 'IMAGE_MIROIR_RESOLVE'">
            <ng-container *ngIf="isActionActor; else miroirResolveSpectate">
              <p class="breakdown">
                Choisissez un lieu pour vous matérialiser :
              </p>

              <div class="mirror-locations-summary">
                <div class="mirror-option" *ngFor="let loc of mirrorChoices">
                  <h3>{{ labelLocation(loc) }}</h3>
                  <p>
                    Chasseurs présents :
                    <span *ngIf="mirrorHuntersByLoc[loc]?.length; else noHuntersHere">
                      <ng-container *ngFor="let h of mirrorHuntersByLoc[loc]; let last = last">
                        {{ h.username }}<span *ngIf="!last">, </span>
                      </ng-container>
                    </span>
                    <ng-template #noHuntersHere>
                      <i>aucun</i>
                    </ng-template>
                  </p>
                  <button
                    type="button"
                    (click)="onMirrorResolveChoose(loc)"
                    [disabled]="actionResolving">
                    Choisir ce lieu
                  </button>
                </div>
              </div>
            </ng-container>

            <ng-template #miroirResolveSpectate>
              <p class="breakdown">
                Le vampire choisit où il se matérialise...
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== ECLIPSE (PRÉPHASE3 : météo forcée Nuit obscure) ========== -->
          <ng-container *ngIf="actionMode === 'ECLIPSE'">
            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isActionActor; else eclipseSpectate">
              <p class="breakdown">
                Vous invoquez une éclipse: la météo devient <b>Nuit obscure</b> pour ce raid.
              </p>
            </ng-container>

            <!-- Vue SPECTATEUR -->
            <ng-template #eclipseSpectate>
              <p class="breakdown">
                Le vampire invoque une éclipse : les ténèbres recouvrent le domaine.
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== LUNE SANGLANTE (PRÉPHASE3 : buff de combat) ========== -->
          <ng-container *ngIf="actionMode === 'BLOOD_MOON'"></ng-container>

          <!-- ========== MARQUE TÉNÉBREUSE ========== -->
          <ng-container *ngIf="actionMode === 'MARQUE_TENEBREUSE'">
            <ng-container *ngIf="isActionActor; else darkMarkSpectate">
              <p class="breakdown">
                Choisissez un chasseur à marquer : il gagnera +1 corruption à chaque raid où
                il croise le vampire, tant qu'il n'est pas purifié.
              </p>

              <div class="target-list">
                <button
                  *ngFor="let h of hunterPlayers"
                  type="button"
                  (click)="onDarkMarkChoose(h.id)"
                  [disabled]="actionResolving"
                >
                  {{ h.username }} (corruption : {{ h.corruption }})
                </button>
              </div>
            </ng-container>

            <ng-template #darkMarkSpectate>
              <p class="breakdown">
                Le vampire choisit une cible pour sa Marque ténébreuse...
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== AFFAIBLISSEMENT OCCULTE ========== -->
          <ng-container *ngIf="actionMode === 'AFFAIBLISSEMENT_OCCULTE'">
            <ng-container *ngIf="isActionActor; else weakeningSpectate">
              <p class="breakdown">
                Choisissez un chasseur : il subira -2 à son jet d’attaque ce raid.
              </p>

              <div class="target-list">
                  <button *ngFor="let h of hunterPlayers"
                    type="button"
                    (click)="onOccultWeakeningTarget(h.id)"
                    [disabled]="actionResolving">
                  {{ h.username }}
                </button>
              </div>
            </ng-container>

            <ng-template #weakeningSpectate>
              <p class="breakdown">
                Le vampire choisit une cible pour Affaiblissement occulte...
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== PASSAGE SECRET ========== -->
          <ng-container *ngIf="actionMode === 'PASSAGE_SECRET'">
            <ng-container *ngIf="isActionActor; else passageSecretSpectate">
              <p class="breakdown">
                Choisissez un passage secret pour vous déplacer vers
              </p>

              <div class="target-list">
                <button
                  *ngFor="let loc of secretPassageChoices"
                  type="button"
                  [class.selected]="loc === selectedSecretPassageLoc"
                  (click)="selectedSecretPassageLoc = loc">
                    {{ labelLocation(loc) }}
                </button>
              </div>

              <div style="margin-top:1rem">
                <button 
                  (click)="onSecretPassageConfirm()"
                  [disabled]="!selectedSecretPassageLoc || actionResolving">
                  Valider
                </button>
              </div>
            </ng-container>

            <ng-template #passageSecretSpectate>
              <p class="breakdown">
                Le vampire choisit d'emprunter un passage secret...
              </p>
            </ng-template>
          </ng-container>

          <!-- ========== AVIDITE_NOCTURNE ========== -->
          <ng-container *ngIf="actionMode === 'AVIDITE_NOCTURNE'">
            <p>
              <ng-container *ngIf="isActionActor; else greedGeneric">
                Vous attisez la cupidité des marchands:
                tous les prix en or à la boutique des chasseurs augmentent de 50 ce raid.
              </ng-container>
              <ng-template #greedGeneric>
                Le vampire renforce la cupidité des marchands:
                tous les prix en or à la boutique augmentent de 50 ce raid.
              </ng-template>
            </p>
          </ng-container>

          <!-- ========== Résultat commun (acteurs + spectateurs) ========== -->
          <div class="trap-result">

            <!-- Dé visuel -->
            <div class="dice-wrap"
                *ngIf="actionMode === 'PIT' 
                    || actionMode === 'NET' 
                    || actionMode === 'CLONES_OMBRE'
                    || actionMode === 'BLESSED_STAKE'
                    || actionMode === 'MARCHAND_ITINERANT'">
              <img class="dice"
                  [src]="diceAsset(
                    actionMode === 'CLONES_OMBRE' || actionMode === 'BLESSED_STAKE' ? 'D4'
                    : actionMode === 'MARCHAND_ITINERANT' ? 'D6'
                    : 'D20',
                    actionDiceColor
                  )"
                  alt="dice"/>

              <div class="dice20-overlay"
                  *ngIf="(actionRoll) != null">
                {{ actionRoll }}
              </div>
            </div>

            <!-- Texte existant -->
            <p *ngFor="let line of actionBreakdownLines">
              {{ line }}
            </p>
          </div>
        </div>
      </div>
    </div>
  </div>
  <!-- ===== MODALE CHOIX DE CONSTRUCTION ===== -->
  <div class="modal-backdrop" *ngIf="buildModalOpen">
    <div class="modal construction-modal choices" [style.backgroundImage]="setImageBackground('construire')">
        <div>
          <h2 class="bg-badge">Construire un lieu</h2>
        </div>

        <div class="construction-scroll">
          <div class="res-board" *ngIf="me as m">
            <span class="res-title">Ressources:</span>

            <span *ngIf="m.role==='HUNTER'" class="res-item" title="L'or est utile pour obtenir des actions et de l'eau bénite">
              <span class="res-ico-box">
                <img class="res-ico res-ico-s" src="/assets/icons/gold.png" alt="Or">
              </span>
              <span class="res-val">{{ m.gold || 0 }}</span>
            </span>

            <span *ngIf="m.role==='VAMPIRE' || m.role==='SERVANT'" class="res-item" title="Les âmes déchues sont utiles pour obtenir des actions">
              <span class="res-ico-box">
                <img class="res-ico res-ico-s" src="/assets/icons/souls.png" alt="Âmes déchues">
              </span>
              <span class="res-val">{{ m.souls || 0 }}</span>
            </span>

            <span class="res-item" title="L'eau pure est utile pour l'alchimie et obtenir de l'eau bénite">
              <span class="res-ico-box">
                <img class="res-ico res-ico-s" src="/assets/icons/water.png" alt="Eau pure">
              </span>
              <span class="res-val">{{ m.water || 0 }}</span>
            </span>

            <span class="res-item" title="L'herbe médicinale est utile pour l'alchimie">
              <span class="res-ico-box">
                <img class="res-ico res-ico-s" src="/assets/icons/medical_grass.png" alt="Herbe médicinale">
              </span>
              <span class="res-val">{{ m.herbs || 0 }}</span>
            </span>

            <span class="res-item" title="Le bois est utile pour la fabrication d'équipement">
              <span class="res-ico-box">
                <img class="res-ico res-ico-l" src="/assets/icons/wood.png" alt="Bois">
              </span>
              <span class="res-val">{{ m.wood || 0 }}</span>
            </span>

            <span class="res-item" title="Le fer est utile pour la fabrication d'équipement">
              <span class="res-ico-box">
                <img class="res-ico res-ico-m" src="/assets/icons/iron.png" alt="Fer">
              </span>
              <span class="res-val">{{ m.iron || 0 }}</span>
            </span>

            <span class="res-item" title="La pierre est utile pour les constructions du vampire ou pour vendre a la ville et gagner de l'or">
              <span class="res-ico-box">
                <img class="res-ico res-ico-s" src="/assets/icons/stone.png" alt="Pierre">
              </span>
              <span class="res-val">{{ m.stone || 0 }}</span>
            </span>

            <span *ngIf="m.role==='HUNTER'" class="res-item" title="L'argent est utile late game pour fabriquer de l'équipement sacré">
              <span class="res-ico-box">
                <img class="res-ico res-ico-s" src="/assets/icons/silver.png" alt="Argent">
              </span>
              <span class="res-val">{{ m.silver || 0 }}</span>
            </span>
          </div>

          <div>
            <div class="build-grid">

              <ng-container *ngFor="let opt of buildOptions">

                <div class="build-item" *ngIf="!isInfraBuilt(opt.code)">

                  <!-- bouton = image du lieu + zoom -->
                  <button type="button"
                          class="build-card-btn"
                          (click)="onChooseInfra(opt.code)"
                          (mouseenter)="zoomEnter($event, undefined, undefined, 'L', locationInfo(opt.code))"
                          (mousemove)="zoomMove($event)"
                          (mouseleave)="zoomLeave()">
                    <img class="build-card-img"
                        [src]="infraImg(opt.code)"
                        [alt]="opt.title" />
                  </button>

                  <div class="build-title">{{ opt.title }}</div>
                  <div class="build-sub">Coûts de construction</div>

                  <!-- coût en icônes -->
                  <div class="build-cost price price-icons">
                    <span class="cost-item" *ngFor="let c of infraCostList(opt.code)">
                      <span class="cost-val">{{ c.qty }}</span>
                      <span class="cost-ico-box">
                        <img class="cost-ico cost-ico-s" [src]="c.icon" [alt]="c.label" />
                      </span>
                    </span>
                  </div>

                </div>

              </ng-container>

            </div>


            <button class="btn-secondary" (click)="closeBuildModal()">Annuler</button>
          </div>
        </div> 
    </div>
  </div>
  <!-- ===== MODALE CONFIRMATION CONSTRUCTION ===== -->
  <div class="modal-backdrop" *ngIf="buildConfirmModalOpen && buildChoice">
    <div class="modal construction-modal" [style.backgroundImage]="setImageBackground('construction')">
      <div class="modal-overlay-content">
        <p class="bg-badge">{{ getInfraConfirmText(buildChoice!) }}</p>

        <div class="modal-button-row">
          <button class="btn-primary" (click)="doBuild()" style="margin-right: 10px;">Oui</button>
          <button class="btn-secondary" (click)="cancelBuild()">Annuler</button>
        </div>
      </div>
    </div>
  </div>
  <!-- ===== MODALE SELECTION EFFET DE LIEU: LIBRARY ===== -->
  <div class="modal-backdrop"
      *ngIf="game?.locationEffectPending 
      && game?.locationEffectInfra === 'LIBRARY'
      && !locationActionModalOpen"
  >
    <div class="modal construction-modal location-effect-modal"
        [style.backgroundImage]="locationEffectBackground()">
      <div class="modal-overlay-content">
        <h2>Bibliothèque occulte</h2>

        <p class="bg-badge">
          <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
            <ng-container *ngIf="ownerId === me?.id; else otherLocOwner">
              Vous devez choisir un effet de la Bibliothèque pour ce raid.
            </ng-container>
            <ng-template #otherLocOwner>
              {{ usernameOf(ownerId) }} choisit un effet de la Bibliothèque…
            </ng-template>
          </ng-container>
        </p>

        <div class="modal-button-row">
          <!-- Étude des grimoires -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('STUDY')"
            [disabled]="!canChooseLocationEffect"
            [class.selected]="effectChoice === 'STUDY'">
            Étude des grimoires<br />
            <small>Piocher 1 carte Action.</small>
          </button>

          <!-- Subtilisation -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('THEFT')"
            [disabled]="!canChooseLocationEffect"
            [class.selected]="effectChoice === 'THEFT'">
            Subtilisation de manuscrit<br />
            <small>Voler 1 carte Action à l’adversaire et la mélanger dans sa pioche.</small>
          </button>

          <!-- Prédiction -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('OMEN')"
            [disabled]="!canChooseLocationEffect"
            [class.selected]="effectChoice === 'OMEN'">
            Prédiction occulte<br />
            <small>Regarder et réordonner secrètement les 3 prochaines cartes Action de l’adversaire.</small>
          </button>
        </div>

        <div class="modal-button-row footer-row">
          <button
            type="button"
            class="btn-primary"
            style="margin-right: 10px;"
            (click)="chooseLocationEffect()"
            [disabled]="!canChooseLocationEffect || !effectChoice">
            Valider
          </button>

          <!-- Annuler = juste reset local de ta sélection -->
          <button
            type="button"
            class="btn-secondary"
            *ngIf="isLocationEffectOwner && !game?.locationEffectChoice"
            (click)="onCancelLocationEffect()">
            Annuler
          </button>
        </div>

        <!-- Quand le serveur a figé le choix, on l’affiche clairement -->
        <p *ngIf="game?.locationEffectChoice"
          class="location-effect-result">
          Effet choisi : {{ translateLocationEffect(game?.locationEffectChoice) }}
        </p>
      </div>
    </div>
  </div>
  <!-- ===== MODALE SELECTION EFFET DE LIEU : LABORATORY ===== -->
  <div class="modal-backdrop"
      *ngIf="game?.locationEffectPending 
            && game?.locationEffectInfra === 'LABORATORY'
            && !locationActionModalOpen">
    <div class="modal construction-modal location-effect-modal"
        [style.backgroundImage]="locationEffectBackground()">
      <div class="modal-overlay-content">
        <h2>Laboratoire occulte</h2>

        <p class="bg-badge">
          <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
            <ng-container *ngIf="ownerId === me?.id; else labOtherOwner">
              Vous devez choisir un effet du Laboratoire pour ce raid.
            </ng-container>
            <ng-template #labOtherOwner>
              {{ usernameOf(ownerId) }} choisit un effet du Laboratoire…
            </ng-template>
          </ng-container>
        </p>

        <div class="modal-button-row" *ngIf="isLocationEffectOwner">
          <!-- EXPERIMENT -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('EXPERIMENT')"
            [disabled]="!canChooseLocationEffect || !canUseExperiment()"
            [class.selected]="effectChoice === 'EXPERIMENT'">
            Expérience occulte<br />
            <small>Invoquer une créature et l’envoyer défendre un lieu.</small>
          </button>

          <!-- ALCHEMY -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('ALCHEMY')"
            [disabled]="!canChooseLocationEffect"
            [class.selected]="effectChoice === 'ALCHEMY'">
            Alchimie<br />
            <small>Préparer 1 potion gratuitement.</small>
          </button>

          <!-- RARE_ALCHEMY -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('RARE_ALCHEMY')"
            [disabled]="!canChooseLocationEffect || !canUseRareAlchemy()"
            [class.selected]="effectChoice === 'RARE_ALCHEMY'">
            Alchimie avancée<br />
            <small>Préparer 1 élixir (6 eau, 6 herbes).</small>

            <div class="warning" *ngIf="!canUseRareAlchemy()">
              <small>Ressources insuffisantes.</small>
            </div>
          </button>

          <!-- EXPLOSION -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('EXPLOSION')"
            [disabled]="!canChooseLocationEffect || !canUseExplosion()"
            [class.selected]="effectChoice === 'EXPLOSION'">
            Explosion alchimique<br />
            <small>Détruire le Laboratoire (chasseur uniquement).</small>
          </button>
        </div>

        <div class="modal-button-row footer-row" *ngIf="isLocationEffectOwner">
          <button
            type="button"
            class="btn-primary"
            style="margin-right: 10px;"
            (click)="chooseLocationEffect()"
            [disabled]="!canChooseLocationEffect || !effectChoice">
            Valider
          </button>

          <button
            type="button"
            class="btn-secondary"
            *ngIf="!game?.locationEffectChoice"
            (click)="onCancelLocationEffect()">
            Annuler
          </button>
        </div>

        <p *ngIf="game?.locationEffectChoice"
          class="location-effect-result">
          Effet choisi : {{ translateLocationEffect(game?.locationEffectChoice) }}
        </p>
      </div>
    </div>
  </div>
  <!-- ===== MODALE SELECTION EFFET DE LIEU : BALLROOM ===== -->
  <div class="modal-backdrop"
      *ngIf="game?.locationEffectPending 
            && game?.locationEffectInfra === 'BALLROOM'
            && !locationActionModalOpen">
    <div class="modal construction-modal location-effect-modal"
        [style.backgroundImage]="locationEffectBackground()">
      <div class="modal-overlay-content">
        <h2>Salle de bal vampirique</h2>

        <p class="bg-badge">
          <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
            <ng-container *ngIf="ownerId === me?.id; else ballroomOtherOwner">
              Vous devez choisir un effet de la Salle de bal pour ce raid.
            </ng-container>
            <ng-template #ballroomOtherOwner>
              {{ usernameOf(ownerId) }} choisit un effet de la Salle de bal…
            </ng-template>
          </ng-container>
        </p>

        <div class="modal-button-row" *ngIf="isLocationEffectOwner">
          <!-- Danse macabre -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('DEATH_DANCE')"
            [disabled]="isBallroomChoiceDisabled('DEATH_DANCE')"
            [class.selected]="effectChoice === 'DEATH_DANCE'">
            Danse macabre<br />
            <small>Lorsque le vampire réussit une attaque, le chasseur subit aussi directement 1 point de corruption.</small>
          </button>

          <!-- Attaque sournoise -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('SNEAK_ATTACK')"
            [disabled]="isBallroomChoiceDisabled('SNEAK_ATTACK')"
            [class.selected]="effectChoice === 'SNEAK_ATTACK'">
            Attaque sournoise<br />
            <small>Le vampire peut voler directement une ressource au hasard à chaque chasseur présent.</small>
          </button>

          <!-- Valse sanguinaire -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('BLOOD_WALTZ')"
            [disabled]="isBallroomChoiceDisabled('BLOOD_WALTZ')"
            [class.selected]="effectChoice === 'BLOOD_WALTZ'">
            Valse sanguinaire<br />
            <small>Le vampire jette autant de dé d’attaque que de chasseurs présents, conserve le meilleur dé et applique l’attaque contre tous les chasseurs de la salle.</small>
          </button>

          <!-- Pillage -->
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('LOOTING')"
            [disabled]="isBallroomChoiceDisabled('LOOTING')"
            [class.selected]="effectChoice === 'LOOTING'">
            Pillage<br />
            <small>Les chasseurs gagnent plus d'or.</small>
          </button>
        </div>

        <div class="modal-button-row footer-row" *ngIf="isLocationEffectOwner">
          <button
            type="button"
            class="btn-primary"
            style="margin-right: 10px;"
            (click)="chooseLocationEffect()"
            [disabled]="!canChooseLocationEffect || !effectChoice">
            Valider
          </button>

          <button
            type="button"
            class="btn-secondary"
            *ngIf="!game?.locationEffectChoice"
            (click)="onCancelLocationEffect()">
            Annuler
          </button>
        </div>

        <!-- Résultat figé (info pour tous) -->
        <p *ngIf="game?.locationEffectChoice"
          class="location-effect-result">
          Effet choisi : {{ translateLocationEffect(game?.locationEffectChoice) }}
        </p>
      </div>
    </div>
  </div>
  <!-- ===== MODALE SELECTION EFFET DE LIEU : ALTAR ===== -->
  <div class="modal-backdrop"
      *ngIf="game?.locationEffectPending 
            && game?.locationEffectInfra === 'ALTAR'
            && !locationActionModalOpen">
    <div class="modal construction-modal location-effect-modal"
        [style.backgroundImage]="locationEffectBackground()">
      <div class="modal-overlay-content">
        <h2>
          <ng-container *ngIf="game?.altarCorrupted; else altarPureTitle">
            Autel corrompu
          </ng-container>
          <ng-template #altarPureTitle>
            Sanctuaire du sang
          </ng-template>
        </h2>

        <p class="bg-badge">
          <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
            <ng-container *ngIf="ownerId === me?.id; else altarOtherOwner">
              Vous devez choisir un effet de l’autel pour ce raid.
            </ng-container>
            <ng-template #altarOtherOwner>
              {{ usernameOf(ownerId) }} choisit un effet de l’autel…
            </ng-template>
          </ng-container>
        </p>

        <div class="modal-button-row" *ngIf="isLocationEffectOwner">

          <!-- Purifier un chasseur (autel PUR seulement) -->
          <button
            *ngIf="!game?.altarCorrupted"
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('HEAL')"
            [disabled]="isAltarChoiceDisabled('HEAL')"
            [class.selected]="effectChoice === 'HEAL'">
            Purifier un chasseur<br />
            <small>Soigne 1 point de corruption sur un chasseur vivant.</small>
          </button>

          <!-- Corrompre un chasseur (autel CORROMPU seulement) -->
          <button
            *ngIf="game?.altarCorrupted"
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('CORRUPT')"
            [disabled]="isAltarChoiceDisabled('CORRUPT')"
            [class.selected]="effectChoice === 'CORRUPT'">
            Corrompre un chasseur<br />
            <small>Ajoute 1 point de corruption à un chasseur vivant.</small>
          </button>

          <!-- Purifier le sanctuaire (autel CORROMPU seulement) -->
          <button
            *ngIf="game?.altarCorrupted"
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('PURIFY_WATER')"
            [disabled]="isAltarChoiceDisabled('PURIFY_WATER')"
            [class.selected]="effectChoice === 'PURIFY_WATER'">
            Purifier le sanctuaire<br />
            <small>Rend le lieu à nouveau sacré.</small>
          </button>

          <!-- Corrompre le sanctuaire (autel PUR seulement) -->
          <button
            *ngIf="!game?.altarCorrupted"
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('CORRUPT_SOULS')"
            [disabled]="isAltarChoiceDisabled('CORRUPT_SOULS')"
            [class.selected]="effectChoice === 'CORRUPT_SOULS'">
            Profaner le sanctuaire<br />
            <small>Renforce la corruption spirituelle du lieu.</small>
          </button>
        </div>

        <div class="modal-button-row footer-row" *ngIf="isLocationEffectOwner">
          <button
            type="button"
            class="btn-primary"
            style="margin-right: 10px;"
            (click)="chooseLocationEffect()"
            [disabled]="!canChooseLocationEffect || !effectChoice">
            Valider
          </button>

          <button
            type="button"
            class="btn-secondary"
            *ngIf="!game?.locationEffectChoice"
            (click)="onCancelLocationEffect()">
            Annuler
          </button>
        </div>

        <!-- Résultat figé (info pour tous) -->
        <p *ngIf="game?.locationEffectChoice"
          class="location-effect-result">
          Effet choisi : {{ translateLocationEffect(game?.locationEffectChoice) }}
        </p>
      </div>
    </div>
  </div>
  <!-- ===== MODALE SELECTION EFFET DE LIEU: FORGE ===== -->
  <div class="modal-backdrop"
      *ngIf="game?.locationEffectPending
          && game?.locationEffectInfra === 'FORGE'
          && !locationActionModalOpen">
    <div class="modal construction-modal location-effect-modal"
        style="background-image: url('/assets/locations/forge.png')">
      <div class="modal-overlay-content">
        <h2>Forge maudite</h2>

        <p class="bg-badge">
          <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
            <ng-container *ngIf="ownerId === me?.id; else otherForgeOwner">
              Vous devez choisir comment utiliser la Forge pour ce raid.
            </ng-container>
            <ng-template #otherForgeOwner>
              {{ usernameOf(ownerId) }} choisit comment utiliser la Forge…
            </ng-template>
          </ng-container>
        </p>

        <div class="modal-button-row" *ngIf="isLocationEffectOwner">
          <button
            type="button"
            class="effect-option"
            (click)="onEffectOptionClick('FORGE')"
            [disabled]="!canChooseLocationEffect"
            [class.selected]="effectChoice === 'FORGE'">
            Forger de l’équipement<br />
            <small>Fabrication d’arme ou d’armure.</small>
          </button>
        </div>

        <div class="modal-button-row footer-row" *ngIf="isLocationEffectOwner">
          <button
            type="button"
            class="btn-primary"
            style="margin-right: 10px;"
            (click)="chooseLocationEffect()"
            [disabled]="!canChooseLocationEffect || !effectChoice">
            Valider
          </button>

          <button
            type="button"
            class="btn-secondary"
            *ngIf="!game?.locationEffectChoice"
            (click)="onCancelLocationEffect()">
            Annuler
          </button>
        </div>

        <p *ngIf="game?.locationEffectChoice"
          class="location-effect-result">
          Effet choisi : {{ translateLocationEffect(game?.locationEffectChoice) }}
        </p>
      </div>
    </div>
  </div>
  <!-- ===== MODALE UTILISATION EFFET DE LIEU ===== -->
  <div class="modal-backdrop"
      *ngIf="locationActionModalOpen
              && (game?.locationEffectInfra === 'LIBRARY' 
                  || game?.locationEffectInfra === 'LABORATORY'
                  || game?.locationEffectInfra === 'ALTAR'
                  || game?.locationEffectInfra === 'FORGE')">
    <div class="modal construction-modal location-effect-modal"
        [style.backgroundImage]="locationEffectBackground()">
      <div class="modal-overlay-content">
        <h2>
          <ng-container [ngSwitch]="game?.locationEffectInfra">
            <span *ngSwitchCase="'LIBRARY'">Bibliothèque — Effet en cours</span>
            <span *ngSwitchCase="'LABORATORY'">Laboratoire occulte — Effet en cours</span>
            <span *ngSwitchCase="'ALTAR'">
              <ng-container *ngIf="game?.altarCorrupted; else sanctuaryTitle">
                Autel corrompu — Effet en cours
              </ng-container>
              <ng-template #sanctuaryTitle>
                Autel purifié — Effet en cours
              </ng-template>
            </span>
            <span *ngSwitchCase="'FORGE'">Forge maudite — Effet en cours</span>
            <span *ngSwitchDefault>Lieu — Effet en cours</span>
          </ng-container>
        </h2>

        <p class="bg-badge">
          <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
            <ng-container *ngIf="ownerId === me?.id; else otherLocActionOwner">
              Vous résolvez l’effet :
              {{ translateLocationEffect(game?.locationEffectChoice) }}.
            </ng-container>
            <ng-template #otherLocActionOwner>
              {{ usernameOf(ownerId) }} résout l’effet :
              {{ translateLocationEffect(game?.locationEffectChoice) }}…
            </ng-template>
          </ng-container>
        </p>

        <ng-container [ngSwitch]="locationActionKind">

          <!-- OMEN: choix TOP / BOTTOM pour chaque carte -->
          <div *ngSwitchCase="'OMEN'">
            <p *ngIf="!isLocationEffectOwner">
              Le joueur consulte secrètement les trois prochaines cartes d’action de l’adversaire.
            </p>

            <div class="cards-row omen-row" *ngIf="isLocationEffectOwner">
              <div class="omen-card" *ngFor="let cardId of omenCards; let i = index">

                <!-- Image carte (zoomable) -->
                <button type="button"
                        class="omen-card-btn"
                        (mouseenter)="zoomEnter($event, undefined, undefined, 'L')"
                        (mousemove)="zoomMove($event)"
                        (mouseleave)="zoomLeave()">
                  <img class="omen-card-img"
                      [src]="actionImg(cardId, me?.role)"
                      [alt]="actionLabelFr(cardId)">
                </button>

                <!-- Boutons -->
                <button type="button"
                        class="omen-place-btn"
                        (click)="onOmenPlacementClick(i, 'TOP')"
                        [disabled]="!isLocationEffectOwner"
                        [class.selected]="omenPlacements[i] === 'TOP'">
                  Dessus le deck
                </button>

                <button type="button"
                        class="omen-place-btn"
                        (click)="onOmenPlacementClick(i, 'BOTTOM')"
                        [disabled]="!isLocationEffectOwner"
                        [class.selected]="omenPlacements[i] === 'BOTTOM'">
                  Dessous le deck
                </button>

              </div>
            </div>

            <div class="modal-button-row footer-row" *ngIf="isLocationEffectOwner">
              <button type="button"
                      (click)="confirmOmenPlacements()"
                      [disabled]="!omenCanSubmit || omenSubmitting">
                Valider les placements
              </button>
            </div>
          </div>

          <!-- THEFT: choix de cible + slot de carte -->
          <div *ngSwitchCase="'THEFT'" class="theft-container">

            <!-- Vue joueur actif -->
            <div *ngIf="isLocationActionOwner; else theftSpectator">
              <p>
                Choisissez d’abord une cible, puis une carte parmi sa main d’actions.
              </p>

              <!-- Liste des cibles possibles -->
              <div class="theft-targets" *ngIf="theftTargets.length > 0; else noTheftTargets">
                <div
                  class="theft-target"
                  *ngFor="let t of theftTargets"
                  [class.selected]="t.id === theftSelectedTargetId"
                  (click)="onSelectTheftTarget(t.id)"
                >
                  <strong>{{ t.username }}</strong>
                  <span> — {{ t.actionsCount }} carte(s) Action</span>
                </div>
              </div>

              <ng-template #noTheftTargets>
                <p>Aucun adversaire ne possède de carte Action à subtiliser.</p>
              </ng-template>

              <!-- Slots de cartes pour la cible sélectionnée -->
              <div class="theft-slots" *ngIf="theftSelectedTargetId && theftSlots.length > 0">
                <p>Choisissez une carte :</p>
                <div class="slot-row">
                  <button
                    type="button"
                    *ngFor="let idx of theftSlots"
                    (click)="onSelectTheftSlot(idx)"
                    [class.selected]="theftSelectedSlotIndex === idx"
                  >
                    Carte {{ idx + 1 }}
                  </button>
                </div>
              </div>

              <div class="modal-button-row footer-row">
                <button
                  type="button"
                  (click)="confirmTheftSelection()"
                  [disabled]="!theftCanSubmit || theftSubmitting"
                >
                  Valider le vol
                </button>
              </div>
            </div>

            <!-- Vue spectateurs -->
            <ng-template #theftSpectator>
              <p>
                Le joueur actif choisit secrètement une carte d’action à subtiliser
                chez son adversaire…
              </p>
            </ng-template>
          </div>  

          <!-- EXPERIMENT: choix monstre + lieu -->
          <div *ngSwitchCase="'EXPERIMENT'" class="experiment-container">
            <p *ngIf="isLocationActionOwner; else spectateText">
              Choisissez le type de créature et le lieu où l’envoyer défendre.
            </p>
            <ng-template #spectateText>
              <p>Le vampire choisit une créature et un lieu…</p>
            </ng-template>

            <div class="experiment-row">
              <div class="experiment-col">
                <h3>Type de créature</h3>
                <div class="monster-pick-grid">
                  <!-- REVENANT -->
                  <button type="button"
                          class="monster-pick"
                          (click)="onExperimentMonsterClick('REVENANT')"
                          [disabled]="!isLocationActionOwner"
                          [class.selected]="experimentMonsterType === 'REVENANT'">
                    <img class="monster-img" src="/assets/monster/revenant.png" alt="Revenant" />
                    <div class="monster-name">Revenant</div>
                    <div class="monster-stats">
                      <div>Coût : {{ experimentMonsterMeta.REVENANT.cost }}</div>
                      <div>PV : {{ experimentMonsterMeta.REVENANT.hp }}</div>
                      <div>ATK : {{ experimentMonsterMeta.REVENANT.atkDice }}</div>
                      <div>DEF : {{ experimentMonsterMeta.REVENANT.defDice }}</div>
                    </div>
                  </button>

                  <!-- GARGOYLE -->
                  <button type="button"
                          class="monster-pick"
                          (click)="onExperimentMonsterClick('GARGOYLE')"
                          [disabled]="!isLocationActionOwner"
                          [class.selected]="experimentMonsterType === 'GARGOYLE'">
                    <img class="monster-img" src="/assets/monster/gargouille.png" alt="Gargouille" />
                    <div class="monster-name">Gargouille</div>
                    <div class="monster-stats">
                      <div>Coût : {{ experimentMonsterMeta.GARGOYLE.cost }}</div>
                      <div>PV : {{ experimentMonsterMeta.GARGOYLE.hp }}</div>
                      <div>ATK : {{ experimentMonsterMeta.GARGOYLE.atkDice }}</div>
                      <div>DEF : {{ experimentMonsterMeta.GARGOYLE.defDice }}</div>
                    </div>
                  </button>

                  <!-- ABERRATION -->
                  <button type="button"
                          class="monster-pick"
                          (click)="onExperimentMonsterClick('ABERRATION')"
                          [disabled]="!isLocationActionOwner"
                          [class.selected]="experimentMonsterType === 'ABERRATION'">
                    <img class="monster-img" src="/assets/monster/aberration.png" alt="Aberration" />
                    <div class="monster-name">Aberration</div>
                    <div class="monster-stats">
                      <div>Coût : {{ experimentMonsterMeta.ABERRATION.cost }}</div>
                      <div>PV : {{ experimentMonsterMeta.ABERRATION.hp }}</div>
                      <div>ATK : {{ experimentMonsterMeta.ABERRATION.atkDice }}</div>
                      <div>DEF : {{ experimentMonsterMeta.ABERRATION.defDice }}</div>
                    </div>
                  </button>
                </div>
              </div>

              <div class="experiment-col">
                <h3>Lieu de déploiement</h3>
                <div class="location-list">
                  <button type="button"
                          *ngFor="let loc of experimentAvailableLocations"
                          (click)="onExperimentLocationClick(loc)"
                          [disabled]="!isLocationActionOwner"
                          [class.selected]="experimentLocation === loc">
                    {{ labelLocation(loc) }}
                  </button>
                </div>
              </div>
            </div>

            <div class="modal-button-row footer-row" *ngIf="isLocationActionOwner">
              <button type="button"
                      (click)="confirmExperiment()"
                      [disabled]="!experimentCanSubmit || experimentSubmitting">
                {{ experimentSubmitting ? 'Validation…' : 'Valider l’expérience' }}
              </button>
            </div>
          </div>

          <!-- ALTAR: HEAL -->
          <div *ngSwitchCase="'HEAL'" class="altar-container">
            <p *ngIf="!isLocationActionOwner">
              Le joueur actif choisit un chasseur à purifier à l’autel…
            </p>

            <div *ngIf="isLocationActionOwner">
              <p>Choisissez un chasseur vivant possédant au moins 1 point de corruption.</p>

              <div class="altar-targets" *ngIf="altarTargets.length > 0; else noAltarHealTargets">
                <div
                  class="altar-target"
                  *ngFor="let t of altarTargets"
                  [class.selected]="t.id === altarSelectedTargetId"
                  (click)="onSelectAltarTarget(t.id)"
                >
                  <strong>{{ t.username }}</strong>
                  <span> — PV {{ t.hp }}, Corruption {{ t.corruption }}</span>
                </div>
              </div>

              <ng-template #noAltarHealTargets>
                <p>Aucun chasseur corrompu et vivant à purifier.</p>
              </ng-template>

              <div class="modal-button-row footer-row">
                <button type="button"
                        (click)="confirmAltarHeal()"
                        [disabled]="!altarCanSubmit || altarSubmitting">
                  Valider la purification
                </button>
              </div>
            </div>
          </div>

          <!-- ALTAR: CORRUPT -->
          <div *ngSwitchCase="'CORRUPT'" class="altar-container">
            <p *ngIf="!isLocationActionOwner">
              Le joueur actif choisit un chasseur à corrompre depuis l’autel…
            </p>

            <div *ngIf="isLocationActionOwner">
              <p>Choisissez un chasseur vivant à corrompre (+1 corruption).</p>

              <div class="altar-targets" *ngIf="altarTargets.length > 0; else noAltarCorruptTargets">
                <div
                  class="altar-target"
                  *ngFor="let t of altarTargets"
                  [class.selected]="t.id === altarSelectedTargetId"
                  (click)="onSelectAltarTarget(t.id)"
                >
                  <strong>{{ t.username }}</strong>
                  <span> — PV {{ t.hp }}, Corruption {{ t.corruption }}</span>
                </div>
              </div>

              <ng-template #noAltarCorruptTargets>
                <p>Aucun chasseur vivant à corrompre.</p>
              </ng-template>

              <div class="modal-button-row footer-row">
                <button type="button"
                        (click)="confirmAltarCorrupt()"
                        [disabled]="!altarCanSubmit || altarSubmitting">
                  Valider la corruption
                </button>
              </div>
            </div>
          </div>

          <!-- FORGE: choix d’équipement à fabriquer -->
          <div *ngSwitchCase="'FORGE'" class="forge-container">
            <!-- Vue spectateurs AVANT résolution -->
            <p *ngIf="!isLocationActionOwner && !forgeResolvedLabel">
              <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
                {{ usernameOf(ownerId) }} est en train de forger un nouvel équipement…
              </ng-container>
            </p>

            <!-- Vue joueur actif AVANT résolution -->
            <div *ngIf="isLocationActionOwner && !forgeResolvedLabel">
              <p>
                Choisissez l’équipement de tier supérieur que vous voulez forger
                pour ce raid.
              </p>

              <!-- Armes disponibles -->
              <div class="forge-section" *ngIf="forgeWeaponOptions.length > 0">
                <h3>Armes disponibles</h3>
                <div class="forge-options-row">
                  <button
                    type="button"
                    class="effect-option"
                    *ngFor="let opt of forgeWeaponOptions"
                    (click)="onSelectForgeOption(opt)"
                    [class.selected]="forgeSelectedId === opt.id"
                    [disabled]="forgeSubmitting"
                  >
                    {{ opt.label }}<br />
                    <small>{{ opt.desc }}</small>
                  </button>
                </div>
              </div>

              <!-- Armures disponibles -->
              <div class="forge-section" *ngIf="forgeArmorOptions.length > 0">
                <h3>Armures disponibles</h3>
                <div class="forge-options-row">
                  <button
                    type="button"
                    class="effect-option"
                    *ngFor="let opt of forgeArmorOptions"
                    (click)="onSelectForgeOption(opt)"
                    [class.selected]="forgeSelectedId === opt.id"
                    [disabled]="forgeSubmitting"
                  >
                    {{ opt.label }}<br />
                    <small>{{ opt.desc }}</small>
                  </button>
                </div>
              </div>

              <!-- Aucun choix possible -->
              <div *ngIf="forgeWeaponOptions.length === 0 && forgeArmorOptions.length === 0">
                <p>Aucun équipement supérieur n’est disponible à la forge pour ce raid.</p>
              </div>

              <div class="modal-button-row footer-row">
                <button
                  type="button"
                  (click)="confirmForge()"
                  [disabled]="!forgeCanSubmit || forgeSubmitting"
                >
                  Forger cet équipement
                </button>
              </div>
            </div>

            <!-- Message final visible pour TOUT LE MONDE après résolution -->
            <p *ngIf="forgeResolvedLabel" class="bg-badge">
              <ng-container *ngIf="game?.locationEffectOwnerId as ownerId">
                {{ usernameOf(ownerId) }} fabrique {{ forgeResolvedLabel }} à la forge.
              </ng-container>
            </p>
          </div>

        </ng-container>
      </div>
    </div>
  </div>
  <!-- Modale de mort (game pas finie) -->
  <div class="modal-overlay" *ngIf="showDeathModal">
    <div class="modal end-game-modal">
      <h2>Vous êtes mort…</h2>
      <p>Vous pouvez observer la partie ou retourner au lobby.</p>

      <div class="end-game-actions">
        <button (click)="observeAfterDeath()" style="margin-right:.5rem">Observer</button>
        <button (click)="leaveAfterDeath()">Retour au lobby</button>
      </div>
    </div>
  </div>
  <!-- Modale de fin de partie -->
  <div class="modal-overlay" *ngIf="isGameEnded">
    <div class="modal end-game-modal">
      <h2>{{ winnerTitle() }}</h2>
      <p>{{ winnerSubtitle() }}</p>

      <div class="end-game-actions">
        <button (click)="leaveAfterEnd()">Retour au lobby</button>
      </div>
    </div>
  </div>
  <div *ngIf="zoomOn" class="equip-zoom" [class.with-info]="zoomInfoOn" [ngStyle]="zoomStyle">
    <div class="equip-zoom-media" [style.width.px]="zoomMediaW" [style.height.px]="zoomMediaH">
      <img class="equip-zoom-img" [src]="zoomSrc" alt="zoom" />
      <span *ngIf="zoomBadgeOn"
            class="equip-zoom-badge"
            [class.badge-hunter]="zoomBadgeIsHunter === true"
            [class.badge-vamp]="zoomBadgeIsHunter === false">
        {{ zoomBadgeText }}
      </span>
    </div>

    <!-- panneau info (uniquement quand zoomEnter() reçoit une info) -->
    <div *ngIf="zoomInfoOn" class="zoom-info">

      <!--rendu spécial ALTAR -->
      <ng-container *ngIf="zoomInfoKey === 'altar'; else normalInfo">

        <div class="zoom-info-section">Chasseur</div>
        <ul class="zoom-info-list">
          <li *ngFor="let line of zoomInfoLinesHunter">{{ line }}</li>
        </ul>

        <div class="zoom-info-section">Vampire</div>
        <ul class="zoom-info-list">
          <li *ngFor="let line of zoomInfoLinesVamp">{{ line }}</li>
        </ul>

        <div class="zoom-info-note">{{ zoomInfoNote }}</div>

      </ng-container>

      <!-- rendu normal -->
      <ng-template #normalInfo>
        <ul class="zoom-info-list">
          <li *ngFor="let line of zoomInfoLines">{{ line }}</li>
        </ul>
      </ng-template>

    </div>
  </div>
  `,
  styles: [`
  /* ============================
    DARK MODE (soft) - GAME PAGE
    ============================ */

  /* Layout des boards */
  *,
  *::before,
  *::after{
    box-sizing: border-box;
  }

  /* Thème (valeurs douces, pas agressives) */
  :host{
    --bg: #0f1218;
    --surface: #151a24;
    --surface-2: #1b2130;
    --surface-3: #20283a;

    --text: #e7eaf3;
    --muted: #aab1c2;
    --muted-2: #8790a6;

    --border: rgba(255,255,255,.10);
    --border-2: rgba(255,255,255,.16);
    --border-dashed: rgba(255,255,255,.22);

    --shadow: 0 10px 30px rgba(0,0,0,.45);

    --hunter: #69a7ff;
    --vamp: #ff6b6b;

    --chip-bg: #232b3d;
    --chip-text: #f1f4ff;

    --focus: rgba(231,234,243,.85);

    --warn-bg: #2a250f;
    --warn-border: #6e5a13;
    --warn-text: #ffe8a3;

    --info-bg: #151a2a;
    --info-border: #2d3550;

    --danger-bg: #2a1114;
    --danger-border: #7a2a33;
    --danger-text: #ffd7dc;

    display: block;
    min-height: 100vh;
    color: var(--text);
    background:
      radial-gradient(1200px 600px at 20% -10%, rgba(105,167,255,.10), transparent 60%),
      radial-gradient(900px 500px at 110% 10%, rgba(255,107,107,.10), transparent 55%),
      var(--bg);
  }

  /* Container */
  .container{
    width: 100%;
    max-width: 1920px;
    margin: 0 auto;
    padding: .5rem;
  }

  /* Titres */
  h2, h3{
    color: var(--text);
  }

  /* Boutons (sauf les boutons-cartes) */
  .container button:not(.card-btn){
    color: var(--text);
    background: linear-gradient(180deg, rgba(255,255,255,.07), rgba(255,255,255,.03));
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: .45rem .8rem;
    box-shadow: 0 6px 18px rgba(0,0,0,.18);
    cursor: pointer;
  }

  .container button:not(.card-btn):hover{
    border-color: var(--border-2);
  }

  .container button:not(.card-btn):active{
    transform: translateY(1px);
  }

  .container button:not(.card-btn):disabled{
    opacity: .5;
    cursor: not-allowed;
  }

  /* Boutons fixes */
  .leave-btn{ position: absolute; right: 40px; top: 5px; }
  .lobby-btn{ position: absolute; right: 150px; top: 5px; }

  .leave-btn,
  .lobby-btn{
    background: rgba(21,26,36,.80) !important;
    border: 1px solid var(--border) !important;
    backdrop-filter: blur(8px);
    box-shadow: var(--shadow);
  }

  /* Boards */
  .board-wide{
    width:100%;
    padding:.5rem;
    border:1px solid var(--border);
    margin:.5rem 0;
    background: rgba(21,26,36,.65);
    border-radius: 12px;
    box-shadow: 0 6px 18px rgba(0,0,0,.18);
  }

  .board-wide-center{
    width:100%;
    padding:.25rem;
    margin:.25rem 0;
    background: transparent;
  }

  /* Grille joueurs */
  .players-grid{
    display: flex;
    flex-wrap: wrap;
    gap: .5rem;
    justify-content: center;
  }

  /* ⚠️ écrase ton inline style (player-card) */
  .player-card{
    flex: 0 0 auto;
    width: 280px;

    padding: .5rem !important;
    border: 1px dashed var(--border-dashed) !important;
    background: linear-gradient(180deg, rgba(255,255,255,.06), rgba(255,255,255,.03)) !important;
    border-radius: 12px !important;
    box-shadow: 0 10px 26px rgba(0,0,0,.22);
  }

  /* Panels */
  .panel{
    border:1px solid var(--border);
    padding:.5rem;
    background: rgba(21,26,36,.70);
    border-radius: 12px;
    min-width: 0;
    box-shadow: 0 10px 26px rgba(0,0,0,.18);
  }

  /* ====== GRAND ÉCRAN ======
    [bloc1] [bloc2] [bloc3]
  */
  .boards-row{
    display: grid;

    row-gap: .75rem;
    column-gap: 1rem;

    align-items: start;
    grid-template-columns: 290px minmax(0, 1fr) 290px;
    grid-template-areas: "left center right";
  }

  .panel-left  { grid-area: left; }
  .panel-center{ grid-area: center; min-width: 0; }
  .panel-right { grid-area: right; }

  .boards-row.no-left{
    grid-template-columns: minmax(0, 1fr) 290px;
    grid-template-areas: "center right";
  }

  /* ====== MOYEN ÉCRAN ======
    ligne 1: bloc2 (plein)
    ligne 2: bloc1 | bloc3
  */
  @media (max-width: 1000px){
    .boards-row{
      grid-template-columns: 1fr 1fr;
      grid-template-areas:
        "center center"
        "left   right";

      column-gap: 1rem;
      row-gap: .75rem;

      justify-items: stretch;
      align-items: start;
    }

    .panel-center{
      justify-self: stretch;
      width: 100%;
    }

    .panel-left,
    .panel-right{
      width: 290px;
      max-width: 100%;
      justify-self: center;
    }

    .panel-right{
      height: 250px;
    }

    .boards-row.no-left{
      grid-template-columns: 1fr;
      grid-template-areas:
        "center"
        "right";
    }

    .boards-row.no-left .panel-right{
      width: 290px;
      justify-self: center;
    }
  }

  /* ====== PETIT ÉCRAN ======
    bloc2 (plein)
    bloc1 (plein)
    bloc3 (plein)
  */
  @media (max-width: 640px){
    .boards-row{
      grid-template-columns: 1fr;
      grid-template-areas:
        "center"
        "left"
        "right";

      row-gap: .75rem;

      justify-items: stretch;
      align-items: start;
    }

    .panel-center{
      justify-self: stretch;
      width: 100%;
    }

    .panel-left,
    .panel-right{
      width: 290px;
      max-width: 100%;
      height: auto;
      justify-self: center;
    }

    .boards-row.no-left{
      grid-template-areas:
        "center"
        "right";
    }
  }

  /* Centre plateau */
  .center-grid{
    display:grid;
    grid-template-columns: 1fr 1fr;
    gap: .75rem;
    align-items:start;
  }

  .center-col{ min-width:0; }

  .center-title{
    margin: .25rem 0 .5rem;
    font-size: 1.2rem;
    color: var(--text);
  }

  /* Préphase: version sombre */
  .prephase3{
    margin:.5rem 0;
    padding:.5rem;
    background: rgba(42,37,15,.85);
    border: 1px solid var(--warn-border);
    color: var(--warn-text);
    border-radius: 10px;
  }

  /* Live box */
  .live-box{
    margin:.5rem 0;
    padding:.5rem;
    background: rgba(21,26,42,.70);
    border: 1px solid var(--info-border);
    border-radius: 10px;
  }

  .live-line{
    margin:.15rem 0;
    color: var(--text);
  }

  /* Historique scrollable */
  .history-box{
    max-height: 206px;
    overflow: auto;
    border:1px solid var(--border);
    border-radius: 10px;
    padding: .5rem;
    background: rgba(27,33,48,.55);
  }

  .history-head{
    margin-top:.35rem;
    font-weight: 800;
    color: var(--text);
  }

  .history-line{
    padding-left:.25rem;
    margin:.15rem 0;
    color: var(--muted);
  }

  /* Bandeau joueur */
  .player-strip{
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap:.75rem;

    padding:.4rem .6rem;
    border:1px solid var(--border);
    border-radius:10px;

    background: rgba(27,33,48,.60);
    margin-bottom: 4px;
  }

  .player-strip .name{
    font-weight: 800;
    color: var(--text);
  }

  .player-strip .hp{
    display:flex;
    align-items:center;
    gap:.35rem;
  }

  .hp-heart{ width:22px; height:22px; }
  .hp-value{
    font-weight: 800;
    min-width:2ch;
    text-align:right;
    color: var(--text);
  }

/* --- Barre d'équipement --- */
.equip-bar{
  display:flex;
  align-items:stretch;
  gap:.5rem;
  flex-wrap:nowrap;
}

.equip-bar .equip-item{
  width: 100px;
  height: 136px;
  display: block;
  object-fit: contain;
  filter: drop-shadow(0 6px 10px rgba(0,0,0,.25));
}

/* Zoom box */
.equip-zoom{
  position: fixed;
  z-index: 9999;
  pointer-events: none;

  border: 1px solid var(--border-2);
  border-radius: 12px;
  background: rgba(21,26,36,.92);

  overflow: visible;

  box-shadow: var(--shadow);
}

.equip-zoom-media{
  position: relative;
}

/* image */
.equip-zoom-img{
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
}

.zoom-info{
  position: absolute;
  left: calc(100% + 10px);
  top: 0;

  width: 340px;
  max-width: 340px;
  height: 100%;

  overflow: auto;

  border-radius: 10px;
  border: 1px solid rgba(255,255,255,.14);
  background: rgb(0, 0, 0);
  padding: .55rem .65rem;

  color: rgba(255,255,255,.92);
}

.zoom-info-section{
  font: 900 16px/1.1 system-ui, sans-serif;
  margin: .35rem 0 .25rem;
  color: rgba(255,255,255,.96);
}

.zoom-info-note{
  margin-top: .55rem;
  padding-top: .45rem;
  border-top: 1px solid rgba(255,255,255,.12);

  font: 650 16px/1.25 system-ui, sans-serif;
  color: rgba(255,255,255,.9);
}


.zoom-info-list{
  margin: 0;
  padding-left: 1.05rem;
}

.zoom-info-list li{
  margin: .2rem 0;
  font: 650 16px/1.25 system-ui, sans-serif;
  color: rgba(255,255,255,.88);
}

.mini-badge{
  pointer-events: none;
}

/* Centre : cartes jouées */
.center-cards{
  display: flex;
  flex-wrap: wrap;
  gap: .5rem;
  align-items: flex-start;
}

.center-card{
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: .25rem;
}

.center-card-name{
  font: 700 12px/1.1 system-ui, sans-serif;
  color: var(--muted);
}

.center-card-img{
  width: 60px;
  height: 82px;
  display: block;
  object-fit: contain;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: rgba(0,0,0,.15);
}

/* Badges */
.equip-zoom-badge.badge-hunter{
  background: rgba(105, 167, 255, .35);
  border: 1px solid rgba(105, 167, 255, .35);
  color: #fff;
}

.equip-zoom-badge.badge-vamp{
  background: rgba(255, 107, 107, .33);
  border: 1px solid rgba(255, 107, 107, .33);
  color: #fff;
}

.mini-card.deck-pile.deck-discard-common .mini-badge{
  background: rgba(62,62,62, .35);
  border: 1px solid rgba(62,62,62, .33);
}

.name-hunter { color: var(--hunter); }
.name-vamp   { color: var(--vamp); }

/* Badge dans le zoom (texte ou nombre) */
.equip-zoom-badge{
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);

  padding: 6px 10px;
  border-radius: 999px;

  font: 900 14px/1.1 system-ui, sans-serif;
  color: #fff;
  background: rgba(0,0,0,.55);
  box-shadow: 0 2px 10px rgba(0,0,0,.35);

  max-width: calc(100% - 16px);
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;

  pointer-events: none;
}

/* Mini stack */
.equip-bar > .mini-stack{
  flex: 0 0 50px;
  width: 50px;
  min-width: 0;
  display:flex;
  flex-direction:column;
  gap:.5rem;
}

.mini-card{
  position: relative;
  height: 62px;
  width: 46px;
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: rgba(27,33,48,.55);
  margin: 1px 3px 0 0;
}

.mini-back-img{
  width: 100%;
  height: 100%;
  display: block;
  object-position: center;
}

.mini-badge{
  position: absolute;
  left: 50%;
  bottom: -8%;
  transform: translate(-50%, -50%);

  width: 10px;
  height: 16px;
  padding: 0 6px;

  display: inline-flex;
  align-items: center;
  justify-content: center;

  border-radius: 999px;
  background: rgba(0,0,0,.55);
  border: 1px solid rgba(0,0,0,.55);
  color: #fff;
  font: 800 12px/1 system-ui, sans-serif;
}

.equip-label{
  font:700 12px/1.1 system-ui, sans-serif;
  color: var(--muted);
  margin-bottom:.35rem;
}

.dice-chip{
  display:inline-block;
  font:800 12px/1 system-ui, sans-serif;
  padding:.25rem .5rem;
  border-radius:999px;
  background: rgba(255,255,255,.10);
  color: var(--text);
  border: 1px solid var(--border);
}

/* ===== Decks layout : 2 colonnes / 2 lignes ===== */
.decks-grid{
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: .9rem 1.2rem;
  align-items: start;
}

.deck-block{
  display: flex;
  flex-direction: column;
}

.deck-block-title{
  font: 800 12px/1.1 system-ui, sans-serif;
  color: var(--muted);
}

/* 2 piles côte à côte */
.deck-piles{
  display: flex;
  align-items: flex-start;
}

/* Mini-card en taille "carte" */
.mini-card.deck-pile{
  width: 60px;
  height: 82px;
}

/* Images deck */
.mini-back-img.deck-img{
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
}

/* Badge version deck */
.mini-card.deck-pile .mini-badge{
  left: 50%;
  bottom: 4px;
  transform: translateX(-50%);
  width: auto;
  height: auto;
  padding: 2px 8px;
  font: 900 12px/1 system-ui, sans-serif;
}

.mini-badge.badge-hunter{
  background: rgba(105, 167, 255, .35);
  border: 1px solid rgba(105, 167, 255, .35);
}

.mini-badge.badge-vamp{
  background: rgba(255, 107, 107, .33);
  border: 1px solid rgba(255, 107, 107, .33);
}

/* Défausse vide */
.mini-card.deck-pile.deck-empty{
  background: linear-gradient(135deg, rgba(255,255,255,.06), rgba(255,255,255,.03));
  border: 1px solid var(--border);
  box-shadow: inset 0 0 0 1px rgba(255,255,255,.06);
}


  /* --- BOARD DU BAS --- */
  .res-board{
    display:flex;
    flex-wrap: wrap;
    gap: .5rem;
    align-items:center;
    justify-content:center;
    margin:.25rem 0;
  }

  .res-title{
    font-weight: 800;
    color: var(--text);
  }

  .res-item{
    display:inline-flex;
    align-items:center;
    gap: .25rem;
    padding: .15rem .35rem;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: rgba(155, 158, 165, 0.55);
  }

  .res-ico-box{
    width: 26px;
    height: 26px;
    display:flex;
    align-items:center;
    justify-content:center;
    flex: 0 0 26px;
  }

  .res-ico{
    display:block;
    object-fit: contain;
    filter: drop-shadow(0 4px 10px rgba(0,0,0,.25));
  }

  .res-ico-s{ width: 20px; height: 20px; }
  .res-ico-m{ width: 23px; height: 23px; }
  .res-ico-l{ width: 29px; height: 29px; }

  .res-val{
    font: 800 12px/1 system-ui, sans-serif;
    color: var(--text);
    min-width: 2ch;
    text-align: right;
  }

  /* Hand */
  .hand{
    display: grid;
    gap: .35rem;
    margin-top: 10px;
  }

  .hand-labels{
    display: flex;
    gap: .5rem;
    align-items: end;
  }

  .equip-title{
    width: calc(120px + 120px + .5rem);
  }

  .hand-label{
    font: 700 14px/1.1 system-ui, sans-serif;
    color: var(--muted);
  }

  .hand-cards{
    display:flex;
    flex-wrap:wrap;
    gap:.5rem;
    align-items:flex-start;
  }

  .equip-slot{
    width: 128px;
    height: 174px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex: 0 0 128px;
  }

  .my-equip-item{
    width: 120px;
    height: 164px;
    display:block;
    object-fit: contain;
    filter: drop-shadow(0 10px 20px rgba(0,0,0,.30));
  }

  /* Boutons cartes (ne pas appliquer le style bouton normal) */
  .card-btn{
    width: 128px;
    height: 174px;
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
    box-sizing: border-box;

    display: flex;
    align-items: center;
    justify-content: center;
  }

  .card-frame{
    width: 100%;
    height: 100%;
    border-radius: 12px;
    box-sizing: border-box;

    display: flex;
    align-items: center;
    justify-content: center;

    padding: 2px;
  }

  .card-surface{
    width: 120px;
    height: 164px;
    overflow: hidden;
    border-radius: 10px;
    border: 1px solid rgba(255,255,255,.08);
    background: rgba(0,0,0,.12);
  }

  .card-btn.selected .card-frame{
    box-shadow: 0 0 0 2px var(--focus);
  }

  .card-img{
    width: 100%;
    height: 100%;
    display:block;
    object-fit: contain;
  }

  .card-btn.is-disabled{
    opacity: .55;
    cursor:not-allowed;
    pointer-events: auto;
    filter: grayscale(.15);
  }

  /* Text buttons (si tu les utilises ailleurs) */
  .text-btn{
    padding: .5rem 1rem;
    border: 1px solid var(--border);
    background: rgba(27,33,48,.55);
    color: var(--text);
    cursor: pointer;
    border-radius: 10px;
  }

  .text-btn.is-disabled{
    opacity: .55;
    cursor:not-allowed;
    pointer-events: auto;
  }

  .text-btn.selected{
    outline: 2px solid var(--focus);
    outline-offset: 2px;
  }

  /* Chips buffs */
  .mods-row{
    display:flex;
    flex-wrap: wrap;
    width: 270px;
    gap:.35rem;
    margin-top:.4rem;
    align-content: flex-start;
  }

  .mod-chip{
    flex: 0 0 auto;
    display:inline-flex;
    align-items:center;
    gap:.25rem;
    font:800 11px/1 system-ui, sans-serif;

    background: var(--chip-bg);
    color: var(--chip-text);
    border: 1px solid rgba(255,255,255,.10);

    border-radius:999px;
    padding:.18rem .4rem;
    white-space: nowrap;
    opacity:.95;
  }

  .mod-chip .chip-ico{
    width:14px;
    height:14px;
    border-radius:3px;
    background: rgba(255,255,255,.85);
  }

  .mod-chip .chip-val{
    line-height:1;
    color: var(--chip-text);
  }

  /* Focus clavier (accessibilité) */
  .container :is(button, .card-btn):focus-visible{
    outline: 2px solid var(--focus);
    outline-offset: 2px;
    border-radius: 12px;
  }

  /* =========================
    Overrides des INLINE (error)
    ========================= */

  /* Ton bandeau error est inline (background:#fee / border:#f99) -> on écrase */
  div[style*="background:#fee"]{
    background: var(--danger-bg) !important;
    border: 1px solid var(--danger-border) !important;
    color: var(--danger-text) !important;
    border-radius: 10px;
  }

  /* Petit texte "En attente..." dans la préphase */
  small{
    color: var(--muted) !important;
  }

  /* AJOUT pour modales */
  .modal-backdrop{
    position: fixed; inset: 0; background: rgba(0,0,0,.5);
    display: flex; align-items: center; justify-content: center; z-index: 1000;
  }
  .modal{
    background: #fff; padding: 1rem; border-radius: 8px; width: min(680px, 95vw);
    box-shadow: 0 10px 30px rgba(0,0,0,.2);
  }
  .content.spectate{
    position: relative;
    display: grid;
    grid-template-columns: 1fr 1fr;
    align-items: center;
    justify-items: center;
  }
  .content.action{
    display: flex; align-items: center; justify-content: center; min-height: 260px; gap: .75rem;
  }
  .side{ position: relative; display: grid; gap: .25rem; justify-items: center; }
  .dice{ width: 180px; height: auto; }
  .dice-big{ width: 240px; height: auto; }
  .icon-side{ width: 120px; height: 180px; opacity: .9; }

  .dice-wrap{ position: relative; display:inline-block; }

  .dice-overlay{
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    font: 700 42px/1 system-ui, sans-serif;
    color: #cc9c00;
    pointer-events: none;
    font-variant-numeric: tabular-nums;
    transform: translate(var(--dx, 0px), var(--dy, 0px));
  }

  /* 1 chiffre */
  .dice-wrap[data-digits="1"] .dice-overlay{
    --dx: -2px;
    --dy: 7px;
  }

  /* 2 chiffres */
  .dice-wrap[data-digits="2"] .dice-overlay{
    --dx: -5px;
    --dy:  7px;
    letter-spacing: -0.5px;
  }

  .dice-overlay-small{
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    font: 700 42px/1 system-ui, sans-serif;
    color: #cc9c00;
    pointer-events: none;
    font-variant-numeric: tabular-nums;
    font-size: 25px;
    transform: translate(var(--dx, 0px), var(--dy, 0px));
  }

    /* 1 chiffre */
  .dice-wrap[data-digits="1"] .dice-overlay-small{
    --dx: 0px;
    --dy: 5px;
  }


  .mods-col{
    position: absolute;
    top: 0; bottom: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 6px;
    pointer-events: none;
  }

  /* Ancrages gauche/droite */
  .mods-col.left{  left:  -72px; align-items: flex-start; }
  .mods-col.right{ right: -72px; align-items: flex-end;   }

  .modal.location-modal.spectate.has-focus .mods-col.left{
    left: -130px;
  }

  .modal.location-modal.spectate.has-focus .mods-col.right{
    right: -130px;
  }

  .modal.location-modal.spectate.has-focus.bite-active .mods-col.left{
    left: -72px;
  }
  .modal.location-modal.spectate.has-focus.bite-active .mods-col.right{
    right: -72px;
  }

  /* Les chips en colonne */
  .mods-col .mod-chip{
    display: inline-block;
    white-space: nowrap;
  }
  .breakdown { text-align: center; }
  .result{ margin-top: .75rem; font-weight: 600; text-align: center; }
  .modal .footer{ margin-top: .75rem; text-align: center; color: #eeeeeeff; }

  /* MODALE ROLL */
  .modal.location-modal{
    display: flex;
    flex-direction: column;
    justify-content: space-around;
    background-repeat: no-repeat; 
    background-size: cover; 
    background-position: center 50%; 
    min-height: 500px;
  }

  /* Badge de titre compact et centré */
  .modal.location-modal .bg-badge, .modal.bite-modal .bg-badge{
    display: inline-block;
    margin: 0 auto .4rem;
    text-align: center;
    line-height: 1.15;
    padding: .35rem .75rem;
    border-radius: 999px;
    background: rgba(0,0,0,.45);
    color: #fff;
  }

  .title-side-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .roll-side {
    text-align: center;
  }

  /* Contenu centré même quand des chips existent */
  .modal.location-modal .content.action, .modal.bite-modal .content.action{
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 1rem;
    flex-wrap: wrap;
    transform: translateY(-50px);
  }

  /* Les chips de la modale roll passent sur une nouvelle ligne et ne perturbent pas l’alignement */
  .modal.location-modal .mods-row{
    width: auto;
    flex-basis: 100%;
    display: flex;
    justify-content: center;
    gap: .35rem;
    margin-top: .5rem;
    order: 3;
  }

  /* Uniformiser exactement le style des chips comme sur les boards */
  .modal.location-modal .mod-chip{
    font: 700 11px/1 system-ui, sans-serif;
    padding: .40rem .50rem;
    background: #222;
    color: #fff;
    border-radius: 999px;
    gap: .25rem;
    opacity: .92;
  }


  .modal.location-modal .mod-chip .chip-ico{
    width: 14px; height: 14px; border-radius: 3px;
    background: rgba(255,255,255,.85);
  }
    
  .modal.location-modal .icon-halo, .modal.bite-modal .icon-halo{
    position: relative;
    display: grid;
    place-items: center;
    padding: 0;
    background: transparent;
    box-shadow: none;
    backdrop-filter: none;
  }

  /* dessiner Le HALO  */
  .icon-bubble{ display:grid; place-items:center; }

  .modal.location-modal .icon-halo::before, .modal.bite-modal .icon-halo::before{
    content: "";
    position: absolute;
    z-index: 0;

    /* tailles par défaut (overridées ci-dessous) */
    width: var(--halo-w, 110px);
    height: var(--halo-h, 160px);
    top: 50%; left: 50%;
    transform:
      translate(-50%, -50%)
      translate(var(--halo-dx, 0px), var(--halo-dy, 0px));
    background: rgba(20,22,24,.45);
    -webkit-backdrop-filter: blur(2px);
    backdrop-filter: blur(2px);
    box-shadow:
      inset 0 8px 24px rgba(0,0,0,.35),
      0 6px 16px rgba(0,0,0,.25);
  }

  /* Ligne principale de la modale roll : icône + gros dé + colonne premier jet */
  .roll-row{
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 1rem;
  }

  /* Colonne à droite qui contient "Premier jet" + petit dé */
  .focus-column{
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: .25rem;
  }

  /* Petit label dans la colonne */
  .focus-column .focus-label{
    font-weight: 600;
    font-size: 13px;
    line-height: 1.2;
    padding-inline: .6rem;
  }

  /* Le petit dé de premier jet (plus petit que le gros) */
  .focus-small .dice{
    width: 90px;
    height: auto;
  }

.dice-column {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

/* 2 dés côte à côte (Foca seule) */
.dice-pair {
  display: flex;
  gap: 0.5rem;
}

/* Un dé + son label au-dessus */
.dice-with-label {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
}

.dice-label {
  font-size: 11px;
  text-transform: none;
  opacity: 0.9;
  color: white;
}

/* Label "global" pour un groupe (Valse) */
.dice-label.global {
  font-weight: 600;
}

/* Section logique (Valse, Foca, etc.) */
.dice-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
}

.dice-section.focus-single {
  margin-top: 0.25rem;
}

/* Grille Valse 2 par 2 max */
.waltz-dice-grid {
  display: grid;
  grid-template-columns: repeat(2, auto);
  gap: 0.25rem;
}

/* Tailles */
.dice-wrap.waltz-small .dice,
.dice-wrap.waltz-small .dice-small {
  width: 100px;
  height: 100px;
}

.dice-wrap.waltz-verysmall .dice-verysmall {
  width: 60px;
  height: 60px;
}


  /* L'image passe au-dessus du halo */
  .modal.location-modal .icon-halo .icon-side, modal.bite-modal .icon-halo .icon-side{
    position: relative;
    z-index: 1;
    width: 120px;
    height: auto;
    opacity: .95;
  }

  .modal.location-modal .icon-halo.oval::before{
    border-radius: 50% / 35%;
  }
  .modal.location-modal .icon-halo.round::before, .modal.bite-modal .icon-halo.round::before{
    border-radius: 50%;
  }

  .modal.location-modal .icon-halo.oval{
    --halo-w: 120px;
    --halo-h: 160px;
    --halo-dx: 0px;
    --halo-dy: 0px;
  }

  .modal.location-modal .icon-halo.round{
    --halo-w: 140px;
    --halo-h: 140px;
    --halo-dx: 5px;
    --halo-dy: 5px;
  }

  .modal.location-modal.spectate{
    width: min(780px, 95vw);
    min-height: 540px;
    background-repeat: no-repeat;
    background-size: cover;
    background-position: center 50%;
    display: flex;
    flex-direction: column;
  }

  .modal.location-modal.spectate.has-focus{
    width: min(1000px, 95vw);
    min-height: 700px;
  }

  .modal.location-modal.spectate.has-focus.bite-active{
    width: min(780px, 95vw);
    min-height: 540px;
  }

  /* Spectateur : chips identiques aux boards */
  .modal.location-modal.spectate .mods-col .mod-chip{
    display: inline-flex;
    align-items: center;
    gap: .25rem;
    font: 700 11px/1 system-ui, sans-serif;
    padding: .40rem .50rem;
    background: #222;
    color: #fff;
    border-radius: 999px;
    opacity: .92;
  }
  .modal.location-modal.spectate .mods-col .mod-chip .chip-ico{
    width: 14px; height: 14px; border-radius: 3px;
    background: rgba(255,255,255,.85);
  }

  /* Spectateur : centrer verticalement les colonnes de mods */
  .modal.location-modal.spectate .mods-col{
    top: 50%;
    bottom: auto;
    transform: translateY(-50%);
  }

  /* Spectateur : afficher l’icône placeholder dans les chips */
  .modal.location-modal.spectate .mods-col .mod-chip .chip-ico{
    width: 14px; height: 14px; border-radius: 3px;
    background: rgba(255,255,255,.85);
  }

  /* 3) Spectateur : forcer l’alignement des DÉS sur la même ligne */
  .modal.location-modal.spectate .side{
    grid-template-rows: 160px auto;
    justify-items: center;
    align-items: start;
  }

  /* centre le halo */
  .modal.location-modal.spectate .icon-halo.round,
  .modal.location-modal.spectate .icon-halo.oval{
    --halo-dx: 0px;
  }

  .modal.location-modal.spectate .dice-row{
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .modal.location-modal.spectate.has-focus .content.spectate .dice{
    width: 150px;
  }

  .modal.location-modal.spectate.has-focus.bite-active .content.spectate .dice{
    width: 180px;
  }

  /* 1) centre l'élément icône+halo dans la 1ère ligne (160px) */
  .modal.location-modal.spectate .side > .icon-halo{
    align-self: center;
  }

  /* 2) annule le petit décalage vertical du halo round UNIQUEMENT en spectate */
  .modal.location-modal.spectate .icon-halo.round{
    --halo-dy: 0px;
  }

  /* AJOUT WEATHER */
  .modal.weather-modal{
    width: min(680px, 95vw);
    min-height: 540px;
    background-color: #452000;
    background-image: url("https://www.transparenttextures.com/patterns/purty-wood.png");
    position: relative;
  }

  .weather-modal.with-bg{
    background-size: cover;
    background-position: center;
    background-color: rgba(0,0,0,.30);
    background-blend-mode: multiply;
    color: #fff;
    text-shadow: 0 1px 2px rgba(0,0,0,.6);
  }

  .content.weather.result{
    display:flex;
    flex-direction:column;
    justify-content:center;
    align-items:center;
    gap:.5rem;
    min-height: 260px;
    text-align:center;
  }

  .weather-badge{
    font: 800 1.1rem/1.1 system-ui, sans-serif;
    padding:.35rem .75rem;
    border-radius: 999px;
    background: rgba(0,0,0,.45);
  }

  .weather-desc, .weather-note{
    max-width: 60ch;
    white-space: pre-line;
    margin: 0;
    text-shadow: 0 2px 6px rgba(0,0,0,.55);
    opacity:.95;
  }
  
  .content.weather{
    display:flex; flex-direction:column; align-items:center; justify-content:center;
    min-height: 260px; gap:.75rem;
  }

    /* Conteneur pour positionner l'icône sur la roue */
  .wheel-wrap{
    position: relative;
    width: 400px;
    height: auto;
  }
  .wheel-wrap .wheel{ display:block; width:100%; height:auto; }

  /* icône météo sur une pale */
  .weather-ico{
    position: relative;
    top: 0%; left: 0%;
    width: 28px; height: 28px;
    transform-origin: 50% 50%;
    filter: drop-shadow(0 1px 2px rgba(0,0,0,.5));
    transition: transform .25s ease-out;
  }

  .badge-weather{
    position: absolute;
    top: 50%; left: 50%;
    width: 28px; height: 28px;
    background: #ddddddff;
    border-radius: 999px;
    opacity: .92;
  }

  .mods-badge-weather, .mods-badge-potion, .mods-badge-action, .mods-badge-corruption, .mods-badge-equip, .mods-badge-hit{
    width: 28px; height: 28px;
    background: #ddddddff;
    border-radius: 999px;
    opacity: .92;
  }

  .modal.location-modal.spectate .mods-col .mods-row{
    display:flex;
    flex-direction:column;
    align-items: flex-start;
    gap:6px;
  }

  /* ==== MODALE MOSRURE ==== */
  .modal.bite-modal{
    display: flex;
    flex-direction: column;
    justify-content: space-around;
    background-repeat: no-repeat; 
    background-size: cover; 
    background-position: center 30%; 
    min-height: 500px;
  }

  .modal.bite-modal {
    width: min(780px, 95vw);
    min-height: 700px;
  }

  .modal.bite-modal .icon-side{ width: 120px; height: 120px; opacity: .9; }

  .modal.bite-modal .icon-halo.oval{
    --halo-w: 120px;
    --halo-h: 120px;
    --halo-dx: 0px;
    --halo-dy: 0px;
  }

  .modal.bite-modal .icon-halo.round, .modal.bite-modal .icon-halo.round{
    --halo-w: 140px;
    --halo-h: 140px;
    --halo-dx: 0px;
    --halo-dy: 0px;
  }

  /* ==== MODALE CORRUPTION ==== */
  .modal.corruption-modal{
    position: relative;
    width: min(680px, 95vw);
    min-height: 540px;
    display: flex;
    flex-direction: column;
  }

  .corruption-modal.with-bg{
    background-size: cover;
    background-position: center 20%;
    background-color: rgba(0,0,0,.30);
    background-blend-mode: multiply;
    color: #fff;
    text-shadow: 0 1px 2px rgba(0,0,0,.6);
  }

  .modal.corruption-modal .scrim-bottom{
    position: absolute;
    left: 0; right: 0; bottom: 0;
    height: 45%;
    background: linear-gradient(to top, rgba(0,0,0,.55), rgba(0,0,0,0));
    pointer-events: none;
    z-index: 1;
  }

  .modal.corruption-modal .content.corruption{
    position: relative;
    flex: 1;
    display: flex;
    align-items: flex-end;
    justify-content: center;
    padding: .75rem;
    z-index: 2;
  }

  /* Boîte lisible autour du texte et des choix */
  .modal.corruption-modal .bg-box{
    background: rgba(0,0,0,.45);
    color: #fff;
    border-radius: 12px;
    padding: .6rem .75rem;
    box-shadow:
      inset 0 8px 24px rgba(0,0,0,.25),
      0 6px 16px rgba(0,0,0,.25);
    backdrop-filter: blur(2px);
    -webkit-backdrop-filter: blur(2px);
    max-width: 560px;
    width: min(560px, 90vw);
  }

  .modal.corruption-modal .bg-badge{
    margin: .2rem auto 0;
    padding: .35rem .75rem;
    border-radius: 999px;
    background: rgba(0,0,0,.45);
    color: #fff;
    text-align: center;
    z-index: 2;
    padding:.4rem .8rem;
    font-weight:600;
  }

  .label-choices{
    font-weight:500;
    margin:.75rem 0 .25rem;
  }

  .modal.corruption-modal .choices{
    display: flex;
    flex-wrap: wrap;
    gap: .35rem;
  }

  .modal.corruption-modal .choices > button{
    padding: .4rem .65rem;
    border-radius: 8px;
    border: 1px solid rgba(255,255,255,.25);
    background: rgba(255,255,255,.08);
    color: #fff;
    cursor: pointer;
  }
  .modal.corruption-modal .choices > button:hover{
    background: rgba(255,255,255,.16);
  }

  /* Icône de potion et d'action à l'intérieur du badge */
  .mods-badge-potion .mod-ico,
  .mods-badge-action .mod-ico,
  .mods-badge-corruption .mod-ico,
  .mods-badge-equip .mod-ico,
  .mods-badge-hit .mod-ico{
    width: 28px;
    height: 28px;
    display: block;
    object-fit: contain;
  }

  .weather-footer{color: white; font-weight: bold;}
  .wheel{ width: 400px; height:auto; opacity:.95; }
  .btn-primary{ padding:.5rem 1rem; font-weight:600; }

  /* === MODALE ACTION (Filet / Fosse) === */
  .modal.action-modal{
    position: relative;
    width: min(780px, 95vw);  /* même largeur que spectate */
    min-height: 640px;        /* même hauteur mini que spectate */
    display: flex;
    align-items: center;
    justify-content: center;
    background-repeat: no-repeat;
    background-size: cover;
    background-position: center 40%;
  }

  .action-modal.with-bg{
    background-color: rgba(0,0,0,.30);
    background-size: cover;
    background-position: center 10%;
    color: #fff;
    text-shadow: 0 1px 2px rgba(0,0,0,.6);
  }

  .action-modal .content.action{
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: .75rem;
  }

  /* Boîte lisible pour le texte et les boutons */
  .action-box{
    background: rgba(0,0,0,.55);
    color: #fff;
    border-radius: 12px;
    padding: .75rem 1rem;
    box-shadow:
      inset 0 8px 24px rgba(0,0,0,.25),
      0 6px 16px rgba(0,0,0,.25);
    backdrop-filter: blur(2px);
    -webkit-backdrop-filter: blur(2px);
    max-width: 560px;
    width: min(560px, 90vw);
  }

  /* Titres et textes */
  .action-box h2{
    text-align: center;
    margin: 0 0 .5rem;
  }

  .action-box p{
    margin: .25rem 0;
  }

  /* Liste de cibles Filet */
  .action-box .action-targets{
    display: flex;
    flex-wrap: wrap;
    gap: .35rem;
    margin: .5rem 0;
  }

  /* Boutons dans la boîte (cibles + "Lancer le dé") */
  .action-box .action-targets button,
  .action-box button{
    padding: .4rem .75rem;
    border-radius: 8px;
    border: 1px solid rgba(255,255,255,.35);
    background: rgba(255,255,255,.08);
    color: #fff;
    cursor: pointer;
  }

  .action-box .action-targets button.selected{
    background: rgba(255,255,255,.25);
  }

  .action-box button[disabled]{
    opacity: .5;
    cursor: not-allowed;
  }

  /* Résultat du jet */
  .trap-result{
    margin-top: .5rem;
    padding-top: .5rem;
    border-top: 1px solid rgba(255,255,255,.25);
    text-align: center;
  }
  .trap-info{
    font-size: .9rem;
    opacity: .9;
  }

  .trap-dice-row{
    display: flex;
    align-items: center;
    justify-content: center;
    gap: .75rem;
    margin-bottom: .5rem;
  }

  .dice20-overlay{
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    font: 700 42px/1 system-ui, sans-serif;
    color: #cc9c00;
    pointer-events: none;
    font-variant-numeric: tabular-nums;
    transform: translate(var(--dx, 0px), var(--dy, 0px));
  }

  /* 1 chiffre */
  .dice-wrap[data-digits="1"] .dice20-overlay{
    --dx: -2px;
    --dy: 2px;
  }

  /* 2 chiffres */
  .dice-wrap[data-digits="2"] .dice20-overlay{
    --dx: -5px;
    --dy:  2px;
    letter-spacing: -0.5px;
  }

  .target-list {
    display: flex;
    justify-content: space between;
    align-items: center;
  }

  /* =========================
    Trade modale - Base
    ========================= */

  .modal.trade-modal,
  .modal.trade-modal *{
    box-sizing: border-box;
  }

  /* Boutons génériques */
  .modal.trade-modal .shop-btn{
    padding: .5rem 1rem;
    border: 1px solid #ccc;
    cursor: pointer;
    background: #fff;
    border-radius: 8px;
  }

  .modal.trade-modal .shop-btn.is-disabled,
  .modal.trade-modal .shop-btn:disabled{
    opacity: .55;
    cursor: not-allowed;
    pointer-events: auto;
  }

  /* Container modale */
  .modal.trade-modal{
    max-width: 1500px;
    width: 95vw;
    height: 90vh;
    border-radius: 12px;
    padding: 1rem;

    position: relative;
    background: transparent;

    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  /* Background image hunters/vampires */
  .modal.trade-modal::before{
    content:"";
    position:absolute;
    inset:0;
    background-size: cover;
    background-position:center;
    pointer-events:none;
    z-index:0;
  }
  .modal.trade-modal.hunters::before{   background-image:url('/assets/locations/bg-trade-hunters.png'); }
  .modal.trade-modal.vampires::before{  background-image:url('/assets/locations/bg-trade-vampires.png'); }

  /* mettre le contenu au-dessus du fond */
  .modal.trade-modal > *{ position:relative; z-index:1; }

  /* Head */
  .modal.trade-modal .modal-head{
    flex: 0 0 auto;
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap:.75rem;

    border-bottom:1px solid rgba(255,255,255,.25);
    padding-bottom:.5rem;
    margin-bottom:1rem;
    color:#eee;
  }

  .modal.trade-modal .modal-head .head-actions{
    display:flex;
    align-items:center;
    gap:.5rem;
  }

  .modal.trade-modal .modal-head .finish{
    width:auto;
  }

  /* Body */
  .modal.trade-modal .modal-body{
    flex: 1 1 auto;
    min-height: 0;
    overflow: hidden;

    display: grid;
    gap: 1rem;
  }

  .card-header-line {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: .5rem;
  }

  /* Vampire/servant: 2 colonnes (shop + échanges) */
  .modal.trade-modal .modal-body{
    grid-template-columns: 1fr 1.2fr;
  }

  /* Hunter: 3 colonnes */
  .modal.trade-modal .modal-body.has-mid{
    grid-template-columns: 1fr 1fr 1.2fr;
  }

  /* Colonnes scrollables (grand écran) */
  .modal.trade-modal .modal-body > .col{
    min-height: 0;
    overflow: auto;
    -webkit-overflow-scrolling: touch;
    padding-right: .25rem;
  }

  /* Cards */
  .modal.trade-modal .card{
    border:1px solid rgba(255,255,255,.25);
    border-radius:8px;
    padding:.50rem;
    margin-bottom:.75rem;

    background:rgba(32,32,32,.35);
    color:#fff;

    min-width: 0;
  }

  .modal.trade-modal img{
    max-width:100%;
  }

  /* marges internes existantes */
  .modal.trade-modal .card h4,
  .modal.trade-modal .shop-btn{
    margin: 10px 5px;
  }

  /* Helpers */
  .modal.trade-modal .row{
    display:flex;
    gap:.5rem;
    align-items:center;
    flex-wrap:wrap;
  }

  .modal.trade-modal .grid{
    display:grid;
    grid-template-columns: repeat(4, minmax(0,1fr));
    gap:.5rem;
  }

  .modal.trade-modal .card .grid.sell-grid{
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }

  /* ====== SELL GRID (icônes seulement) ====== */
  .modal.trade-modal .card .grid.sell-grid.sell-grid-icons{
    grid-template-columns: repeat(auto-fit, minmax(60px, 1fr));
    gap: .6rem;
  }

  .modal.trade-modal .sell-grid-icons .sell{
    display:flex;
    flex-direction:column;
    align-items:center;
    justify-content:flex-start;
    gap:.35rem;

    border:none;
    border-radius:0;
    background:transparent;

    padding:.2rem 0;
  }

  .modal.trade-modal .sell-grid-icons .sell-ico{
    width:34px;
    height:34px;
    display:flex;
    align-items:center;
    justify-content:center;
    flex:0 0 34px;

    border:1px solid rgba(255,255,255,.22);
    border-radius:10px;
    background:rgba(0,0,0,.22);
  }

  .modal.trade-modal .sell-grid-icons .sell-res-ico{
    width:22px;
    height:22px;
    object-fit:contain;
    display:block;
  }

  .modal.trade-modal .sell-grid-icons .sell .actions{
    display:flex;
    justify-content:center;
    width:100%;
  }

  .modal.trade-modal .targets button.active{ outline:2px solid #fff; }

  .modal.trade-modal .trade-area .pill{
    display:inline-block;
    border:1px solid rgba(255,255,255,.25);
    border-radius:999px;
    padding:.15rem .5rem;
    margin:.15rem;
  }

  .modal.trade-modal .ok{ background:rgba(0,160,0,.18); }
  .modal.trade-modal .ko{ background:rgba(200,0,0,.18); }

  .modal.trade-modal .muted{
    opacity:1;
    color: rgba(255,255,255,.75);
  }

  .modal.trade-modal .timer{ font-weight:600; }

  .modal.trade-modal .recipes li{
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap:.5rem;
    margin:.25rem 0;
  }

  .modal.trade-modal .sell .actions button{ min-width:3rem; }

  /* =========================
    INVENTAIRE (inv-item)
    ========================= */
  .modal.trade-modal .modal-head .inventory{
    margin: 0 auto;
    display:flex;
    gap:.35rem;
    flex-wrap:wrap;
    align-items:center;
    justify-content:center;
  }

  .modal.trade-modal .inv-item{
    display:inline-flex;
    align-items:center;
    gap:.25rem;

    border:1px solid rgba(255,255,255,.35);
    background:rgba(0,0,0,.25);
    border-radius:999px;
    padding:.12rem .45rem;

    color:#fff;
    font-weight:700;
  }

  .modal.trade-modal .inv-ico-box{
    width:22px;
    height:22px;
    display:flex;
    align-items:center;
    justify-content:center;
    flex:0 0 22px;
  }

  .modal.trade-modal .inv-ico{
    display:block;
    object-fit:contain;
  }

  .modal.trade-modal .inv-ico-s{ width:18px; height:18px; }
  .modal.trade-modal .inv-ico-m{ width:20px; height:20px; }
  .modal.trade-modal .inv-ico-l{ width:24px; height:24px; }

  .modal.trade-modal .inv-val{
    font:800 12px/1 system-ui, sans-serif;
    color:#fff;
    min-width:2ch;
    text-align:right;
  }

  /* =========================
    PRIX / COÛTS en icônes
    ========================= */
  .modal.trade-modal .price.price-icons{
    display:inline-flex;
    align-items:center;
    gap:.25rem;
    flex-wrap:wrap;
    justify-content:flex-end;
  }

  .modal.trade-modal .cost-item{
    display:inline-flex;
    align-items:center;

    border:1px solid rgba(255,255,255,.35);
    background:rgba(0,0,0,.25);
    border-radius:999px;
    padding:.06rem .25rem;
  }

  .modal.trade-modal .cost-ico-box{
    width:18px;
    height:18px;
    display:flex;
    align-items:center;
    justify-content:center;
    flex:0 0 18px;
  }

  .modal.trade-modal .cost-ico{
    display:block;
    width:100%;
    height:100%;
    object-fit:contain;
  }

  .modal.trade-modal .cost-ico-s{ width:16px; height:16px; }
  .modal.trade-modal .cost-ico-m{ width:18px; height:18px; }
  .modal.trade-modal .cost-ico-l{ width:20px; height:20px; }

  .modal.trade-modal .cost-val{
    font:800 12px/1 system-ui, sans-serif;
    color:#fff;
    min-width:2ch;
    text-align:right;
  }

  .modal.trade-modal .cost-plus{
    opacity:.9;
    font-weight:900;
    padding:0 .1rem;
  }

  .modal.trade-modal .cost-or{
    opacity:.9;
    font-weight:900;
    padding:0 .15rem;
  }

  /* =========================
    LIGNES 2-CARDS (toujours sur 2 colonnes)
    ========================= */
  .modal.trade-modal .card-row.row-2{
    display:grid;
    grid-template-columns: 1fr 1fr;
    gap:.75rem;
    margin-bottom:.75rem;
  }

  .modal.trade-modal .card-row.row-2 > .card{
    margin-bottom: 0; /* pas de double margin */
    min-width: 0;
  }

  /* =========================
    Boutons Acheter = image + badge
    ========================= */
  .modal.trade-modal .buy-card-btn{
    width:60px;
    height:82px;
    margin:.5rem auto 0 auto;
    padding:0;

    display:flex;
    align-items:center;
    justify-content:center;

    border-radius:10px;
    border:1px solid rgba(255,255,255,.25);
    background:rgba(0,0,0,.20);

    position:relative;
    overflow:hidden;
  }

  .modal.trade-modal .buy-card-btn .buy-img{
    width:100%;
    height:100%;
    display:block;
    object-fit:contain;
  }

  .modal.trade-modal .buy-badge{
    position:absolute;
    left:50%;
    bottom:4px;
    transform:translateX(-50%);

    padding:2px 8px;
    border-radius:999px;

    font:800 12px/1 system-ui, sans-serif;
    background:rgba(0,0,0,.65);
    color:#fff;

    pointer-events:none;
  }

  .modal.trade-modal .buy-badge.badge-hunter{ background: rgba(29, 78, 216, .65); }
  .modal.trade-modal .buy-badge.badge-vamp{   background: rgba(185, 28, 28, .65); }

  /* Card achat avec bouton collé en bas */
  .modal.trade-modal .card.card-buy-bottom{
    display:flex;
    flex-direction:column;
  }
  .modal.trade-modal .card.card-buy-bottom .buy-card-btn{
    margin-top:auto;
    align-self:center;
    margin-bottom:.35rem;
  }

  /* =========================
    Actions de maintenance
    ========================= */
  .modal.trade-modal .shop-actions-grid{
    display:flex;
    flex-wrap:wrap;
    gap:.5rem;
    justify-content:center;
    margin:.35rem 0 .25rem;
  }

  .modal.trade-modal .shop-card-action-btn{
    width:78px;
    height:105px;
    padding:0;

    display:flex;
    align-items:center;
    justify-content:center;

    border-radius:10px;
    border:1px solid rgba(255,255,255,.25);
    background:rgba(0,0,0,.20);

    overflow:hidden;
  }

  .modal.trade-modal .shop-card-action-btn.is-disabled,
  .modal.trade-modal .shop-card-action-btn:disabled{
    opacity:.55;
    cursor:not-allowed;
    pointer-events:auto;
  }

  .modal.trade-modal .shop-card-img{
    width:100%;
    height:100%;
    display:block;
    object-fit:contain;
  }

  /* =========================
    Échanges
    ========================= */
  .modal.trade-modal .trade-list{
    padding-right: 0;
    margin-top: .35rem;
  }

  .modal.trade-modal .trade-block{
    border:1px solid rgba(255,255,255,.25);
    border-radius:8px;
    padding:.5rem;
    margin-bottom:.5rem;
  }

  .modal.trade-modal .card.sub{ margin:.5rem 0; }

  .modal.trade-modal .trade-toast{
    border:1px solid transparent;
    border-radius:6px;
    padding:.5rem .75rem;
    margin-bottom:.5rem;
    font-weight:600;
    color:#fff;
  }

  .modal.trade-modal .trade-toast.ok{
    background: rgba(0,160,0,.18);
    border-color: rgba(0,160,0,.35);
  }

  .modal.trade-modal .trade-toast.ko{
    background: rgba(200,0,0,.18);
    border-color: rgba(200,0,0,.35);
  }

  .modal.trade-modal .trade-block.closing-ok .card.sub{
    background: rgba(0,160,0,.16) !important;
    outline: 2px solid rgba(0,160,0,.6) !important;
  }

  .modal.trade-modal .trade-block.closing-ko .card.sub{
    background: rgba(200,0,0,.16) !important;
    outline: 2px solid rgba(200,0,0,.6) !important;
  }

  .modal.trade-modal .trade-block.closing .buttons button{
    opacity:.6;
    pointer-events:none;
  }

  /* =========================
    UN SEUL BREAKPOINT : 1 colonne + scroll unique (contenu reste dans la modale)
    ========================= */
  @media (max-width: 1200px){

    /* on garde la modale "fermée", et on scroll l'intérieur */
    .modal.trade-modal{
      width: 96vw;
      height: 92vh;
      overflow: hidden;
    }

    /* 1 colonne, et scroll unique sur le body */
    .modal.trade-modal .modal-body{
      grid-template-columns: 1fr !important;
      overflow: auto !important;
      -webkit-overflow-scrolling: touch;
      padding-right: 0;
    }

    /* plus aucun scroll interne */
    .modal.trade-modal .modal-body > .col{
      overflow: visible !important;
      min-height: auto;
      padding-right: 0;
    }

    .modal.trade-modal .trade-list{
      overflow: visible !important;
      max-height: none !important;
    }

    /* grilles adaptatives pour éviter le dépassement horizontal */
    .modal.trade-modal .grid{
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
    }

    .modal.trade-modal .card .grid.sell-grid{
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
    }

    /* sell grid icônes : garde des cases plus petites */
    .modal.trade-modal .card .grid.sell-grid.sell-grid-icons{
      grid-template-columns: repeat(auto-fit, minmax(60px, 1fr));
    }
  }

  /* === MODALE CONSTRUCTION === */
  /* Conteneur de la modale construction */
  .modal.construction-modal{
    position: relative;

    width: 80vw;
    height: 90vh;

    max-width: 1200px;
    max-height: 1100px;

    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;

    padding: 1.25rem;
    text-align: center;

    background-size: cover;
    background-position: center;
    box-sizing: border-box;
  }

  /* zone scrollable */
  .modal.construction-modal .construction-scroll{
    width: 100%;
    max-width: 100%;

    max-height: 100%;

    overflow: auto;

    box-sizing: border-box;

    padding-right: 2px;
  }

  /* petit ajustement responsive */
  @media (max-width: 900px){
    .modal.construction-modal{
      width: 92vw;
      height: 86vh;
      padding: 1rem;
    }
  }

  @media (max-width: 600px){
    .modal.construction-modal{
      width: 96vw;
      height: 90vh;
      padding: .85rem;
    }
  }

  .modal.construction-modal.choices {
    justify-content: space-around;
  }

  .modal.construction-modal .bg-badge{
    display: inline-block;
    margin: 0 auto .4rem;
    text-align: center;
    line-height: 1.15;
    padding: .35rem .75rem;
    border-radius: 999px;
    background: rgba(0,0,0,.45);
    color: #fff;
  }

    /* ===== Construction : grille des cartes ===== */
  .modal.construction-modal .build-grid{
    width: 100%;
    margin: 1rem 0;

    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: .9rem;
    align-items: flex-start;
  }

  .modal.construction-modal .build-item{
    flex: 0 0 190px;

    height: 280px;
    box-sizing: border-box;

    display: flex;
    flex-direction: column;
    align-items: center;
    gap: .45rem;

    padding: .65rem .65rem .75rem;
    border-radius: 14px;

    background: rgba(0,0,0,.35);
    border: 1px solid rgba(255,255,255,.18);
    box-shadow: 0 10px 26px rgba(0,0,0,.25);
  }

  /* bouton-image */
  .modal.construction-modal .build-card-btn{
    border: none;
    background: transparent;
    padding: 0;
    cursor: pointer;
  }

  .modal.construction-modal .build-card-img{
    width: 110px;
    height: 150px;
    object-fit: contain;
    display: block;
    filter: drop-shadow(0 10px 18px rgba(0,0,0,.35));
  }

  .modal.construction-modal .build-title{
    flex: 0 0 auto;
    font: 800 14px/1.1 system-ui, sans-serif;
    color: #fff;
    text-align: center;
  }

  .modal.construction-modal .build-sub{
    flex: 0 0 auto;
    font: 650 12px/1.1 system-ui, sans-serif;
    color: rgba(255,255,255,.75);
    margin-top: -2px;
    text-align: center;
  }

  /* ===== Coûts en icônes (copie du style boutique, mais pour construction-modal) ===== */
  .modal.construction-modal .price.price-icons{
    display:inline-flex;
    align-items:center;
    gap:.25rem;
    flex-wrap:wrap;
    justify-content:center;
  }

  .modal.construction-modal .cost-item{
    display:inline-flex;
    align-items:center;
    border:1px solid rgba(255,255,255,.28);
    background:rgba(0,0,0,.22);
    border-radius:999px;
    padding:.06rem .25rem;
  }

  .modal.construction-modal .cost-ico-box{
    width:18px;
    height:18px;
    display:flex;
    align-items:center;
    justify-content:center;
    flex:0 0 18px;
  }

  .modal.construction-modal .cost-ico{
    display:block;
    width:100%;
    height:100%;
    object-fit:contain;
  }

  .modal.construction-modal .cost-ico-s{ width:16px; height:16px; }

  .modal.construction-modal .cost-val{
    font:800 12px/1 system-ui, sans-serif;
    color:#fff;
    min-width:2ch;
    text-align:right;
  }

  /* ===== Cadre info (uniquement lieux à effet) ===== */
  .modal.construction-modal .build-info{
    width: 100%;
    margin-top: .25rem;

    border-radius: 12px;
    padding: .55rem .6rem;

    background: rgba(0,0,0,.28);
    border: 1px solid rgba(255,255,255,.16);

    text-align: left;
    color: rgba(255,255,255,.92);
  }

  .modal.construction-modal .build-info ul{
    margin: .25rem 0 0;
    padding-left: 1.05rem;
  }

  .modal.construction-modal .build-info li{
    margin: .15rem 0;
    font: 650 12px/1.25 system-ui, sans-serif;
    color: rgba(255,255,255,.88);
  }

  /* Annuler garde son style */
  .modal.construction-modal .btn-secondary{
    padding: 0.5rem 1rem;
  }

  .modal.construction-modal .btn-secondary {
    background: #444;
    color: #eee;
    margin-top: 10px;
  }

  /* Modale Bibliothèque = même base que construction-modal */
  .modal.location-effect-modal {
    background-size: cover;
    background-position: center 30%;
  }

  .modal.location-effect-modal .modal-overlay-content{
    box-sizing: border-box;

    /* garde ton look */
    background: rgba(0, 0, 0, 0.65);
    padding: 1.5rem;
    border-radius: 8px;
    max-width: 560px;
    width: min(560px, 90vw);
    color: #fff;
    text-align: center;

    max-height: 100%;
    overflow: auto;

    max-width: 100%;

    /* autorise le shrink + scroll au lieu de déborder */
    min-height: 0;
    min-width: 0;
  }

  /* Boutons d'option d'effet (texte cliquable) */
  .modal.location-effect-modal .modal-button-row .effect-option,
  .modal.location-effect-modal .altar-target {
    background: transparent;
    border: none;
    color: #ddd;
    padding: 0.25rem 0.5rem;
    cursor: pointer;
  }

  /* désactivés = grisés */
  .modal.location-effect-modal .modal-button-row .effect-option[disabled] {
    cursor: default;
    opacity: 0.5;
  }

  /* sélectionné = blanc + souligné */
  .modal.location-effect-modal .modal-button-row .effect-option.selected,
  .modal.location-effect-modal .altar-target.selected {
    color: #fff;
    font-weight: 600;
    text-decoration: underline;
    outline: none;
    background: transparent;
  }

  .modal.location-effect-modal .forge-section .forge-options-row .effect-option.selected,
  .modal.location-effect-modal .location-list button.selected {
    border: 3px solid white;
    background-color: #cbcbcb;
  }

  /* rangée du bas de la modale (Valider / Annuler) */
  .modal.location-effect-modal .footer-row {
    margin-top: 0.75rem;
  }

  /* petite ligne d'info sous la modale quand un choix est figé par le serveur */
  .location-effect-result {
    margin-top: 0.75rem;
    font-style: italic;
  }

  .monster-pick-grid{
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: .75rem;
  }

  .monster-pick{
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: .35rem;
    padding: .5rem;
    border: 1px solid rgba(255,255,255,.2);
    border-radius: .75rem;
    background: rgba(0,0,0,.25);
    cursor: pointer;
  }

  .monster-pick.selected{
    outline: 2px solid rgba(255,255,255,.6);
  }

  .monster-img{
    height: min(300px, 30vh);
    width: auto;
    max-width: 100%;
    object-fit: contain;
    display: block;
  }

  .monster-name{
    color: white;
    font-weight: 700;
  }

  .monster-stats{
    font-size: .9rem;
    opacity: .9;
    color: white;
    text-align: center;
    line-height: 1.2rem;
  }

  /* =========================
    OMEN (3 cartes sur 1 ligne)
    ========================= */
  .cards-row.omen-row{
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: .75rem;
    align-items: start;
  }

  .omen-card{
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: .45rem;
  }

  /* bouton image : pas de bordure, pas de fond, pas de padding */
  .omen-card-btn{
    border: none;
    background: transparent;
    padding: 0;
  }

  /* taille image carte (si tu veux “comme ailleurs”) */
  .omen-card-img{
    width: 110px;
    height: 150px;
    object-fit: contain;
    display: block;
  }

  /* boutons : on ne force pas de style, juste une largeur propre */
  .omen-place-btn{
    width: 100%;
    max-width: 170px;
  }

  /* selected : tu avais demandé le selected visuel */
  .omen-place-btn.selected{
    text-decoration: underline;
    font-weight: 700;
  }

  /* responsive */
  @media (max-width: 700px){
    .cards-row.omen-row{
      grid-template-columns: 1fr;
    }

    .omen-card-img{
      width: 140px;
      height: 190px;
    }
  }

  /* End game */
  .modal-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.7);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }

  .end-game-modal {
    background: #111;
    color: #eee;
    border-radius: 8px;
    padding: 1.5rem 2rem;
    max-width: 420px;
    width: 90%;
    box-shadow: 0 0 25px rgba(0,0,0,0.8);
    text-align: center;
  }

  .end-game-modal h2 {
    margin-top: 0;
    margin-bottom: .5rem;
  }

  .end-game-modal p {
    margin-top: 0;
    margin-bottom: 1rem;
  }

  .end-game-actions button {
    padding: .5rem 1.5rem;
    border-radius: 4px;
    border: none;
    cursor: pointer;
  }
`]
})
export class GameComponent {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private live = inject(LiveService);
  private unsubscribeGameTopic?: () => void; // coupe l’abonnement WS au destroy
  private notify = inject(NotifyService);
  private cdr = inject(ChangeDetectorRef);

  private weatherWaitTimer?: any;

  manyModsForDebug(p: SPlayer, times = 3) {
    const base = this.modsForDisplay(p);
    return Array(times).fill(base).flat();
  }

  gameId = '';
  game?: GameSnapshot;
  errorMsg = '';
  hasSkipped = false;
  private lastPhaseNotified?: Phase;
  private harvestBubbleShown = false;

  meId = sessionStorage.getItem('userId') || '';
  username = sessionStorage.getItem('username') || '';

  deathModalOpen = false;
  deathModalSeen = false;

  get showDeathModal(): boolean {
    return !this.isGameEnded && this.isMeDead && this.deathModalOpen;
  }

  get isMeDead(): boolean {
    const me = this.game?.players?.find(p => p.id === this.meId);
    return !!me && !me.leftGame && me.hp <= 0;
  }

  selectedLocation: string | null = null;
  selectedAction: string | null = null;
  selectedActionIndex: number | null = null;
  selectedPotion: string | null = null;
  selectedPotionIndex: number | null = null;

  private seenEventKeys = new Set<string>();

  private stableEventKey(ev: any): string {
    const t = ev?.type || 'UNKNOWN';
    const ts = ev?.ts ?? 0;
    const p = ev?.payload || {};

    switch (t) {
      case 'LOCATION_SELECTED':
        return `${t}|${ts}|${p.playerId ?? ''}|${p.card ?? ''}`;
      case 'DICE_ROLLED':
        return `${t}|${ts}|${p.roundId ?? ''}|${p.who ?? ''}|${p.side ?? ''}|${p.roll ?? ''}`;
      case 'COMBAT_RESOLVED':
        return `${t}|${ts}|${p.roundId ?? ''}|${p.dmg ?? ''}`;
      case 'BITE_STARTED':
        return `${t}|${ts}|${p.attackerId ?? ''}|${p.targetId ?? ''}|${p.location ?? ''}`;
      case 'BITE_ROLLED':
        return `${t}|${ts}|${p.attackerId ?? ''}|${p.targetId ?? ''}|${p.roll ?? ''}`;
      case 'READY_UPDATED':
        return `${t}|${ts}|${p.playerId ?? ''}|${p.ready ?? ''}/${p.total ?? ''}`;
      default:
        // fallback très discriminant
        return `${t}|${ts}|${JSON.stringify(p)}`;
    }
  }

  private weatherAdvanceSent = false;
  private phase2AdvanceSent = false;
  private prephase3AdvanceSent = false;
  private phase4AdvanceSent = false;
  private toPhase0AdvanceSent = false;

  // === PREPHASE3 — timer local (30 s non-autoritaire) ===
  prephaseEndsAtMillis: number | null = null;   // timestamp local de fin
  remainingPrePhaseSeconds = 0;                 // affichage "XX s"
  private prephaseTicker: any | null = null;    // setInterval handle
  private static readonly PREPHASE_MS = 30_000;

  // --- Actions ---
  preparedGarlicForThisRaid = false;
  // NET ou PIT, ou null si pas de piège en cours
  actionMode: 'NET' | 'PIT' | 'INCENDIAIRE' | 'PROVOCATION' | 'AMBUSH' | 'BLESSED_STAKE' | 'CHARISMATIQUE' | 'MARCHAND_ITINERANT' | 'MARCHAND_BONUS_BUY' | 'PRESENCE_ECRASANTE' | 'CATACLYSME' | 'CLONES_OMBRE' | 'IMAGE_MIROIR_SETUP' | 'IMAGE_MIROIR_RESOLVE' | 'ECLIPSE' | 'BLOOD_MOON' | 'VOILE_DE_BRUME' | 'FAIM_IRREPRESSIBLE' | 'MARQUE_TENEBREUSE' | 'AFFAIBLISSEMENT_OCCULTE' | 'PASSAGE_SECRET' | 'AVIDITE_NOCTURNE' | 'EAU_BENITE' | null = null;
  actionOwnerId: string | null = null;
  actionLocation: string | null = null;
  trapEnemies: SPlayer[] = [];
  // Pour le FILET ou INCENDIAIRE : cible choisie par le chasseur
  actionSelectedTargetId: string | null = null;
  // Pour la FOSSE : index de la cible en cours dans trapEnemies
  trapCurrentIndex = 0;
  // Résultat courant à afficher dans la modale
  actionRoll: number | null = null;
  actionBreakdownLines: string[] = [];
  // Afficher / cacher la modale
  showActionModal = false;
  // Flag pour éviter les doubles clics pendant l'appel API
  actionResolving = false;

  hadIncendiaireThisPrephase = false;
  // Choix possibles pour INCENDIAIRE (codes d’infra)
  incendiaireChoices: (
    'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE'
  )[] = [];

  ambushEnemies: SPlayer[] = [];
  isSacredRosaryUsed: boolean = false;
  lastCharismRaid: number | null = null;

  clonesSelectedLocations: string[] = [];

  selectedWeather2: string | null = null;
  selectedWeather3: string | null = null;

  weatherSecondChoices: string[] = ['WIND', 'STORM', 'RAIN', 'BLIZZARD'];
  weatherThirdChoices: string[] = ['WIND', 'STORM', 'RAIN', 'BLIZZARD'];


  // Pour éviter de réafficher 15 fois la modale Présence écrasante
  private actionTimeoutId: any = null;
  private lastPresenceRaid: number | null = null;
  private lastEclipseRaid: number | null = null;
  private lastBloodMoonRaid: number | null = null;
  private lastFogRaid: number | null = null;
  private lastCataclysmeRaid: number | null = null;

  // Image miroir
  selectedMirrorLoc: string | null = null;
  mirrorPrimaryLoc: string | null = null;
  mirrorAltLocations: string[] = [];
  mirrorChoices: string[] = [];
  mirrorHuntersByLoc: Record<string, SPlayer[]> = {};

  // Passage secret
  secretPassageChoices: string[] = [];
  selectedSecretPassageLoc: string | null = null;

  // Avidité nocturne
  lastGreedRaid: number | null = null;

  // --- Morsure : état purement UI (pas dans GameSnapshot)
  isRolling = false;
  private biteNotBeforeMillis = 0; // petit délai de lecture avant d'ouvrir la modale
  unstableLockedIds: Set<string> = new Set();

  isUnstableLocked = (unstableId: string) => this.unstableLockedIds.has(unstableId);
  private lockUnstable(id: string)   { this.unstableLockedIds.add(id); }
  private unlockUnstable(id: string) { this.unstableLockedIds.delete(id); }

  // --- Météo: états/temporisations contrôlées côté client ---
  weatherBgActive = false;                // quand true => on affiche le fond météo
  private weatherTimer?: any;             // timer pour le délai AVANT fond
  private weatherPostTimer?: any;         // timer après affichage du fond
  private lastWeatherRollSeen: number | null = null;

  // Hold pour garder la modale visible même si phase != PHASE0
  private weatherModalHold = false;

  private static readonly WEATHER_WAIT_BEFORE_MODAL_MS = 2000;
  readonly WEATHER_WHEEL_MS = 5000;   // durée de la roue
  readonly WEATHER_BG_MS    = 5000;   // fondu du fond
  readonly WEATHER_HOLD_MS  = 5000;   // petite pause lecturegarantie après fond

  readonly CENTER_FLIP_HOLD_MS = 4000;   // petite pause après le flip en PREPHASE3

  private readonly SPECTATE_HOLD_MS = 5000;

  // roue = 12 pales => 30° par pale. Offset pour aligner la pale.
  private static readonly WHEEL_DEG_PER_FACE = 30;
  private static readonly WHEEL_BASE_OFFSET = 15;
  private static readonly WEATHER_ICON_RADIUS = 120;
  
  // --- SHOP (modale Phase 4) --- //
  shopOpen = false;
  waitingDone = false;      // vrai après clic "Ne rien faire"
  phase4LeftSec = 0;
  private phase4TimerId: any = null;

  // Constructions
  buildModalOpen = false;
  buildConfirmModalOpen = false;
  buildChoice: 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE' | null = null;
  effectChoice: 'STUDY' | 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'ALCHEMY' | 'RARE_ALCHEMY' | 'EXPLOSION' | 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING' | 'HEAL' | 'CORRUPT_SOULS' | 'CORRUPT' | 'PURIFY_WATER' | 'FORGE' | null = null;

  // ----- Effet de lieu : modale d'action (Bibliothèque) -----
  locationActionModalOpen = false;
  locationActionKind: 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'HEAL' | 'CORRUPT' | 'FORGE' | null = null;

  // THEFT
  theftTargets: { id: string; username: string; actionsCount: number }[] = [];
  theftSelectedTargetId: string | null = null;
  theftSlots: number[] = [];
  theftSelectedSlotIndex: number | null = null;
  theftSubmitting = false;

  // OMEN
  omenCards: string[] = [];
  omenPlacements: ('TOP' | 'BOTTOM' | null)[] = [];
  omenSubmitting = false;

  // EXPERIMENT
  experimentMonsterType: 'REVENANT' | 'GARGOYLE' | 'ABERRATION' | null = null;
  experimentLocation: string | null = null;
  experimentSubmitting = false;
  experimentPossibleLocations: string[] = [];

  experimentMonsterMeta = {
    REVENANT:   { cost: 100, hp: 5,  atkDice: 'D6',  defDice: 'D4' },
    GARGOYLE:   { cost: 250, hp: 10, atkDice: 'D8',  defDice: 'D6' },
    ABERRATION: { cost: 500, hp: 15, atkDice: 'D12', defDice: 'D8' },
  } as const;

  // ALTAR
  altarTargets: { id: string; username: string; hp: number; corruption: number }[] = [];
  altarSelectedTargetId: string | null = null;
  altarSubmitting = false;

  // ----- FORGE -----
  forgeOptions: ForgeOption[] = [];
  forgeSelectedId: string | null = null;
  forgeSubmitting = false;
  forgeResolvedLabel: string | null = null;

  // Echanges
  selectedTradeTargetId: string | null = null;
  myOffersByTarget: Record<string, Record<string, number>> = {};
  myOffer: Record<string, number> = {};

  private closingUntil: Record<string, number> = {};
  private closingKind:  Record<string, 'ok'|'ko'> = {};

  // Ressources vendables à la boutique (typage littéral)
  readonly sellableResources: ReadonlyArray<'wood'|'herbs'|'stone'|'iron'|'water'> =
    ['wood','herbs','stone','iron','water'] as const;

  // Ressources complètes pour la zone "Mes ressources" de l’échange
  allResources: Array<'gold'|'silver'|'souls'|'wood'|'herbs'|'stone'|'iron'|'water'> =
    ['gold','silver','souls','wood','herbs','stone','iron','water'] as const;

  back(){ this.router.navigate(['/lobby']); }

  private emitPhaseBubble(prev: Phase | undefined, g: GameSnapshot) {
    if (!g?.phase) return;
    if (prev === g.phase && this.lastPhaseNotified === g.phase) return;

    const txt = this.phaseTextFor(g.phase, g);
    if (txt) {
      this.notify.phase(txt);
      this.lastPhaseNotified = g.phase;
    }
  }

  private phaseTextFor(phase: Phase, g: GameSnapshot): string | null {
    const me = g.players?.find(p => p.id === this.meId);
    const role = me?.role; // 'HUNTER' | 'VAMPIRE' | 'SERVANT' | undefined
    const isHunter = role === 'HUNTER';
    const isVampSide = role === 'VAMPIRE' || role === 'SERVANT';

    // "Est-ce qu'au moins un joueur du camp a une carte lieu en main ?"
    const anyHunterHasLoc =
      (g.players || []).some(p => p.role === 'HUNTER' && !p.leftGame && (p.hp ?? 0) > 0 && (p.hand?.length ?? 0) > 0);

    const anyVampSideHasLoc =
      (g.players || []).some(p => (p.role === 'VAMPIRE' || p.role === 'SERVANT') && !p.leftGame && (p.hp ?? 0) > 0 && (p.hand?.length ?? 0) > 0);

    if (phase === 'PHASE0') {
      return 'Tirage météo';
    }

    // PHASE1 = tour chasseurs (lieu)
    if (phase === 'PHASE1') {
      if (isHunter) {
        return 'À vous de jouer !';
      }
      return 'Les chasseurs partent en raid !';
    }

    // PHASE2 = tour vampire/serviteurs (lieu) OU réveil si pas de carte
    if (phase === 'PHASE2') {
      if (isVampSide) {
        return 'À vous de jouer !';
      }
      return 'Le vampire s’éveille !';
    }

    if (phase === 'PREPHASE3' && this.imInUpcomingCombat()) return 'Préparation au combat !';
    
    const hasCombat = g.currentCombat != null;

    if (phase === 'PHASE3') {
      return hasCombat ? 'Résolution des combats' : 'Résolution des récoltes';
    }
    if (phase === 'PHASE4') return 'Fin de raid... Préparez-vous pour le raid suivant !';

    return null;
  }

  private resetHarvestBubbleIfPhaseChanged(prev: GameSnapshot | undefined, g: GameSnapshot) {
    const prevPhase = prev?.phase as Phase | undefined;
    const nextPhase = g?.phase as Phase | undefined;
    if (!nextPhase) return;

    // Dès qu'on entre dans PHASE3 (nouveau raid/phase3), on réarme
    if (prevPhase !== nextPhase && nextPhase === 'PHASE3') {
      this.harvestBubbleShown = false;
    }

    // sécurité : si on sort de PHASE3, on réarme aussi
    if (prevPhase !== nextPhase && nextPhase !== 'PHASE3') {
      this.harvestBubbleShown = false;
    }
  }

  private maybeEmitHarvestBubble(g: GameSnapshot) {
    if (this.harvestBubbleShown) return;
    if (g.phase !== 'PHASE3') return;

    // tant qu'il y a un combat OU une morsure en cours => pas de récolte
    if (g.currentCombat != null) return;
    if (g.currentBite != null) return;

    this.notify.phase('Résolution des récoltes');
    this.harvestBubbleShown = true;
  }

  private toastActionUsed(payload: any) {
    const pid  = payload?.playerId ;
    const code = payload?.type;

    const who = pid ? this.usernameOf(pid) : 'Un joueur';
    const label = this.actionLabelFr(code as any);

    this.notify.toast(`${who} utilise l'action ${label} !`);
  }

  private toastPotionUsed(payload: any) {
    let kind = payload?.type;
    if(
      payload?.type === 'FORCE'
      || payload?.type === 'ENDURANCE'
      || payload?.type === 'VIE'
      || payload?.type === 'FOCALISATION'
      || payload?.type === 'SANGSUE'
    ) {
      kind = 'la potion'
    } else { kind = 'l\'élixir'}


    const pid  = payload?.playerId ;
    const code = payload?.type;

    const who = pid ? this.usernameOf(pid) : 'Un joueur';

    const label = this.potionLabelFr(code as any);

    this.notify.toast(`${who} utilise ${kind} ${label} !`);
  }

  private toastInfraBuilt(payload: any) {
    console.log('payload=', payload);

    const builderId = payload?.builderId ?? payload?.playerId ?? payload?.uid;
    const infra = payload?.infra ?? payload?.code ?? payload?.kind;

    const who = builderId ? this.usernameOf(builderId) : 'Le vampire';
    const what = infra ? (this.labelLocation(infra) || String(infra)) : 'un lieu';

    this.notify.toast(`${who} construit ${what}`);
  }

  leave() {
    if (!this.game) return;

    const ok = window.confirm(
      "Êtes-vous sûr de vouloir abandonner la partie ?\n" +
      "Votre personnage meurt si vous abandonnez."
    );
    if (!ok) return;

    this.api.surrenderGame(this.game.id).subscribe({
      next: () => {
      this.deathModalSeen = false;
      this.deathModalOpen = true;
      },
      error: e => this.showError(e)
    });
  }

  observeAfterDeath() {
    this.deathModalOpen = false;
    this.deathModalSeen = true;
  }

  leaveAfterDeath() {
    if (!this.game) { this.router.navigate(['/lobby']); return; }

    // là on quitte vraiment
    this.api.leaveGame(this.game.id).subscribe({
      next: () => {
        sessionStorage.removeItem('gameId');
        this.router.navigate(['/lobby']);
      },
      error: e => this.showError(e)
    });
  }

  leaveAfterEnd() {
    if (!this.game) { this.router.navigate(['/lobby']); return; }

    this.api.leaveGame(this.game.id).subscribe({
      next: () => {
        sessionStorage.removeItem('gameId');
        this.router.navigate(['/lobby']);
      },
      error: e => this.showError(e)
    });
  }

  get isGameEnded(): boolean {
    const g = this.game;
    return !!g && g.status === 'ENDED' && !!g.winnerSide;
  }

  winnerTitle(): string {
    const g = this.game;
    if (!g || !g.winnerSide) return 'Fin de partie';

    switch (g.winnerSide) {
      case 'HUNTERS':
        return 'Victoire des chasseurs';
      case 'VAMPIRE':
        return 'Victoire du vampire';
      default:
        return 'Fin de partie';
    }
  }

  winnerSubtitle(): string {
    const g = this.game;
    if (!g || !g.winnerSide) return '';

    switch (g.winnerSide) {
      case 'HUNTERS':
        return 'Le vampire est terrassé. Les chasseurs triomphent.';
      case 'VAMPIRE':
        return 'Plus aucun chasseur n’est debout. Le vampire règne sans partage.';
      default:
        return '';
    }
  }

  private showError(e:any){
    try{ this.errorMsg = e?.error?.message || 'Erreur'; } catch { this.errorMsg='Erreur'; }
    setTimeout(()=>this.errorMsg='', 4000);
  }

  private advanceToPhase1IfNeeded() {
    if (this.weatherAdvanceSent) return;
    if (!this.game?.id) return;
    if (this.game.phase !== 'PHASE0') return;

    this.weatherAdvanceSent = true;
    this.api.advancePhase(this.game.id, 'PHASE1').subscribe({
      error: (httpError) => {
        // si un autre client a déjà avancé (409), on ignore
        if (httpError?.status !== 409) {
          this.weatherAdvanceSent = false; // autorise un retry si vraie erreur
          this.showError(httpError);
        }
      }
    });
  }

  /** Renvoie les compteurs "prêts/total" d'après l'état courant. */
  private readyCounts(): { ready: number; total: number } {
    const g: any = this.game || {};
    const ready = (typeof g.readyCount === 'number') ? g.readyCount : (g.readyForPhase3?.length || 0);
    const total = (typeof g.readyTotal === 'number') ? g.readyTotal : (g.players?.length || 0);
    return { ready, total };
  }

  /** Lance (ou relance) le timer PREPHASE3 local. */
  private startPrephaseTimer(seedEndsAt?: number): void {
    // Si on donne une seed (ex: relance après reload), on la respecte, sinon on part de maintenant + 30s
    this.prephaseEndsAtMillis = seedEndsAt ?? (Date.now() + GameComponent.PREPHASE_MS);

    // Nettoie un éventuel ancien interval
    if (this.prephaseTicker) {
      clearInterval(this.prephaseTicker);
      this.prephaseTicker = null;
    }

    // Tick 4x/s pour un affichage fluide
    this.prephaseTicker = setInterval(() => {
      if (!this.prephaseEndsAtMillis) return;

      const left = this.prephaseEndsAtMillis - Date.now();
      this.remainingPrePhaseSeconds = Math.max(0, Math.ceil(left / 1000));

      if (left <= 0) {
        this.stopPrephaseTimer();
        this.remainingPrePhaseSeconds = 0;
        // ❌ On ne fait rien ici (pas d’auto-advance côté front) :
        // le serveur forcera PHASE3 via son propre timer, et on recevra PHASE_CHANGED en WS.
      }
    }, 250);
  }

  /** Stoppe le timer local et remet à zéro les valeurs. */
  private stopPrephaseTimer(): void {
    if (this.prephaseTicker) {
      clearInterval(this.prephaseTicker);
      this.prephaseTicker = null;
    }
    this.prephaseEndsAtMillis = null;
    this.remainingPrePhaseSeconds = 0;
  }

  private startPhase4Timer(deadlineMillis?: number | null) {
    this.stopPhase4Timer();
    const deadline = deadlineMillis ?? (Date.now() + 120000); // fallback robuste
    this.phase4TimerId = setInterval(() => {
      const ms = Math.max(0, deadline - Date.now());
      this.phase4LeftSec = Math.ceil(ms / 1000);
      if (ms <= 0) this.stopPhase4Timer();
    }, 1000);
  }

  private stopPhase4Timer() {
    if (this.phase4TimerId) { clearInterval(this.phase4TimerId); this.phase4TimerId = null; }
    this.phase4LeftSec = 0;
  }

  /** Déclenche automatiquement l'avance vers PHASE3 si tout le monde est prêt. */
  private maybeAutoAdvanceToPhase3(): void {
    if (!this.game) return;
    if (this.game.phase !== 'PREPHASE3') return;
    if (this.prephase3AdvanceSent) return;

    const { ready, total } = this.readyCounts();
    if (total > 0 && ready >= total) {
      this.prephase3AdvanceSent = true;
      // petit hold UX (facultatif)
      setTimeout(() => {
        this.api.advancePhase(this.gameId, 'PHASE3').subscribe({
        error: e => {
          if (e?.status === 409 || e?.error?.message === 'illegal advance') return;
          this.prephase3AdvanceSent = false;
          this.showError(e);
        }
        });
      }, 400);
    }
  }  

  /** Avance automatiquement en PHASE4 quand PHASE3 n’a plus rien à traiter. */
  private maybeAdvanceToPhase4EndOfRaid() {
    const g = this.game;
    if (!g || g.phase !== 'PHASE3') return;
    if (this.phase4AdvanceSent) return;

    const noCurrent = !g.currentCombat && g.currentCombatIndex == null;
    const queueEmpty = !g.combatsQueue || g.combatsQueue.length === 0;

    if (noCurrent || queueEmpty) {
      this.phase4AdvanceSent = true;
      setTimeout(() => {
        this.api.advancePhase(this.gameId, 'PHASE4').subscribe({
          error: e => { this.phase4AdvanceSent = false; this.showError(e); }
        });
      }, this.SPECTATE_HOLD_MS); // petit temps de lecture du dernier breakdown
    }
  }

  /** Patch “flip” local des cartes (utilisé quand on reçoit CENTER_REVEALED). */
  private flipCenterFaceUpLocally() {
    if (!this.game) return;
    this.game.center = this.game.center.map(c => ({ ...c, faceUp: true }));
    this.game = { ...(this.game as any) };
  }

  private scheduleActionAutoClose() {
    if (this.actionTimeoutId) {
      clearTimeout(this.actionTimeoutId);
    }

    this.actionTimeoutId = setTimeout(() => {
      this.showActionModal = false;
      this.actionTimeoutId = null;
      this.maybeAutoAdvanceToPhase3();
    }, 5000);
  }

  // assets card style
  private diceToTier(d?: string): 0 | 1 | 2 | 3 {
    const s = (d || 'D6').toUpperCase();
    if (s.includes('20')) return 3;
    if (s.includes('12')) return 2;
    if (s.includes('8'))  return 1;
    return 0;
  }

  // Hunters: T1+ => BLEED / STUN / RANGE selon player.weapon
  private hunterWeaponType(weapon?: string): 'BLEED' | 'STUN' | 'RANGE' {
    const w = (weapon || '').toUpperCase();
    // stun
    if (w.includes('MACE') || w.includes('HAMMER') || w.includes('FLAIL')) return 'STUN';
    // range
    if (w.includes('SPEAR') || w.includes('CROSSBOW') || w.includes('PISTOL')) return 'RANGE';
    // bleed par défaut (SWORD / HALBERD / WRIST_BLADES / ou inconnu)
    return 'BLEED';
  }

  weaponImg(p: any): string {
    const tier = this.diceToTier(p?.attackDice);
    const isVamp = p?.role === 'VAMPIRE' || p?.role === 'SERVANT'

    if (isVamp) {
      return `/assets/cards/stuff/W_T${tier}_VAMP.png`;
    }

    // Hunter (et SERVANT si jamais): T0 n’a pas de type
    if (tier === 0) {
      return `/assets/cards/stuff/W_T0_HUNTER.png`;
    }

    const type = this.hunterWeaponType(p?.weapon);
    return `/assets/cards/stuff/W_T${tier}_${type}_HUNTER.png`;
  }

  armorImg(p: any): string {
    const tier = this.diceToTier(p?.defenseDice);
    const isVamp = p?.role === 'VAMPIRE' || p?.role === 'SERVANT'
    return isVamp
      ? `/assets/cards/stuff/A_T${tier}_VAMP.png`
      : `/assets/cards/stuff/A_T${tier}_HUNTER.png`;
  }

  private readonly HUNTER_ACTIONS_DIR = '/assets/cards/hunter_actions/';
  private readonly VAMP_ACTIONS_DIR   = '/assets/cards/vampire_actions/';
  private readonly POTIONS_DIR        = '/assets/cards/potions/';

  /** Action -> image (dossier dépend du rôle du joueur courant) */
  actionImg(code: string | null | undefined, role: string | null | undefined = undefined): string {
    if (!code) return '';
    let base;

    if(role === 'HUNTER'){
      base = this.VAMP_ACTIONS_DIR;
    } else if(role === 'VAMPIRE' || role === 'SERVANT'){
      base = this.HUNTER_ACTIONS_DIR;
    } else {
      base = (this.me?.role === 'VAMPIRE')
      ? this.VAMP_ACTIONS_DIR
      : this.HUNTER_ACTIONS_DIR;
    }


    return base + this.actionFile(code);
  }

  /** Map des exceptions + fallback auto */
  private actionFile(code: string): string {
    switch (code) {
      // --- HUNTER ---
      case 'EAU_BENITE':       return 'eau_benite.png';
      case 'FUMIGATION_AIL':   return 'fumigation_ail.png';
      case 'PISTEUR':          return 'pistage.png';
      case 'FEU_DE_CAMP':      return 'feu_de_camp.png';
      case 'NET':              return 'net.png';
      case 'PIT':              return 'pit.png';
      case 'PROVOCATION':      return 'provocation.png';
      case 'INCENDIAIRE':      return 'incendiaire.png';
      case 'AMBUSH':           return 'ambush.png';
      case 'BLESSED_STAKE':    return 'blessed_stake.png';
      case 'SACRED_ROSARY':    return 'sacred_rosary.png';
      case 'CHARISMATIQUE':    return 'charismatique.png';
      case 'MARCHAND_ITINERANT':
      case 'MARCHAND_BONUS_BUY':
        return 'marchand_itinerant.png';

      // --- VAMPIRE ---
      case 'AFFAIBLISSEMENT_OCCULTE': return 'affaiblissement_occulte.png';
      case 'CLONES_OMBRE':           return 'clones_ombre.png';
      case 'MARQUE_TENEBREUSE':      return 'marque_tenebreuse.png';
      case 'AVIDITE_NOCTURNE':       return 'avidite_nocturne.png';
      case 'ECLIPSE':                return 'eclipse.png';
      case 'PASSAGE_SECRET':         return 'passage_secret.png';
      case 'BLOOD_MOON':             return 'blood_moon.png';
      case 'FAIM_IRREPRESSIBLE':     return 'faim_irrepressible.png';
      case 'PRESENCE_ECRASANTE':     return 'presence_ecrasante.png';
      case 'CATACLYSME':             return 'cataclysme.png';
      case 'VOILE_DE_BRUME':         return 'voile_de_brume.png';

      case 'IMAGE_MIROIR':
      case 'IMAGE_MIROIR_SETUP':
      case 'IMAGE_MIROIR_RESOLVE':
        return 'image_miroir.png';

      default:
        // fallback auto: "PRESENCE_ECRASANTE" -> "presence_ecrasante.png"
        return code.toLowerCase() + '.png';
    }
  }

  /** Potion/Elixir -> image */
  potionImg(code: string | null | undefined): string {
    if (!code) return '';
    return this.POTIONS_DIR + code.toLowerCase() + '.png';
  }

  potionBackSrc = '/assets/cards/potion_verso.png';

  actionBackSrc(p: any): string {
    // Servant -> verso hunter (tu peux changer si tu veux)
    return p?.role === 'VAMPIRE'
      ? '/assets/cards/vampire_verso.png'
      : '/assets/cards/hunter_verso.png';
  }

  actionCount(p: any): number {
    return (p?.actions?.length ?? 0);
  }

  consumablesCount(p: any): number {
    const pot = (p?.potions?.length ?? 0);
    const eli = (p?.elixirs?.length ?? 0);
    return pot + eli;
  }

  isHunterId(playerId: string | null | undefined): boolean {
    if (!playerId) return false;
    const ps = this.game?.players;
    if (!ps) return false;

    for (const p of ps) {
      if (p.id === playerId) return p.role === 'HUNTER';
    }
    return false;
  }

  deckCount(pile: any): number {
    return pile?.deck ?? 0;
  }

  discardCards(pile: any): string[] {
    return Array.isArray(pile?.discardCards) ? pile.discardCards : [];
  }

  discardCount(pile: any): number {
    return this.discardCards(pile).length;
  }

  lastDiscardId(pile: any): string | null {
    const xs = this.discardCards(pile);
    return xs.length ? xs[xs.length - 1] : null;
  }

  private readonly ELIXIRS_DIR = '/assets/cards/elixirs/';

  elixirImg(code: string | null | undefined): string {
    if (!code) return '';
    return this.ELIXIRS_DIR + code.toLowerCase() + '.png';
  }

  discardImgFor(kind: 'HUNTER_ACTIONS'|'VAMP_ACTIONS'|'POTIONS'|'ELIXIRS', pile: any): string {
    const id = this.lastDiscardId(pile);
    if (!id) return '';

    switch (kind) {
      case 'HUNTER_ACTIONS': return this.HUNTER_ACTIONS_DIR + this.actionFile(id);
      case 'VAMP_ACTIONS':   return this.VAMP_ACTIONS_DIR + this.actionFile(id);
      case 'POTIONS':        return this.potionImg(id);
      case 'ELIXIRS':        return this.elixirImg(id);
    }
  }

  deckBackFor(kind: 'HUNTER_ACTIONS'|'VAMP_ACTIONS'|'POTIONS'|'ELIXIRS'): string {
    switch (kind) {
      case 'HUNTER_ACTIONS': return '/assets/cards/hunter_verso.png';
      case 'VAMP_ACTIONS':   return '/assets/cards/vampire_verso.png';
      case 'POTIONS':        return this.potionBackSrc;
      case 'ELIXIRS':        return this.potionBackSrc;
    }
  }

  // ---- BONUS (marchand) : coûts + image ----
  bonusResCost(): { water?: number; herbs?: number; wood?: number; iron?: number, silver?: number } | null {
    const kind = this.game?.shopBonusKind;
    if (!kind) return null;

    switch (kind) {
      case 'POTION':        return { water: 4, herbs: 3 };
      case 'ELIXIR':        return { water: 6, herbs: 6 };
      case 'EQUIP_WEAPON':
      case 'EQUIP_ARMOR':   return { wood: 6, iron: 6 };
      default:              return null;
    }
  }

  bonusGoldCost(): number | null {
    const g: any = this.game;
    const me: any = this.me;
    const kind = g?.shopBonusKind;
    if (!g || !me || !kind) return null;

    const greedy      = !!g.shopPricesIncreasedThisRaid;
    const charismatic = !!me.charismaticThisRaid;

    const computeCost = (base: number) => {
      let c = base;
      if (greedy) c += 50;
      if (charismatic) c = Math.max(0, c - 20);
      return c;
    };

    switch (kind) {
      case 'POTION':        return computeCost(60);
      case 'ELIXIR':        return computeCost(120);
      case 'EQUIP_WEAPON':
      case 'EQUIP_ARMOR':   return computeCost(150);
      default:              return null;
    }
  }

bonusBuyImgSrc(): string {
  const kind = this.game?.shopBonusKind;
  if (!kind) return '';

  switch (kind) {
    case 'POTION':
    case 'ELIXIR':
      return this.deckBackFor('POTIONS');

    case 'EQUIP_WEAPON':
    case 'EQUIP_ARMOR':
      return this.deckBackFor('HUNTER_ACTIONS');

    default:
      return this.deckBackFor('HUNTER_ACTIONS');
  }
}

  // assets zoom
  zoomBadgeIsHunter: boolean | undefined = undefined;

  zoomOn = false;
  zoomStyle: any = {};
  zoomSrc = '';

  zoomBadgeOn = false;
  zoomBadgeText = '';

  private zoomW = 0;
  private zoomH = 0;

  // Zoom moyen (défausse / cartes normales) => inchangé
  private readonly zoomScale = 2.2;
  private readonly zoomMaxSide = 340;

  // ✅ Zoom large (défausse)
  private readonly zoomScaleLarge = 4.0;
  private readonly zoomMaxSideLarge = 420;

  // zoom info lieu
  zoomMediaW = 0;
  zoomMediaH = 0;

  zoomInfoOn = false;
  zoomInfoKey = '';
  zoomInfoLinesHunter: string[] = [];
  zoomInfoLinesVamp: string[] = [];
  zoomInfoNote = '';
  zoomInfoLines: string[] = [];

  private readonly zoomInfoW = 340; // largeur du panneau info
  private readonly zoomInfoGap = 10; // espace entre image et info

  zoomEnter(
    ev: MouseEvent,
    badge?: string | number,
    badgeIsHunter?: boolean,
    size: 'M'|'L' = 'M',
    info?: { key: string; lines: string[] } | null
  ) {
    const host = ev.currentTarget as HTMLElement | null;
    if (!host) return;

    const img = (host.tagName === 'IMG'
      ? (host as HTMLImageElement)
      : (host.querySelector('img') as HTMLImageElement | null));

    if (!img?.src) return;

    this.zoomSrc = img.src;

    // badge (inchangé)
    if (badge !== undefined && badge !== null && String(badge).trim() !== '') {
      this.zoomBadgeOn = true;
      this.zoomBadgeText = String(badge);
      this.zoomBadgeIsHunter = badgeIsHunter;
    } else {
      this.zoomBadgeOn = false;
      this.zoomBadgeText = '';
      this.zoomBadgeIsHunter = undefined;
    }

    // info (NOUVEAU)
    if (info && info.lines?.length) {
      this.zoomInfoOn = true;
      this.zoomInfoKey = (info as any).key || '';
      this.zoomInfoLines = info.lines;

      if (this.zoomInfoKey === 'altar' && this.zoomInfoLines.length >= 5) {
        this.zoomInfoLinesHunter = this.zoomInfoLines.slice(0, 2);
        this.zoomInfoLinesVamp   = this.zoomInfoLines.slice(2, 4);
        this.zoomInfoNote        = this.zoomInfoLines[4] || '';
      } else {
        this.zoomInfoLinesHunter = [];
        this.zoomInfoLinesVamp   = [];
        this.zoomInfoNote        = '';
      }

    } else {
      this.zoomInfoOn = false;
      this.zoomInfoKey = '';
      this.zoomInfoLines = [];
      this.zoomInfoLinesHunter = [];
      this.zoomInfoLinesVamp = [];
      this.zoomInfoNote = '';
    }

    const rect = img.getBoundingClientRect();

    let scale = this.zoomScale;
    let maxSide = this.zoomMaxSide;
    if (size === 'L') { scale = this.zoomScaleLarge; maxSide = this.zoomMaxSideLarge; }

    let w = rect.width * scale;
    let h = rect.height * scale;

    const max = Math.max(w, h);
    if (max > maxSide) {
      const k = maxSide / max;
      w *= k;
      h *= k;
    }

    this.zoomW = Math.round(w);
    this.zoomH = Math.round(h);

    // exposé au template
    this.zoomMediaW = this.zoomW;
    this.zoomMediaH = this.zoomH;

    this.zoomOn = true;
    this.zoomMove(ev);
  }

zoomMove(ev: MouseEvent) {
  if (!this.zoomOn) return;

  const mediaW = this.zoomW;
  const mediaH = this.zoomH;

  // largeur "réelle" à protéger à l'écran (image + panneau si présent)
  const totalW = mediaW + (this.zoomInfoOn ? (this.zoomInfoGap + this.zoomInfoW) : 0);
  const totalH = mediaH;

  let left = ev.clientX + 12;
  let top  = ev.clientY + 12;

  const vw = window.innerWidth;
  const vh = window.innerHeight;

  // clamp avec totalW/totalH
  if (left + totalW + 8 > vw) left = vw - totalW - 8;
  if (top + totalH + 8 > vh)  top  = vh - totalH - 8;

  // ✅ IMPORTANT : on n'agrandit plus la box, elle reste à la taille de l'image
  this.zoomStyle = {
    left: left + 'px',
    top: top + 'px',
    width: mediaW + 'px',
    height: mediaH + 'px',
  };
}


  zoomLeave() {
    this.zoomOn = false;
    this.zoomSrc = '';
    this.zoomBadgeOn = false;
    this.zoomBadgeText = '';
    this.zoomBadgeIsHunter = undefined;
    this.zoomInfoOn = false;
    this.zoomInfoOn = false;
    this.zoomInfoKey = '';
    this.zoomInfoLinesHunter = [];
    this.zoomInfoLinesVamp = [];
    this.zoomInfoNote = '';
    this.zoomInfoLines = [];
  }

  // === Helpers center history ===
  @ViewChild('historyBox') historyBox?: ElementRef<HTMLDivElement>;
  historyHover = false;
  private lastHistorySize = 0;

  /** Groupement dynamique par (raid, phase) */
  historyGroups(){
    const hist = this.game?.history || [];
    interface Group { raid:number; phase:string; phaseNum:string; items: typeof hist; }
    const out: Group[] = [];
    let curKey = '';
    let cur: Group | null = null;

    const phaseNum = (p:string) => p.startsWith('PHASE') ? p.substring(5) : p;

    for (const it of hist) {
      const key = `${it.raid}|${it.phase}`;
      if (key !== curKey) {
        curKey = key;
        cur = { raid: it.raid, phase: it.phase, phaseNum: phaseNum(it.phase), items: [] as any };
        out.push(cur);
      }
      cur!.items.push(it);
    }
    return out;
  }

  private bumpHistoryScroll(){
    const newSize = this.game?.history?.length || 0;
    const grew = newSize > this.lastHistorySize;
    this.lastHistorySize = newSize;
    if (!grew) return;

    // Laisse Angular peindre puis scroll seulement le conteneur
    requestAnimationFrame(() => {
      const box = this.historyBox?.nativeElement;
      if (box) box.scrollTop = box.scrollHeight;
    });
  }

  // === Helpers buff/debuff ===
  // mêmes règles que modsForDisplay, mais en filtrant aussi par STAT
  modsForStat(p: SPlayer | undefined, stat: 'ATTACK'|'DEFENSE'): RawStatMod[] {
    if (!p || !this.game?.raidMods) return [];
    const list = this.game.raidMods[p.id] || [];

    const weatherActive    = this.isWeatherActive();
    const weatherCancelled = this.isWeatherCancelledForPlayer(p);

    const out: RawStatMod[] = list.filter(m => {
      if (m.stat !== stat) return false;

      const src = m.source || '';
      const isWeather    = src.startsWith('WEATHER:');
      const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');

      // on masque les mods CORRUPTION:...:ENG (comme avant)
      if (isCorruptEng) return false;

      // on masque les mods météo :
      // - si aucune météo active
      // - OU si la météo est annulée pour ce joueur (Feu de camp)
      if (isWeather) {
        if (!weatherActive) return false;
        if (weatherCancelled) return false;
      }

      return true;
    });

    // 1) Corruption DSP : puce d’état
    const dsp = list.filter(mm =>
      mm.source?.startsWith('CORRUPTION:') && mm.source?.includes(':DSP')
    );
    if (dsp.length) {
      out.push(dsp[0]); // une seule puce d’état
    } else {
      // 2) Sinon, fallback sur ta puce synthétique front (si le niveau est dispo)
      const statusChip = this.corruptionDisplayChip(p);
      if (statusChip) out.push(statusChip);
    }

    // 3) Potions DSP (FOCALISATION + nouvelles)
    const dspPotions = list.filter(mm =>
      mm.source?.startsWith('POTION:') &&
      mm.source?.includes(':DSP')
    );

    for (const pm of dspPotions) {
      const src = (pm as any).source as string;

      const isFoca  = src.includes('FOCALISATION');
      const isLeech = src.includes('SANGSUE');
      const isRage  = src.includes('RAGE');
      const isResi  = src.includes('RESILIENCE');
      const isRap   = src.includes('RAPIDITE');
      const isInv   = src.includes('INVISIBILITE');
      const isInvul = src.includes('INVULNERABILITE');

      let shouldShow = false;

      if (isFoca) {
        // Focalisation : visible sur ATTACK + DEFENSE
        shouldShow = true;
      } else if (stat === 'ATTACK' && (isLeech || isRage || isRap)) {
        // Effets d’attaque
        shouldShow = true;
      } else if (stat === 'DEFENSE' && (isResi || isInv || isInvul)) {
        // Effets de défense
        shouldShow = true;
      }

      if (shouldShow && !out.includes(pm)) {
        out.push(pm);
      }
    }

    return out;
  }

  modsForEntityStat(id: string, stat: 'ATTACK'|'DEFENSE'): RawStatMod[] {
    const p = this.getPlayer(id);
    if (p) return this.modsForStat(p, stat);

    // Monstre
    if (!this.game?.raidMods) return [];
    const list = this.game.raidMods[id] || [];

    return list.filter(m => {
      if (m.stat !== stat) return false;

      const src = m.source || '';
      const isWeather    = src.startsWith('WEATHER:');
      const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');

      // pas de météo sur les monstres, ni CORRUPTION:...:ENG
      if (isWeather)    return false;
      if (isCorruptEng) return false;

      return true;
    });
  }

  chipOf(m: UiStatMod): string {
    if (m.labelFr) return m.labelFr;
    if (m.stat === 'MULTIPLE') return 'affaibli'; // fallback
    if (m.stat === 'INSTABLE') return 'instable'; // fallback

    const short = m.stat === 'ATTACK' ? 'ATK' : 'DEF';
    const sign = m.amount > 0 ? `+${m.amount}` : `${m.amount}`;
    return `${short}${sign}`;
  }

  // Mods à AFFICHER (tous stats confondues)
  modsForDisplay(p?: SPlayer): RawStatMod[] {
    if (!p || !this.game?.raidMods) return [];
    const list = this.game.raidMods[p.id] || [];

    const weatherActive    = this.isWeatherActive();
    const weatherCancelled = this.isWeatherCancelledForPlayer(p);

    const filtered = list.filter(m => {
      const src = m.source || '';
      const isWeather    = src.startsWith('WEATHER:');
      const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');

      if (isCorruptEng) return false;

      if (isWeather) {
        if (!weatherActive) return false;
        if (weatherCancelled) return false;
      }

      return true;
    });

    return filtered;
  }

  private totalModForDisplay(pId: string, stat: 'ATTACK'|'DEFENSE'): number {
  const p = this.getPlayer(pId);
  if (p) {
    const mods = this.modsForDisplay(p);
    return mods.reduce((sum, m) => sum + (m.stat === stat ? m.amount : 0), 0);
  }

  // Monstre : somme simple des mods pertinents (sans météo ni CORRUPTION:...:ENG)
  if (!this.game?.raidMods) return 0;
  const list = this.game.raidMods[pId] || [];

  return list.reduce((sum, m) => {
    if (m.stat !== stat) return sum;
    const src = m.source || '';
    const isWeather    = src.startsWith('WEATHER:');
    const isCorruptEng = src.startsWith('CORRUPTION') && src.includes(':ENG');
    if (isWeather || isCorruptEng) return sum;
    return sum + m.amount;
  }, 0);
}

  // Construit UN chip d’affichage : MULTIPLE (L1), INSTABLE (L2) ou SERVITEUR (L3)
  private corruptionDisplayChip(p?: SPlayer): UiStatMod | null {
    const lvl = p?.corruption;
    if (lvl === 1) {
      return {
        stat: 'MULTIPLE',
        amount: 0,
        source: 'CORRUPTION:L1:DSP',
        labelFr: 'affaibli',
        displayOnly: true
      };
    }
    if (lvl === 2) {
      return {
        stat: 'INSTABLE',
        amount: 0,
        source: 'CORRUPTION:L2:DSP',
        labelFr: 'instable',
        displayOnly: true
      };
    }
    if (lvl === 3) {
      return {
        stat: 'SERVITEUR',
        amount: 0,
        source: 'CORRUPTION:L3:DSP',
        labelFr: 'serviteur',
        displayOnly: true
      };
    }
    return null;
  }

  labelOrChip(m: RawStatMod): string {
    const s = (m as any).source || '';

    // DSP Corruption
    if (s.startsWith('CORRUPTION:') && s.includes(':DSP')) {
      if (s.includes(':MARK:')) {
        return 'marqué';
      }
      switch ((m as any).stat) {
        case 'MULTIPLE':  return 'affaibli';
        case 'INSTABLE':  return 'instable';
        case 'SERVITEUR': return 'serviteur';
      }
    }

    // Potions DSP
    if (s.startsWith('POTION:') && s.includes(':DSP')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'FOCALISATION':    return 'focalisation';
        case 'SANGSUE':         return 'sangsue';
        case 'RESILIENCE':      return 'résilience';
        case 'RAGE':            return 'rage';
        case 'RAPIDITE':        return 'rapidité';
        case 'INVISIBILITE':    return 'invisibilité';
        case 'INVULNERABILITE': return 'invulnérabilité';
      }
    }

    // ACTION DSP
    if (s.startsWith('ACTION:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'PROVOCATION':        return 'provoqué';
        case 'BLESSED_STAKE':      return 'pieu béni';
        case 'SACRED_ROSARY':      return 'chapelet sacré';
      }
    }

        // Équipement DSP / ENG (EQUIP:...)
    if (s.startsWith('EQUIP:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'BLEED_WEAPON':       return 'arme tranchante';
        case 'STUN_WEAPON':        return 'arme contondante';
        case 'RANGED_WEAPON':      return 'arme à distance';
        case 'HUNTER_ARMOR':       return 'armure sacrée';
        case 'VAMPIRE_WEAPON':     return 'arme vampirique';
        case 'VAMPIRE_ARMOR_T3':   return 'armure vampirique';
      }
    }

    // Effets de coup (HIT:...)
    if (s.startsWith('HIT:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      switch (type) {
        case 'BLEED_WEAPON':   return 'saigne';
        case 'RANGED_WEAPON':  return 'tenu à distance';
      }
    }

    if (s.startsWith('WEATHER:WIND:')) {
      return 'cyclone';
    }


    return (m as any).labelFr || this.chipOf(m);
  }

  titleFor(m: RawStatMod): string | null {
    const s = (m as any).source || '';

    // Corruption (DSP)
    if (s.startsWith('CORRUPTION:') && s.endsWith(':DSP')) {
      if (s.includes(':L1:')) return 'Attaque et défense diminuées de 1 (persiste entre les raids).';
      if (s.includes(':L2:')) return 'Peut se retourner contre ses alliés sur un jet défavorable.';
      if (s.includes(':L3:')) return 'Ce chasseur est un serviteur du vampire';
      if (s.includes(':MARK:')) {
        return 'Marque ténébreuse: ce chasseur gagne +1 corruption à chaque raid où il croise le vampire, jusqu’à purification.';
      }
      return 'Effet de corruption';
    }

    if (s.startsWith('WEATHER:')) {
      // Cas spé : Vent violent DSP avec distinction côté chasseur / domaine
      if (s.startsWith('WEATHER:WIND:HUNTER')) {
        return 'Réparations du village: ce chasseur perd 1 ressource aléatoire (bois, fer ou pierre).';
      }
      if (s.startsWith('WEATHER:WIND:VAMP')) {
        return 'Réparations du domaine: perte de ressources aléatoires pour chaque construction.';
      }

      // Fallback générique (comme avant)
      const g = this.game;
      const w = g?.weather;
      if (!w) return 'Effets météo';

      const parts = s.split(':');
      const code = parts.length >= 2 ? parts[1] : null; // "RAIN", "FULL_MOON", ...

      // Si on a une météo secondaire (Cataclysme)
      if (code && w.secondaryStatus) {
        // 1) Statut principal
        if (code === w.status && w.nameFr) {
          return w.descFr
            ? `${w.nameFr} — ${w.descFr}`
            : w.nameFr;
        }

        // 2) Statut secondaire
        if (code === w.secondaryStatus && w.secondaryNameFr) {
          return w.secondaryDescFr
            ? `${w.secondaryNameFr} — ${w.secondaryDescFr}`
            : w.secondaryNameFr;
        }
      }

      // Fallback MONO météo (comportement ancien)
      if (w.nameFr && w.descFr) {
        return `${w.nameFr} — ${w.descFr}`;
      }
      if (w.nameFr) return w.nameFr;

      return 'Effets météo';
    }

    if (s.startsWith('POTION:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltips: Record<string, string> = {
        FORCE:           'Augmente de +1 le dé d’attaque.',
        ENDURANCE:       'Augmente de +1 le dé de défense.',
        VIE:             'Se soigner de +10 PV.',
        FOCALISATION:    'Lancer 2 dés lors des combat et garder le meilleur.',
        SANGSUE:         'Se soigner d’un montant égal aux dégats infligés.',
        RESILIENCE:      'Double la défense.',
        RAGE:            'Double l’attaque.',
        RAPIDITE:        'Attaque x2.',
        INVISIBILITE:    'L\'adversaire ne jette pas de dé de défense.',
        INVULNERABILITE: 'Insensible aux dégâts.',
      };
      return tooltips[type] ?? null;
    }

    if (s.startsWith('ACTION:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltips: Record<string, string> = {
        PROVOCATION:    'Forcé d\'attaquer un seul chasseur.',
        BLESSED_STAKE:  'Arme secondaire sacrée à utilisation unique.',
        SACRED_ROSARY:  'Objet à utilisation unique qui annule une morsure réussie.'
      };
      return tooltips[type] ?? null;
    }
    
        // Effets d'équipement (EQUIP:...)
    if (s.startsWith('EQUIP:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltipsEquip: Record<string, string> = {
        BLEED_WEAPON:     'Cette arme provoque un saignement lorsqu’elle inflige des dégâts.',
        STUN_WEAPON:      'Cette arme peut étourdir sa cible et réduire son attaque au prochain tour.',
        RANGED_WEAPON:    'Cette arme peut tenir le vampire à distance sur une riposte avec un mauvais jet.',
        HUNTER_ARMOR:     'Cette armure réduit les risques liés aux morsures du vampire.',
        VAMPIRE_WEAPON:   'Cette arme peut soigner le vampire lorsqu’il inflige des dégâts.',
        VAMPIRE_ARMOR_T3: 'Cette armure permet de se dématerialiser et esquiver une attaque critique.'
      };
      return tooltipsEquip[type] ?? 'Effet d’équipement';
    }

    // Effets déclenchés par un coup (HIT:...)
    if (s.startsWith('HIT:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltipsHit: Record<string, string> = {
        BLEED_WEAPON:   'Ce personnage saigne et subira des dégâts supplémentaires en phase 4.',
        RANGED_WEAPON:  'Cette attaque a été repoussée par une arme à distance.',
        STUN_WEAPON:    'Ce personnage est étourdi et sa prochaine attaque est réduite.'
      };
      return tooltipsHit[type] ?? 'Effet de coup spécial';
    }

    // (Garde le reste de tes cas, ex. météo si tu l’avais déjà ajouté)
    return null;
  }

  private isWeatherActive(): boolean {
    const g = this.game;
    return !!g && g.weather?.roll != null && !!g.weather.status;
  }

  // === Helpers combat ===
  nameOrId(id: string): string {
    return this.entityDisplayName(id);
  }

  /** SPECTATE & ROLL: affiche le nom joueur dans sa colonne */
  modalTitle(r: any): string {
    const atkPlayer = this.getPlayer(r.attackerId);
    const defPlayer = this.getPlayer(r.defenderId);

    const atkMonster = this.getMonster(r.attackerId);
    const defMonster = this.getMonster(r.defenderId);

    const isClone = !!r.cloneAttack; // 🔹 flag envoyé par le back

    // --- CAS CLONE DES OMBRES ---
    if (isClone && atkPlayer && defPlayer) {
      const atkIsVamp   = atkPlayer.role === 'VAMPIRE';
      const defIsHunter = defPlayer.role === 'HUNTER';

      if (atkIsVamp && defIsHunter) {
        const hunterName = this.entityDisplayName(r.defenderId);
        return `Clone d'ombre vs ${hunterName}`;
      }
    }

    // Cas 1 : EXACTEMENT un monstre dans le duel → on met le joueur à gauche
    if (atkMonster && !defMonster && defPlayer) {
      return `${this.entityDisplayName(r.defenderId)} vs ${this.entityDisplayName(r.attackerId)}`;
    }
    if (defMonster && !atkMonster && atkPlayer) {
      return `${this.entityDisplayName(r.attackerId)} vs ${this.entityDisplayName(r.defenderId)}`;
    }

    // Cas 2 : duel 100% joueurs → logique vampire/hunter comme avant
    if (atkPlayer && defPlayer) {
      const vampireLeft = atkPlayer.role === 'VAMPIRE';
      const vampireName = vampireLeft
        ? this.entityDisplayName(r.attackerId)
        : this.entityDisplayName(r.defenderId);
      const hunterName  = vampireLeft
        ? this.entityDisplayName(r.defenderId)
        : this.entityDisplayName(r.attackerId);

      return vampireLeft
        ? `${vampireName} vs ${hunterName}`
        : `${hunterName} vs ${vampireName}`;
    }

    // Fallback : juste "A vs B"
    return `${this.entityDisplayName(r.attackerId)} vs ${this.entityDisplayName(r.defenderId)}`;
  }

  /* non utilisé pour le moment 
  /*
  get readyGauge(): string {
    const g: any = this.game || {};
    const ready = (typeof g.readyCount === 'number') ? g.readyCount : (g.readyForPhase3?.length || 0);
    const total = (typeof g.readyTotal === 'number') ? g.readyTotal : (g.players?.length || 0);
    return `${ready}/${total}`;
  }
  */

  trackById(_i: number, p: SPlayer) { return p.id; }

  get currentCombat() {
    return this.game?.currentCombat || null;
  }
  get waitingForMyRoll(): 'ATTACK'|'DEFENSE'|null {
    const r = this.currentCombat; if (!r) return null;
    if (r.attackerId === this.meId && (r.attackerRoll == null)) return 'ATTACK';
    if (r.defenderId === this.meId && (r.defenderRoll == null)) return 'DEFENSE';
    return null;
  }
  get showRollModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.waitingForMyRoll && !!this.currentCombat;
  }
  get showSpectatorModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.currentCombat && !this.waitingForMyRoll;
  }
  getPlayer(id: string): SPlayer | undefined {
    return this.game?.players.find(p => p.id === id);
  }
  roleColorOf(p?: SPlayer): 'red'|'blue' {
    return (p?.role === 'VAMPIRE' || p?.role === 'SERVANT') ? 'red' : 'blue';
  }
  getRole(p?: SPlayer): 'VAMPIRE'|'HUNTER'|'SERVANT'|undefined {
    return p?.role ;
  }
  diceAsset(dice: string | undefined, color: 'red'|'blue'|'purple'): string {
    const d = (dice || 'D6').toLowerCase();

    // Monstre => dossier monster + dés violets
    if (color === 'purple') {
      return `/assets/monster/${d}-purple.png`;
    }

    // comportement existant
    return `/assets/dices/${d}-${color}.png`;
  }
  roleIcon(role?: 'VAMPIRE'|'HUNTER'|'SERVANT'|undefined, name?: 'sword'|'armor'): string {
    return role === 'SERVANT' ? `/assets/icons/HUNTER-${name}.png` : `/assets/icons/${role}-${name}.png`;
  }

  getMonster(id: string): SMonster | undefined {
    const g = this.game as GameSnapshot | undefined;
    const list = g?.monsters ?? [];
    return list.find(m => m.id === id);
  }

  entityDisplayName(id: string): string {
    const p = this.getPlayer(id);
    if (p) return p.username;

    const m = this.getMonster(id);
    if (m) {
      switch (m.type) {
        case 'REVENANT':   return 'Revenant';
        case 'GARGOYLE':   return 'Gargouille';
        case 'ABERRATION': return 'Aberration';
        default:           return 'Créature';
      }
    }
    return id;
  }

  entityAttackDice(id: string): string | undefined {
    const p = this.getPlayer(id);
    if (p) return p.attackDice;
    const m = this.getMonster(id);
    return m?.attackDice;
  }

  entityDefenseDice(id: string): string | undefined {
    const p = this.getPlayer(id);
    if (p) return p.defenseDice;
    const m = this.getMonster(id);
    return m?.defenseDice;
  }

  entityColor(id: string): 'red'|'blue'|'purple' {
    const p = this.getPlayer(id);
    if (p) return this.roleColorOf(p);

    return 'purple';
  }

  private servantEquipSide(p: SPlayer, name: 'sword'|'armor'): 'HUNTER'|'VAMPIRE' {
    const code = (name === 'sword') ? p.weapon : p.armor;
    if (!code) return 'HUNTER'; // pas d'équipement -> ancien chasseur

    const c = code.toUpperCase();

    // tes codes back sont du style H_WEAPON..., V_ARMOR..., etc.
    if (c.startsWith('V_') || c.includes('V_WEAPON') || c.includes('V_ARMOR')) return 'VAMPIRE';
    if (c.startsWith('H_') || c.includes('H_WEAPON') || c.includes('H_ARMOR')) return 'HUNTER';

    // fallback (au pire)
    return 'HUNTER';
  }

  entityRoleIcon(id: string, name: 'sword'|'armor'): string {
    const p = this.getPlayer(id);
    if (p) {
      if (p.role === 'SERVANT') {
        const side = this.servantEquipSide(p, name);
        return `/assets/icons/${side}-${name}.png`;
      }
      return `/assets/icons/${p.role}-${name}.png`;
    }

    if (this.getMonster(id)) {
      return `/assets/monster/MONSTER-${name}.png`;
    }

    return `/assets/icons/VAMPIRE-${name}.png`;
  }

  entityHaloIcon(id: string, type: 'attack'|'defense'){
    const p = this.getPlayer(id);

    if (p?.role === 'VAMPIRE') {
      if (type === 'attack') return 'round';
      if (type === 'defense') return 'oval';
    }
    if (p?.role === 'SERVANT') {
      if (p.weapon.startsWith('V_') && type === 'attack') return 'round';
      return 'oval' 
    }
    if (p?.role === 'HUNTER') return 'oval';

    // Monstre
    if (this.getMonster(id)) return 'oval';

    // fallback
    return 'round';
  }

  private locationOf(playerId?: string): string | null {
    if (!playerId || !this.game) return null;

    // this.game.center = tableau des cartes posées au centre
    const entry = this.game.center?.find(c => c.playerId === playerId);
    return entry ? entry.card : null;
  }

  private playersOnLocation(loc: string): SPlayer[] {
    const g = this.game;
    if (!g) return [];
    return g.players.filter(p => this.locationOf(p.id) === loc);
  }

  // quantité d'une ressource 'res' pour un joueur p
  resOf(p: SPlayer | undefined, res: string): number {
    if (!p) return 0;
    switch (res) {
      case 'gold':   return p.gold;
      case 'silver': return p.silver;
      case 'souls':  return p.souls;
      case 'wood':   return p.wood;
      case 'herbs':  return p.herbs;
      case 'stone':  return p.stone;
      case 'iron':   return p.iron;
      case 'water':  return p.water;
      default:       return 0;
    }
  }

  // quantité déjà proposée dans mon offre pour 'res'
  offerQty(res: string): number {
    return this.myOffer && this.myOffer[res] ? this.myOffer[res] : 0;
  }

  rollNow(){
    if (!this.waitingForMyRoll || this.isRolling) return;

    this.isRolling = true;

    if (!this.game) return;
    this.api.rollDice(this.game.id).subscribe({
      next: () => this.isRolling = false,
      error: e => {
        this.isRolling = false;

        const msg = e?.error?.message || e?.message || '';
        if (msg === 'bite pending') {

          this.api.getGame(this.gameId).subscribe({
            next: g => this.game = g,
            error: err => this.showError(err)
          });
          return;
        }

        this.showError(e);
      }
    });
  }

  // --- Assets helpers (cœurs + cartes équipement) ---
  heartIconFor(p?: SPlayer): string {
    const role = p?.role.toUpperCase();
    if (role === 'SERVANT') return `/assets/icons/VAMPIRE-hearth.png`;
    else return `/assets/icons/${role}-hearth.png`;
  }

  // Image de fond une fois la météo tirée
  setImageBackground(modal:'weather'|'location'|'bite'|'corruption'|'construction'|'construire'): string | null {

    if (modal === 'weather') {
      const ws = this.game?.weather?.status;
      if (!ws || this.game?.weather?.roll == null) return null;
      return `url('/assets/weather/bg-${ws.toLowerCase()}.png')`;
    }
    if (modal === 'location') {
      const loc = this.game?.currentCombat?.location?.toLowerCase();
      return loc ? `url('/assets/locations/${loc}.png')` : 'none';
    }
    if (modal === 'corruption') {
      return `url('/assets/corruption/corrupted.png')`;
    }
    if (modal === 'bite') {
      return `url('/assets/corruption/bite.png')`;
    }
    if(modal === 'construction') {
      if(this.buildChoice === 'SAWMILL') return "url('/assets/locations/forest.png')";
      if(this.buildChoice === 'MINE') return "url('/assets/locations/quarry.png')";
      else return "url('/assets/locations/manor.png')"
    }
    if(modal === 'construire') return "url('/assets/actions/build.png')";

    return 'none';
  }

  // chemin de l'icône météo
  weatherIconSrc(ws?: string | null): string {
    if (!ws) return '';

    if(ws.includes('WIND')) return `/assets/weather/icon-wind.png`;
    if(ws.includes('BLOOD_MOON')) return `/assets/weather/icon-red_moon.png`;
    return `/assets/weather/icon-${ws.toLowerCase()}.png`;
  }

  private weatherCodeFromSource(m: RawStatMod): string | null {
    const src = (m as any).source || '';
    if (!src.startsWith('WEATHER:')) return null;

    // "WEATHER:RAIN" -> "RAIN"
    const code = src.substring('WEATHER:'.length);
    return code || null;
  }


  weatherIconSrcForMod(m: RawStatMod): string {
    const code = this.weatherCodeFromSource(m) || this.game?.weather?.status || null;
    return this.weatherIconSrc(code);
  }

  modIconSrc(source: string): string {
    const fallback = '/assets/icons/action-hunter-icon.png';
    if (!source) return fallback;

    if (source.startsWith('ACTION:')) {
      const parts = source.split(':');
      const code = parts[1] || '';

      // Liste des actions qui sont forcément jouées par les chasseurs
      const hunterActions = [
        'NET',
        'PIT',
        'PROVOCATION',
        'AMBUSH'
      ];

      const isHunter = hunterActions.includes(code);

      if (code === 'BLESSED_STAKE') return '/assets/icons/HUNTER-sword.png';
      if (code === 'SACRED_ROSARY') return '/assets/icons/HUNTER-armor.png';

      return isHunter
        ? '/assets/icons/action-hunter-icon.png'
        : '/assets/icons/action-vampire-icon.png';
    }

    if (source.startsWith('EQUIP:')) {
      const parts = source.split(':');
      const type = (parts[1] || '').toUpperCase();

      if (type === 'BLEED_WEAPON'
        || type === 'STUN_WEAPON'
        || type === 'RANGED_WEAPON') {
        return '/assets/icons/HUNTER-sword.png';
      }

      if (type === 'HUNTER_ARMOR') {
        return '/assets/icons/HUNTER-armor.png';
      }

      if (type === 'VAMPIRE_WEAPON') {
        return '/assets/icons/VAMPIRE-sword.png';
      }

      if (type === 'VAMPIRE_ARMOR') {
        return '/assets/icons/VAMPIRE-armor.png';
      }

      return fallback;
    }

    if (source.startsWith('HIT:BLEED_WEAPON')) {
      return '/assets/icons/bleed.png';
    }
      if (source.startsWith('HIT:STUN_WEAPON')) {
      return '/assets/icons/stun.png';
    }
      if (source.startsWith('HIT:RANGED_WEAPON')) {
      return '/assets/icons/range.png';
    }

    return fallback;
  }

  actionBackgroundSrc(mode: 'EAU_BENITE' | 'NET' | 'PIT' | 'INCENDIAIRE' | 'PROVOCATION' | 'AMBUSH' | 'BLESSED_STAKE' | 'CHARISMATIQUE' | 'MARCHAND_ITINERANT' | 'MARCHAND_BONUS_BUY' | 'PRESENCE_ECRASANTE' | 'CATACLYSME' | 'CLONES_OMBRE' | 'IMAGE_MIROIR_SETUP' | 'IMAGE_MIROIR_RESOLVE' | 'ECLIPSE' | 'BLOOD_MOON' | 'VOILE_DE_BRUME' | 'FAIM_IRREPRESSIBLE' | 'MARQUE_TENEBREUSE' | 'AFFAIBLISSEMENT_OCCULTE' | 'PASSAGE_SECRET' | 'AVIDITE_NOCTURNE' | null): String {
    if(mode === 'NET') return 'url(/assets/actions/net.png)';
    if(mode === 'PIT') return 'url(/assets/actions/traphole.png)';
    if(mode === 'INCENDIAIRE') return 'url(/assets/actions/burn.png)';
    if(mode === 'PROVOCATION') return 'url(/assets/actions/taunt.png)';
    if(mode === 'AMBUSH') return 'url(/assets/actions/ambush.png)';
    if(mode === 'BLESSED_STAKE') return 'url(/assets/actions/blessed_stake.png)';
    if(mode === 'CHARISMATIQUE') return 'url(/assets/actions/charismatic.png)';
    if(mode === 'MARCHAND_ITINERANT' || mode === 'MARCHAND_BONUS_BUY') return 'url(/assets/actions/traveling_merchant.png)';
    if(mode === 'PRESENCE_ECRASANTE') return 'url(/assets/actions/overwhelming_presence.png)';
    if(mode === 'CATACLYSME') return 'url(/assets/actions/cataclysm.png)';
    if(mode === 'CLONES_OMBRE') return 'url(/assets/actions/shadow_clones.png)';
    if(mode === 'IMAGE_MIROIR_SETUP' || mode === 'IMAGE_MIROIR_RESOLVE') return 'url(/assets/actions/miror_image.png';
    if(mode === 'ECLIPSE') return 'url(/assets/actions/eclipse.png';
    if(mode === 'BLOOD_MOON') return 'url(/assets/actions/redmoon.png';
    if(mode === 'VOILE_DE_BRUME') return 'url(/assets/actions/veil_of_mist.png';
    if(mode === 'FAIM_IRREPRESSIBLE') return 'url(/assets/actions/irrepressible_hunger.png';
    if(mode === 'MARQUE_TENEBREUSE') return 'url(/assets/actions/dark_mark.png';
    if(mode === 'AFFAIBLISSEMENT_OCCULTE') return 'url(/assets/actions/occult_weakening.png';
    if(mode === 'PASSAGE_SECRET') return 'url(/assets/actions/secret_passage.png';
    if(mode === 'AVIDITE_NOCTURNE') return 'url(/assets/actions/nocturnal_greed.png)';
    if(mode === 'EAU_BENITE') return 'url(/assets/actions/holy_water.png';
    return '';
  }

  // --- HP helpers (pour une jauge plus tard) ---
  maxHpOf(p: SPlayer): number {
    if (p.role === 'VAMPIRE') {
      const hunters = (this.game?.players ?? []).filter(x => x.role === 'HUNTER').length;
      return 20 + hunters * 10;
    }
    return 20;
  }
  hpPercent(p: SPlayer): number {
    const max = this.maxHpOf(p);
    const cur = Math.max(0, Math.min(p.hp ?? 0, max));
    return Math.round((cur / max) * 100);
  }

  // Helpers (lecture via players[])
  get me(): SPlayer | undefined {
    return this.game?.players.find(p => p.id === this.meId);
  }
  get isVampireSide(): boolean {
    return this.me?.role === 'VAMPIRE' || this.me?.role === 'SERVANT';
  }
  get isHunter() { return this.me?.role === 'HUNTER'; }
  get hasVampire(): boolean {
    return !!this.game && this.game.players.some(p => p.role === 'VAMPIRE');
  }
  get vampirePlayer(): SPlayer {
    return this.game!.players.find(p => p.role === 'VAMPIRE')!;
  }
  get hunterPlayers(): SPlayer[] {
    const list = (this.game?.players ?? []).filter(
      p => (p.role === 'HUNTER') && p.id !== this.meId
    );
    // garder les serviteurs en premier puis les chasseurs
    // return list.sort((a, b) => (a.role === b.role ? 0 : a.role === 'SERVANT' ? -1 : 1));
    return list;
  }
  get HunterAndServantPlayers(): SPlayer[] {
    const list = (this.game?.players ?? []).filter(
      p => (p.role === 'HUNTER' || p.role === 'SERVANT') && p.id !== this.meId
    );
    // garder les serviteurs en premier puis les chasseurs
    // return list.sort((a, b) => (a.role === b.role ? 0 : a.role === 'SERVANT' ? -1 : 1));
    return list;
  }
  get canPlaySelection(): boolean {
    const g  = this.game;
    const me = this.me;
    if (!g || !me) return false;

    // Lieu sélectionné
    if (this.selectedLocation) {
      if (!this.canPlayLocation(this.selectedLocation)) return false;

      const phase = g.phase;
      if (me.role === 'HUNTER') {
        return phase === 'PHASE1';
      }
      if (me.role === 'VAMPIRE' || me.role === 'SERVANT') {
        return phase === 'PHASE2';
      }
      return false;
    }

    // Action sélectionnée
    if (this.selectedAction) {
      return this.canUseActionNow(this.selectedAction);
    }

    // Potion / élixir sélectionné
    if (this.selectedPotion) {
      return this.canUsePotionNow(this.selectedPotion);
    }

    return false;
  }

  get isMeVampire(): boolean {
    return this.me?.role === 'VAMPIRE';
  }

  usernameOf(id: string): string {
    if (!this.game) return id;
    const p = this.game.players.find(x => x.id === id);
    if (p?.username) return p.username;
    if (id === this.meId && this.username) return this.username;
    return id;
    // (plus tard, on pourra faire une vraie map id->username côté back si besoin)
  }

  isCurrent(_p: SPlayer){ return false; } // on branchera plus tard

  labelLocation(c: string){
    switch(c){
      case 'forest': return 'Forêt';
      case 'quarry': return 'Carrière';
      case 'lake': return 'Lac';
      case 'manor': return 'Manoir';
      case 'sawmill': return 'Scierie';
      case 'mine': return 'Mine';
      case 'library': return 'Bibliothèque';
      case 'laboratory': return 'Laboratoire';
      case 'ballroom': return 'Salle de bal';
      case 'altar': return 'Autel';
      case 'forge': return 'Forge';
      default: return c;
    }
  }

  /** INIT
   * fait 1 refresh au montage, puis s'abonne au WebSocket.
   */
  ngOnInit() {
    this.route.paramMap.subscribe(pm => {
      const id = pm.get('id');
      if (!id) { this.showError('Identifiant de game inconnu.'); return; }

      this.unsubscribeGameTopic?.();
      this.gameId = id;
      // 1) D’abord WS
      this.unsubscribeGameTopic = this.live.subscribeGame(this.gameId, ev => this.onLiveEvent(ev));

      // 2) Puis snapshot initial (autorité)
      this.api.getGame(this.gameId).subscribe({
        next: snap => {
          const previous = null;

          this.game = snap;

          if ((snap as any).status === 'CREATED' || (snap as any).status === 'STARTING') {
            this.router.navigate(['/lobby']);
            return;
          }

          // === Sync waitingDone sur reload ===
          const meId = this.meId;
          if (snap.phase === 'PHASE4') {
            this.waitingDone = !!(meId && snap.readyForNextRaid?.includes(meId));
          } else {
            // Dans toutes les autres phases, on reset proprement
            this.waitingDone = false;
          }

          this.handleWeatherReveal(snap);

          // Instables
          this.recomputeUnstableChoices();

          // Modale action
          this.syncActionFromSnapshot(snap);

          // Effets de lieu (Bibliothèque, etc.)
          this.syncLocationEffectFromSnapshot(snap, previous);

          // Timer PREPHASE3 si on arrive "en cours de route" ET PAS d'effet de lieu en cours
          if (snap.phase === 'PREPHASE3') {
            this.prephase3AdvanceSent = false;
            if (!snap.locationEffectPending) {
              this.startPrephaseTimer();
            } else {
              this.stopPrephaseTimer();
            }
          } else {
            this.stopPrephaseTimer();
          }

          // ouvrir/fermer la modale + timer si on arrive en PHASE4
          this.syncShopVisibilityFromSnapshot();
        },
        error: err => { this.showError(err); }
      });
    });
  }

  /** DESTROY
   * on se désabonne du WS et on nettoie les timeouts météo.
   */
  ngOnDestroy(){
    this.unsubscribeGameTopic?.(); // <-- à la place du clearInterval
    if (this.weatherWaitTimer) clearTimeout(this.weatherWaitTimer);
    if(this.weatherTimer) clearTimeout(this.weatherTimer);
    if(this.weatherPostTimer) clearTimeout(this.weatherPostTimer);
    if(this.actionTimeoutId) clearTimeout(this.actionTimeoutId);
    this.stopPrephaseTimer();

  }

  /** onLiveEvent
   *  Reçoit les événements pushés par le backend.
   *  - WEATHER_ROLLED: on fait 1 GET (pour récupérer roll + messages) puis on lance la séquence météo côté front.
   *  - PHASE_CHANGED: on fait 1 GET pour refléter la nouvelle phase.
   *  - MESSAGE: on pousse le texte dans le flux local.
   *
   * l’info arrive en temps réel → 1 GET ponctuel → rendu UI.
   */
  private onLiveEvent(event: GameEvent) {
    // (optionnel) anti-doublon
  const key = this.stableEventKey(event);
  if (this.seenEventKeys.has(key)) return;
  this.seenEventKeys.add(key);

    switch (event.type) {
      case 'PHASE_CHANGED': {
        const next = (event?.payload?.phase as Phase | undefined) ?? undefined;

        // Gestion du timer PREPHASE3 AVANT le snapshot détaillé
        if (next === 'PREPHASE3') {
          if (this.game?.locationEffectPending) {
            this.stopPrephaseTimer();
            this.prephase3AdvanceSent = false;
          } else {
            this.prephase3AdvanceSent = false;
            this.startPrephaseTimer();
          }
        } else {
          this.stopPrephaseTimer();
        }

        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;

            const prevPhase = previous?.phase as Phase | undefined;
            this.emitPhaseBubble(prevPhase, g);

            const me = g?.players?.find(p => p.id === this.meId);
            const isDeadNow = !!me && !me.leftGame && me.hp <= 0;

            if (!g || g.status !== 'ACTIVE') {
              this.deathModalOpen = false;
              this.deathModalSeen = false;
            } else if (!isDeadNow) {
              // vivant => reset
              this.deathModalOpen = false;
              this.deathModalSeen = false;
            } else {
              // mort + game active
              if (!this.deathModalSeen) {
                this.deathModalOpen = true;   // open une seule fois
                this.deathModalSeen = true;   // verrouille immédiatement
              } else {
                // déjà “vu” => ne rien faire
              }
            }


            this.syncActionFromSnapshot(g);

            // recalculer les choix instables à partir du snapshot
            this.recomputeUnstableChoices();
            
            // Effets de lieu (Bibliothèque, etc.)
            this.syncLocationEffectFromSnapshot(g, previous);

            if (g.phase !== 'PHASE1') this.phase2AdvanceSent = false;

            // Début de raid → réarme météo et resets usuels
            if (g.phase === 'PHASE0') {
              this.weatherBgActive = false;
              this.weatherModalHold = false;
              this.lastWeatherRollSeen = null;
              this.weatherAdvanceSent = false;
              this.hasSkipped = false;
              this.prephase3AdvanceSent = false;
              this.phase4AdvanceSent = false;
              this.toPhase0AdvanceSent = false;
              this.game.currentBite = null;
              this.hadIncendiaireThisPrephase = false;

              this.handleWeatherReveal(g);
            } else {
              this.weatherModalHold = false; // ferme si on n’est plus en PHASE0
            }

            // Robustesse si 'next' manquait dans l'event
            if (!next) {
              if (g.phase === 'PREPHASE3') {
                if (g.locationEffectPending) {
                  this.stopPrephaseTimer();
                  this.prephase3AdvanceSent = false;
                } else {
                  this.prephase3AdvanceSent = false;
                  this.startPrephaseTimer();
                }
              } else {
                this.stopPrephaseTimer();
              }
              if (g.phase !== 'PHASE3') this.game.currentBite = null;
            }

            if (g.phase === 'PHASE3') {
              // Si aucun piège en cours (currentAction null),
              // tu laisses ta logique existante décider si on enchaîne.
              if (!g.currentAction) {
                this.maybeAdvanceToPhase4EndOfRaid();
              }
            }

            // Ouvrir/fermer la modale + timer si on bascule vers/depuis PHASE4
            this.syncShopVisibilityFromSnapshot();

            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'DRAFT_UPDATED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;
            this.syncLocationEffectFromSnapshot(g, previous);
          },
          error: e => this.showError(e),
        });
        break;
      }

      case 'LOBBY_UPDATED': {
        // si status == CREATED, refresh pour voir les pseudos/players en live
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'WEATHER_ROLLED': {
        const w = {
          roll: event.payload.roll,
          status: event.payload.status,
          nameFr: event.payload.nameFr,
          descFr: event.payload.descFr,
        };

        if (!this.game) {
          // Pas encore de snapshot → récupère tout et lance l’anim proprement
          this.api.getGame(this.gameId).subscribe({
            next: g => { 
              this.game = g;
              // (optionnel) sécurité si le back a poussé WEATHER_ROLLED une micro-seconde avant d’écrire le roll
              if (!g.weather || g.weather.roll == null) {
                this.game = { ...g, weather: w } as GameSnapshot;
              }
              this.handleWeatherReveal(this.game!);
              this.bumpHistoryScroll();
            },
            error: e => this.showError(e)
          });
        } else {
          // Snapshot déjà présent → patch léger + anim
          this.game = { ...(this.game as any), weather: w } as GameSnapshot;
          this.handleWeatherReveal(this.game!);

          // Sync propre un peu après pour récupérer mods/messages exacts
          setTimeout(() => {
            this.api.getGame(this.gameId).subscribe({
              next: g => { this.game = g; this.bumpHistoryScroll(); },
              error: e => this.showError(e)
            });
          }, 200);
        }
        break;
      }

      case 'MESSAGE': {
        this.game?.messages?.push(event.payload.text);
        this.bumpHistoryScroll();
        break;
      }

      case 'LOCATION_SELECTED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'READY_UPDATED': {
        const g: any = this.game || {};
        g.readyForPhase3 = Array.isArray(g.readyForPhase3) ? g.readyForPhase3 : [];
        const pid = event.payload.playerId;
        if (!g.readyForPhase3.includes(pid)) g.readyForPhase3.push(pid);
        (g as any).readyCount = event.payload.ready;
        (g as any).readyTotal = event.payload.total;
        this.game = { ...(g as GameSnapshot) };

        this.maybeAutoAdvanceToPhase3();
        break;
      }

      case 'RAID_MODS_UPDATED': {
        // Simple: resynchronise l’état complet
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'CENTER_REVEALED': {
        // 1) Flip local immédiat (animation)
        this.flipCenterFaceUpLocally();

        // 2) Petit hold UX, puis sync complète
        setTimeout(() => {
          this.api.getGame(this.gameId).subscribe({
            next: g => this.game = g,
            error: e => this.showError(e)
          });
        }, this.CENTER_FLIP_HOLD_MS);

        break;
      }

      /*
      case 'DICE_ROLLED': {
        // patch léger si le roll concerne le round courant; sinon GET
        const r = this.game?.currentCombat;
        if (!this.game || !r || r.id !== event.payload.roundId) {
          this.api.getGame(this.gameId).subscribe({
            next: g => this.game = g,
            error: e => this.showError(e)
          });
          break;
        }
        const side = event.payload.side; // "ATTACK"|"DEFENSE"
        const val  = event.payload.roll as number;
        const patched = { ...(this.game as any) };
        if (side === 'ATTACK') patched.currentCombat.attackerRoll = val;
        if (side === 'DEFENSE') patched.currentCombat.defenderRoll = val;
        this.game = patched as GameSnapshot;
        break;
      }
      */

      case 'DICE_ROLLED': {
        // Toujours un GET : on récupère attackerFirstRoll / defenderFirstRoll / rolls finaux proprement
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'COMBAT_RESOLVED': {
        if (this.game) {
          const def = this.game.players.find(p => p.id === event.payload.defenderId);
          if (def) def.hp = event.payload.defenderHp;
          const r = (this.game as any).currentCombat;
          if (r) r.breakdownLines = event.payload.breakdown || [];
          this.game = { ...(this.game as GameSnapshot) };
        }
        setTimeout(() => {
          this.api.combatContinue(this.gameId).subscribe({
            next: _ => {
              // Resync propre :
              this.api.getGame(this.gameId).subscribe({
                next: g => {
                  const previous = this.game;
                  this.game = g;

                  this.resetHarvestBubbleIfPhaseChanged(previous, g);
                  this.maybeEmitHarvestBubble(g);

                  this.bumpHistoryScroll();
                  this.syncActionFromSnapshot(g);
                  this.maybeAdvanceToPhase4EndOfRaid();
                },
                error: e => this.showError(e)
              });
            },
            error: e => this.showError(e)
          });
        }, this.SPECTATE_HOLD_MS);
        break;
      }

      case 'BITE_STARTED': {
        // petit délai de lecture avant d’ouvrir la modale
        this.biteNotBeforeMillis = Date.now() + this.SPECTATE_HOLD_MS;
        // récupère currentBite depuis le snapshot
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'BITE_ROLLED': {
        this.isSacredRosaryUsed = event?.payload?.isSacredRosaryUsed;
        // 1) refresh pour voir roll/resolvedAtMillis dans le snapshot
        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        // 2) laisse le résultat affiché, puis enchaîne
        setTimeout(() => {
          this.api.combatContinue(this.gameId).subscribe({
            error: e => this.showError(e)
          });
        }, this.SPECTATE_HOLD_MS);
        break;
      }

      case 'BITE_RESOLVED': {
        // le back a déjà remis currentBite = null ; on resynchronise
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;

            this.resetHarvestBubbleIfPhaseChanged(previous, g);
            this.maybeEmitHarvestBubble(g);
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'UNSTABLE_ASSIGNED': {
        const kind = event?.payload?.kind as string;   // "TARGET" | "HARVEST" | "NOTHING"
        const unstableId = event?.payload?.unstableId as string | undefined;

        // Stratégie simple et robuste : on resynchronise entièrement
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.recomputeUnstableChoices(); // met à jour la modale
            // Optionnel: si plus aucun choix restant, la modale se ferme automatiquement via showUnstableModal()
          },
          error: e => this.showError(e)
        });

        break;
      }
      case 'POTION_BOUGHT':
      case 'ACTION_BOUGHT':
      case 'SILVER_BOUGHT':
      case 'HOLY_WATER_BOUGHT':
      case 'TRACKING_BOUGHT':
      case 'RESOURCE_SOLD':
      case 'TRANSMUTED': {
        // stratégie simple: GET de synchro
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            // si on est en phase 4, s’assurer que la modale est bien ouverte et timer à jour
            this.syncShopVisibilityFromSnapshot();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'POTION_USED': {
        this.toastPotionUsed(event.payload);

        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'INFRA_BUILT': {
        this.toastInfraBuilt(event.payload);

        this.api.getGame(this.gameId).subscribe({
          next: g => this.game = g,
          error: e => this.showError(e)
        });
        break;
      }

      case 'ACTION_USED': {
        this.toastActionUsed(event.payload);

        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
                  
            if (g.currentAction && 
              (g.currentAction.mode === 'PROVOCATION' 
              || g.currentAction.mode === 'AMBUSH'
              || g.currentAction.mode === 'CHARISMATIQUE'
              || g.currentAction.mode === 'MARCHAND_ITINERANT'
              || g.currentAction.mode === 'MARCHAND_BONUS_BUY'
              || g.currentAction.mode === 'PRESENCE_ECRASANTE' 
              || g.currentAction.mode === 'CATACLYSME'
              || g.currentAction.mode === 'CLONES_OMBRE'
              || g.currentAction.mode === 'IMAGE_MIROIR_SETUP'
              || g.currentAction.mode === 'IMAGE_MIROIR_RESOLVE'
              || g.currentAction.mode === 'ECLIPSE'
              || g.currentAction.mode === 'BLOOD_MOON'
              || g.currentAction.mode === 'VOILE_DE_BRUME'
              || g.currentAction.mode === 'FAIM_IRREPRESSIBLE'
              || g.currentAction.mode === 'MARQUE_TENEBREUSE'
              || g.currentAction.mode === 'AFFAIBLISSEMENT_OCCULTE'
              || g.currentAction.mode === 'PASSAGE_SECRET'
              || g.currentAction.mode === 'AVIDITE_NOCTURNE'
              || g.currentAction.mode === 'EAU_BENITE')) {
              this.syncActionFromSnapshot(g);
            }
            this.bumpHistoryScroll(); // optionnel, mais cohérent avec les autres
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'ACTION_STARTED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            // ouvre/MAJ la modale selon currentAction (NET/PIT)
            this.syncActionFromSnapshot(g);
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'ACTION_ROLLED': {
        // 1) refresh pour voir roll / breakdownLines dans currentAction
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.syncActionFromSnapshot(g); // met à jour actionRoll + breakdown dans la modale
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });

        // 2) laisse le résultat affiché, puis enchaîne (comme BITE_ROLLED)
        setTimeout(() => {
          this.api.combatContinue(this.gameId).subscribe({
            error: e => this.showError(e)
          });
        }, this.SPECTATE_HOLD_MS);

        break;
      }

      case 'ACTION_RESOLVED': {
        //const prevActionMode = this.actionMode;

        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.syncActionFromSnapshot(g); // ferme la modale si currentAction=null

          if (g.phase === 'PREPHASE3' && g.hasUpcomingCombat && !g.locationEffectPending) {
            // On (re)lance simplement le timer local.
            // Si tu veux éviter de le redémarrer toutes les 2s, tu peux ajouter un petit guard :
            if (!this.prephaseTicker) {
              this.startPrephaseTimer();
            }
          } else {
            this.stopPrephaseTimer();
          }
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e)
        });
        break;
      }

      case 'TRADE_SYNC': {
        const t = event.payload as STrade;

        // upsert
        const arr = (this.game as any).trades as STrade[] | undefined;
        if (!arr) (this.game as any).trades = [t];
        else {
          const i = arr.findIndex(x => x.id === t.id);
          if (i >= 0) arr[i] = t; else arr.push(t);
        }

        // auto-open si utile
        const meInvolved = t.aId === this.me?.id || t.bId === this.me?.id;
        const mySt = this.myStatus(t);
        const hasAnyOffer =
          (t.offerA && Object.keys(t.offerA).length > 0) ||
          (t.offerB && Object.keys(t.offerB).length > 0);

        if (meInvolved && this.shopOpen && !this.selectedTradeTargetId &&
            mySt !== 'CANCELLED' && mySt !== 'REFUSED' && hasAnyOffer) {
          const otherId = this.otherIdFromTrade(t);
          this.selectedTradeTargetId = otherId;
          this.myOffer = { ...(this.myOffersByTarget[otherId] || {}) };
        }

        this.game = { ...(this.game as GameSnapshot) };
        break;
      }

      case 'TRADE_DELETED': {
        const payload: any = event.payload || {};
        const id      = payload.id as string | undefined;
        const aId     = payload.aId as string | undefined;
        const bId     = payload.bId as string | undefined;
        const result  = payload.result as ('SUCCESS'|'CLOSED'|undefined);
        if (!id || !aId || !bId) break;

        const meId    = this.me?.id;
        const otherId = meId === aId ? bId : aId;

        const offerA = (payload.offerA || {}) as Record<string, number>;
        const offerB = (payload.offerB || {}) as Record<string, number>;

        const success = (result === 'SUCCESS');

        this.closingUntil[id] = Date.now() + 1500;
        this.closingKind[id]  = success ? 'ok' : 'ko';
        this.game = { ...(this.game as GameSnapshot) };

        setTimeout(() => {
          if (this.game?.trades)
            this.game.trades = this.game.trades.filter((x: STrade) => x.id !== id);

          if (otherId) delete this.myOffersByTarget[otherId];
          if (this.selectedTradeTargetId === otherId) {
            this.selectedTradeTargetId = null;
            this.myOffer = {};
          }

          if (success) {
            const iAmA = (meId === aId);
            const give = iAmA ? offerA : offerB;
            const recv = iAmA ? offerB : offerA;
            const txt  = `Vous avez échangé ${this.packToText(give)} contre ${this.packToText(recv)} avec ${this.usernameOf(otherId)}.`;
            this.setFlashFor(otherId, txt, 'ok', 2500);
            this.api.getGame(this.gameId).subscribe({ next: g => this.game = g, error: e => this.showError(e) });
          } else {
            this.setFlashFor(otherId, `Échange annulé avec ${this.usernameOf(otherId)}.`, 'ko', 2500);
            this.game = { ...(this.game as GameSnapshot) };
          }
        }, 1500);

        break;
      }

      case 'LOCATION_STARTED': {
        if (!this.game) break;
        const { ownerId, infra } = event.payload;

        // Mise à jour minimale du snapshot local
        this.game.locationEffectPending = true;
        this.game.locationEffectOwnerId = ownerId;
        this.game.locationEffectInfra = infra as any;
        this.game.locationEffectChoice = null;

        // Le joueur qui choisit repart de zéro
        this.effectChoice = null;
        break;
      }

      case 'LOCATION_USED': {
        // On recharge toujours un snapshot frais après utilisation d'un effet de lieu
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            const previous = this.game;
            this.game = g;

            this.syncActionFromSnapshot(g);
            this.recomputeUnstableChoices();
            this.syncLocationEffectFromSnapshot(g, previous);
            this.syncShopVisibilityFromSnapshot();
            this.bumpHistoryScroll();
          },
          error: e => this.showError(e),
        });
        break;
      }

      case 'PHASE4_READY_UPDATED': {
        const pid = event?.payload?.playerId as string | undefined;
        if (pid && pid === this.meId) this.waitingDone = true;
        break;
      }
    }
  }
  
  //====== Select location ======/
  canPlayLocation(c: string): boolean {
    const g  = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.hp <= 0) return false;

    const isVampSide = me.role === 'VAMPIRE' || me.role === 'SERVANT';

    // On ne bloque que le camp vampire en PHASE2
    if (g.phase === 'PHASE2' && isVampSide && this.isGarlicBlockedLocation(c)) {
      return false;
    }

    return true;
  }

  onLocationClick(c: string) {
    if (!this.canPlayLocation(c)) return;
    this.selectedLocation = c;
    this.selectedAction = null;
    this.selectedPotion = null;
  }

  playSelected() {
    if (!this.game || !this.me) return;

    const g  = this.game;
    const me = this.me;

    // 1) CAS LIEU SÉLECTIONNÉ
    if (this.selectedLocation) {
      const loc = this.selectedLocation;
      const hasPisteur = this.myActions().includes('PISTEUR');
      const isHunterPhase1 = (me.role === 'HUNTER' && g.phase === 'PHASE1');

      // Cas spécial : Fumigation + Pisteur (chasseur en PHASE1)
      if (isHunterPhase1 && this.preparedGarlicForThisRaid && hasPisteur) {
        const useTracker = window.confirm(
          "Voulez-vous également utiliser Pisteur pour traquer le vampire ?"
        );

        this.api.selectLocation(g.id, loc).subscribe({
          next: _ => {
            if (useTracker) {
              this.api.useAction(g.id, 'PISTEUR').subscribe({
                error: e => this.showError(e)
              });
            }

            this.selectedLocation = null;
            this.preparedGarlicForThisRaid = false;
          },
          error: e => this.showError(e)
        });

        return;
      }

      // Cas normal : jouer uniquement le lieu
      if (String(loc).toLowerCase() === 'forge') {
        // 1) Options possibles (tiers suivant) via ton code existant
        const tmpG = { ...g, locationEffectOwnerId: me.id } as GameSnapshot;
        const possible = this.computeForgeOptionsForOwner(tmpG);

        // Si aucune option => probablement full T3
        if (possible.length === 0) {
          const ok = window.confirm(
            "Attention : vous ne pouvez plus forger d’amélioration.\n" +
            "Raison : votre arme et votre armure semblent déjà au palier maximum (T3).\n\n" +
            "Voulez-vous quand même jouer cette carte ?"
          );
          if (!ok) {
            this.selectedLocation = null;
            return;
          }
        } else {
          // 2) Vérif ressources (copie des coûts back, en inline)
          const wood   = (me as any).wood   ?? 0;
          const iron   = (me as any).iron   ?? 0;
          const silver = (me as any).silver ?? 0;
          const souls  = (me as any).souls  ?? 0;

          const costOf = (id: string) => {
            switch (id) {
              // Hunters T1
              case 'H_WEAPON_T1_SWORD':     return { wood: 2, iron: 4 };
              case 'H_WEAPON_T1_MACE':      return { wood: 3, iron: 3 };
              case 'H_WEAPON_T1_SPEAR':     return { wood: 5, iron: 1 };
              case 'H_ARMOR_T1_BRIGANDINE': return { iron: 6 };

              // Hunters T2
              case 'H_WEAPON_T2_HALBERD':   return { wood: 6, iron: 4 };
              case 'H_WEAPON_T2_HAMMER':    return { wood: 4, iron: 6 };
              case 'H_WEAPON_T2_CROSSBOW':  return { wood: 5, iron: 5 };
              case 'H_ARMOR_T2_HAUBERT':    return { iron: 8 };

              // Hunters T3
              case 'H_WEAPON_T3_WRIST_BLADES': return { iron: 6, silver: 10 };
              case 'H_WEAPON_T3_FLAIL':        return { wood: 3, iron: 3, silver: 10 };
              case 'H_WEAPON_T3_PISTOL':       return { wood: 6, silver: 10 };
              case 'H_ARMOR_T3_PLATE_SILVER':  return { iron: 10, silver: 15 };

              // Vamp/Servant
              case 'V_WEAPON_T1_SCYTHE':    return { wood: 2, iron: 4, souls: 40 };
              case 'V_ARMOR_T1_CARAPACE':   return { iron: 4, souls: 40 };

              case 'V_WEAPON_T2_SWORD':     return { wood: 3, iron: 6, souls: 60 };
              case 'V_ARMOR_T2_HAUBERT':    return { iron: 6, souls: 60 };

              case 'V_WEAPON_T3_CLAWS':     return { wood: 4, iron: 4, souls: 100 };
              case 'V_ARMOR_T3_ECORCE':     return { wood: 3, iron: 6, souls: 100 };

              default: return null;
            }
          };

          const missingOf = (id: string) => {
            const c = costOf(id);
            if (!c) return { total: 999, parts: ['coût inconnu'] };

            const missWood   = Math.max(0, (c.wood   ?? 0) - wood);
            const missIron   = Math.max(0, (c.iron   ?? 0) - iron);
            const missSilver = Math.max(0, (c.silver ?? 0) - silver);
            const missSouls  = Math.max(0, (c.souls  ?? 0) - souls);

            const parts: string[] = [];
            if (missWood)   parts.push(`${missWood} bois`);
            if (missIron)   parts.push(`${missIron} fer`);
            if (missSilver) parts.push(`${missSilver} argent`);
            if (missSouls)  parts.push(`${missSouls} âmes`);

            return {
              total: missWood + missIron + missSilver + missSouls,
              parts
            };
          };

          const affordable = possible.filter(o => missingOf(o.id).total === 0);

          if (affordable.length === 0) {
            // On prend l’option “la plus proche” pour expliquer clairement la raison
            const ranked = possible
              .map(o => ({ o, miss: missingOf(o.id) }))
              .sort((a, b) => a.miss.total - b.miss.total);

            const best = ranked[0];

            const reason =
              "Attention: aller à la Forge n’aura aucun effet pour l’instant.\n" +
              "Raison: vous n’avez pas assez de ressources pour forger un équipement.\n\n" +
              `Option la plus proche : ${best.o.label}\n` +
              `Il vous manque: ${best.miss.parts.join(', ')}\n\n` +
              `Vos ressources: ${wood} bois, ${iron} fer, ${silver} argent, ${souls} âmes.\n\n` +
              "Voulez-vous quand même jouer cette carte ?";

            const ok = window.confirm(reason);
            if (!ok) {
              this.selectedLocation = null;
              return;
            }
          }
        }
      }

      this.api.selectLocation(g.id, loc).subscribe({
        next: _ => {
          this.selectedLocation = null;
        },
        error: e => this.showError(e)
      });

      return;
    }

    // 2) CAS ACTION SÉLECTIONNÉE
    if (this.selectedAction) {
      const action = this.selectedAction;
      if (!this.canUseActionNow(action)) return;

      this.useAction(action);
      this.selectedAction = null;
      return;
    }

    // 3) CAS POTION / ELIXIR SÉLECTIONNÉ
    if (this.selectedPotion) {
      const potion = this.selectedPotion;
      if (!this.canUsePotionNow(potion)) return;

      this.usePotion(potion);
      this.selectedPotion = null;
      return;
    }
  }

  skipNow() {
    if (!this.game || this.hasSkipped) return; // évite le spam
    this.hasSkipped = true;

    this.api.skipPrePhase3(this.game.id).subscribe({
      // on ne touche PAS à this.game ici : READY_UPDATED fait foi
      error: e => {
        this.hasSkipped = false;     // on relâche le garde-fou si erreur
        this.showError(e);
      }
    });
  }

  isUnstableAlreadyDecided(unstableId: string): boolean {
    const g: any = this.game;
    if (!g) return false;
    const chosenT = g.unstableTargetByPlayer || {};
    const chosenH = g.unstableHarvestLocByPlayer || {};
    return !!(chosenT[unstableId] || chosenH[unstableId]);
  }

  //====== Météo ======/
  canShowWeatherModal(): boolean {
    // La modale n’existe que pendant PHASE0 et uniquement quand on a activé le hold local
    return this.game?.phase === 'PHASE0' && this.weatherModalHold === true;
  }

  rollWeather() {
    this.api.rollWeather(this.gameId).subscribe({
      error: e => this.showError(e)
    });
  }

  /** handleWeatherReveal
   * Rôle :
   *  - Piloter l'animation météo côté front : petite attente (PRE_BG), activer le fond,
   *    éventuellement un post-hold (POST_BG), puis relâcher la modale.
   *
   *  À la fin de l'animation locale, on appelle explicitement advance(PHASE1).
   *  On ajoute un flag local (weatherAdvanceSent) pour éviter d'appeler /advance plusieurs fois
   *  si plusieurs onglets sont ouverts.
   */
  private handleWeatherReveal(g: GameSnapshot){
    const roll = g.weather?.roll ?? null;

    // ---- RESET si pas (encore) de tirage
    if (roll == null){
      this.lastWeatherRollSeen = null;
      this.weatherBgActive = false;

      if (g.phase === 'PHASE0') {
        // Petit teaser avant d’ouvrir la modale “En attente du tirage…”
        if (!this.weatherWaitTimer) {
          this.weatherModalHold = false; // masquée pendant le teaser
          this.weatherWaitTimer = setTimeout(() => {
            this.weatherModalHold = true; // ouverture “En attente du tirage…”
            this.weatherWaitTimer = undefined;
          }, GameComponent.WEATHER_WAIT_BEFORE_MODAL_MS);
        }
      } else {
        this.weatherModalHold = false;
      }

      // nouveau cycle autorisé
      this.weatherAdvanceSent = false;

      // cleanup timers
      if (this.weatherTimer)     { clearTimeout(this.weatherTimer);     this.weatherTimer = undefined; }
      if (this.weatherPostTimer) { clearTimeout(this.weatherPostTimer); this.weatherPostTimer = undefined; }
      return;
    }

    // ---- NOUVEAU TIRAGE détecté → lance l’animation locale
    if (this.lastWeatherRollSeen !== roll){
      if (this.weatherWaitTimer) { clearTimeout(this.weatherWaitTimer); this.weatherWaitTimer = undefined; }

      this.lastWeatherRollSeen = roll;
      this.weatherAdvanceSent = false; // nouveau cycle météo → on réautorise 1 avance

      this.weatherModalHold = true;    // on garde la modale ouverte le temps de l’anim
      this.weatherBgActive  = false;

      if (this.weatherTimer) clearTimeout(this.weatherTimer);
      if (this.weatherPostTimer) clearTimeout(this.weatherPostTimer);

      // 1) roue/teaser
      this.weatherTimer = setTimeout(() => {
        this.weatherBgActive = true; // active le fond/visuel principal

        // 2) petit “hold de lecture”
        const post = this.WEATHER_HOLD_MS;
        if (post > 0){
          this.weatherPostTimer = setTimeout(() => {
            this.weatherModalHold = false;       // on ferme la modale…
            this.advanceToPhase1IfNeeded();      // …et on avance PHASE1 exactement ici (une seule fois)
          }, post);
        } else {
          this.weatherModalHold = false;
          this.advanceToPhase1IfNeeded();
        }
      }, this.WEATHER_WHEEL_MS);
    }
  }

  // on reste sur la roue tant qu'on n'a pas activé le bg météo
  isWeatherPreReveal(): boolean {
    const hasRoll = this.game?.weather?.roll != null;
    return !hasRoll || !this.weatherBgActive;
  }

  // calculer la transform pour poser l'icône sur la pale correspondante
  weatherIconTransform(): string {
    const roll = Math.max(1, Math.min(12, this.game?.weather?.roll ?? 1));
    const angle = (roll - 1) * GameComponent.WHEEL_DEG_PER_FACE + GameComponent.WHEEL_BASE_OFFSET;
    const r = GameComponent.WEATHER_ICON_RADIUS;
    // centre ➜ rotation vers la pale ➜ translation radiale ➜ remise à l'horizontale
    return `translate(-50%, -50%) rotate(${angle}deg) translate(0, -${r}px) rotate(${-angle}deg)`;
  }



  //====== Action & potion ======/
  // afficher mes potions (IDs)
  myPotions(): string[] {
    const g = this.game;
    if (!g) return [];
    const me = g.players.find(p => p.id === this.meId);
    return me?.potions ?? [];
  }

  myElixirs(): string[] {
    const g = this.game;
    if (!g) return [];
    const me = g.players.find(p => p.id === this.meId);
    return me?.elixirs ?? [];
  }

  canUsePotionNow(_pot: string): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.hp <= 0) return false;

    const ws  = g.weather?.status;
    const wss = g.weather?.secondaryStatus;

    // Sous blizzard : potions inutilisables
    if (ws === 'BLIZZARD' || wss === 'BLIZZARD') {
      return false;
    }

    // Web app : fenêtre unique de préparation avant les duels
    if (g.phase === 'PREPHASE3' && g.hasUpcomingCombat) {
      return this.imInUpcomingCombat();
    }

    // Jamais en PHASE3
    return false;
  }

  // Suis-je (moi) sur un lieu face-up où il y aura un combat ?
  // Ennemi = vampire / serviteur / monstre / clone d'ombre.
  imInUpcomingCombat(): boolean {
    const game = this.game;
    const meId = this.meId;
    if (!game || !meId) return false;

    const faceUp = (game.center || []).filter(cb => cb.faceUp);
    if (faceUp.length === 0) return false;

    const harvestMap: Record<string,string> =
      (game as any).unstableHarvestLocByPlayer || {};

    const allLocs = Array.from(new Set(faceUp.map(cb => cb.card)));
    const combatLocs = new Set<string>();

    // Monstres du snapshot (vivants)
    const monsters = (game as any).monsters as SMonster[] | undefined;
    const aliveMonsters = (monsters || []).filter(m => m.hp > 0);

    // Lieux des clones
    const cloneLocs: string[] = (game as any).clonesLocations || [];

    for (const loc of allLocs) {
      const idsOnLoc = faceUp
        .filter(cb => cb.card === loc)
        .map(cb => cb.playerId);

      const playersOnLoc = idsOnLoc
        .map(id => game.players.find(p => p.id === id))
        .filter((p): p is SPlayer => !!p);

      const monstersOnLoc = aliveMonsters.filter(m => m.location === loc);
      const cloneHere = cloneLocs.includes(loc);

      // Ennemi = joueur ennemi OU monstre OU clone d'ombre présent sur ce lieu
      const hasEnemy =
        playersOnLoc.some(p => this.isEnemy(p)) ||
        monstersOnLoc.length > 0 ||
        cloneHere;

      // Hunters présents (non récolteurs instables)
      const hasHunter = playersOnLoc.some(p =>
        p.role === 'HUNTER' &&
        p.hp > 0 &&
        !harvestMap[p.id]
      );

      if (hasEnemy && hasHunter) {
        combatLocs.add(loc);
      }
    }

    const myFaceUpCard = faceUp.find(cb => cb.playerId === meId)?.card;

    // si je suis récolteur instable, jamais combat pour moi
    if (harvestMap[meId]) return false;

    return !!myFaceUpCard && combatLocs.has(myFaceUpCard);
  }

  private isEnemy(p?: SPlayer): boolean {
    return p?.role === 'VAMPIRE' || p?.role === 'SERVANT';
  }

  potionLabelFr(id: string): string {
    switch (id) {
      case 'FORCE': return 'Potion de force';
      case 'ENDURANCE': return 'Potion d’endurance';
      case 'VIE': return 'Potion de vie';
      default: return id;
    }
  }

  isElixirMod(mod: { source?: string | null } | null | undefined): boolean {
    const src = mod?.source || '';
    if (!src.startsWith('POTION:')) return false;

    // "POTION:RAGE" ou "POTION:RAGE:DSP" → on récupère "RAGE"
    const type = src.split(':')[1] || '';

    return type === 'RAGE'
        || type === 'RESILIENCE'
        || type === 'RAPIDITE'
        || type === 'INVISIBILITE'
        || type === 'INVULNERABILITE';
  }

  usePotion(type: string) {
    if (!this.game) return;
    this.api.usePotion(this.game.id, type).subscribe({
      error: e => this.showError(e)
    });
  }

  onPotionClick(potion: string, index: number){
    if (!this.canUsePotionNow(potion)) return;
    this.selectedPotion = potion;
    this.selectedPotionIndex = index;

    this.selectedLocation = null;
    this.selectedAction = null;
    this.selectedActionIndex = null;
  }

    /** Est-ce que ce joueur a un effet de focalisation actif ce raid ? */
  hasFocus(playerId?: string | null): boolean {
    if (!playerId || !this.game?.raidMods) return false;
    const list = this.game.raidMods[playerId] || [];
    return list.some(m =>
      m.source?.startsWith('POTION:FOCALISATION') &&
      m.source?.includes(':DSP')
    );
  }

  /** Suis-je entre le 1er et le 2e dé de focalisation ? */
  get isMyFocusFirstStep(): boolean {
    const r    = this.currentCombat;
    const side = this.waitingForMyRoll;
    if (!r || !side) return false;
    if (!this.hasFocus(this.meId)) return false;

    if (side === 'ATTACK' && r.attackerId === this.meId) {
      // J’ai déjà un premier jet, mais pas encore le jet final
      return r.attackerFirstRoll != null && r.attackerRoll == null;
    }
    if (side === 'DEFENSE' && r.defenderId === this.meId) {
      return r.defenderFirstRoll != null && r.defenderRoll == null;
    }
    return false;
  }

  /** Label du bouton dans la modale roll (Lancer / Relancer) */
  get rollButtonLabel(): string {
    return this.isMyFocusFirstStep ? 'Relancer le dé' : 'Lancer le dé';
  }

  showFocusSpectate(playerId?: string): boolean {
    if (!playerId) return false;
    if (!this.hasFocus(playerId)) return false;

    // Tant qu'on est AVANT l'ouverture de la modale morsure,
    // on garde l'affichage focalisation (2 dés).
    return this.isBeforeBiteModal;
  }

  get isBeforeBiteModal(): boolean {
    // Pas de morsure en cours => on considère qu'on est "avant" la morsure
    if (!this.game?.currentBite) return true;

    // Morsure posée mais pas de délai configuré => on est après
    if (this.biteNotBeforeMillis == null) return false;

    // Si l'heure actuelle est encore avant biteNotBeforeMillis,
    // on est toujours dans la fenêtre "spectate" avant affichage de la modale morsure
    return Date.now() < this.biteNotBeforeMillis;
  }

  myActions(): string[] {
    const g = this.game;
    if (!g) return [];
    const me = g.players.find(p => p.id === this.meId);
    return me?.actions ?? [];
  }

  myMaintenanceActions(): string[] {
    const g = this.game;
    if (!g) return [];

    const me = g.players.find(p => p.id === this.meId);
    if (!me || !me.actions) return [];

    return me.actions.filter(a => a === 'CHARISMATIQUE' || a === 'MARCHAND_ITINERANT' || a === 'AVIDITE_NOCTURNE');
  }

  canUseActionNow(_action: string): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.hp <= 0) return false;

    const ws = g.weather?.status;
    const wss = g.weather?.secondaryStatus;

    // 1) Action vampire:
    if (_action === 'CATACLYSME' || _action === 'CLONES_OMBRE') {
      return g.phase === 'PHASE2' && me.role === 'VAMPIRE';
    }

    if (_action === 'IMAGE_MIROIR') {
      // Phase + rôle de base
      if (g.phase !== 'PHASE2' || me.role !== 'VAMPIRE') return false;

      const hand = me.hand || [];

      // Lieux non fumigés distincts dans la main du vampire
      const nonGarlicDistinct = Array.from(
        new Set(
          hand.filter(loc => !this.isGarlicBlockedLocation(loc))
        )
      );

      const nonGarlicCount = nonGarlicDistinct.length;

      // Combien d'Image miroir ont déjà été "préparées" ce raid ?
      // (côté back : mirrorAltLocations s'incrémente à chaque IMAGE_MIROIR_SETUP)
      const alreadyUsed = (g as any).mirrorAltLocations
        ? (g as any).mirrorAltLocations.length
        : 0;

      if (alreadyUsed === 0) {
        // 1ère utilisation : il faut au moins 2 lieux non fumigés
        return nonGarlicCount >= 2;
      } else if (alreadyUsed === 1) {
        // 2ème utilisation : il faut au moins 3 lieux non fumigés
        return nonGarlicCount >= 3;
      } else {
        // Tu n'as que 2 copies dans le deck → pas de 3ème utilisation logique
        return false;
      }
    }

    if (_action === 'PRESENCE_ECRASANTE' 
      || _action === 'ECLIPSE' 
      || _action === 'VOILE_DE_BRUME' 
      || _action === 'MARQUE_TENEBREUSE') {
      return g.phase === 'PREPHASE3' && me.role === 'VAMPIRE';
    }

    if (_action === 'FAIM_IRREPRESSIBLE' || _action === 'AFFAIBLISSEMENT_OCCULTE') {
      return (
        g.phase === 'PREPHASE3' &&
        me.role === 'VAMPIRE' &&
        this.imInUpcomingCombat()
      );
    }

    if (_action === 'PASSAGE_SECRET') {
      if (g.phase !== 'PREPHASE3' || me.role !== 'VAMPIRE') return false;

      const loc = this.locationOf(me.id);
      if (loc !== 'manor') return false;

      // Optionnel : vérifier qu'il y a au moins une destination possible
      const choices = this.computeSecretPassageChoices(g, loc);
      return choices.length > 0;
    }

    if (_action === 'AVIDITE_NOCTURNE') {
      if (g.phase !== 'PHASE4' || me.role !== 'VAMPIRE') return false;


      const greedy = (g as any).shopPricesIncreasedThisRaid;
      if (greedy) return false;

      return true;
    }

    if (_action === 'BLOOD_MOON' && (ws === 'FULL_MOON' || wss === 'FULL_MOON')) {
      return g.phase === 'PREPHASE3' && me.role === 'VAMPIRE';
    }

    // 2) Actions PHASE1 (chasseurs)
    if (g.phase === 'PHASE1' && me.role === 'HUNTER') {
      if (_action === 'FUMIGATION_AIL' || _action === 'PISTEUR') {
        return true;
      }
    }

    // À partir d'ici : on parle des actions de raid des chasseurs
    if (me.role !== 'HUNTER') return false;

    // Si le vampire a joué Présence écrasante ce raid, les actions chasseurs sont bloquées
    // uniquement pour les chasseurs sur le même lieu que le vampire
    if (g.hunterActionsBlockedThisRaid &&
        (_action === 'FEU_DE_CAMP'
          || _action === 'NET'
          || _action === 'PIT'
          || _action === 'INCENDIAIRE'
          || _action === 'PROVOCATION'
          || _action === 'AMBUSH'
          || _action === 'BLESSED_STAKE'
          || _action === 'SACRED_ROSARY'
          || _action === 'CHARISMATIQUE'
          || _action === 'MARCHAND_ITINERANT'
          || _action === 'EAU_BENITE')) {

      const me   = this.me;
      const vamp = g.players?.find(p => p.role === 'VAMPIRE') || null;

      const myLoc   = me   ? this.locationOf(me.id)   : null;
      const vampLoc = vamp ? this.locationOf(vamp.id) : null;

      if (myLoc && vampLoc && myLoc === vampLoc) {
        return false;
      }
    }    

    // 3) Règles par action
    switch (_action) {
      case 'FEU_DE_CAMP':
        if (g.phase !== 'PREPHASE3') return false;
        return (ws === 'DUSK' || ws === 'NIGHT_DARK' || ws === 'NIGHT_CLEAR') 
        && (wss === 'DUSK' || wss === 'NIGHT_DARK' || wss === 'NIGHT_CLEAR') ;

      case 'NET':
      case 'PIT': {
        // météo qui bloque
        if (ws === 'NIGHT_DARK' || wss === 'NIGHT_DARK') return false;

        // rôle + phase
        if (g.phase !== 'PREPHASE3') return false;
        if (me.role !== 'HUNTER') return false;

        // Il faut au moins un ennemi physique (vamp/serviteur/monstre) sur mon lieu.
        return this.hasRealEnemyOnMyLocation();
      }

      case 'PROVOCATION': {
        if (g.phase !== 'PREPHASE3') return false;
        if (me.role !== 'HUNTER') return false;
        return this.canPlayProvocationHere();
      }

      case 'BLESSED_STAKE': {
        if (!me) return false;
        if (me?.isBlessedStake) return false;
        if (g.phase === 'PREPHASE3' && me.role === 'HUNTER') return true;
        return false;
      }

      case 'SACRED_ROSARY': {
        if (!me) return false;
        if (me.isSacredRosary) return false;
        if (g.phase === 'PREPHASE3' && me.role === 'HUNTER') return true;
        return false;
      }

      case 'INCENDIAIRE':
        if (g.phase !== 'PREPHASE3') return false;
        return this.canPlayIncendiaireHere();
        
      case 'AMBUSH': {
        if (g.phase !== 'PREPHASE3' || me.role !== 'HUNTER') return false;

        const loc = this.locationOf(me.id);
        if (!loc) return false;

        const onLoc = this.playersOnLocation(loc);
        const hunters = onLoc.filter(p => p.role === 'HUNTER' && p.hp > 0);
        const enemies = onLoc.filter(p =>
          (p.role === 'VAMPIRE' || p.role === 'SERVANT') && p.hp > 0
        );

        return hunters.length >= 2 && enemies.length >= 1;
      }

      case 'CHARISMATIQUE': {
        if (!me) return false;
        if (me.role !== 'HUNTER') return false;
        if (g.phase !== 'PHASE4') return false;
        if (me.charismaticThisRaid) return false;

        // Bloqué par Présence écrasante
        if (g.hunterActionsBlockedThisRaid) return false;

        return true;
      }

      case 'MARCHAND_ITINERANT': {
        if (!me) return false;
        if (me.role !== 'HUNTER') return false;
        if (g.phase !== 'PHASE4') return false;

        // Bloqué par Présence écrasante
        if (g.hunterActionsBlockedThisRaid) return false;

        return true;
      }

      case 'EAU_BENITE':
        if (g.phase !== 'PREPHASE3') return false;
        return this.canPlayHolyWaterkHere();

      default:
        return false;
    }
  }

  // Le joueur a-t-il encore au moins une action préphase HUNTER jouable maintenant ?
  canUseHunterPrephaseActions(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.role !== 'HUNTER') return false;

    const acts = me.actions || [];

    const canInc  = acts.includes('INCENDIAIRE')      && this.canPlayIncendiaireHere();
    const canHoly = acts.includes('EAU_BENITE')       && this.canPlayHolyWaterkHere();
    const canProv  = acts.includes('PROVOCATION')     && this.canPlayProvocationHere();
    const canAmbush  = acts.includes('AMBUSH')        && this.canUseActionNow('AMBUSH');
    const canStake  = acts.includes('BLESSED_STAKE')  && this.canUseActionNow('BLESSED_STAKE');
    const canRosary  = acts.includes('SACRED_ROSARY')    && this.canUseActionNow('SACRED_ROSARY');

    return (
      canInc ||
      this.hadIncendiaireThisPrephase ||
      canHoly ||
      canProv ||
      canAmbush ||
      canStake ||
      canRosary
    );
  }

  // Le joueur a-t-il encore au moins une action préphase VAMP jouable maintenant ?
  canUseVampPrephaseActions(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;
    if (me.role !== 'VAMPIRE') return false;

    const acts = me.actions || [];

    const canFog       = acts.includes('VOILE_DE_BRUME')          && this.canUseActionNow('VOILE_DE_BRUME');
    const canPresence  = acts.includes('PRESENCE_ECRASANTE')      && this.canUseActionNow('PRESENCE_ECRASANTE');
    const canEclipse   = acts.includes('ECLIPSE')                 && this.canUseActionNow('ECLIPSE');
    const canBloodMoon = acts.includes('BLOOD_MOON')              && this.canUseActionNow('BLOOD_MOON');
    const canHunger    = acts.includes('FAIM_IRREPRESSIBLE')      && this.canUseActionNow('FAIM_IRREPRESSIBLE');
    const canDarkMark  = acts.includes('MARQUE_TENEBREUSE')       && this.canUseActionNow('MARQUE_TENEBREUSE');
    const canWeakening = acts.includes('AFFAIBLISSEMENT_OCCULTE') && this.canUseActionNow('AFFAIBLISSEMENT_OCCULTE');
    const canSecret    = acts.includes('PASSAGE_SECRET')          && this.canUseActionNow('PASSAGE_SECRET');

    return (
      canFog ||
      canPresence ||
      canEclipse ||
      canBloodMoon ||
      canHunger ||
      canDarkMark ||
      canWeakening ||
      canSecret
    );
  }

  actionLabelFr(mode: string | null | undefined): string {
    if (!mode) return '';

    switch (mode) {
      case 'EAU_BENITE':              return 'Eau bénite';
      case 'FUMIGATION_AIL':          return 'Fumigation d\'ail';
      case 'PISTEUR':                 return 'Pisteur';
      case 'FEU_DE_CAMP':             return 'Feu de camp';
      case 'NET':                     return 'Filet';
      case 'PIT':                     return 'Fosse';
      case 'PROVOCATION':             return 'Provocation';
      case 'INCENDIAIRE':             return 'Incendiaire';
      case 'AMBUSH':                  return 'Embuscade';
      case 'BLESSED_STAKE':           return 'Pieu béni';
      case 'SACRED_ROSARY':           return 'Chapelet sacré';
      case 'CHARISMATIQUE':           return 'Charismatique';
      case 'MARCHAND_ITINERANT':
      case 'MARCHAND_BONUS_BUY':      return 'Marchand itinérant';
      case 'PRESENCE_ECRASANTE':      return 'Présence écrasante';
      case 'CATACLYSME':              return 'Cataclysme';
      case 'CLONES_OMBRE':            return 'Clones d’ombre';
      case 'IMAGE_MIROIR':
      case 'IMAGE_MIROIR_SETUP':
      case 'IMAGE_MIROIR_RESOLVE':    return 'Image miroir';
      case 'ECLIPSE':                 return 'Éclipse';
      case 'BLOOD_MOON':              return 'Lune sanglante';
      case 'VOILE_DE_BRUME':          return 'Voile de brume';
      case 'FAIM_IRREPRESSIBLE':      return 'Faim irrépressible';
      case 'MARQUE_TENEBREUSE':       return 'Marque ténébreuse';
      case 'AFFAIBLISSEMENT_OCCULTE': return 'Affaiblissement occulte';
      case 'PASSAGE_SECRET':          return 'Passage secret';
      case 'AVIDITE_NOCTURNE':        return 'Avidité nocturne';
      // etc si tu as d’autres modes
      default:                  return '';
    }
  }

  useAction(type: string) {
    if (!this.game) return;

    this.zoomLeave();

    this.api.useAction(this.game.id, type).subscribe({
      next: _g => {
        if (type === 'FUMIGATION_AIL') {
          // On mémorise qu’on a préparé une fumigation pour CE raid
          this.preparedGarlicForThisRaid = true;
        }
        if (type === 'INCENDIAIRE') {
          this.hadIncendiaireThisPrephase = true;
        }
      },
      error: e => this.showError(e)
    });
  }

  onActionClick(action: string, index: number){
    if (!this.canUseActionNow(action)) return;
    this.selectedAction = action;
    this.selectedActionIndex = index;

    this.selectedLocation = null;
    this.selectedPotion = null;
    this.selectedPotionIndex = null;
  }

  isGarlicBlockedLocation(location: string): boolean {
    return !!this.game && this.game.garlicBlockedLocations.includes(location);
  }

  garlicTooltip = "Ce lieu est protégé par une fumigation d'ail";

  private isWeatherCancelledForPlayer(p?: SPlayer): boolean {
    if (!p || !this.game || !this.game.weather) return false;

    const status = this.game.weather.status;
    const cancellable =
      status === 'DUSK' ||
      status === 'NIGHT_DARK' ||
      status === 'NIGHT_CLEAR';

    if (!cancellable) return false;

    const loc = this.locationOf(p.id);
    if (!loc) return false;

    return this.game.campfireLocations?.includes(loc) ?? false;
  }

  onNetRoll() {
    if (!this.game || !this.actionSelectedTargetId || this.actionMode !== 'NET') {
      return;
    }
    if (!this.isActionActor) return;
    if (this.actionResolving) return;

    this.actionResolving = true;

    this.api.resolveNet(this.gameId, this.actionSelectedTargetId).subscribe({
      next: () => {
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onPitRoll() {
    if (!this.game || this.actionMode !== 'PIT') return;
    if (this.actionResolving) return;
    if (!this.isActionActor) return; // doit être la victime courante

    const current = this.currentPitTarget;
    if (!current) return;

    this.actionResolving = true;

    this.api.resolvePit(this.gameId).subscribe({
      next: () => {
        // Le résultat arrive via ACTION_ROLLED + getGame
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  private hasRealEnemyOnMyLocation(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    // Joueurs ennemis physiques sur ce lieu
    const playersHere = this.playersOnLocation(loc);
    const enemyPlayers = playersHere.filter(p =>
      p.hp > 0 && (p.role === 'VAMPIRE' || p.role === 'SERVANT')
    );

    // Monstres vivants sur ce lieu
    const monsters = (g as any).monsters as SMonster[] | undefined;
    const monstersHere = (monsters || []).filter(m => m.hp > 0 && m.location === loc);

    return enemyPlayers.length > 0 || monstersHere.length > 0;
  }

  get isActionActor(): boolean {
    const meId = this.me?.id;
    if (!meId) return false;

    if (this.actionMode === 'NET'
      || this.actionMode === 'PROVOCATION'
      || this.actionMode === 'INCENDIAIRE'
      || this.actionMode === 'AMBUSH'
      || this.actionMode === 'BLESSED_STAKE'
      || this.actionMode === 'CHARISMATIQUE'
      || this.actionMode === 'MARCHAND_ITINERANT'
      || this.actionMode === 'MARCHAND_BONUS_BUY'
      || this.actionMode === 'CATACLYSME'
      || this.actionMode === 'CLONES_OMBRE'
      || this.actionMode === 'IMAGE_MIROIR_SETUP'
      || this.actionMode === 'IMAGE_MIROIR_RESOLVE'
      || this.actionMode === 'ECLIPSE'
      || this.actionMode === 'BLOOD_MOON'
      || this.actionMode === 'VOILE_DE_BRUME'
      || this.actionMode === 'MARQUE_TENEBREUSE'
      || this.actionMode === 'AFFAIBLISSEMENT_OCCULTE'
      || this.actionMode === 'PASSAGE_SECRET'
      || this.actionMode === 'AVIDITE_NOCTURNE'
      || this.actionMode === 'EAU_BENITE'
    ) {
      // Filet : acteur = chasseur propriétaire
      return this.actionOwnerId === meId;
    }
    if (this.actionMode === 'PIT') {
      // Fosse : acteur = victime courante
      const current = this.currentPitTarget;
      return !!current && current.id === meId;
    }

    return false;
  }

  // Nom de la cible (pour tout le monde)
  get ActionTargetName(): string | null {
    if (!this.game) return null;
    const targetId = this.actionSelectedTargetId;
    if (!targetId) return null;

    // INCENDIAIRE utilise un code d'infra, pas un joueur
    if (this.actionMode === 'INCENDIAIRE') {
      return this.labelFr(targetId.toLowerCase());
    }

    const p = this.game.players.find(pl => pl.id === targetId);
    return p?.username ?? targetId;
  }

  get currentPitTarget(): SPlayer | null {
    if (!this.trapEnemies || !this.trapEnemies.length) return null;

    // priorité à targetId venant du back
    if (this.actionSelectedTargetId) {
      const found = this.trapEnemies.find(p => p.id === this.actionSelectedTargetId);
      if (found) return found;
    }

    return this.trapEnemies[this.trapCurrentIndex] ?? null;
  }

  selectActionTarget(id: string) {
    // Filet uniquement, par design (Fosse n’a pas de ciblage manuel chez toi)
    if (this.actionMode !== 'NET') return;
    if (!this.isActionActor || this.actionResolving || this.actionRoll !== null) return;

    this.actionResolving = true; // on réutilise ce flag pour désactiver les boutons pendant l’appel

    this.api.setNetTarget(this.gameId, id).subscribe({
      next: () => {
        this.actionResolving = false;
        // On met aussi à jour localement pour feedback instantané
        this.actionSelectedTargetId = id;
        // Le snapshot “officiel” arrivera via l’event ACTION_STARTED
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  private canPlayProvocationHere(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me || me.role !== 'HUNTER') return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    const playersHere = this.playersOnLocation(loc);

    const huntersCount = playersHere.filter(p => p.role === 'HUNTER' && p.hp > 0).length;
    const enemiesCount = playersHere.filter(p =>
      (p.role === 'VAMPIRE' || p.role === 'SERVANT') && p.hp > 0
    ).length;

    return huntersCount >= 2 && enemiesCount >= 1;
  }

  onProvocationChoose(targetId: string) {
    if (!this.game || this.actionMode !== 'PROVOCATION') return;
    if (!this.isActionActor) return;
    if (this.actionResolving) return;

    this.actionResolving = true;

    this.api.resolveProvocation(this.game.id, targetId).subscribe({
      next: () => {
        this.actionResolving = false;
        // La modale se fermera quand currentAction repassera à null via snapshot
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  private computeIncendiaireChoices(g: GameSnapshot, loc: string | null) {
    const built = g.builtInfras || [];
    const pending = g.pendingConstructionInfra as InfraCode | undefined;

    const choices: InfraCode[] = [];
    const addIfPresent = (code: InfraCode) => {
      if (built.includes(code) || pending === code) {
        if (!choices.includes(code)) choices.push(code);
      }
    };

    switch (loc) {
      case 'forest':
      case 'sawmill':
        addIfPresent('SAWMILL');
        break;

      case 'quarry':
      case 'mine':
        addIfPresent('MINE');
        break;

      case 'manor':
        (['LIBRARY','LABORATORY','BALLROOM','ALTAR','FORGE'] as InfraCode[])
          .forEach(addIfPresent);
        break;

      case 'library':
        addIfPresent('LIBRARY');
        break;

      case 'laboratory':
        addIfPresent('LABORATORY');
        break;

      case 'ballroom':
        addIfPresent('BALLROOM');
        break;

      case 'altar':
        addIfPresent('ALTAR');
        break;

      case 'forge':
        addIfPresent('FORGE');
        break;

      default:
        // lac ou autre : aucun choix
        break;
    }

    return choices;
  }

  onIncendiaireRoll() {
    if (!this.game) return;
    if (this.actionMode !== 'INCENDIAIRE') return;
    if (!this.actionSelectedTargetId) return; // ici c'est le code infra
    if (!this.isActionActor) return;
    if (this.actionResolving || this.actionRoll !== null) return;

    this.actionResolving = true;

    this.api.resolveIncendiaire(
      this.gameId,
      this.actionSelectedTargetId as
        'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE'
    ).subscribe({
      next: () => {
        // le résultat (d20 + texte) arrive via le snapshot / websocket
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  canPlayIncendiaireHere(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;

    // Il doit avoir la carte Incendiaire en main
    if (!me.actions || !me.actions.includes('INCENDIAIRE')) return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    // On réutilise la logique de mapping loc -> infra jouables
    const snapshot = g as unknown as GameSnapshot;
    const choices = this.computeIncendiaireChoices(snapshot, loc);

    return choices.length > 0;
  }

  onAmbushChoose(targetId: string) {
    if (!this.game) return;
    this.actionResolving = true;

    this.api.resolveAmbush(this.game.id, targetId).subscribe({
      next: _g => {
        this.actionResolving = false;
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onBlessedStakeRoll() {
    if (!this.game || this.actionMode !== 'BLESSED_STAKE') {
      return;
    }
    if (!this.isActionActor) return;
    if (this.actionResolving) return;

    this.actionResolving = true;

    this.api.resolveBlessedStake(this.gameId).subscribe({
      next: () => {
        this.actionResolving = false;
        // la suite est gérée par syncActionFromSnapshot + combatContinue/ACTION_RESOLVED
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onMerchantRoll(): void {
    if (!this.game) return;

    this.actionResolving = true;
    this.api.rollMerchant(this.gameId).subscribe({
      next: g => {
        this.actionResolving = false;
        this.bumpHistoryScroll();
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  merchantResultText(): string {
    const v = this.actionRoll;
    if (v == null) return '';

    if (v === 1 || v === 2) {
      return "une potion est disponible à la boutique.";
    }
    if (v === 3 || v === 4) {
      return "un élixir est disponible à la boutique.";
    }
    return "un équipement est disponible à la boutique.";
  }

  merchantBuyText(): string {
    const g = this.game;
    const kind = g?.shopBonusKind;
    if (!kind) return 'Marchand itinérant — offre spéciale disponible.';

    switch (kind) {
      case 'POTION':
        return 'Marchand itinérant — choisissez comment payer la potion (ressources ou or).';
      case 'ELIXIR':
        return 'Marchand itinérant — choisissez comment payer l\'élixir (ressources ou or).';
      case 'EQUIP_WEAPON':
        return 'Marchand itinérant — choisissez comment payer l\'arme (ressources ou or).';
      case 'EQUIP_ARMOR':
        return 'Marchand itinérant — choisissez comment payer l\'armure (ressources ou or).';
      default:
        return 'Marchand itinérant — choisissez un mode de paiement.';
    }
  }

  bonusTitle(): string {
    const g = this.game;
    const kind = g?.shopBonusKind;
    if (!kind) return 'Objet bonus';

    switch (kind) {
      case 'POTION':        return 'Potion';
      case 'ELIXIR':        return 'Élixir';
      case 'EQUIP_WEAPON':  return 'Arme du marchand';
      case 'EQUIP_ARMOR':   return 'Armure du marchand';
      default:              return 'Objet bonus';
    }
  }

  canBuyBonus(): boolean {
    const me = this.me;
    if (!me) return false;
    if (me.hp <= 0) return false;
    return this.canPayBonusWithResource() || this.canPayBonusWithGold();
  }

  canPayBonusWithResource(): boolean {
    const g = this.game;
    const me = this.me;
    const kind = g?.shopBonusKind;
    if (!g || !me || me.role !== 'HUNTER' || !kind) return false;

    switch (kind) {
      case 'POTION':
        return me.water >= 4 && me.herbs >= 3;
      case 'ELIXIR':
        return me.water >= 6 && me.herbs >= 6;
      case 'EQUIP_WEAPON':
      case 'EQUIP_ARMOR':
        return me.wood >= 6 && me.iron >= 6;
      default:
        return false;
    }
  }

  canPayBonusWithGold(): boolean {
    const g = this.game;
    const me = this.me;
    const kind = g?.shopBonusKind;
    if (!g || !me || me.role !== 'HUNTER' || !kind) return false;

    let base: number;
    switch (kind) {
      case 'POTION':       base = 60; break;
      case 'ELIXIR':       base = 120; break;
      case 'EQUIP_WEAPON':
      case 'EQUIP_ARMOR':  base = 150; break;
      default:             return false;
    }

    let cost = base;
    const greedy = (g as any).shopPricesIncreasedThisRaid;
    if (greedy) cost += 50;

    if (me.charismaticThisRaid) {
      cost = Math.max(0, cost - 20);
    }
    return me.gold >= cost;
  }

  onConfirmBonus(mode: 'RESOURCE' | 'GOLD'): void {
    if (!this.game) return;
    this.actionResolving = true;

    this.api.buyShopBonus(this.gameId, mode).subscribe({
      next: g => {
        this.actionResolving = false;
        // currentAction est remis à null côté back → syncActionFromSnapshot fermera la modale
        this.bumpHistoryScroll();
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onBuyBonus(ev: MouseEvent): void {
    if (!this.game) return;

    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.StartShopBonus(this.gameId).subscribe({
      next: g => {
        this.flyDomToViewportBottom(src, rect);
        this.bumpHistoryScroll();
      },
      error: e => this.showError(e)
    });
  }

  onCancelBonus(): void {
    if (!this.game) return;

    this.actionResolving = true;
    this.api.cancelShopBonus(this.gameId).subscribe({
      next: () => {
        this.actionResolving = false;
        // currentAction passe à null côté back et ACTION_RESOLVED est émis,
        // donc on va recevoir l’event, faire getGame(), syncActionFromSnapshot()
        // et la modale se fermera toute seule.
        this.bumpHistoryScroll();
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onSelectWeather2(ws: string) {
    this.selectedWeather2 = ws;
    if (this.selectedWeather3 === ws) {
      this.selectedWeather3 = null;
    }
  }

  onSelectWeather3(ws: string) {
    this.selectedWeather3 = ws;
    if (this.selectedWeather2 === ws) {
      this.selectedWeather2 = null;
    }
  }

  onCataclysmeConfirm() {
    if (!this.game || !this.isActionActor) return;
    if (!this.selectedWeather2 || !this.selectedWeather3) return;
    if (this.selectedWeather2 === this.selectedWeather3) return;

    this.actionResolving = true;
    this.api.resolveCataclysme(this.game.id, this.selectedWeather2, this.selectedWeather3)
      .subscribe({
        next: () => { this.actionResolving = false; },
        error: e => { this.actionResolving = false; this.showError(e); }
      });
  }

  labelWeather(ws: string | null | undefined): string {
    if (!ws) return '';

    switch (ws) {
      case 'SUNNY':
        return 'Jour ensoleillé';
      case 'FOG':
        return 'Brouillard protecteur';
      case 'AURORA':
        return 'Aurore';
      case 'CLOUDY':
        return 'Ciel couvert';
      case 'WIND':
        return 'Cyclone';
      case 'STORM':
        return 'Orage';
      case 'RAIN':
        return 'Pluie diluvienne';
      case 'BLIZZARD':
        return 'Blizzard';
      case 'DUSK':
        return 'Crépuscule';
      case 'NIGHT_DARK':
        return 'Nuit obscure';
      case 'NIGHT_CLEAR':
        return 'Nuit claire';
      case 'FULL_MOON':
        return 'Pleine lune';
      default:
        // au cas où un nouveau statut arrive, on affiche la clé brute
        return ws;
    }
  }

  get cataclysmeLabelPair(): string | null {
    if (!this.actionSelectedTargetId) return null;
    const parts = this.actionSelectedTargetId.split(',');
    const w1 = parts[0]?.trim();
    const w2 = parts[1]?.trim();
    if (!w1 || !w2) return null;
    return `${this.labelWeather(w1)} & ${this.labelWeather(w2)}`;
  }

  get clonesIndexes(): number[] {
    if (this.actionRoll == null) return [];
    return Array.from({ length: this.actionRoll }, (_, i) => i);
  }

  onClonesRoll() {
    if (!this.game || this.actionMode !== 'CLONES_OMBRE') return;
    if (!this.isActionActor || this.actionResolving || this.actionRoll !== null) return;

    this.actionResolving = true;

    this.api.rollClones(this.game.id).subscribe({
      next: _g => {
        this.actionResolving = false;
        // le snapshot à jour arrive via websockets / GET
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onCloneLocationChange(idx: number, ev: Event) {
    const select = ev.target as HTMLSelectElement;
    const loc = select.value;

    const arr = [...(this.clonesSelectedLocations || [])];
    arr[idx] = loc || '';
    this.clonesSelectedLocations = arr;
  }

  onClonesConfirm() {
    if (!this.game || this.actionMode !== 'CLONES_OMBRE') return;
    if (!this.isActionActor || this.actionResolving) return;
    if (!this.canConfirmClones()) return;

    this.actionResolving = true;

    this.api.confirmClones(this.game.id, this.clonesSelectedLocations).subscribe({
      next: _g => {
        this.actionResolving = false;
        // snapshot + fermeture modale via syncActionFromSnapshot
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  canConfirmClones(): boolean {
    if (this.actionRoll == null) return false;
    if (!this.clonesSelectedLocations) return false;
    if (this.clonesSelectedLocations.length !== this.actionRoll) return false;

    // Chaque clone doit avoir un lieu non vide
    return this.clonesSelectedLocations.every(l => !!l);
  }

  get clonesLocationChoices(): string[] {
    const me = this.me;
    const g  = this.game;
    if (!g || !me || !me.hand) return [];
    return me.hand.filter(loc => !this.isGarlicBlockedLocation(loc));
  }

  onMirrorSetupConfirm() {
    if (!this.game || !this.selectedMirrorLoc || !this.isActionActor || this.actionResolving) return;

    this.actionResolving = true;
    this.api.resolveImageMiroirSetup(this.game.id, this.selectedMirrorLoc).subscribe({
      next: () => {
        this.actionResolving = false;
        // La modale se fermera quand currentAction repassera à null côté snapshot
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  onMirrorResolveChoose(loc: string) {
    if (!this.game || !this.isActionActor || this.actionResolving) return;

    this.actionResolving = true;
    this.api.resolveImageMiroirChoice(this.game.id, loc).subscribe({
      next: () => {
        this.actionResolving = false;
        // Là aussi, fermeture via snapshot (currentAction null)
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  get mirrorLocationChoices(): string[] {
    const me = this.me;
    const g  = this.game;
    if (!g || !me || !me.hand) return [];

    const usedAlts: string[] = (g as any).mirrorAltLocations || [];

    return me.hand
      // pas fumigé
      .filter(loc => !this.isGarlicBlockedLocation(loc))
      // pas déjà choisi par une Image miroir précédente de CE raid
      .filter(loc => !usedAlts.includes(loc));
  }

  onDarkMarkChoose(targetId: string) {
    if (!this.game) return;

    this.actionResolving = true;
    this.api.resolveDarkMark(this.game.id, targetId).subscribe({
      next: () => { this.actionResolving = false; },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  isDarkMarked(p: SPlayer): boolean {
    if (!this.game?.raidMods) return false;
    const list = this.game.raidMods[p.id] || [];
    return list.some(m => {
      const src = (m as any).source as string | undefined;
      return src != null && src.startsWith('CORRUPTION:MARK') && src.includes(':DSP');
    });
  }

  isDarkMarkedMe(): boolean {
    const me = this.me;
    return !!(me && this.isDarkMarked(me));
  }

  onHolyWaterChoice(choice: 'REDUCE' | 'ATTACK' | 'CLEANSE'): void {
    if (!this.game) return;
    // (optionnel : sécurité côté front)
    if (choice === 'REDUCE' && !this.canHolyWaterReduce()) return;
    if (choice === 'ATTACK' && !this.canHolyWaterAttack()) return;
    if (choice === 'CLEANSE' && !this.canHolyWaterCleanse()) return;
    this.actionResolving = true;

    this.api.resolveHolyWater(this.game.id, choice).subscribe({
      next: () => {
        this.actionResolving = false;
        // Le WS ACTION_RESOLVED rafraîchira la game, fermera la modale
        // et relancera le timer PREPHASE3 côté front.
      },
      error: e => {
        this.actionResolving = false;
        this.showError(e);
      }
    });
  }

  canHolyWaterReduce(): boolean {
    const me = this.me;
    if (!me) return false;
    return (me.corruption || 0) > 0;
  }

  canHolyWaterAttack(): boolean {
    const g = this.game as GameSnapshot | null;
    const me = this.me;
    if (!g || !me) return false;

    const loc = this.locationOf(me.id);
    if (!loc) return false;

    // Au moins un vampire / serviteur vivant sur le même lieu
    return this.playersOnLocation(loc).some(p =>
      (p.role === 'VAMPIRE' || p.role === 'SERVANT') && p.hp > 0
    );
  }

  canHolyWaterCleanse(): boolean {
    const g = this.game as GameSnapshot | null;
    const me = this.me;
    if (!g || !me || !g.raidMods) return false;

    const list = g.raidMods[me.id] || [];

    return list.some(m => {
      const src = (m as any).source as string | undefined;
      if (!src) return false;
      return src.startsWith('CORRUPTION:MARK') && src.endsWith(':DSP');
    });
  }

  // Holy water jouable ? (pour bouton "J’ai fini", canUseActionNow, etc.)
  canPlayHolyWaterkHere(): boolean {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return false;

    if (!me.actions || !me.actions.includes('EAU_BENITE')) return false;

    return (
      this.canHolyWaterReduce() ||
      this.canHolyWaterAttack() ||
      this.canHolyWaterCleanse()
    );
  }

  onOccultWeakeningTarget(targetId: string) {
    if (!this.game) return;
    this.api.resolveOccultWeakening(this.game.id, targetId).subscribe({
      next: g => {
        this.game = g;
        this.syncActionFromSnapshot(g);
      },
      error: e => this.showError(e)
    });
  }

  computeSecretPassageChoices(g: GameSnapshot, currentLoc: string | null): string[] {
    const choices = new Set<string>();

    const baseLocs = ['forest', 'quarry', 'lac', 'manor'];
    for (const code of baseLocs) {
      if (code && code !== currentLoc) {
        choices.add(code);
      }
    }

    const built = g.builtInfras || [];
    for (const infra of built) {
      switch (infra) {
        case 'SAWMILL':    choices.add('sawmill'); break;
        case 'MINE':       choices.add('mine'); break;
        case 'LIBRARY':    choices.add('library'); break;
        case 'LABORATORY': choices.add('laboratory'); break;
        case 'BALLROOM':   choices.add('ballroom'); break;
        case 'ALTAR':      choices.add('altar'); break;
        case 'FORGE':      choices.add('forge'); break;
      }
    }

      return [...baseLocs, ...built]
    .filter(loc => loc !== currentLoc)                    // pas le même lieu
    .filter(loc => !this.isGarlicBlockedLocation(loc));   // pas fumigé
  }

  onSecretPassageConfirm() {
    if (!this.game || !this.selectedSecretPassageLoc || this.actionResolving) return;

    this.actionResolving = true;
    this.api.resolveSecretPassage(this.game.id, this.selectedSecretPassageLoc)
      .subscribe({
        next: _g => {
          // comme pour Marque / Affaiblissement / Holy
          this.actionResolving = false;
          // Le vrai refresh viendra via ACTION_RESOLVED, mais on peut aussi
          // mettre à jour tout de suite si tu veux.
        },
        error: e => {
          this.actionResolving = false;
          this.showError(e);
        }
      });
  }

  get actionOwner() {
    const g = this.game;
    if (!g || !g.currentAction) return null;
    return g.players.find(p => p.id === g.currentAction?.ownerId) ?? null;
  }

  get actionDiceColor(): 'red' | 'blue' {
    if (this.actionMode === 'NET' || this.actionMode === "INCENDIAIRE" || this.actionMode === 'BLESSED_STAKE') return 'blue';
    if (this.actionMode === 'PIT' || this.actionMode === "CLONES_OMBRE") return 'red';
    return 'blue';
  }

  private syncActionFromSnapshot(g: GameSnapshot) {
    const act = g.currentAction;

    // 1) Pas d'action ou mode non géré -> on ferme
    if (!act || (act.mode !== 'NET'
              && act.mode !== 'PIT'
              && act.mode !== 'PROVOCATION'
              && act.mode !== 'INCENDIAIRE'
              && act.mode !== 'AMBUSH'
              && act.mode !== 'BLESSED_STAKE'
              && act.mode !== 'CHARISMATIQUE'
              && act.mode !== 'MARCHAND_ITINERANT'
              && act.mode !== 'MARCHAND_BONUS_BUY'
              && act.mode !== 'PRESENCE_ECRASANTE'
              && act.mode !== 'CATACLYSME'
              && act.mode !== 'CLONES_OMBRE'
              && act.mode !== 'IMAGE_MIROIR_SETUP'
              && act.mode !== 'IMAGE_MIROIR_RESOLVE'
              && act.mode !== 'ECLIPSE'
              && act.mode !== 'BLOOD_MOON'
              && act.mode !== 'VOILE_DE_BRUME'
              && act.mode !== 'FAIM_IRREPRESSIBLE'
              && act.mode !== 'MARQUE_TENEBREUSE'
              && act.mode !== 'AFFAIBLISSEMENT_OCCULTE'
              && act.mode !== 'PASSAGE_SECRET'
              && act.mode !== 'AVIDITE_NOCTURNE'
              && act.mode !== 'EAU_BENITE')) {
      this.actionMode = null;
      this.actionOwnerId = null;
      this.actionLocation = null;
      this.actionSelectedTargetId = null;
      this.actionRoll = null;
      this.actionBreakdownLines = [];
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.showActionModal = false;
      this.incendiaireChoices = [];
      this.selectedWeather2 = null;
      this.selectedWeather3 = null;
      return;
    }

    // 2) Base commune
    this.actionMode = act.mode as any;
    this.actionOwnerId = act.ownerId;
    this.actionLocation = act.location;
    this.actionSelectedTargetId = act.targetId ?? null;
    this.actionRoll = act.roll ?? null;
    this.actionBreakdownLines = act.breakdownLines ?? [];

    // 3) NET / PIT / PROVOCATION
    if (this.actionMode === 'NET' || this.actionMode === 'PIT' || this.actionMode === 'PROVOCATION') {
      if (this.actionLocation && g.players) {
        this.trapEnemies = this.playersOnLocation(this.actionLocation)
          .filter(p => p.role === 'VAMPIRE' || p.role === 'SERVANT');
      } else {
        this.trapEnemies = [];
      }

      if (this.actionMode === 'PIT') {
        if (this.actionSelectedTargetId && this.trapEnemies.length) {
          const idx = this.trapEnemies.findIndex(p => p.id === this.actionSelectedTargetId);
          this.trapCurrentIndex = idx >= 0 ? idx : 0;
        } else {
          this.trapCurrentIndex = 0;
        }
      } else {
        this.trapCurrentIndex = 0;
      }

      this.incendiaireChoices = [];
    }

    // 3bis) EMBUSCADE : liste des ennemis sur le lieu
    if (this.actionMode === 'AMBUSH') {
      if (this.actionLocation && g.players) {
        this.ambushEnemies = this.playersOnLocation(this.actionLocation)
          .filter(p => p.role === 'VAMPIRE' || p.role === 'SERVANT');
      } else {
        this.ambushEnemies = [];
      }

      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
    }

    // 4) INCENDIAIRE
    if (this.actionMode === 'INCENDIAIRE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = this.computeIncendiaireChoices(g, this.actionLocation);

      if (!this.actionSelectedTargetId && this.incendiaireChoices.length === 1) {
        this.actionSelectedTargetId = this.incendiaireChoices[0];
      }
    }

    // 5) PRÉSENCE ÉCRASANTE / ECLIPSE / BLOOD_MOON etc : juste info
    if (this.actionMode === 'CHARISMATIQUE'
      || this.actionMode === 'PRESENCE_ECRASANTE' 
      || this.actionMode === 'ECLIPSE' 
      || this.actionMode === 'BLOOD_MOON'
      || this.actionMode === 'VOILE_DE_BRUME'
      || this.actionMode === 'FAIM_IRREPRESSIBLE'
      || this.actionMode === 'AVIDITE_NOCTURNE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
    }

    // 6) CATACLYSME : choix météo uniquement
    if (this.actionMode === 'CATACLYSME') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
      this.selectedWeather2 = null;
      this.selectedWeather3 = null;
    }

    // 7) CLONES_OMBRE: D4 + choix de lieux
    if (this.actionMode === 'CLONES_OMBRE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
      if (this.actionRoll === null) {
        this.clonesSelectedLocations = [];
      }
    }

    // 8) IMAGE MIROIR
    if (this.actionMode === 'IMAGE_MIROIR_RESOLVE') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];

      const ownerId = act.ownerId;
      const gSnap = g;

      // Liste des lieux alternatifs envoyés par le back
      this.mirrorAltLocations = gSnap.mirrorAltLocations || [];

      // Lieu principal = carte actuelle du vampire sur le centre
      const cb = gSnap.center?.find(c => c.playerId === ownerId) || null;
      this.mirrorPrimaryLoc = cb ? cb.card : null;

      // Construire la liste de choix (primary + alts, sans doublons)
      this.mirrorChoices = [];
      this.mirrorHuntersByLoc = {};

      if (this.mirrorPrimaryLoc) {
        this.mirrorChoices.push(this.mirrorPrimaryLoc);
      }
      for (const loc of this.mirrorAltLocations) {
        if (loc && !this.mirrorChoices.includes(loc)) {
          this.mirrorChoices.push(loc);
        }
      }

      // Pour chaque choix, pré-calculer les chasseurs présents
      for (const loc of this.mirrorChoices) {
        this.mirrorHuntersByLoc[loc] = this.playersOnLocation(loc).filter(p => p.role === 'HUNTER');
      }
    }

    // 9) MARQUE_TENEBREUSE / EAU_BENITE / AFFAIBLISSEMENT_OCCULTE: actions de choix, on met en pause le timer local
    if (this.actionMode === 'MARQUE_TENEBREUSE' 
      || this.actionMode === 'EAU_BENITE' 
      || this.actionMode === 'AFFAIBLISSEMENT_OCCULTE'
      || this.actionMode === 'PROVOCATION'
      || this.actionMode === 'AMBUSH') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];
      // On met en pause le timer local de PREPHASE tant que la marque n'est pas résolue
      this.stopPrephaseTimer();
    }

    // 10) PASSAGE_SECRET : choix d'un nouveau lieu
    if (this.actionMode === 'PASSAGE_SECRET') {
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.incendiaireChoices = [];

      const me = this.me;
      const loc = me ? this.locationOf(me.id) : null;
      this.secretPassageChoices = this.computeSecretPassageChoices(g, loc);

      if (!this.selectedSecretPassageLoc && this.secretPassageChoices.length === 1) {
        this.selectedSecretPassageLoc = this.secretPassageChoices[0];
      }

      // Pause du timer local de PREPHASE
      this.stopPrephaseTimer();
    }

    if (this.actionMode === 'CHARISMATIQUE') {
      const alreadyShown = this.lastCharismRaid === g.raid;

      if (!alreadyShown) {
        this.lastCharismRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();
      }
    }

    if (this.actionMode === 'AVIDITE_NOCTURNE') {
      const alreadyShown = this.lastGreedRaid === g.raid;

      if (!alreadyShown) {
        this.lastGreedRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();
      }
    }

    // 11) Afficher la modale / timers
    if (this.actionMode === 'PRESENCE_ECRASANTE') {
      const alreadyShown = this.lastPresenceRaid === g.raid;

      if (!alreadyShown) {
        this.lastPresenceRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }

    } else if (this.actionMode === 'ECLIPSE') {
      const alreadyShown = this.lastEclipseRaid === g.raid;

      if (!alreadyShown) {
        this.lastEclipseRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }

    } else if (this.actionMode === 'BLOOD_MOON') {
      const alreadyShown = this.lastBloodMoonRaid === g.raid;

      if (!alreadyShown) {
        this.lastBloodMoonRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }

    } else if (this.actionMode === 'VOILE_DE_BRUME') {
      const alreadyShown = this.lastFogRaid === g.raid;

      if (!alreadyShown) {
        this.lastFogRaid = g.raid;
        this.showActionModal = true;
        this.scheduleActionAutoClose();

        if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
          this.startPrephaseTimer();
        }
      }

    } else if (this.actionMode === 'FAIM_IRREPRESSIBLE') {
      this.showActionModal = true;
      this.scheduleActionAutoClose();

      if (g.phase === 'PREPHASE3' && !g.locationEffectPending) {
        this.startPrephaseTimer();
      }

    } else if (this.actionMode === 'CATACLYSME') {
      const resolved = !!act.targetId;

      if (!resolved) {
        this.showActionModal = true;
      } else {
        const alreadyShown = this.lastCataclysmeRaid === g.raid;

        if (!alreadyShown) {
          this.lastCataclysmeRaid = g.raid;
          this.showActionModal = true;
          this.scheduleActionAutoClose();
        }
      }

    } else if (this.actionMode === 'MARQUE_TENEBREUSE' 
      || this.actionMode === 'PROVOCATION'
      || this.actionMode === 'AMBUSH'
      || this.actionMode === 'BLESSED_STAKE'
      || this.actionMode === 'EAU_BENITE' 
      || this.actionMode === 'PASSAGE_SECRET' 
      || this.actionMode === 'AFFAIBLISSEMENT_OCCULTE') {
      // Marque ténébreuse : on affiche, PAS d’auto-close, PAS de reset de timer ici
      // (le timer est déjà stoppé plus haut, et sera relancé dans ACTION_RESOLVED)
      this.showActionModal = true;

    } else if (this.actionMode === 'MARCHAND_ITINERANT') {
        this.showActionModal = true;
        if (this.actionRoll != null) this.scheduleActionAutoClose(); // on ferme 5s après le résultat
    } else if (this.actionMode === 'MARCHAND_BONUS_BUY') {
      // On ouvre, mais on ne met PAS d’auto-close :
      // la modale se fermera quand currentAction repassera à null
      this.showActionModal = true;

    } else {
      // NET / PIT / INCENDIAIRE / CLONES / IMAGE_MIROIR_SETUP / IMAGE_MIROIR_RESOLVE
      this.showActionModal = true;
    }
  }

  // ====== Corruption / morsure ======/
  get showBiteModal(): boolean {
    const g = this.game as any;
    return g?.phase === 'PHASE3' && !!g?.currentBite && Date.now() >= this.biteNotBeforeMillis;
  }

  biteAttacker(): SPlayer | undefined {
    const id = (this.game as any)?.currentBite?.attackerId;
    return id ? this.getPlayer(id) : undefined;
  }

  biteTarget(): SPlayer | undefined {
    const id = (this.game as any)?.currentBite?.targetId;
    return id ? this.getPlayer(id) : undefined;
  }

  rollCorruption() {
    if (!this.game) return;
    this.api.rollCorruption(this.game.id).subscribe({
      error: e => this.showError(e)
    });
  }

  // 1) Détermine la "phase" actuelle de la morsure : D6, D4, terminé
  biteStage(): 'BITE' | 'ARMOR' | 'DONE' {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b) return 'DONE';

    // Pas encore de jet de morsure → D6
    if (b.roll == null) return 'BITE';

    // Jet de morsure fait : voir si armure spéciale intervient
    const target = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(target);

    if (b.roll > 3 && hasArmor && b.armorRoll == null) {
      return 'ARMOR';
    }

    return 'DONE';
  }

  // 2) Est-ce qu’on affiche le D4 d’armure ?
  get showArmorDie(): boolean {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b) return false;
    const target = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(target);
    return hasArmor && b.roll != null;
  }

  // 3) Qui peut lancer le D6 de morsure ?
  canRollBite(): boolean {
    const g = this.game;
    const b = g?.currentBite;
    if (!g || !b) return false;
    if (b.roll != null) return false; // déjà lancé

    const meId = this.me?.id;
    if (!meId) return false;

    // Seul le vampire lance le D6
    const me = this.getPlayer(meId);
    return me?.role === 'VAMPIRE';
  }

  // 4) Qui peut lancer le D4 d’armure ?
  canRollArmor(): boolean {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!g || !b) return false;

    if (b.roll == null) return false;           // pas encore de morsure
    if (b.armorRoll != null) return false;      // déjà fait

    const meId = this.me?.id;
    if (!meId || meId !== b.targetId) return false;

    const target = this.getPlayer(meId);
    return this.hasSilverPlate(target);
  }

  // 5) Action : jet d’armure (appel le même endpoint back)
  rollArmor() {
    if (!this.game) return;
    this.api.rollCorruption(this.game.id).subscribe({
      error: e => this.showError(e)
    });
  }

  // 6) Résumé texte du résultat final
  biteResultText(): string {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b || b.roll == null) return '';

    const attacker = this.getPlayer?.(b.attackerId)?.username ?? 'Le vampire';
    const target   = this.getPlayer?.(b.targetId)?.username   ?? 'le chasseur';

    const targetPlayer = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(targetPlayer);

    // Échec direct du D6
    if (b.roll <= 3) {
      return `${attacker} échoue sa tentative de morsure.`;
    }

    if (this.isSacredRosaryUsed) {
      return `${attacker} est repoussé par un chapelet sacré.`;
    }

    // Pas d’armure spéciale → morsure réussie
    if (!hasArmor || b.armorRoll == null) {
      return `${target} est mordu.`;
    }

    // Avec armure: à adapter selon ta règle exacte !
    // Ici j’assume : 1-2 = armure échoue (morsure passe), 3-4 = armure bloque.
    if (b.armorRoll <= 2) {
      return `${target} est mordu malgré son armure d'argent.`;
    } else {
      return `L'armure d'argent de ${target} le protège de la morsure.`;
    }
  }

  altarRitualText(): string  {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b || b.roll == null) return '';

    const isAltarFight = g.currentCombat?.location === 'altar';
    if (!isAltarFight || g.altarCorrupted) return '';

    const target = this.getPlayer?.(b.targetId);
    const hasArmor = this.hasSilverPlate(target);

    let success = false;
    if (b.roll > 3) {
      if (!hasArmor || b.armorRoll == null) {
        success = true;
      } else {
        // même hypothèse que ci-dessus : 1-2 = morsure réussie
        success = b.armorRoll <= 2;
      }
    }

    if (success) {
      return `Le rituel est accompli : l'autel est profané.`;
    }
    return '';
  }

  // 7) Test d’armure argent (à adapter au champ réel du snapshot)
  private hasSilverPlate(p: SPlayer | undefined): boolean {
    if (!p) return false;
    // Adapte ce test au nom réel de l’armure dans ton snapshot :
    // ex : (p as any).armor === 'SILVER_PLATE'
    return (p as any).armor === 'SILVER_PLATE';
  }

  // Conditions d’ouverture de la modale (seulement vampire + PREPHASE3 + choix restants)
  showUnstableModal(): boolean {
    return !!this.game
      && this.game.phase === 'PREPHASE3'
      && this.isMeVampire
      && this.pendingUnstable();
  }
  unstableEntries() {
    const g = this.game as any;
    if (!g) return [];
    const eligT = g.unstableEligibleTargets || {};
    const eligL = g.unstableEligibleLocations || {};
    const ids = new Set<string>([...Object.keys(eligT), ...Object.keys(eligL)]);
    return [...ids].map(uId => ({
      unstableId: uId,
      targets: eligT[uId] || [],
      locations: eligL[uId] || []
    }));
  }
  pendingUnstable(): boolean {
    const g = this.game as any;
    if (!g) return false;
    const t = g.unstableEligibleTargets || {};
    const l = g.unstableEligibleLocations || {};
    return Object.keys(t).length > 0 || Object.keys(l).length > 0;
  }

  assignUnstableTarget(unstableId: string, targetId: string) {
    if (!this.isMeVampire || this.isUnstableLocked(unstableId)) return;

    this.lockUnstable(unstableId);
    this.api.assignUnstableTarget(this.gameId, unstableId, targetId).subscribe({
      next: () => {},
      error: e => {
        // 409 "no pending unstable choice" => le serveur a déjà pris une décision : on laisse lock
        if (!(e?.status === 409 || e?.error?.message === 'no pending unstable choice')) {
          this.unlockUnstable(unstableId); // vraie erreur -> permettre un retry
        }
        this.showError(e);
      }
    });
  }

  assignUnstableHarvest(unstableId: string, loc: string) {
    if (!this.isMeVampire || this.isUnstableLocked(unstableId)) return;

    this.lockUnstable(unstableId);
    this.api.assignUnstableHarvest(this.gameId, unstableId, loc).subscribe({
      next: () => {},
      error: e => {
        if (!(e?.status === 409 || e?.error?.message === 'no pending unstable choice')) {
          this.unlockUnstable(unstableId);
        }
        this.showError(e);
      }
    });
  }

  assignUnstableNothing(unstableId: string) {
    if (!this.isMeVampire || !this.isPendingUnstable(unstableId)) return;

    this.api.assignUnstableNothing(this.gameId, unstableId).subscribe({
      next: _ => {
        // Resync — garantit que la modale se met à jour tout de suite
        this.api.getGame(this.gameId).subscribe({
          next: g => { this.game = g; this.recomputeUnstableChoices(); },
          error: e => this.showError(e)
        });
      },
      error: e => this.showError(e)
    });
  }

  // Garder l’array stable et ne le remplacer que si le contenu change
  unstableChoices: Array<{ unstableId: string; targets: string[]; locations: string[] }> = [];
  trackByUnstable = (_i: number, it: { unstableId: string }) => it.unstableId;

  private recomputeUnstableChoices() {
    const g: any = this.game;
    if (!g) { this.unstableChoices = []; return; }

    const t: Record<string, string[]> = g.unstableEligibleTargets || {};
    const L: Record<string, string[]> = g.unstableEligibleLocations || {};

    const next: Array<{ unstableId: string; targets: string[]; locations: string[] }> = [];
    const ids = new Set([...Object.keys(t || {}), ...Object.keys(L || {})]);
    for (const uid of ids) {
      next.push({
        unstableId: uid,
        targets: (t?.[uid] ?? []).slice(),
        locations: (L?.[uid] ?? []).slice(),
      });
    }

    if (JSON.stringify(this.unstableChoices) !== JSON.stringify(next)) {
      this.unstableChoices = next;
    }

      // ➜ purge des locks pour les IDs qui ne sont plus éligibles (ou déjà décidés)
    const present = new Set(this.unstableChoices.map(x => x.unstableId));
    const decidedIds = new Set<string>([
      ...Object.keys(g.unstableTargetByPlayer || {}),
      ...Object.keys(g.unstableHarvestLocByPlayer || {}),
    ]);
    for (const id of Array.from(this.unstableLockedIds)) {
      if (!present.has(id) || decidedIds.has(id)) this.unstableLockedIds.delete(id);
    }
  }

  vampireName(): string {
    const v = this.game?.players.find(p => p.role === 'VAMPIRE');
    return v?.username || v?.id || 'vampire';
  }

  // Texte d’avertissement si l’instable va combattre par défaut (même lieu qu’un ennemi)
  // Retourne null sinon (aucun avertissement à afficher)
  getUnstableDefaultFight(unstableId: string): DefaultFightInfo {
    const g = this.game;
    if (!g || g.phase !== 'PREPHASE3') return { willFight: false };

    const unstable = g.players.find(p => p.id === unstableId);
    if (!unstable || unstable.role !== 'HUNTER' || unstable.hp <= 0) return { willFight: false };

    // lieu face-up de l’instable
    const cb = (g.center || []).find(c => c.playerId === unstableId && c.faceUp);
    if (!cb) return { willFight: false };
    const loc = cb.card;

    // joueurs présents (face-up) sur ce lieu
    const idsOnLoc = (g.center || []).filter(c => c.faceUp && c.card === loc).map(c => c.playerId);
    const ppl = idsOnLoc
      .map(id => g.players.find(p => p.id === id))
      .filter((p): p is SPlayer => !!p);

    // adversaire prioritaire : vampire, sinon 1er serviteur
    const opponent = ppl.find(p => p.role === 'VAMPIRE') || ppl.find(p => p.role === 'SERVANT');
    if (!opponent) return { willFight: false };

    const opponentName = opponent.username || (opponent.role === 'VAMPIRE' ? 'vampire' : 'serviteur');
    return { willFight: true, loc, opponentName };
  }

  unstableHarvestWarning(unstableId: string): string | null {
    const info = this.getUnstableDefaultFight(unstableId);
    if (!info.willFight) return null;

    const unstableName = this.usernameOf(unstableId);
    const locName = this.labelLocation(info.loc!);

    return `${unstableName} va bientôt combattre sur ${locName} contre ${info.opponentName}. ` +
          `Si vous lui ordonnez une récolte, cela annulera son combat !`;
  }

  // encore éligible ? (présent dans l’un des deux maps serveur)
  isPendingUnstable(id: string): boolean {
    const g: any = this.game; if (!g) return false;
    const t = g.unstableEligibleTargets || {};
    const l = g.unstableEligibleLocations || {};
    return !!(t[id] || l[id]);
  }

  //====== Maintenance ======/
  // Helpers Maintenance:
  private openShop() {
    document.body.classList.add('modal-open');
    this.shopOpen = true;
    this.waitingDone = false;
    this.myOffer = {};
  }

  private closeShop() {
    document.body.classList.remove('modal-open');
    this.shopOpen = false;
    this.waitingDone = false;
    this.stopPhase4Timer();
  }

  private syncShopVisibilityFromSnapshot(): void {
    const inPhase4 = this.game?.phase === 'PHASE4';

    // ouvrir/fermer la modale
    if (inPhase4 && !this.shopOpen) this.openShop();
    if (!inPhase4 && this.shopOpen) this.closeShop();

    // (re)lancer le timer local (comme pour préphase)
    const deadline = (this.game as any)?.phase4DeadlineMillis as number | undefined;
    if (inPhase4) this.startPhase4Timer(deadline ?? null);

    // IMPORTANT : garder l’état “En attente…” après reload
    const ready = (this.game as any)?.readyForNextRaid as string[] | undefined;
    this.waitingDone = Array.isArray(ready) ? ready.includes(this.meId) : false;
  }

  // Retourne MON statut (A ou B selon que je suis aId ou bId)
  public myStatus(t: STrade): TradeStatus {
    const iAmA = (t.aId === this.me?.id);
    return iAmA ? t.statusA : t.statusB;
  }

  // Retourne le statut de l’AUTRE
  public otherStatus(t: STrade): TradeStatus {
    const iAmA = (t.aId === this.me?.id);
    return iAmA ? t.statusB : t.statusA;
  }

  statusClassFrom(st: TradeStatus): string {
    if (st === 'CONFIRMED') return 'ok';
    if (st === 'REFUSED' || st === 'CANCELLED') return 'ko';
    return ''; // PENDING
  }

  deckSize(pile: Pile | null | undefined): number {
    return pile?.deck ?? 0;
  }

  discardSize(pile: Pile | null | undefined): number {
    return pile?.discard ?? 0;
  }

  get canBuyPotion() {
    const me = this.me; 
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.potions);
    if (left <= 0) return false;

    return me.water >= 4 && me.herbs >= 3;
  }

  get canBuyElixir() {
    const me = this.me; 
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.elixirs);
    if (left <= 0) return false;

    return me.water >= 4 && me.herbs >= 3;
  }

  get canBuyVampAction() {
    const me = this.me; 
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.actionsVamp);
    if (left <= 0) return false;

    return me.souls >= 50;
  }

  get canBuyHunterAction() {
    const me = this.me; 
    const snapshot = this.game;
    if (!me || !snapshot) return false;
    if (me.hp <= 0) return false;

    const left = this.deckSize(snapshot.decks?.actionsHunters);
    if (left <= 0) return false;

    return me.gold >= this.actionPrice;
  }

  get canBuySilver() {
    const me = this.me;
    if (!me || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;
    return me.gold >= this.silverPrice;
  }

  canBuySilverQty(qty: number): boolean {
    const me = this.me;
    if (!me || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;
    const unit = this.silverPrice;
    const cost = unit * qty;
    return me.gold >= cost;
  }

  get canBuyHolyWaterAction(): boolean {
    const me = this.me;
    const g  = this.game;
    if (!me || !g || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;

    const goldCost  = this.holyWaterGoldPrice;
    const waterCost = 3;

    if (me.water < waterCost) return false;
    if (me.gold  < goldCost)  return false;

    return true;
  }

    get canBuyTrackingAction(): boolean {
    const me = this.me;
    const g  = this.game;
    if (!me || !g || me.role !== 'HUNTER') return false;
    if (me.hp <= 0) return false;

    const goldCost  = this.trackingGoldPrice;

    if (me.gold  < goldCost)  return false;

    return true;
  }


  iAmA(t: STrade): boolean { return t.aId === this.me?.id; }
  otherIdFromTrade(t: STrade): string { return this.iAmA(t) ? t.bId : t.aId; }

  tradeResources(): string[] {
    const g = this.game;
    const me = this.me;
    if (!g || !me) return this.allResources;

    if (me.role === 'HUNTER') {
      return this.allResources.filter(r => r !== 'souls');
    }

    if (me.role === 'VAMPIRE' || me.role === 'SERVANT') {
      return this.allResources.filter(r => r !== 'gold');
    }

    return this.allResources;
  }

  get actionPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 50;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  get silverPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 50;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  get holyWaterGoldPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 50;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  get trackingGoldPrice(): number {
    const g = this.game;
    const me = this.me;
    let price = 50;

    const greedy = g && (g as any).shopPricesIncreasedThisRaid;
    if (greedy) price += 50;

    if (me && me.role === 'HUNTER' && me.charismaticThisRaid) {
      price = Math.max(0, price - 20);
    }
    return price;
  }

  // UI Maintenance actions
  // --- Boutique --- //
  onBuyAction(ev: MouseEvent) {
    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyAction(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }

  onBuyPotion(ev: MouseEvent) {
    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyPotion(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }
  onBuySilver(qty: number) {
    this.api.buySilver(this.gameId, qty).subscribe({ 
      next: () => {},
      error: e => this.showError(e) 
    }); 
  }
  onBuyHolyWaterAction(ev: MouseEvent): void {
    if (!this.game) return;

    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyHolyWaterAction(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }
  onBuyTrackingAction(ev: MouseEvent): void {
    if (!this.game) return;

    const btn = ev.currentTarget as HTMLElement | null;
    const img = btn?.querySelector('img') as HTMLImageElement | null;
    if (!img?.src) return;

    const src = img.src;
    const rect = img.getBoundingClientRect();

    this.api.buyTrackingAction(this.gameId).subscribe({
      next: () => this.flyDomToViewportBottom(src, rect),
      error: e => this.showError(e)
    });
  }
  onSell(res: 'wood'|'herbs'|'stone'|'iron'|'water', qty: number) { 
    this.api.sellResource(this.gameId, res, qty).subscribe({ 
      next: () => {}, 
      error: e => this.showError(e) 
    }); 
  }
  onTransmute(recipe: 'WOOD_TO_IRON'|'IRON_TO_WOOD'|'TRINITY_TO_SOULS') { 
    this.api.transmute(this.gameId, recipe).subscribe({ 
      next: () => {}, 
      error: e => this.showError(e) 
    }); 
  }
  onFinishPhase4() {
    this.waitingDone = true;
    this.api.finishPhase4(this.gameId).subscribe({ 
      next: () => {}, 
      error: e => this.showError(e) 
    });
  }

  // --- Échanges --- //
  get eligibleTradeTargets(): SPlayer[] {
    if (!this.game || !this.me) return [];
    if (this.isHunter) {
      return this.game.players.filter((p: SPlayer) => p.role === 'HUNTER' && p.id !== this.me!.id && p.hp > 0);
    }
    if (this.me!.role === 'VAMPIRE') {
      return this.game.players.filter((p: SPlayer) => p.role === 'SERVANT' && p.hp > 0);
    }
    return this.game.players.filter((p: SPlayer) => p.role === 'VAMPIRE' && p.hp > 0);
  }

  // quand tu changes de cible : sauvegarde l’ancienne, recharge la nouvelle
  selectTradeTarget(targetId: string): void {
    if (this.selectedTradeTargetId) {
      this.myOffersByTarget[this.selectedTradeTargetId] = { ...this.myOffer };
    }
    this.selectedTradeTargetId = targetId;
    this.myOffer = { ...(this.myOffersByTarget[targetId] || {}) };
  }



  // quand on modifie l’offre, maj aussi de la mémoire et push au back
  bumpOffer(res: string, delta: number): void {
    const cur = this.myOffer[res] ?? 0;
    const next = Math.max(0, cur + delta);
    if (next === 0) delete this.myOffer[res];
    else this.myOffer[res] = next;

    if (this.selectedTradeTargetId) {
      this.myOffersByTarget[this.selectedTradeTargetId] = { ...this.myOffer };
      this.api.tradeOffer(this.gameId, this.selectedTradeTargetId, this.myOffer).subscribe({
        next: () => {},
        error: e => this.showError(e)
      });
    }
  }

  tradeForSelected(): STrade | undefined {
    if (!this.game?.trades || !this.selectedTradeTargetId || !this.me) return undefined;
    const meId = this.me.id;
    return this.game.trades.find((t: STrade) =>
      (t.aId === meId && t.bId === this.selectedTradeTargetId) ||
      (t.bId === meId && t.aId === this.selectedTradeTargetId)
    );
  }

  myTradesSorted(): STrade[] {
    if (!this.game?.trades || !this.me) return [];
    const meId = this.me.id;

    return this.game.trades
      .filter(t => t.aId === meId || t.bId === meId)
      // je ne montre pas un trade si MON statut est cancel/refuse
      .filter(t => {
        const st = this.myStatus(t);
        return st !== 'CANCELLED' && st !== 'REFUSED';
      })
      .sort((a,b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  }

  onTradeAction(action: 'confirm'|'refuse'|'cancel'): void {
    const targetId = this.selectedTradeTargetId; // capture
    if (!targetId) return;

    if (action === 'cancel') {
      this.selectedTradeTargetId = null; // UI immédiate locale
      this.myOffer = {};
    }

    this.api.tradeAction(this.gameId, action, targetId).subscribe({
      next: () => {},
      error: e => this.showError(e)
    });
  }

  onTradeActionFor(action: 'confirm'|'refuse'|'cancel', targetId: string): void {
    if (action === 'cancel') {
      // 2.1 ferme "Mes ressources"
      if (this.selectedTradeTargetId === targetId) {
        delete this.myOffersByTarget[targetId];
        this.selectedTradeTargetId = null;
        this.myOffer = {};
      }

      // 2.2 pose MON statut à CANCELLED en local => le filtre ci-dessus masquera le bloc tout de suite
      const meId = this.me?.id;
      if (this.game?.trades && meId) {
        const t = this.game.trades.find(x =>
          (x.aId === meId && x.bId === targetId) ||
          (x.bId === meId && x.aId === targetId)
        );
        if (t) {
          if (t.aId === meId) t.statusA = 'CANCELLED';
          else                t.statusB = 'CANCELLED';
          t.updatedAt = Date.now();
        }
      }

      // tick UI
      this.game = { ...(this.game as GameSnapshot) };
    }

    // appel serveur (confirmera l’état et/ou supprimera plus tard)
    this.api.tradeAction(this.gameId, action, targetId).subscribe({
      next: () => {},
      error: e => this.showError(e)
    });
  }

  isClosing(id: string): boolean { return Date.now() < (this.closingUntil[id] || 0); }
  isClosingOk(id: string): boolean { return this.isClosing(id) && this.closingKind[id] === 'ok'; }
  isClosingKo(id: string): boolean { return this.isClosing(id) && this.closingKind[id] === 'ko'; }

  // --- Toasts (2,5 s) par cible ---
  private flashByTarget: Record<string, { text: string; kind: 'ok'|'ko'; until: number }> = {};

  activeFlashes() {
    const now = Date.now();
    return Object.entries(this.flashByTarget)
      .filter(([,v]) => v.until > now)
      .map(([targetId, v]) => ({ targetId, ...v }));
  }
  private setFlashFor(targetId: string, text: string, kind: 'ok'|'ko', ms = 2500) {
    this.flashByTarget[targetId] = { text, kind, until: Date.now() + ms };
  }

  // --- Format ressources pour le message succès ---
  private labelFr(k: string): string {
    switch (k) {
      case 'wood': return 'bois'; case 'herbs': return 'herbes'; case 'stone': return 'pierre';
      case 'iron': return 'fer';  case 'water': return 'eau';    case 'gold':  return 'or';
      case 'souls':return 'âmes'; case 'silver':return 'argent'; default: return k;
    }
  }
  private packToText(pack?: Record<string, number>): string {
    if (!pack) return 'rien';
    const entries = Object.entries(pack).filter(([_,q]) => (q||0) > 0);
    if (!entries.length) return 'rien';
    return entries.map(([k,q]) => `${this.labelFr(k)} x${q}`).join(', ');
  }

  // animation pioche
  private flyDomToViewportBottom(src: string, rect: DOMRect) {
    const el = document.createElement('img');
    el.src = src;

    // style de base
    Object.assign(el.style, {
      position: 'fixed',
      left: `${rect.left}px`,
      top: `${rect.top}px`,
      width: `${rect.width}px`,
      height: `${rect.height}px`,
      borderRadius: '10px',
      boxShadow: '0 12px 30px rgba(0,0,0,.35)',
      zIndex: '2147483647',
      pointerEvents: 'none',
      transform: 'translate3d(0,0,0) scale(1)',
      opacity: '1'
    } as Partial<CSSStyleDeclaration>);

    document.body.appendChild(el);

    const startX = rect.left + rect.width / 2;
    const startY = rect.top + rect.height / 2;

    const endX = window.innerWidth / 2;
    const endY = window.innerHeight - 24;

    const dx = endX - startX;
    const dy = endY - startY;

    const anim = el.animate(
      [
        { transform: 'translate3d(0,0,0) scale(1)', opacity: 1 },
        { transform: `translate3d(${dx}px, ${dy}px, 0) scale(0.30)`, opacity: 0.15 }
      ],
      { duration: 1500, easing: 'cubic-bezier(.2,.8,.2,1)', fill: 'forwards' }
    );

    anim.onfinish = () => el.remove();
    anim.oncancel = () => el.remove();
  }

  // Construction
  openBuildModal() {
    if (!this.game || !this.me) return;
    if (this.game.phase !== 'PHASE2') return;
    if (this.me.role !== 'VAMPIRE') return;

    this.buildChoice = null;
    this.buildConfirmModalOpen = false;
    this.buildModalOpen = true;
  }

  closeBuildModal() {
    this.buildModalOpen = false;
  }

  onChooseInfra(infra: 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE') {
    this.buildChoice = infra;
    this.buildModalOpen = false;
    this.buildConfirmModalOpen = true;
    this.zoomLeave();
  }

  cancelBuild() {
    this.buildConfirmModalOpen = false;
    this.buildChoice = null;
  }

  getInfraConfirmText(infra: 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE'): string {
    if (infra === 'SAWMILL') {
      return 'Se déplacer à la Forêt pour construire la Scierie ?';
    } 
    if (infra === 'MINE'){
      return 'Se déplacer à la Carrière pour construire la Mine ?';
    }
    if (infra === 'LIBRARY'){
      return 'Se déplacer au Manoir pour construire la Bibliothèque ?';
    }
    if (infra === 'LABORATORY'){
      return 'Se déplacer au Manoir pour construire le Laboratoire ?';
    }
    if (infra === 'BALLROOM'){
      return 'Se déplacer au Manoir pour construire la salle de bal ?';
    }
    if (infra === 'ALTAR'){
      return 'Se déplacer au Manoir pour construire l\'autel ?';
    }
    if (infra === 'FORGE'){
      return 'Se déplacer au Manoir pour construire la forge ?';
    }
    return '';
  }

  get isWindActive(): boolean {
    const w = this.game?.weather;
    if (!w) return false;
    return w.status === 'WIND' || w.secondaryStatus === 'WIND';
  }

  doBuild() {
    if (!this.game || !this.buildChoice) return;

    this.api.planConstruction(this.game.id, this.buildChoice).subscribe({
      next: () => {
        this.buildConfirmModalOpen = false;
        this.buildChoice = null;
      },
      error: (err) => {
        console.error('Erreur planConstruction', err);
        alert(err.error?.message ?? 'Construction impossible');
        // on laisse la modale ouverte pour que le joueur puisse réessayer/changer
      },
    });
  }

  get isLocationEffectOwner(): boolean {
    return !!this.game && !!this.me && this.game.locationEffectOwnerId === this.me.id;
  }

  get canChooseLocationEffect(): boolean {
    const g = this.game;
    return !!g
      && g.locationEffectPending
      && this.isLocationEffectOwner
      && !g.locationEffectChoice; // le serveur n’a pas encore figé le choix
  }

  translateLocationEffect(
    choice: GameSnapshot['locationEffectChoice'] | undefined
  ): string {
    switch (choice) {
      case 'STUDY':
        return 'Étude des grimoires';
      case 'THEFT':
        return 'Subtilisation de manuscrit';
      case 'OMEN':
        return 'Prédiction occulte';
      case 'EXPERIMENT':
        return 'Expérimentation';
      case 'ALCHEMY':
        return 'Alchimie';
      case 'RARE_ALCHEMY':
        return 'Alchimie rare';
      case 'EXPLOSION':    
        return 'Explosion alchimique';
      case 'DEATH_DANCE':
        return 'Danse macabre';
      case 'SNEAK_ATTACK':
        return 'Attaque sournoise';
      case 'LOOTING':
        return 'Pillage';
      case 'HEAL':
        return 'Purifier un chasseur';
      case 'CORRUPT_SOULS':
        return 'Corrompre le lieu';
      case 'CORRUPT':
        return 'Corrompre un chasseur';
      case 'PURIFY_WATER':
        return 'Purifier le lieu';
      case 'FORGE':
        return 'Fabriquer de l\'équipement';
      default:
        return '';
    }
  }

  locationEffectBackground(): string {
    const infra = this.game?.locationEffectInfra;
    if (infra === 'LIBRARY') {
      return "url('/assets/locations/library.png')";
    }
    if (infra === 'LABORATORY') {
      return "url('/assets/locations/laboratory.png')";
    }
    if (infra === 'BALLROOM') {
      return "url('/assets/locations/ballroom.png')";
    }
    if (infra === 'ALTAR') {
      if (this.game?.altarCorrupted) return "url('/assets/locations/altar.png')"
      else return "url('/assets/locations/sanctuary.png')"
    }
    if (infra === 'FORGE') {
      return "url('/assets/locations/forge.png')"
    }
    return 'none';
  }

  private syncLocationEffectFromSnapshot(g: GameSnapshot, previous?: GameSnapshot | null) {
    // 0) Aucun effet de lieu → on vide tout
    if (!g.locationEffectPending || !g.locationEffectInfra) {
      this.effectChoice = null;
      this.resetLocationActionUi();
      return;
    }

    const prevOwner = previous?.locationEffectOwnerId;
    const ownerChanged = !!prevOwner && prevOwner !== g.locationEffectOwnerId;

    // 1) Si le serveur a déjà figé un choix → on s'aligne
    if (g.locationEffectChoice) {
      this.effectChoice = g.locationEffectChoice;
    } else if (ownerChanged) {
      // Nouveau joueur qui résout un effet → reset la sélection locale
      this.effectChoice = null;
    }

    // 2) À chaque appel, on repart d'un état d'action "propre"
    // (la modale de choix d'effet est gérée par le template via locationEffectPending/Choice)
    this.resetLocationActionUi();

    // ============================================================
    // ===  LIBRARY : logique existante (THEFT / OMEN)          ===
    // ============================================================
    if (g.locationEffectInfra === 'LIBRARY') {

      // --- THEFT : modale d'action pour choisir cible + slot ---
      if (g.locationEffectChoice === 'THEFT') {
        this.locationActionKind = 'THEFT';
        this.locationActionModalOpen = true;

        // Seul le propriétaire a besoin des cibles/slots
        if (this.isLocationEffectOwner) {
          const targets = this.computeTheftTargets(g);
          this.theftTargets = targets;
        } else {
          // Spectateurs : texte uniquement
          this.theftTargets = [];
        }

        return;
      }

      // --- OMEN : modale d'action si des cartes sont préparées ---
      if (g.locationEffectChoice === 'OMEN') {
        const cards = g.libraryOmenCards ?? [];

        if (cards.length > 0) {
          this.locationActionKind = 'OMEN';
          this.locationActionModalOpen = true;

          if (this.isLocationEffectOwner) {
            this.omenCards = cards;

            // reset si nouvelle séquence ou taille différente
            if (!this.omenPlacements || this.omenPlacements.length !== cards.length) {
              this.omenPlacements = cards.map(() => null);
            }
          } else {
            // Observateurs : pas de détail des cartes
            this.omenCards = [];
            this.omenPlacements = [];
          }
        }

        return;
      }

      // STUDY (et autres futurs non interactifs côté LIBRARY) : rien à faire ici.
      return;
    }

    // ============================================================
    // ===  LABORATORY : nouvel effet EXPERIMENT                ===
    // ============================================================
    if (g.locationEffectInfra === 'LABORATORY') {
      if (g.locationEffectChoice === 'EXPERIMENT') {
        this.locationActionKind = 'EXPERIMENT';
        this.locationActionModalOpen = true;

        // Tout le monde voit les lieux possibles
        this.experimentPossibleLocations = this.computeExperimentLocations(g);

        // Tout le monde voit la sélection en cours (depuis snapshot)
        this.experimentMonsterType = (g.laboratoryDraftMonsterType as any) ?? null;
        this.experimentLocation = g.laboratoryDraftLocation ?? null;

        return;
      }
      return;
    }

    // ============================================================
    // ===  ALTAR : effets interactifs HEAL / CORRUPT           ===
    // ============================================================
    if (g.locationEffectInfra === 'ALTAR') {

      // HEAL : choisir un chasseur vivant avec corruption > 0 et < 3 (verrou max)
      if (g.locationEffectChoice === 'HEAL') {
        this.locationActionKind = 'HEAL';
        this.locationActionModalOpen = true;

        if (this.isLocationEffectOwner) {
          this.altarTargets = g.players
            .filter(p => p.role === 'HUNTER' && p.hp > 0 && p.corruption > 0 && p.corruption < 3)
            .map(p => ({
              id: p.id,
              username: p.username,
              hp: p.hp,
              corruption: p.corruption,
            }));
        } else {
          this.altarTargets = [];
        }

        this.altarSelectedTargetId = null;
        return;
      }

      // CORRUPT : choisir un chasseur vivant avec corruption < 3
      if (g.locationEffectChoice === 'CORRUPT') {
        this.locationActionKind = 'CORRUPT';
        this.locationActionModalOpen = true;

        if (this.isLocationEffectOwner) {
          this.altarTargets = g.players
            .filter(p => p.role === 'HUNTER' && p.hp > 0 && p.corruption < 3)
            .map(p => ({
              id: p.id,
              username: p.username,
              hp: p.hp,
              corruption: p.corruption,
            }));
        } else {
          this.altarTargets = [];
        }

        this.altarSelectedTargetId = null;
        return;
      }

      // CORRUPT_SOULS / PURIFY_WATER : pas d’UI interactive côté front,
      // le serveur gère tout lors du choix d’effet.
      return;
    }

    // ============================================================
    // ===  FORGE : choix d’un équipement à fabriquer            ===
    // ============================================================
    if (g.locationEffectInfra === 'FORGE') {
      if (g.locationEffectChoice === 'FORGE') {
        this.locationActionKind = 'FORGE';
        this.locationActionModalOpen = true;

        if (this.isLocationEffectOwner) {
          // Calcul local des options possibles pour le propriétaire
          this.forgeOptions = this.computeForgeOptionsForOwner(g);
        } else {
          this.forgeOptions = [];
        }

        this.forgeSelectedId = null;
        this.forgeResolvedLabel = null;
      }

      return;
    }
  }

  // Lieux où on peut envoyer un monstre créé par le Laboratoire
  private computeExperimentLocations(g: GameSnapshot): string[] {
    const locs = new Set<string>();

    // 1) 4 lieux de base toujours proposés
    [
      'forest',
      'quarry',
      'lake',
      'manor',
    ].forEach(l => locs.add(l));

    // 2) Ajouter les lieux correspondant aux infrastructures construites
    for (const infra of g.builtInfras ?? []) {
      switch (infra) {
        case 'SAWMILL':
          locs.add('sawmill');
          break;
        case 'MINE':
          locs.add('mine');
          break;
        case 'LIBRARY':
          locs.add('library');
          break;
        case 'LABORATORY':
          locs.add('laboratory');
          break;
        case 'BALLROOM':
          locs.add('ballroom');
          break;
        case 'ALTAR':
          locs.add('altar');
          break;
      }
    }

    return Array.from(locs);
  }

  onEffectOptionClick(choice: 'STUDY' | 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'ALCHEMY' | 'RARE_ALCHEMY' | 'EXPLOSION' | 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING' | 'HEAL' | 'CORRUPT_SOULS' | 'CORRUPT' | 'PURIFY_WATER' | 'FORGE') {
    if (!this.canChooseLocationEffect) return;

    // Experiment : pas de chasseur
    if (choice === 'EXPERIMENT' && !this.canUseExperiment()) {
      return;
    }

    // Garde spécifique rare alchimie : si pas les ressources, on ignore le clic
    if (choice === 'RARE_ALCHEMY' && !this.canUseRareAlchemy()) {
      return;
    }

    // Explosion : juste un chasseur
    if (choice === 'EXPLOSION' && !this.canUseExplosion()) {
      return;
    }

      // Garde Ballroom : cohérent avec isBallroomChoiceDisabled
    if (
      (choice === 'DEATH_DANCE' || choice === 'SNEAK_ATTACK' || choice === 'BLOOD_WALTZ') &&
      !this.isVampireSide
    ) {
      return;
    }

    if (choice === 'LOOTING' && !this.isHunter) {
      return;
    }

    // ALTAR : garde role + état du lieu
    if (this.game?.locationEffectInfra === 'ALTAR') {
      if (choice === 'HEAL' || choice === 'CORRUPT' || choice === 'PURIFY_WATER' || choice === 'CORRUPT_SOULS') {
        if (this.isAltarChoiceDisabled(choice)) {
          return;
        }
      }
    }

    this.effectChoice = choice;
  }

  chooseLocationEffect() {
    if (!this.game || !this.effectChoice || !this.canChooseLocationEffect) return;

    if (this.effectChoice === 'RARE_ALCHEMY' && !this.canUseRareAlchemy()) {
      alert("Vous n'avez pas assez d'ingrédients pour une alchimie rare (6 eau, 6 herbes).");
      return;
    }

    this.api.chooseLocationEffect(this.game.id, this.effectChoice).subscribe({
      next: () => {
        // On ne ferme pas la modale :
        // - le serveur mettra à jour locationEffectChoice
        // - puis enchaînera l’effet suivant ou passera en PHASE3.
        // La vue se resynchronise via events + snapshot.
      },
      error: (err) => {
        console.error('Erreur chooseLocationEffect', err);
        alert(err.error?.message ?? 'Erreur Bibliothèque');
      },
    });
  }

  onCancelLocationEffect() {
    // Annule juste la sélection locale, l’effet reste en attente côté serveur
    this.effectChoice = null;
  }

  get isLocationActionOwner(): boolean {
    return this.isLocationEffectOwner; // même logique que pour la modale de choix
  }

  canUseExperiment(): boolean {
    const me = this.me;
    if (!me) return false;
    return (me.role === 'VAMPIRE' || me.role === 'SERVANT') && me.souls >= 100;
  }

  canUseRareAlchemy(): boolean {
    const g = this.game;
    const meId = this.me?.id;
    if (!g || !meId) return false;

    const me = g.players.find(p => p.id === meId);
    if (!me) return false;

    const water = (me as any).water ?? 0;
    const herbs = (me as any).herbs ?? 0;

    return water >= 6 && herbs >= 6;
  }

  canUseExplosion(): boolean {
    const me = this.me;
    if (!me) return false;
    return me.role === 'HUNTER';
  }

  get theftCanSubmit(): boolean {
    return this.locationActionKind === 'THEFT'
      && this.isLocationActionOwner
      && !!this.theftSelectedTargetId
      && this.theftSelectedSlotIndex !== null
      && this.theftSelectedSlotIndex >= 0;
  }

  get omenCanSubmit(): boolean {
    return this.locationActionKind === 'OMEN'
      && this.isLocationActionOwner
      && this.omenPlacements.length > 0
      && this.omenPlacements.every(p => p === 'TOP' || p === 'BOTTOM');
  }

  private resetLocationActionUi() {
    this.locationActionModalOpen = false;
    this.locationActionKind = null;

    // OMEN
    this.omenCards = [];
    this.omenPlacements = [];
    this.omenSubmitting = false;

    // THEFT
    this.theftTargets = [];
    this.theftSelectedTargetId = null;
    this.theftSlots = [];
    this.theftSelectedSlotIndex = null;
    this.theftSubmitting = false;

    // EXPERIMENT
    this.experimentMonsterType = null;
    this.experimentLocation = null;
    this.experimentPossibleLocations = [];

    // ALTAR
    this.altarTargets = [];
    this.altarSelectedTargetId = null;
    this.altarSubmitting = false;

    // FORGE
    this.forgeOptions = [];
    this.forgeSelectedId = null;
    this.forgeSubmitting = false;
    this.forgeResolvedLabel = null;
  }

  private computeTheftTargets(g: GameSnapshot): { id: string; username: string; actionsCount: number }[] {
    const ownerId = g.locationEffectOwnerId;
    if (!ownerId) return [];

    const owner = g.players.find(p => p.id === ownerId);
    if (!owner) return [];

    if (owner.role === 'VAMPIRE') {
      // Vampire → peut cibler n'importe quel chasseur vivant avec ≥1 carte Action
      return g.players
        .filter(p => p.role === 'HUNTER' && p.hp > 0 && p.actions && p.actions.length > 0)
        .map(p => ({
          id: p.id,
          username: p.username,
          actionsCount: p.actions.length,
        }));
    }

    if (owner.role === 'HUNTER') {
      // Chasseur → cible unique = vampire, s'il a des cartes
      const vamp = g.players.find(p => p.role === 'VAMPIRE' && p.hp > 0 && p.actions && p.actions.length > 0);
      return vamp
        ? [{
            id: vamp.id,
            username: vamp.username,
            actionsCount: vamp.actions.length,
          }]
        : [];
    }

    return [];
  }

  onSelectTheftTarget(targetId: string) {
    if (!this.isLocationActionOwner) return;
    this.theftSelectedTargetId = targetId;

    const t = this.theftTargets.find(tt => tt.id === targetId);
    const count = t ? t.actionsCount : 0;

    this.theftSlots = Array.from({ length: count }, (_, i) => i);
    this.theftSelectedSlotIndex = null;
  }

  onSelectTheftSlot(index: number) {
    if (!this.isLocationActionOwner) return;
    if (index < 0 || index >= this.theftSlots.length) return;

    this.theftSelectedSlotIndex = index;
  }

  confirmTheftSelection() {
    if (!this.game) return;
    if (!this.theftCanSubmit) return;
    if (!this.theftSelectedTargetId && this.theftSelectedTargetId !== '') return;
    if (this.theftSelectedSlotIndex === null) return;

    this.theftSubmitting = true;

    this.api.resolveLibraryTheft(
      this.game.id,
      this.theftSelectedTargetId!,
      this.theftSelectedSlotIndex
    ).subscribe({
      next: () => {
        // Le serveur va :
        //  - retirer la carte de la main de la cible
        //  - la remettre dans le bon deck + shuffle
        //  - loguer dans l'historique
        //  - émettre LOCATION_USED(THEFT) puis enchaîner la file d'effets
        //
        // La fermeture de la modale se fera via LOCATION_USED + nouveau snapshot
        // → syncLocationEffectFromSnapshot() la refermera.
        this.theftSubmitting = false;
      },
      error: (err) => {
        console.error('Erreur resolveLibraryTheft', err);
        alert(err.error?.message ?? 'Erreur Subtilisation de manuscrit');
        this.theftSubmitting = false;
      }
    });
  }
  
  onOmenPlacementClick(index: number, where: 'TOP' | 'BOTTOM') {
    if (!this.isLocationActionOwner) return;
    if (!this.omenPlacements || index < 0 || index >= this.omenPlacements.length) return;

    this.omenPlacements = this.omenPlacements.map((p, i) =>
      i === index ? where : p
    );
  }

  confirmOmenPlacements() {
    if (!this.game || !this.isLocationActionOwner || !this.omenCanSubmit) return;

    this.omenSubmitting = true;

    this.api.resolveLibraryOmen(
      this.game.id,
      this.omenPlacements as ('TOP' | 'BOTTOM')[]
    ).subscribe({
      next: () => {
        // Le serveur va :
        //  - remettre les cartes dans le deck (TOP/BOTTOM)
        //  - effacer libraryOmenState
        //  - émettre LOCATION_USED(OMEN) et enchaîner ensuite sur PHASE3 ou effet suivant
        // La fermeture de la modale se fera via snapshot + syncLocationEffectFromSnapshot.
        this.omenSubmitting = false;
      },
      error: (err) => {
        console.error('Erreur resolveLibraryOmen', err);
        alert(err.error?.message ?? 'Erreur Prédiction occulte');
        this.omenSubmitting = false;
      }
    });
  }

  get experimentAvailableLocations(): string[] {
    return this.experimentPossibleLocations;
  }

  get experimentCanSubmit(): boolean {
    return this.locationActionKind === 'EXPERIMENT'
      && this.isLocationActionOwner
      && !!this.experimentMonsterType
      && !!this.experimentLocation;
  }

  monsterHpInCombat(r: any): number | undefined {
    const m = this.getMonster(r.attackerId) || this.getMonster(r.defenderId);
    return m?.hp;
  }

  onExperimentMonsterClick(type: 'REVENANT'|'GARGOYLE'|'ABERRATION') {
    if (!this.isLocationActionOwner || !this.game) return;

    this.experimentMonsterType = type;

    this.api.updateExperimentDraft(this.game.id, {
      type,
      location: this.experimentLocation
    }).subscribe();
  }

  onExperimentLocationClick(loc: string) {
    if (!this.isLocationActionOwner || !this.game) return;

    this.experimentLocation = loc;

    this.api.updateExperimentDraft(this.game.id, {
      type: this.experimentMonsterType,
      location: loc
    }).subscribe();
  }

  confirmExperiment() {
    if (!this.game) return;
    if (!this.experimentCanSubmit || !this.experimentMonsterType || !this.experimentLocation) return;

    this.experimentSubmitting = true;

    this.api.resolveLaboratoryExperiment(
      this.game.id,
      this.experimentMonsterType,
      this.experimentLocation
    ).subscribe({
      next: () => {
        // Le serveur fera LOCATION_USED + snapshot → la modale se fermera via syncLocationEffectFromSnapshot
      },
      error: (err) => {
        console.error('Erreur resolveLaboratoryExperiment', err);
        alert(err.error?.message ?? 'Erreur Expérience occulte');
        this.experimentSubmitting = false;
      }
    });
  }

  isBallroomChoiceDisabled(
    choice: 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING'
  ): boolean {
    const g = this.game;
    if (!g) return true;

    // conditions globales (comme canChooseLocationEffect)
    if (!g.locationEffectPending || !!g.locationEffectChoice) return true;
    if (!this.isLocationEffectOwner) return true;

    // Pillage : chasseurs uniquement → disabled si PAS chasseur
    if (choice === 'LOOTING') {
      return !this.isHunter;
    }

    // Effets vampiriques : vampire ou serviteur uniquement → disabled si PAS vampire-side
    if (choice === 'DEATH_DANCE' || choice === 'SNEAK_ATTACK' || choice === 'BLOOD_WALTZ') {
      return !this.isVampireSide;
    }

    // Par défaut, pas de désactivation spécifique
    return false;
  }


  isBallroomFight(r: RoundFightView): boolean {
    if (!this.game?.builtInfras?.includes('BALLROOM')) return false;
    return r.location === 'ballroom';
  }

  /** Duel où la Valse s'applique : effet actif + duel sur Ballroom + vampire vs chasseur + ≥2 chasseurs sur Ballroom */
  isBallroomWaltzFight(r: RoundFightView): boolean {
    if (!this.game) return false;

    // Effet Valse activé sur ce raid
    if (!this.game.ballroomBloodWaltz) return false;

    // Ballroom construite
    if (!this.game.builtInfras?.includes('BALLROOM')) return false;

    const att = this.getPlayer(r.attackerId);
    const def = this.getPlayer(r.defenderId);
    if (!att || !def) return false;

    // Conditions de rôles (adapte si, en fait, c'est le chasseur qui attaque le vampire)
    if (att.role !== 'VAMPIRE' || def.role !== 'HUNTER') return false;

    // Le duel doit être sur la salle de bal
    const locKey = 'ballroom';
    if (r.location !== locKey) return false;

    // On réutilise la même logique que getHuntersOnBallroomCount()
    if (!this.playersOnLocation) return false;
    const playersOnLoc = this.playersOnLocation(locKey) || [];
    const huntersOnLoc = playersOnLoc.filter(p => p.role === 'HUNTER' && p.hp > 0).length;

    // Valse seulement s'il y a au moins 2 chasseurs sur Ballroom
    return huntersOnLoc > 1;
  }



  /** Est-ce que CE joueur (moi) est le vampire attaquant sur un duel Valse ? */
  isBallroomWaltzForMe(r: RoundFightView): boolean {
    return this.meId === r.attackerId && this.isBallroomWaltzFight(r);
  }

  getHuntersOnBallroomCount(): number {
    if (!this.game?.builtInfras?.includes('BALLROOM')) return 0;

    const locKey = 'ballroom';

    if (!this.playersOnLocation) {
      return 0; // sécurité
    }

    const playersOnLoc = this.playersOnLocation(locKey);

    return playersOnLoc.filter(p => p.role === 'HUNTER' && p.hp > 0).length;
  }

  /** Placeholders avant que les dés de Valse soient connus */
  waltzPlaceholderDice(): number[] {
    const n = this.getHuntersOnBallroomCount();
    return n > 1 ? new Array(n).fill(0) : [];
  }

  /** Dés de Valse (globaux pour le raid) */
  get waltzRolls(): number[] {
    return this.game?.ballroomWaltzRolls || [];
  }

  get waltzBest(): number | null {
    return this.game?.ballroomWaltzBest ?? null;
  }

  get altarCanSubmit(): boolean {
    return (this.locationActionKind === 'HEAL'
            || this.locationActionKind === 'CORRUPT')
      && this.isLocationActionOwner
      && !!this.altarSelectedTargetId;
  }

  isAltarChoiceDisabled(
    choice: 'HEAL' | 'CORRUPT' | 'PURIFY_WATER' | 'CORRUPT_SOULS'
  ): boolean {
    const g = this.game;
    if (!g) return true;

    // Conditions globales : même logique que canChooseLocationEffect
    if (!g.locationEffectPending || !!g.locationEffectChoice) return true;
    if (!this.isLocationEffectOwner) return true;

    // Règles de rôle demandées :
    // 1) Si vampire (ou serviteur), les 2 "purifier" doivent être désactivés
    if ((choice === 'HEAL' || choice === 'PURIFY_WATER') && !this.isHunter) {
      return true;
    }

    // 2) Si chasseur, les 2 "corrompre" doivent être désactivés
    if ((choice === 'CORRUPT' || choice === 'CORRUPT_SOULS') && !this.isVampireSide) {
      return true;
    }

    // Optionnel (mais logique) : éviter les choix absurdes côté lieu
    if (choice === 'PURIFY_WATER' && !g.altarCorrupted) {
      // Sanctuaire déjà pur → rien à purifier
      return true;
    }
    if (choice === 'CORRUPT_SOULS' && g.altarCorrupted) {
      // Sanctuaire déjà corrompu → rien à corrompre en plus
      return true;
    }

    return false;
  }

  onSelectAltarTarget(targetId: string) {
    if (!this.isLocationActionOwner) return;
    this.altarSelectedTargetId = targetId;
  }

  confirmAltarHeal() {
    if (!this.game || !this.altarCanSubmit || !this.altarSelectedTargetId) return;

    this.altarSubmitting = true;
    this.api.resolveAltarHeal(this.game.id, this.altarSelectedTargetId).subscribe({
      next: () => {
        this.altarSubmitting = false;
        // La modale se fermera via snapshot + syncLocationEffectFromSnapshot
      },
      error: (err) => {
        console.error('Erreur resolveAltarHeal', err);
        alert(err.error?.message ?? 'Erreur Purification de chasseur');
        this.altarSubmitting = false;
      },
    });
  }

  confirmAltarCorrupt() {
    if (!this.game || !this.altarCanSubmit || !this.altarSelectedTargetId) return;

    this.altarSubmitting = true;
    this.api.resolveAltarCorrupt(this.game.id, this.altarSelectedTargetId).subscribe({
      next: () => {
        this.altarSubmitting = false;
      },
      error: (err) => {
        console.error('Erreur resolveAltarCorrupt', err);
        alert(err.error?.message ?? 'Erreur Corruption de chasseur');
        this.altarSubmitting = false;
      },
    });
  }

  // Forge
  get forgeWeaponOptions(): ForgeOption[] {
    return this.forgeOptions.filter(o => o.type === 'WEAPON');
  }
  get forgeArmorOptions(): ForgeOption[] {
    return this.forgeOptions.filter(o => o.type === 'ARMOR');
  }
  get forgeCanSubmit(): boolean {
    return this.isLocationActionOwner
      && !!this.game
      && !!this.forgeSelectedId
      && !this.forgeSubmitting;
  }

  // Renvoie le nombre de faces max trouvé dans une chaîne de dés (ex: "2D6+1" → 6)
  private maxDiceFaces(dice: string | null | undefined): number {
    if (!dice) return 0;
    const matches = [...dice.matchAll(/D(\d+)/gi)];
    if (matches.length === 0) return 0;
    return matches
      .map(m => parseInt(m[1], 10))
      .filter(n => !Number.isNaN(n))
      .reduce((a, b) => Math.max(a, b), 0);
  }

  private getWeaponTierFromPlayer(p: GameSnapshot['players'][number]): 0 | 1 | 2 | 3 {
    const faces = this.maxDiceFaces(p.attackDice);
    if (faces >= 20) return 3;
    if (faces >= 12) return 2;
    if (faces >= 8)  return 1;
    return 0;
  }

  private getArmorTierFromPlayer(p: GameSnapshot['players'][number]): 0 | 1 | 2 | 3 {
    const faces = this.maxDiceFaces(p.defenseDice);
    if (faces >= 20) return 3;
    if (faces >= 12) return 2;
    if (faces >= 8)  return 1;
    return 0;
  }

    private buildHunterWeaponOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'H_WEAPON_T1_SWORD',
            type: 'WEAPON',
            tier: 1,
            label: 'Épée de fer',
            desc: 'Arme de chasseur — Saignement +1.',
          },
          {
            id: 'H_WEAPON_T1_MACE',
            type: 'WEAPON',
            tier: 1,
            label: 'Masse de fer',
            desc: 'Arme de chasseur — Étourdissement -1.',
          },
          {
            id: 'H_WEAPON_T1_SPEAR',
            type: 'WEAPON',
            tier: 1,
            label: 'Lance de fer',
            desc: 'Arme de chasseur — Portée 1.',
          },
        ];
      case 2:
        return [
          {
            id: 'H_WEAPON_T2_HALBERD',
            type: 'WEAPON',
            tier: 2,
            label: 'Hallebarde',
            desc: 'Arme de chasseur — Saignement +2.',
          },
          {
            id: 'H_WEAPON_T2_HAMMER',
            type: 'WEAPON',
            tier: 2,
            label: 'Marteau de guerre',
            desc: 'Arme de chasseur — Étourdissement -2.',
          },
          {
            id: 'H_WEAPON_T2_CROSSBOW',
            type: 'WEAPON',
            tier: 2,
            label: 'Arbalète',
            desc: 'Arme de chasseur — Portée 1–2.',
          },
        ];
      case 3:
        return [
          {
            id: 'H_WEAPON_T3_WRIST_BLADES',
            type: 'WEAPON',
            tier: 3,
            label: 'Lames de poignet',
            desc: 'Arme de chasseur — Saignement +3.',
          },
          {
            id: 'H_WEAPON_T3_FLAIL',
            type: 'WEAPON',
            tier: 3,
            label: 'Fléau',
            desc: 'Arme de chasseur — Étourdissement -3.',
          },
          {
            id: 'H_WEAPON_T3_PISTOL',
            type: 'WEAPON',
            tier: 3,
            label: 'Pistolet',
            desc: 'Arme de chasseur — Portée 1–3.',
          },
        ];
    }
  }

  private buildHunterArmorOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'H_ARMOR_T1_BRIGANDINE',
            type: 'ARMOR',
            tier: 1,
            label: 'Brigandine',
            desc: 'Armure de chasseur — Dé de défense D8.',
          },
        ];
      case 2:
        return [
          {
            id: 'H_ARMOR_T2_HAUBERT',
            type: 'ARMOR',
            tier: 2,
            label: 'Haubert',
            desc: 'Armure de chasseur — Dé de défense D12.',
          },
        ];
      case 3:
        return [
          {
            id: 'H_ARMOR_T3_PLATE_SILVER',
            type: 'ARMOR',
            tier: 3,
            label: 'Armure de plates en argent',
            desc: 'Armure de chasseur — D20 + effet spécial contre la morsure.',
          },
        ];
    }
  }

  private buildVampireWeaponOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'V_WEAPON_T1_SCYTHE',
            type: 'WEAPON',
            tier: 1,
            label: 'Faux maudite',
            desc: 'Arme vampirique — D8, régénération 1 sur 7–8.',
          },
        ];
      case 2:
        return [
          {
            id: 'V_WEAPON_T2_SWORD',
            type: 'WEAPON',
            tier: 2,
            label: 'Épée vampirique',
            desc: 'Arme vampirique — D12, régénération 2 sur 10–12.',
          },
        ];
      case 3:
        return [
          {
            id: 'V_WEAPON_T3_CLAWS',
            type: 'WEAPON',
            tier: 3,
            label: 'Griffes maudites',
            desc: 'Arme vampirique — D20, régénération 3 sur 16+.',
          },
        ];
    }
  }

  private buildVampireArmorOptions(tier: 1 | 2 | 3): ForgeOption[] {
    switch (tier) {
      case 1:
        return [
          {
            id: 'V_ARMOR_T1_CARAPACE',
            type: 'ARMOR',
            tier: 1,
            label: 'Carapace d’ombre',
            desc: 'Armure vampirique — D8 +1 DEF.',
          },
        ];
      case 2:
        return [
          {
            id: 'V_ARMOR_T2_HAUBERT',
            type: 'ARMOR',
            tier: 2,
            label: 'Haubert de nuit',
            desc: 'Armure vampirique — D12 +1 DEF.',
          },
        ];
      case 3:
        return [
          {
            id: 'V_ARMOR_T3_ECORCE',
            type: 'ARMOR',
            tier: 3,
            label: 'Écorce impie',
            desc: 'Armure vampirique — D20 + esquive sur 19–20.',
          },
        ];
    }
  }

  private computeForgeOptionsForOwner(g: GameSnapshot): ForgeOption[] {
    const ownerId = g.locationEffectOwnerId;
    if (!ownerId) return [];

    const owner = g.players.find(p => p.id === ownerId);
    if (!owner) return [];

    const role = owner.role; // 'HUNTER' | 'VAMPIRE' | 'SERVANT'

    const weaponTier = this.getWeaponTierFromPlayer(owner);
    const armorTier  = this.getArmorTierFromPlayer(owner);

    const nextWeaponTier = (weaponTier < 3 ? (weaponTier + 1) as 1 | 2 | 3 : null);
    const nextArmorTier  = (armorTier  < 3 ? (armorTier  + 1) as 1 | 2 | 3 : null);

    const options: ForgeOption[] = [];

    const isHunterCamp = role === 'HUNTER';
    const isVampCamp   = role === 'VAMPIRE' || role === 'SERVANT';

    if (nextWeaponTier) {
      if (isHunterCamp) {
        options.push(...this.buildHunterWeaponOptions(nextWeaponTier));
      } else if (isVampCamp) {
        options.push(...this.buildVampireWeaponOptions(nextWeaponTier));
      }
    }

    if (nextArmorTier) {
      if (isHunterCamp) {
        options.push(...this.buildHunterArmorOptions(nextArmorTier));
      } else if (isVampCamp) {
        options.push(...this.buildVampireArmorOptions(nextArmorTier));
      }
    }

    return options;
  }

  onSelectForgeOption(opt: ForgeOption) {
    if (!this.isLocationActionOwner) return;
    this.forgeSelectedId = opt.id;
  }

  confirmForge() {
    if (!this.game || !this.forgeCanSubmit || !this.forgeSelectedId) return;

    this.forgeSubmitting = true;

    const chosen = this.forgeOptions.find(o => o.id === this.forgeSelectedId) || null;

    this.api.resolveForge(
      this.game.id,
      this.forgeSelectedId
    ).subscribe({
      next: () => {
        this.forgeSubmitting = false;

        if (chosen) {
          this.forgeResolvedLabel = chosen.label;
        }
      },
      error: (err) => {
        console.error('Erreur resolveForgeEffect', err);
        alert(err.error?.message ?? 'Erreur Forge');
        this.forgeSubmitting = false;
      },
    });
  }

  buildOptions: Array<{ code: InfraCode; title: string; where: string }> = [
    { code: 'SAWMILL',     title: 'Scierie',             where: 'Forêt' },
    { code: 'MINE',        title: 'Mine',                where: 'Carrière' },
    { code: 'LIBRARY',     title: 'Bibliothèque',        where: 'Manoir' },
    { code: 'LABORATORY',  title: 'Laboratoire occulte', where: 'Manoir' },
    { code: 'BALLROOM',    title: 'Salle de bal',        where: 'Manoir' },
    { code: 'ALTAR',       title: 'Sanctuaire / Autel',  where: 'Manoir' },
    { code: 'FORGE',       title: 'Forge',               where: 'Manoir' },
  ];

  isInfraBuilt(code: InfraCode): boolean {
    return (this.game?.builtInfras ?? []).includes(code);
  }

  infraImg(code: InfraCode): string {
    // ex: "FORGE" -> "/assets/cards/locations/forge.png"
    return `/assets/cards/locations/${code.toLowerCase()}.png`;
  }

  private readonly INFRA_COSTS: Record<InfraCode, Partial<Record<string, number>>> = {
    SAWMILL:    { stone: 5, iron: 3 },
    MINE:       { wood: 6,  iron: 2 },
    LIBRARY:    { wood: 8,  stone: 4, iron: 2 },
    LABORATORY: { water: 5, herbs: 5, stone: 3, souls: 50 },
    BALLROOM:   { stone: 8, iron: 2, souls: 50 },
    ALTAR:      { stone: 4, wood: 2, iron: 2, souls: 50 },
    FORGE:      { iron: 8,  stone: 4, wood: 2 },
  };

  private readonly RES_META: Record<string, { icon: string; label: string }> = {
    wood:  { icon: '/assets/icons/wood.png',          label: 'Bois' },
    stone: { icon: '/assets/icons/stone.png',         label: 'Pierre' },
    iron:  { icon: '/assets/icons/iron.png',          label: 'Fer' },
    water: { icon: '/assets/icons/water.png',         label: 'Eau pure' },
    herbs: { icon: '/assets/icons/medical_grass.png', label: 'Herbe médicinale' },
    souls: { icon: '/assets/icons/souls.png',         label: 'Âmes déchues' },
  };

  infraCostList(code: InfraCode): Array<{ qty: number; icon: string; label: string }> {
    const costs = this.INFRA_COSTS[code] ?? {};
    const order: string[] = ['wood','stone','iron','water','herbs','souls']; // ordre stable
    const out: Array<{ qty: number; icon: string; label: string }> = [];

    for (const k of order) {
      const qty = costs[k];
      if (!qty) continue;
      const meta = this.RES_META[k];
      out.push({ qty, icon: meta.icon, label: meta.label });
    }
    return out;
  }

  private readonly LOCATION_INFO: Partial<Record<string, string[]>> = {
    forge: [
      'Dépenser ressources pour piocher une carte arme ou armure.',
    ],
    library: [
      'Étude des grimoires : piocher une carte action.',
      'Subtilisation de manuscrit : prendre une carte action aléatoire de l’adversaire (un chasseur au choix si vampire) et la mélanger dans la pioche.',
      'Prédiction occulte : révéler la prochaine carte Action de l’adversaire (sans la montrer) et choisir de la mettre au-dessus ou au-dessous de la pioche.',
    ],
    ballroom: [
      'Danse macabre : quand le vampire réussit une attaque sur ce lieu, le chasseur visé subit aussi +1 corruption.',
      'Charme du vampire : vole une ressource au hasard à chaque chasseur présent sur ce lieu (en plus d’attaquer).',
      'Valse sanguinaire : jette autant de dés d’attaque que de chasseurs présents, garde le meilleur et applique l’attaque à tous les chasseurs sur ce lieu.',
      'Les chasseurs sur ce lieu ont deux fois la récolte d’or sur ce lieu.',
    ],

    altar: [
      'Autel purifié : un chasseur peut réduire de 1 la corruption.',
      'Autel corrompu : dépenser eau bénite OU repousser le vampire lors des combats sur ce lieu pour PURIFIER l’autel.',
      'Autel corrompu : le vampire peut augmenter de 1 la corruption d’un chasseur.',
      'Autel purifié : morsure réussie OU sacrifier des âmes corrompues pour CORROMPRE l’autel.',
      'Si le vampire a gagné au moins un affrontement sur ce lieu : le lieu n’est pas purifié.',
    ],

    laboratory: [
      'Expérimentation : dépenser des âmes déchues pour créer un monstre (carte “monstre” jouée pour défendre un lieu).',
      'Explosion alchimique : un chasseur peut détruire le labo → le vampire perd immédiatement 1 ressource au hasard et 20 âmes déchues.',
      'Fabriquer une potion / un élixir : dépenser eau pure + herbes médicinales pour piocher une potion ou un élixir.',
    ],
  };

  locationInfo(code: string | null | undefined): { key: string; lines: string[] } | null {
    if (!code) return null;
    const key = String(code).toLowerCase();
    const lines = this.LOCATION_INFO[key];
    if (!lines?.length) return null;
    return { key, lines };
  }
}
