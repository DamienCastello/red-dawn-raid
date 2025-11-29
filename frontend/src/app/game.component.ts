import { Component, inject, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, GameSnapshot, RawStatMod, Phase, TradeView, Pile } from './api.service';
import { LiveService, GameEvent } from './live.service';

type SPlayer = GameSnapshot['players'][number];
type UiStatMod = RawStatMod & { labelFr?: string; displayOnly?: boolean };
type DefaultFightInfo = { willFight: boolean; loc?: string; opponentName?: string };
type TradeStatus = 'PENDING'|'CONFIRMED'|'REFUSED'|'CANCELLED';
type TradeSide = 'HUNTERS'|'VAMP_SIDE';
interface STrade {
  id: string; side: TradeSide; aId: string; bId: string;
  offerA: Record<string,number>; offerB: Record<string,number>;
  statusA: TradeStatus; statusB: TradeStatus; updatedAt: number;
}

@Component({
  standalone: true,
  selector: 'app-game',
  imports: [CommonModule],
  template: `
  <main class="container">
    <button class="lobby-btn" (click)="back()">← Retour Lobby</button>
    <h2 style="margin: 0px;">Raid {{ game?.raid }} — {{ game?.phase || '...' }}</h2>

    <div *ngIf="errorMsg" style="background:#fee;border:1px solid #f99;padding:.5rem;margin:.5rem 0">
      {{ errorMsg }}
    </div>

    <!-- BOARD HAUT: chasseurs -->
    <section class="board-wide players-grid">

      <div *ngFor="let p of hunterPlayers; trackBy: trackById"
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
          <div class="equip-card">
            <div class="equip-label">Arme :</div>
            <span class="dice-chip">{{ p.attackDice || 'D6' }}</span>
          </div>
          <div class="equip-card">
            <div class="equip-label">Armure :</div>
            <span class="dice-chip">{{ p.defenseDice || 'D6' }}</span>
          </div>
          <div class="mini-stack">
            <div class="mini-card"><!-- placeholder --></div>
            <div class="mini-card"><!-- placeholder --></div>
          </div>
        </div>

        <!-- CHIPS -->
        <ng-container *ngIf="modsForDisplay(p) as mods">
          <div class="mods-row" *ngIf="mods.length">
            <span class="mod-chip" *ngFor="let m of mods" [title]="titleFor(m)">
              <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                <img class="weather-ico" [src]="weatherIconSrc(game?.weather?.status)" alt="icône météo" />
              </div>

              <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
              </div>

              <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                <img class="mod-ico" [src]="actionIconSrc(m.source)" alt="action"/>
              </div>

              <div class="mods-badge-corruption"
                  *ngIf="m.source?.startsWith('CORRUPTION')">
                <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
              </div>
              <span class="chip-val">{{ labelOrChip(m) }}</span>
            </span>
          </div>
        </ng-container>
      </div>
    </section>

    <!-- LIGNE MILIEU -->
    <div class="boards-row">
      <!-- gauche: stats vampire -->
      <section *ngIf="hasVampire" class="panel">
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
          <div class="equip-card">
            <div class="equip-label">Arme :</div>
            <span class="dice-chip">{{ vampirePlayer.attackDice || 'D6' }}</span>
          </div>
          <div class="equip-card">
            <div class="equip-label">Armure :</div>
            <span class="dice-chip">{{ vampirePlayer.defenseDice || 'D6' }}</span>
          </div>
                    <div class="mini-stack">
            <div class="mini-card"><!-- placeholder --></div>
            <div class="mini-card"><!-- placeholder --></div>
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
                [src]="weatherIconSrc(game?.weather?.status)"
                alt="icône météo"
              />
            </div>

            <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
              <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
            </div>

            <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
              <img class="mod-ico" [src]="actionIconSrc(m.source)" alt="action"/>
            </div>
            <span class="chip-val">{{ labelOrChip(m) }}</span>
          </span>
        </div>
      </section>


      <!-- centre: plateau -->
      <section class="panel">

        <div class="center-grid">
          <!-- GAUCHE : Fil en direct -->
          <div class="center-col">
            <h3 class="center-title">Fil en direct</h3>

            <!-- Bandeau PREPHASE3 -->
            <div *ngIf="game?.phase==='PREPHASE3' && game?.hasUpcomingCombat" class="prephase3">
              <b>Préparation au combat</b>
              <span *ngIf="remainingPrePhaseSeconds > 0"> ({{ remainingPrePhaseSeconds }}s)</span>

              <!-- Le bouton n’apparaît QUE s’il y aura un combat -->
              <div *ngIf="me && game?.hasUpcomingCombat" style="margin-top:.5rem">
                <button *ngIf="!pendingUnstable() && imInUpcomingCombat()" (click)="skipNow()" [disabled]="hasSkipped" title="Signaler que vous avez fini vos actions">
                  J’ai fini
                </button>
                <small *ngIf="hasSkipped || !imInUpcomingCombat()" style="margin-left:.5rem; color:#666">En attente des autres…</small>
              </div>
            </div>

            <!-- Messages “live” -->
            <div *ngIf="(game?.messages?.length || 0) > 0" class="live-box">
              <div *ngFor="let m of game?.messages" class="live-line">{{ m }}</div>
            </div>

            <!-- Centre (cartes) -->
            <div *ngIf="!game?.center?.length" style="color:#999">Aucune carte jouée pour l’instant</div>
            <div *ngFor="let cp of game?.center" style="margin:.25rem 0">
              <span *ngIf="cp.faceUp; else back">
                {{ usernameOf(cp.playerId) }}: {{ labelLocation(cp.card) }}
              </span>
              <ng-template #back>
                <i>Carte face cachée ({{ usernameOf(cp.playerId) }})</i>
              </ng-template>
            </div>
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
      <section class="panel">
        <h3>Actions & Potions</h3>
        <div>
          Pioche action chasseur :
          {{ deckSize(game?.decks?.actionsHunters) }}
          (défausse : {{ discardSize(game?.decks?.actionsHunters) }})
        </div>

        <div>
          Pioche potions :
          {{ deckSize(game?.decks?.potions) }}
          (défausse : {{ discardSize(game?.decks?.potions) }})
        </div>

        <div>
          Pioche action vampire :
          {{ deckSize(game?.decks?.actionsVamp) }}
          (défausse : {{ discardSize(game?.decks?.actionsVamp) }})
        </div>
      </section>
    </div>

    <!-- BOARD BAS: moi -->
    <section *ngIf="me && game" class="board-wide my-board">
      <div class="player-strip">
        <div class="name">
          {{ me.username || 'anonyme' }} — {{ me.role === 'VAMPIRE' ? 'Vampire' : (me.role === 'SERVANT' ? 'Serviteur' : 'Chasseur') }}
        </div>
        <div class="hp">
          <img class="hp-heart" [src]="heartIconFor(me)" alt="HP"/>
          <span class="hp-value">{{ me.hp }}</span>
        </div>
      </div>
      <div class="res-board" *ngIf="me as m" style="display:flex;gap:.5rem;align-items:center;justify-content:center;margin:.25rem 0;">
        <span>Ressources&nbsp;:</span>
        <span title="Bois">🪵 {{ m.wood || 0 }}</span>
        <span title="Herbe médicinale">🌿 {{ m.herbs || 0 }}</span>
        <span title="Pierre">🪨 {{ m.stone || 0 }}</span>
        <span title="Fer">⛓️ {{ m.iron || 0 }}</span>
        <span title="Eau pure">💧 {{ m.water || 0 }}</span>
        <span *ngIf="m.role==='HUNTER'" title="Or">🪙 {{ m.gold || 0 }}</span>
        <span *ngIf="m.role==='VAMPIRE' || m.role==='SERVANT'" title="Âmes déchues">🕯️ {{ m.souls || 0 }}</span>
        <span *ngIf="m.role==='HUNTER'" title="Argent">🥈 {{ (m.silver || 0) }}</span>
      </div>

      <!-- BARRE D’ÉQUIPEMENT -->
      <div class="equip-bar">
        <!-- Arme -->
          <div class="equip-card">
            <div class="equip-label">Arme :</div>
            <span class="dice-chip">{{ me.attackDice || 'D6' }}</span>
          </div>
          

          <!-- Armure -->
          <div class="equip-card">
            <div class="equip-label">Armure :</div>
            <span class="dice-chip">{{ me.defenseDice || 'D6' }}</span>
          </div>
      </div>

      <!-- CHIPS -->
      <ng-container *ngIf="modsForDisplay(me) as myMods">
        <div class="mods-row" *ngIf="myMods.length">
          <span class="mod-chip" *ngFor="let m of myMods" [title]="titleFor(m)">
            <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
              <img class="weather-ico" [src]="weatherIconSrc(game.weather?.status)" alt="icône météo" />
            </div>

            <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
              <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
            </div>

            <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
              <img class="mod-ico" [src]="actionIconSrc(m.source)" alt="action"/>
            </div>

            <div class="mods-badge-corruption"
                *ngIf="m.source?.startsWith('CORRUPTION')">
              <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
            </div>
            <span class="chip-val">{{ labelOrChip(m) }}</span>
          </span>
        </div>
      </ng-container>

     <div class="hand">
        <!-- En-têtes sur la même ligne -->
        <div class="hand-head">
          <div class="hand-title">Votre main</div>
        </div>

        <div class="hand-cards">
          <button *ngFor="let c of (me?.hand || [])"
                  class="card-btn"
                  (click)="onLocationClick(c)"
                  [class.selected]="selectedLocation === c"
                  [class.is-disabled]="!canPlayLocation(c)"
                  [attr.aria-disabled]="!canPlayLocation(c) ? true : null"
                  [title]="(!canPlayLocation(c) ? 'garlicTooltip' : null)"
                  style="padding:.5rem 1rem; border:1px solid #ccc; cursor:pointer">
            {{ labelLocation(c) }}
          </button>
          <button *ngFor="let action of myActions()"
                  class="card-btn"
                  [class.is-disabled]="!canUseActionNow(action)"
                  [attr.aria-disabled]="!canUseActionNow(action) ? true : null"
                  (click)="onActionClick(action)"
                  [title]="canUseActionNow(action)
                  ? 'Utiliser maintenant (préparation au combat)'
                  : 'Disponible uniquement en PREPHASE3 si vous participez à un combat'">
            {{ actionLabelFr(action) }}
          </button>
          <button *ngFor="let potion of myPotions()"
                  class="card-btn"
                  [class.is-disabled]="!canUsePotionNow(potion)"
                  [attr.aria-disabled]="!canUsePotionNow(potion) ? true : null"
                  (click)="onPotionClick(potion)"
                  [title]="canUsePotionNow(potion)
                  ? 'Utiliser maintenant (préparation au combat)'
                  : 'Disponible uniquement en PREPHASE3 si vous participez à un combat'">
            {{ potionLabelFr(potion) }}
          </button>
        </div>   


        <div style="margin-top:.5rem">
          <button (click)="playSelected()" [disabled]="!canPlay">Jouer cette carte</button>
          <button
            style="margin-left:.5rem"
            *ngIf="me?.role === 'VAMPIRE'"
            [disabled]="game.phase !== 'PHASE2'"
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
  <div *ngIf="showRollModal && currentCombat as r" class="modal-backdrop">
    <div class="modal location-modal" [style.backgroundImage]="setImageBackground('location')">
      <h3 style="margin-top:0" class="bg-badge">
        {{ modalTitle(r) }}
      </h3>
          <div class="bg-badge" *ngIf="isMyFocusFirstStep">
            Potion de focalisation : vous pouvez relancer ce dé et garder le meilleur.
          </div>
      <div class="content action">
        <!-- On affiche le dé du joueur courant, avec icône -->
        
        <ng-container *ngIf="waitingForMyRoll as side">
          <ng-container *ngIf="side==='ATTACK'; else defenseSide">
            <!-- ATTAQUANT -->
            <div class="roll-row">
              <!-- Icône (à gauche) -->
              <div class="icon-bubble oval">
                <div class="icon-halo" [ngClass]="(getPlayer(r.attackerId)?.role==='VAMPIRE') ? 'round' : 'oval'">
                  <img class="icon-side"
                      [src]="roleIcon(getRole(getPlayer(r.attackerId)),'sword')"
                      alt="attaque"/>
                </div>
              </div>

              <!-- Gros dé principal -->
              <div class="dice-wrap">
                <img class="dice-big"
                    [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                    roleColorOf(getPlayer(r.attackerId)))"
                    alt="dice"/>
                <div class="dice-overlay" *ngIf="r.attackerRoll != null">
                  {{ r.attackerReroll }}
                </div>
              </div>

              <!-- Colonne "Premier jet" (uniquement en focalisation, entre 1er et 2e dé) -->
              <div class="focus-column"
                  *ngIf="hasFocus(r.attackerId)
                          && r.attackerId === meId
                          && r.attackerFirstRoll != null
                          && r.attackerRoll == null">
                <div class="bg-badge focus-label">
                  Premier jet d'attaque
                </div>
                <div class="dice-wrap focus-small">
                  <img class="dice"
                      [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                        roleColorOf(getPlayer(r.attackerId)))"
                      alt="premier dé"/>
                  <div class="dice-overlay">
                    {{ r.attackerFirstRoll }}
                  </div>
                </div>
              </div>
            </div>

            <ng-container *ngIf="modsForStat(getPlayer(r.attackerId), 'ATTACK') as atkMods">
              <div class="mods-row" *ngIf="atkMods.length">
                <span class="mod-chip" *ngFor="let m of atkMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weather?.status)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
                  </div>
                  <span class="chip-val">{{ labelOrChip(m) }}</span>
                </span>
              </div>
            </ng-container>
          </ng-container>
          <ng-template #defenseSide>
            <!-- DEFENSEUR -->
            <div class="roll-row">
              <!-- Icône à gauche -->
              <div class="icon-bubble oval">
                <div class="icon-halo oval">
                  <img class="icon-side"
                      [src]="roleIcon(getRole(getPlayer(r.defenderId)),'armor')"
                      alt="défense"/>
                </div>
              </div>

              <!-- Gros dé principal -->
              <div class="dice-wrap">
                <img class="dice-big"
                    [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                    roleColorOf(getPlayer(r.defenderId)))"
                    alt="dice"/>
                <div class="dice-overlay" *ngIf="r.defenderRoll != null">
                  {{ r.defenderReroll }}
                </div>
              </div>

              <!-- Colonne "Premier jet" pour la défense -->
              <div class="focus-column"
                  *ngIf="hasFocus(r.defenderId)
                          && r.defenderId === meId
                          && r.defenderFirstRoll != null
                          && r.defenderRoll == null">
                <div class="bg-badge focus-label">
                  Premier jet de défense
                </div>
                <div class="dice-wrap focus-small">
                  <img class="dice"
                      [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                        roleColorOf(getPlayer(r.defenderId)))"
                      alt="premier dé"/>
                  <div class="dice-overlay">
                    {{ r.defenderFirstRoll }}
                  </div>
                </div>
              </div>
            </div>

            <ng-container *ngIf="modsForStat(getPlayer(r.defenderId), 'DEFENSE') as defMods">
              <div class="mods-row" *ngIf="defMods.length">
                <span class="mod-chip" *ngFor="let m of defMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weather?.status)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
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
  <div *ngIf="showSpectatorModal && currentCombat as r" class="modal-backdrop">
    <div class="modal location-modal spectate" 
        [class.has-focus]="hasFocus(r.attackerId) || hasFocus(r.defenderId)"
        [class.bite-active]="!!game?.currentBite && !isBeforeBiteModal"
        [style.backgroundImage]="setImageBackground('location')">
      <h3 style="margin-top:0" class="bg-badge">
        {{ modalTitle(r) }}
      </h3>

      <div class="content spectate">
        <!-- Côté attaquant -->
        <div class="side">
          <!-- Colonne mods à GAUCHE -->
          <ng-container *ngIf="modsForStat(getPlayer(r.attackerId), 'ATTACK') as atkMods">
            <div class="mods-col left" *ngIf="atkMods.length">
              <div class="mods-row">
                <span class="mod-chip" *ngFor="let m of atkMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weather?.status)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
                  </div>
                  <span class="chip-val">{{ labelOrChip(m) }}</span>
                </span>
              </div>
            </div>
          </ng-container>

          <div class="icon-halo oval"
              [ngClass]="(getPlayer(r.attackerId)?.role==='VAMPIRE') ? 'round' : 'oval'">
            <img class="icon-side"
                [src]="roleIcon(getRole(getPlayer(r.attackerId)),'sword')"
                alt="attaque"/>
          </div>

          <div class="dice-row">
            <ng-container *ngIf="showFocusSpectate(r.attackerId); else attackerSingleDie">
              <!-- Layout 2 dés (FOCA) AVANT la morsure -->
              <div class="dice-wrap">
                <img class="dice"
                    [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                      roleColorOf(getPlayer(r.attackerId)))"
                    alt="premier dé"/>
                <div class="dice-overlay" *ngIf="r.attackerFirstRoll != null">
                  {{ r.attackerFirstRoll }}
                </div>
              </div>

              <div class="dice-wrap">
                <img class="dice"
                    [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                      roleColorOf(getPlayer(r.attackerId)))"
                    alt="dé de focalisation"/>
                <div class="dice-overlay" *ngIf="r.attackerRoll != null">
                  {{ r.attackerReroll }}
                </div>
              </div>
            </ng-container>

            <!-- Layout classique : un seul dé (utilisé dès que currentBite existe) -->
            <ng-template #attackerSingleDie>
              <div class="dice-wrap">
                <img class="dice"
                    [src]="diceAsset(getPlayer(r.attackerId)?.attackDice,
                                      roleColorOf(getPlayer(r.attackerId)))"
                    alt="dice"/>
                <div class="dice-overlay"
                    *ngIf="(r.attackerRoll ?? r.attackerFirstRoll) != null">
                  {{ r.attackerRoll ?? r.attackerFirstRoll }}
                </div>
              </div>
            </ng-template>
          </div>
        </div>

        <!-- Côté défenseur -->
        <div class="side">
          <!-- Colonne mods à DROITE -->
          <ng-container *ngIf="modsForStat(getPlayer(r.defenderId), 'DEFENSE') as defMods">
            <div class="mods-col right" *ngIf="defMods.length">
              <div class="mods-row">
                <span class="mod-chip" *ngFor="let m of defMods" [title]="titleFor(m)">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weather?.status)" alt="icône météo" />
                  </div>
                  
                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(m.source)" alt="action"/>
                  </div>

                  <div class="mods-badge-corruption"
                      *ngIf="m.source?.startsWith('CORRUPTION')">
                    <img class="mod-ico" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
                  </div>
                  <span class="chip-val">{{ labelOrChip(m) }}</span>
                </span>
              </div>
            </div>
          </ng-container>

          <div class="icon-halo oval">
            <img class="icon-side"
                [src]="roleIcon(getRole(getPlayer(r.defenderId)),'armor')"
                alt="défense"/>
          </div>

          <div class="dice-row">
            <ng-container *ngIf="showFocusSpectate(r.defenderId); else defenderSingleDie">
              <!-- 2 dés FOCA AVANT la morsure -->
              <div class="dice-wrap">
                <img class="dice"
                    [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                      roleColorOf(getPlayer(r.defenderId)))"
                    alt="premier dé"/>
                <div class="dice-overlay" *ngIf="r.defenderFirstRoll != null">
                  {{ r.defenderFirstRoll }}
                </div>
              </div>

              <div class="dice-wrap">
                <img class="dice"
                    [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                      roleColorOf(getPlayer(r.defenderId)))"
                    alt="dé de focalisation"/>
                <div class="dice-overlay" *ngIf="r.defenderRoll != null">
                  {{ r.defenderReroll }}
                </div>
              </div>
            </ng-container>

            <!-- Layout classique : un seul dé -->
            <ng-template #defenderSingleDie>
              <div class="dice-wrap">
                <img class="dice"
                    [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice,
                                      roleColorOf(getPlayer(r.defenderId)))"
                    alt="dice"/>
                <div class="dice-overlay"
                    *ngIf="(r.defenderRoll ?? r.defenderFirstRoll) != null">
                  {{ r.defenderRoll ?? r.defenderFirstRoll }}
                </div>
              </div>
            </ng-template>
          </div>
        </div>
      </div>

      <div class="breakdown bg-badge" *ngIf="currentCombat?.breakdownLines?.length">
        <div *ngFor="let line of currentCombat!.breakdownLines">{{ line }}</div>
      </div>
      <div class="result bg-badge" *ngIf="getCombatResultText() as txt">{{ txt }}</div>
      <div class="footer bg-badge" *ngIf="!getCombatResultText()">Les adversaires s’affrontent…</div>
    </div>
  </div>
  <!-- === MODALE MORSURE (PHASE3, quand currentBite actif) === -->
  <div *ngIf="showBiteModal" class="modal-backdrop">
    <div class="modal bite-modal" [style.backgroundImage]="setImageBackground('bite')">
      <h3 class="bg-badge">Tentative de morsure</h3>

      <div class="content action">
        <div class="icon-bubble round">
          <div class="icon-halo round">
            <img class="icon-side" src="/assets/corruption/corruption-icon.png" alt="corruption"/>
          </div>
        </div>

        <div class="dice-wrap" [attr.data-digits]="1">
          <img class="dice-big" src="/assets/dices/d6-red.png" alt="d6"/>
          <div class="dice-overlay" *ngIf="game?.currentBite?.roll != null">
            {{ game?.currentBite?.roll }}
          </div>
        </div>
      </div>

      <div class="footer">
        <!-- avant le jet -->
        <ng-container *ngIf="game?.currentBite?.roll == null; else biteResult">
          <ng-container *ngIf="canRollBite(); else waitBite">
            <button (click)="rollCorruption()" class="btn-primary">Jeter le dé</button>
          </ng-container>
          <ng-template #waitBite >
            <span class="bg-badge">En attente du jet…</span>
          </ng-template>
        </ng-container>

        <!-- après le jet -->
        <ng-template #biteResult>
          <span class="bg-badge">{{ biteResultText() }}</span>
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
  <div *ngIf="shopOpen" class="modal-backdrop">
    <div class="modal trade-modal"
        [class.hunters]="isHunter"
        [class.vampires]="isVampireSide">
      <header class="modal-head">
        <h3 *ngIf="isHunter">Boutique</h3>
        <h3 *ngIf="isVampireSide">Transmutation</h3>

          <div class="inventory" *ngIf="me as m">
            <span class="inv">🪵 {{ m.wood }}</span>
            <span class="inv">🌿 {{ m.herbs }}</span>
            <span class="inv">🪨 {{ m.stone }}</span>
            <span class="inv">⛓️ {{ m.iron }}</span>
            <span class="inv">💧 {{ m.water }}</span>
            <span *ngIf="m.role==='HUNTER'" class="inv">🪙 {{ m.gold }}</span>
            <span *ngIf="m.role==='HUNTER'" class="inv">🥈 {{ m.silver }}</span>
            <span *ngIf="m.role==='VAMPIRE' || m.role==='SERVANT'" class="inv">🕯️ {{ m.souls }}</span>
          </div>

        <div class="timer" *ngIf="phase4LeftSec>0">⏱ {{ phase4LeftSec }}s</div>
      </header>

      <section class="modal-body">
        <div class="col">
          <div class="card" *ngIf="isMeVampire">
            <h4>Actions</h4>
            <p>Coût : 50 âmes déchues</p>
            <p>Pioche restante : {{ deckSize(game?.decks?.actionsVamp) }}</p>
            <button (click)="onBuyAction()" [disabled]="!canBuyVampAction">
              Acheter une action aléatoire
            </button>
          </div>

          <div class="card" *ngIf="isHunter">
            <h4>Actions</h4>
            <p>Coût : 50 pièces d'or</p>
            <p>Pioche restante : {{ deckSize(game?.decks?.actionsHunters) }}</p>
            <button (click)="onBuyAction()" [disabled]="!canBuyHunterAction">
              Acheter une action aléatoire
            </button>
          </div>

          <div class="card">
            <h4>Potions</h4>
            <p>Coût : 4 eaux pures + 3 herbes médicinales</p>
            <p>Pioche restante : {{ deckSize(game?.decks?.potions) }}</p>
            <button (click)="onBuyPotion()" [disabled]="!canBuyPotion">
              Acheter une potion aléatoire
            </button>
          </div>

          <div class="card" *ngIf="isHunter">
            <h4>Acheter de l’argent</h4>
            <p>50 or → 1 argent</p>
            <div class="row">
              <button (click)="onBuySilver(1)" [disabled]="!canBuySilver">+1</button>
              <button (click)="onBuySilver(5)" [disabled]="me?.gold! < 250">+5</button>
            </div>
          </div>

          <div class="card" *ngIf="isHunter">
            <h4>Vendre des ressources</h4>
            <p>1 ressource → +10 or</p>
            <div class="grid">
              <div class="sell" *ngFor="let r of sellableResources">
                <div class="label">
                  {{ r }} <small>(x{{ resOf(me, r) }})</small>
                </div>
                <div class="actions">
                  <button (click)="onSell(r, 1)" [disabled]="resOf(me, r) < 1">-1</button>
                </div>
              </div>
            </div>
          </div>

          <div class="card" *ngIf="isVampireSide">
            <h4>Recettes de transmutation</h4>
            <ul class="recipes">
              <li>
                <span>2 bois + 1 eau → +2 fer</span>
                <button (click)="onTransmute('WOOD_TO_IRON')" [disabled]="me?.wood!<2 || me?.water!<1">Transmuter</button>
              </li>
              <li>
                <span>2 fer + 1 eau → +2 bois</span>
                <button (click)="onTransmute('IRON_TO_WOOD')" [disabled]="me?.iron!<2 || me?.water!<1">Transmuter</button>
              </li>
              <li>
                <span>1 bois + 1 fer + 1 eau → +20 âmes</span>
                <button (click)="onTransmute('TRINITY_TO_SOULS')" [disabled]="me?.wood!<1 || me?.iron!<1 || me?.water!<1">Transmuter</button>
              </li>
            </ul>
          </div>
        </div>

        <div class="col">
          <div class="card">
            <h4>Proposer un échange</h4>
            <div class="targets">
              <button *ngFor="let p of eligibleTradeTargets"
                      (click)="selectTradeTarget(p.id)"
                      [class.active]="p.id===selectedTradeTargetId">
                {{ p.username }}
              </button>
            </div>

            <div *ngIf="selectedTradeTargetId as tgtId" class="trade-area">
              <div class="card">
                <h5>Mes ressources</h5>
                <div class="grid">
                  <div class="res" *ngFor="let r of allResources">
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

                  <div class="row" style="justify-content:space-between">
                    <strong>{{ usernameOf(otherIdFromTrade(T)) }}</strong>
                    <small class="muted">maj {{ T.updatedAt | date:'shortTime' }}</small>
                  </div>

                  <!-- Sa proposition -->
                  <div class="card sub"
                      [class.ok]="statusClassFrom(otherStatus(T))==='ok'"
                      [class.ko]="statusClassFrom(otherStatus(T))==='ko'">
                    <h5>Proposition d’échange de {{ usernameOf(T.aId===me?.id ? T.bId : T.aId) }}</h5>
                    <div class="pill" *ngFor="let k of ((iAmA(T)?T.offerB:T.offerA) | keyvalue)">
                      {{k.key}} x{{k.value}}
                    </div>
                    <div *ngIf="!((iAmA(T)?T.offerB:T.offerA) | keyvalue).length" class="muted">En attente…</div>
                  </div>

                  <!-- Ta proposition -->
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
            <div class="card">
              <h4>Statut</h4>
              <div *ngIf="!waitingDone; else waitingTpl">
                <button class="finish" (click)="onFinishPhase4()">Ne rien faire</button>
              </div>
              <ng-template #waitingTpl>
                <div class="muted">En attente des autres joueurs…</div>
              </ng-template>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
  <!-- ===== MODALE ACTION ===== -->
  <div class="modal-backdrop" *ngIf="showActionModal">
    <div
      class="modal action-modal with-bg"
      [ngStyle]="trapMode === 'NET'
        ? {'background-image': 'url(/assets/actions/net.png)'}
        : trapMode === 'PIT'
          ? {'background-image': 'url(/assets/actions/traphole.png)'}
          : null"
    >
      <div class="content action">
        <!-- Boîte noire semi-transparente qui contient tout le texte -->
        <div class="action-box">
          <h2 *ngIf="trapMode === 'NET'">Filet</h2>
          <h2 *ngIf="trapMode === 'PIT'">Fosse</h2>

          <p *ngIf="trapLocation">
            Lieu : {{ trapLocation }}
          </p>

          <!-- ========== FILET ========== -->
          <ng-container *ngIf="trapMode === 'NET'">

            <!-- Vue ACTEUR : choix de cible + bouton de dé -->
            <ng-container *ngIf="isTrapActor; else netSpectate">

              <p>Choisis une cible :</p>

              <div class="trap-targets">
                <button
                  type="button"
                  *ngFor="let p of trapEnemies"
                  (click)="selectTrapTarget(p.id)"
                  [disabled]="trapResolving || trapRoll !== null"
                  [class.selected]="trapSelectedTargetId === p.id">
                  {{ p.username }} ({{ p.role }})
                </button>
              </div>

              <button
                type="button"
                (click)="onNetRoll()"
                [disabled]="!trapSelectedTargetId || trapResolving || trapRoll !== null">
                Lancer le dé
              </button>
            </ng-container>

            <!-- Vue SPECTATEUR : on montre seulement la cible choisie / en attente -->
            <ng-template #netSpectate>
              <ng-container *ngIf="trapSelectedTargetId; else netWaitTarget">
                <p>Cible : {{ trapTargetName }}</p>
              </ng-container>
              <ng-template #netWaitTarget>
                <p>En attente du choix de la cible...</p>
              </ng-template>
            </ng-template>
          </ng-container>

          <!-- ========== FOSSE ========== -->
          <ng-container *ngIf="trapMode === 'PIT'">

            <!-- Vue ACTEUR -->
            <ng-container *ngIf="isTrapActor; else pitSpectate">

              <p *ngIf="currentPitTarget">
                Cible : {{ currentPitTarget.username }} ({{ currentPitTarget.role }})
              </p>

              <button
                type="button"
                (click)="onPitRoll()"
                [disabled]="trapResolving || trapRoll !== null || !currentPitTarget">
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

          <!-- ========== Résultat commun (acteurs + spectateurs) ========== -->
          <div class="trap-result">

          <!-- Dé visuel -->
            <div class="dice-wrap">
              <img class="dice"
                  [src]="diceAsset('D20', trapDiceColor)"
                  alt="dice"/>
              <div class="dice20-overlay"
                  *ngIf="(trapRoll) != null">
                {{ trapRoll }}
              </div>
            </div>

            <!-- Texte existant -->
            <p *ngFor="let line of trapBreakdownLines">
              {{ line }}
            </p>
          </div>
        </div>
      </div>
    </div>
  </div>
  <!-- ===== MODALE CHOIX DE CONSTRUCTION ===== -->
  <div class="modal-backdrop" *ngIf="buildModalOpen">
    <div class="modal construction-modal">
        <h2>Construire un lieu</h2>
        <p>Choisissez l’infrastructure à construire :</p>

        <div class="modal-button-row">
          <button *ngIf="!(game?.builtInfras?.includes('SAWMILL'))"
           (click)="onChooseInfra('SAWMILL')">
            Scierie<br />
            <small>(Forêt · 5 pierre, 3 fer)</small>
          </button>

          <button *ngIf="!(game?.builtInfras?.includes('MINE'))"
           (click)="onChooseInfra('MINE')">
            Mine<br />
            <small>(Carrière · 6 bois, 2 fer)</small>
          </button>

          <button *ngIf="!(game?.builtInfras?.includes('LIBRARY'))"
           (click)="onChooseInfra('LIBRARY')">
            Bibliothèque<br />
            <small>(Carrière · 8 bois, 4 pierre, 2 fer)</small>
          </button>
        </div>

        <button class="btn-secondary" (click)="closeBuildModal()">Annuler</button>
    </div>
  </div>
  <!-- ===== MODALE CONFIRMATION CONSTRUCTION ===== -->
  <div class="modal-backdrop" *ngIf="buildConfirmModalOpen && buildChoice">
    <div class="modal construction-modal" [style.backgroundImage]="setImageBackground('construction')">
      <div class="modal-overlay-content">
        <p class="bg-badge">{{ getInfraConfirmText(buildChoice!) }}</p>

        <div class="modal-button-row">
          <button (click)="doBuild()">Oui</button>
          <button class="btn-secondary" (click)="cancelBuild()">Annuler</button>
        </div>
      </div>
    </div>
  </div>
  <!-- ===== MODALE SELECTION EFFET DE LIEU ===== -->
  <div class="modal-backdrop"
      *ngIf="game?.locationEffectPending 
      && game?.locationEffectInfra === 'LIBRARY'
      && !locationActionModalOpen"
  >
    <div class="modal construction-modal location-effect-modal"
        style="background-image: url('/assets/locations/library.png')">
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
  <!-- ===== MODALE UTILISATION EFFET DE LIEU ===== -->
  <div class="modal-backdrop"
      *ngIf="locationActionModalOpen && game?.locationEffectInfra === 'LIBRARY'">
    <div class="modal construction-modal location-effect-modal"
        style="background-image: url('/assets/locations/library.png')">
      <div class="modal-overlay-content">
        <h2>Bibliothèque — Effet en cours</h2>

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
          <div *ngSwitchCase="'OMEN'" class="omen-container">
            <p *ngIf="!isLocationEffectOwner">
              Le joueur consulte secrètement les trois prochaines cartes d’action de l’adversaire.
            </p>

            <div class="cards-row" *ngIf="isLocationEffectOwner">
              <div
                class="card omen-card"
                *ngFor="let cardId of omenCards; let i = index"
              >
                <div class="card-title">
                  {{ actionLabelFr(cardId) }}
                </div>

                <div class="card-buttons">
                  <button type="button"
                          (click)="onOmenPlacementClick(i, 'TOP')"
                          [disabled]="!isLocationEffectOwner"
                          [class.selected]="omenPlacements[i] === 'TOP'">
                    Dessus le deck
                  </button>
                  <button type="button"
                          (click)="onOmenPlacementClick(i, 'BOTTOM')"
                          [disabled]="!isLocationEffectOwner"
                          [class.selected]="omenPlacements[i] === 'BOTTOM'">
                    Dessous le deck
                  </button>
                </div>
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

        </ng-container>
      </div>
    </div>
  </div>
  `,
  styles: [`
  /* Layout des boards */
  .container{
    width: 96vw;
    max-width: 1920px;
    margin: 0 auto;
    padding: .5rem;
  }
  .lobby-btn{ position: fixed; right: 40px; top: 10px; }
  .board-wide{ width:100%; padding:.5rem; border:1px solid #ddd; margin:.5rem 0; background:#fff; }
  .players-grid{
    display: flex;
    flex-wrap: wrap;
    gap: .5rem;
    justify-content: center;
  }
  .player-card{
    flex: 0 0 auto;
    max-width: 270px;
    min-width: 220px;
  }
  .boards-row{ display:grid; gap:.75rem; align-items:start; grid-template-columns: 1fr 4fr 1fr; margin:.5rem 0; }
  .panel{ border:1px solid #ddd; padding:.5rem; background:#fefefe; }

  /* Centre plateau */
  .center-grid{
    display:grid;
    grid-template-columns: 1fr 1fr;
    gap: .75rem;
    align-items:start;
  }
  .center-col{ min-width:0; }
  .center-title{ margin: .25rem 0 .5rem; font-size: 1.2rem; }
  .prephase3{
    margin:.5rem 0; padding:.5rem; background:#fffbe6; border:1px solid #e6c200;
  }
  .live-box{
    margin:.5rem 0; padding:.5rem; background:#f8f8ff; border:1px solid #ccd;
  }
  .live-line{ margin:.15rem 0; }

  /* Historique scrollable */
  .history-box{
    max-height: 206px;
    overflow: auto;
    border:1px solid #e5e5e5;
    border-radius: 6px;
    padding: .5rem;
    background: #fff;
  }
  .history-head{
    margin-top:.35rem;
    font-weight: 700;
    color:#333;
  }
  .history-line{
    padding-left:.25rem;
    margin:.15rem 0;
  }

  /* Bandeau joueur */
  .player-strip{ display:flex; align-items:center; justify-content:space-between; gap:.75rem; padding:.4rem .6rem; border:1px solid #ddd; border-radius:8px; background:#fff; margin-bottom: 4px;}
  .player-strip .name{ font-weight:700; }
  .player-strip .hp{ display:flex; align-items:center; gap:.35rem; }
  .hp-heart{ width:22px; height:22px; }
  .hp-value{ font-weight:700; min-width:2ch; text-align:right; }

  /* --- Barre d'équipement --- */
  .equip-bar{
    display:flex;
    align-items:stretch;
    gap:.5rem;
    flex-wrap:nowrap;
  }

  .equip-bar > .equip-card{
    flex: 0 0 90px;
    min-width: 0;
    border:1px solid #ddd; border-radius:10px; padding:.5rem .4rem;
    background:linear-gradient(180deg,#f8f9fb,#eef1f5);
    height: 100px
  }

  .equip-bar > .mini-stack{
    flex: 0 0 50px;
    width: 50px;
    min-width: 0;
    display:flex;
    flex-direction:column;
    gap:.5rem;

  }

  .mini-card {
    border:1px solid #ddd; border-radius:10px; padding:.5rem .4rem;
    background:linear-gradient(180deg,#f8f9fb,#eef1f5);
    margin: 1px 3px 0 0;
    height: 35px
  }


  .equip-label{ font:600 12px/1.1 system-ui, sans-serif; color:#333; margin-bottom:.35rem; }

  .dice-chip{
    display:inline-block;
    font:700 12px/1 system-ui, sans-serif;
    padding:.25rem .5rem;
    border-radius:999px;
    background:#111; color:#fff; opacity:.95;
  }

  /* --- BOARD DU BAS --- */
  .my-board .equip-bar{ width: min(270px, 100%); }
  .my-board .equip-bar > .equip-card{
    flex: 0 0 calc((100% - 1rem) / 3);
    width:     calc((100% - 1rem) / 3);
  }
  .my-board .equip-bar > .mini-stack{ display:none; }

  .hand{
    display: grid;
    grid-template-rows: auto 1fr auto;
    gap: .5rem;
    margin-top: 10px;
  }

  .hand-head{
    display:grid;
    grid-template-columns: 1fr 1fr;
    gap: 1rem;
    align-items: end;
    font-weight: 600;
  }

  .hand-title{
    font: 600 14px/1.1 system-ui, sans-serif;
  }

  .hand-cards{
    display:flex;
    gap:.5rem;
    flex-wrap:wrap;
    margin-top:.5rem;
  }

  .card-btn{
    padding:.5rem 1rem;
    border:1px solid #ccc;
    cursor:pointer;
    background:#fff;
  }

  .card-btn.is-disabled{
    opacity:.55;
    cursor:not-allowed;
    pointer-events: auto;
  }
  .selected{ outline:2px solid #000 }


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
    font:700 11px/1 system-ui, sans-serif;
    background:#222; color:#fff; 
    border-radius:999px; 
    padding:.18rem .4rem;
    white-space: nowrap;
    opacity:.92;
  }
  .mod-chip .chip-ico{
    width:14px; height:14px; border-radius:3px;
    background: rgba(255,255,255,.85); /* placeholder */
  }
  .mod-chip .chip-val{ line-height:1; }

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
    left: -100px;
  }

  .modal.location-modal.spectate.has-focus .mods-col.right{
    right: -100px;
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
    gap: .75rem;
  }

  .modal.location-modal.spectate.has-focus .content.spectate .dice{
    width: 150px;
  }

  .modal.location-modal.spectate.has-focus.bite-active .content.spectate .dice{
    width: 180px;
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
    max-width: 48ch;
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

  .mods-badge-weather, .mods-badge-potion, .mods-badge-action, .mods-badge-corruption{
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
    min-height: 540px;
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
  .mods-badge-corruption .mod-ico{
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
  .action-box .trap-targets{
    display: flex;
    flex-wrap: wrap;
    gap: .35rem;
    margin: .5rem 0;
  }

  /* Boutons dans la boîte (cibles + "Lancer le dé") */
  .action-box .trap-targets button,
  .action-box button{
    padding: .4rem .75rem;
    border-radius: 8px;
    border: 1px solid rgba(255,255,255,.35);
    background: rgba(255,255,255,.08);
    color: #fff;
    cursor: pointer;
  }

  .action-box .trap-targets button.selected{
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


  /* Trade modale */
  .modal.trade-modal{
    background:#fff;
    max-width:1000px;
    width:90vw;
    height:90vh;
    overflow:auto;
    border-radius:12px;
    padding:1rem;
  }
  .modal-head{display:flex;align-items:center;justify-content:space-between;gap:.5rem;border-bottom:1px solid #eee;padding-bottom:.5rem;margin-bottom:1rem;color: #eee;}
  .modal-body{display:grid;grid-template-columns:1fr 1fr;gap:1rem;}
  .modal.trade-modal .card{border:1px solid #eee;border-radius:8px;padding:.50rem;margin-bottom:.75rem;}
    .modal.trade-modal .card h4{margin: 10px 5px;}
  .row{display:flex;gap:.5rem;align-items:center;flex-wrap:wrap;}
  .grid{display:grid;grid-template-columns:repeat(2, minmax(0,1fr));gap:.5rem;}
  .targets{}
  .targets button.active{outline:2px solid #333;}
  .trade-area .pill{display:inline-block;border:1px solid #ddd;border-radius:999px;padding:.15rem .5rem;margin:.15rem;}
  .ok{background:rgba(0,160,0,.08);}
  .ko{background:rgba(200,0,0,.08);}
  .finish{width:100%;}
  .muted{opacity:.7;}
  .timer{font-weight:600;}
  .recipes li{display:flex;align-items:center;justify-content:space-between;gap:.5rem;margin:.25rem 0;}
  .sell .actions button{min-width:3rem;}
  .modal.trade-modal .modal-head .inventory{
    margin: 0 auto;                 /* pousse au centre entre titre et timer */
    display: flex; gap: .35rem; flex-wrap: wrap;
  }
  .modal.trade-modal .modal-head .inv{
    border:1px solid rgba(255,255,255,.35);
    background:rgba(0,0,0,.25);
    border-radius:999px;
    padding:.1rem .5rem;
    font-weight:600;
  }

  .trade-list{
    max-height:32vh;
    overflow-y:auto;
    padding-right:.25rem;
  }

  .trade-block{
    border:1px solid #eee;
    border-radius:8px;
    padding:.5rem;
    margin-bottom:.5rem;
  }
  .card.sub{ margin:.5rem 0; }
  .trade-block.active{ outline:2px solid #333; }

  .modal.trade-modal{
    position:relative;
    overflow:hidden;
    background:transparent;
  }
  .modal.trade-modal::before{
    content:"";
    position:absolute; inset:0;
    background-size: cover;
    background-position:center;
    pointer-events:none;
    z-index:0;
  }
  .modal.trade-modal.hunters::before{   background-image:url('/assets/locations/bg-trade-hunters.png'); }
  .modal.trade-modal.vampires::before{  background-image:url('/assets/locations/bg-trade-vampires.png'); }
  .modal.trade-modal > *{ position:relative; z-index:1; }

  .modal.trade-modal .card{
    background:rgba(32,32,32,.35);
    border-color:rgba(255,255,255,.25);
    color:#fff;
  }

  .modal.trade-modal .card .muted{
    opacity:1;
    color:rgba(255,255,255,.75);
  }

  .modal.trade-modal .pill{
    border:1px solid rgba(255,255,255,.35);
    background:rgba(255,255,255,.14);
    color:#fff;
  }

  .modal.trade-modal .ok{ background:rgba(0,160,0,.18); }
  .modal.trade-modal .ko{ background:rgba(200,0,0,.18); }

  .modal.trade-modal .targets button.active{ outline-color:#fff; }

  /* Petits toasts (remplacent le bloc supprimé pendant 2.5s) */
  .trade-toast{
    border:1px solid transparent;
    border-radius:6px;
    padding:.5rem .75rem;
    margin-bottom:.5rem;
    font-weight:600;
    color:#fff;
  }
  .trade-toast.ok{
    background: rgba(0,160,0,.18);
    border-color: rgba(0,160,0,.35);
  }
  .trade-toast.ko{
    background: rgba(200,0,0,.18);
    border-color: rgba(200,0,0,.35);
  }

  /* Pendant la fermeture, force le fond sur les deux sous-cards,
   même si elles portent déjà .ok/.ko */
  .modal.trade-modal .trade-block.closing-ok .card.sub {
    background: rgba(0,160,0,.16) !important;
    outline: 2px solid rgba(0,160,0,.6) !important;
  }
  .modal.trade-modal .trade-block.closing-ko .card.sub {
    background: rgba(200,0,0,.16) !important;
    outline: 2px solid rgba(200,0,0,.6) !important;
  }

  /* Verrou des boutons inchangé */
  .trade-block.closing .buttons button {
    opacity: .6; pointer-events: none;
  }

  /* === MODALE CONSTRUCTION === */
  /* Conteneur de la modale construction */
  .modal.construction-modal {
    position: relative;
    width: min(780px, 95vw);
    min-height: 540px;

    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;

    padding: 1.5rem;
    text-align: center;
    background-size: cover;
    background-position: center;
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

  /* boutons */
  .modal.construction-modal .modal-button-row {
    display: flex;
    justify-content: center;
    gap: 0.75rem;
    margin: 1rem 0;
    flex-wrap: wrap;
  }

  .modal.construction-modal button {
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

  /* On réutilise la même overlay que construction */
  .modal.location-effect-modal .modal-overlay-content {
    background: rgba(0, 0, 0, 0.65);
    padding: 1.5rem;
    border-radius: 8px;
    max-width: 560px;
    width: min(560px, 90vw);
    color: #fff;
    text-align: center;
  }

  /* Boutons d'option d'effet (texte cliquable) */
  .modal.location-effect-modal .modal-button-row .effect-option {
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
  .modal.location-effect-modal .modal-button-row .effect-option.selected {
    color: #fff;
    font-weight: 600;
    text-decoration: underline;
    outline: none;
    background: transparent;
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
`]
})
export class GameComponent {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private live = inject(LiveService);
  private unsubscribeGameTopic?: () => void; // pour couper l’abonnement WS au destroy

  private weatherWaitTimer?: any;

  manyModsForDebug(p: SPlayer, times = 3) {
    const base = this.modsForDisplay(p);
    return Array(times).fill(base).flat();
  }

  gameId = '';
  game?: GameSnapshot;
  errorMsg = '';
  hasSkipped = false;

  meId = sessionStorage.getItem('userId') || '';
  username = sessionStorage.getItem('username') || '';

  selectedLocation: string | null = null;

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
  // NET ou PIT, ou null si pas de piège en cours
  trapMode: 'NET' | 'PIT' | null = null;
  trapOwnerId: string | null = null;
  trapLocation: string | null = null;
  trapEnemies: SPlayer[] = [];
  // Pour le FILET : cible choisie par le chasseur
  trapSelectedTargetId: string | null = null;
  // Pour la FOSSE : index de la cible en cours dans trapEnemies
  trapCurrentIndex = 0;
  // Résultat courant à afficher dans la modale
  trapRoll: number | null = null;
  trapBreakdownLines: string[] = [];
  // Afficher / cacher la modale
  showActionModal = false;
  // Flag pour éviter les doubles clics pendant l'appel API
  trapResolving = false;

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
  buildChoice: 'SAWMILL' | 'MINE' | 'LIBRARY' | null = null;
  effectChoice: 'STUDY' | 'THEFT' | 'OMEN' | null = null;

  // ----- Effet de lieu : modale d'action (Bibliothèque) -----
  locationActionModalOpen = false;
  locationActionKind: 'THEFT' | 'OMEN' | null = null;

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
  readonly allResources: ReadonlyArray<'gold'|'silver'|'souls'|'wood'|'herbs'|'stone'|'iron'|'water'> =
    ['gold','silver','souls','wood','herbs','stone','iron','water'] as const;

  back(){ this.router.navigate(['/lobby']); }

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
    if (!p) return 0;

    // On réutilise exactement les mêmes filtres que pour l’affichage des puces :
    // - météo inactive -> pas de mods WEATHER
    // - météo annulée par Feu de camp -> pas de mods WEATHER
    // - CORRUPTION:...:ENG masqués
    const mods = this.modsForDisplay(p);

    return mods.reduce((sum, m) => sum + (m.stat === stat ? m.amount : 0), 0);
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

    return (m as any).labelFr || this.chipOf(m);
  }

  titleFor(m: RawStatMod): string | null {
    const s = (m as any).source || '';

    // Corruption (DSP)
    if (s.startsWith('CORRUPTION:') && s.endsWith(':DSP')) {
      if (s.includes(':L1:')) return 'Attaque et défense diminuées de 1 (persiste entre les raids).';
      if (s.includes(':L2:')) return 'Peut se retourner contre ses alliés sur un jet défavorable.';
      if (s.includes(':L3:')) return 'Ce chasseur est un serviteur du vampire';
      return 'Effet de corruption';
    }

    if (s.startsWith('WEATHER:')) {
      const g = this.game;
      if (g?.weather?.nameFr && g?.weather.descFr) {
        return `${g.weather.nameFr} — ${g.weather.descFr}`;
      }
      return 'Effets météo';
    }

    if (s.startsWith('POTION:')) {
      const type = (s.split(':')[1] || '').toUpperCase();
      const tooltips: Record<string, string> = {
        FORCE:           'augmente de +1 le dé d’attaque',
        ENDURANCE:       'augmente de +1 le dé de défense',
        VIE:             'se soigner de +10 PV',
        FOCALISATION:    'lancer 2 dés lors des combat et garder le meilleur',
        SANGSUE:         'se soigner d’un montant égal aux dégats infligés',
        RESILIENCE:      'double la défense',
        RAGE:            'double l’attaque',
        RAPIDITE:        'attaque x2',
        INVISIBILITE:    'l\'adversaire ne jette pas de dé de défense',
        INVULNERABILITE: 'insensible aux dégâts',
      };
      return tooltips[type] ?? null;
    }

    // (Garde le reste de tes cas, ex. météo si tu l’avais déjà ajouté)
    return null;
  }

  private isWeatherActive(): boolean {
    const g = this.game;
    return !!g && g.weather?.roll != null && !!g.weather.status;
  }

  // === Helpers combat ===
  private nameOrId(id: string): string {
    return this.getPlayer(id)?.username || id;
  }

  /** SPECTATE: affiche le nom joueur dans sa colonne */
  modalTitle(r: any): string {
    const atk = this.getPlayer(r.attackerId);
    const def = this.getPlayer(r.defenderId);
    if (!atk || !def) return `${this.nameOrId(r.attackerId)} vs ${this.nameOrId(r.defenderId)}`;

    const vampireLeft = atk.role === 'VAMPIRE';
    const vampireName = vampireLeft ? this.nameOrId(r.attackerId) : this.nameOrId(r.defenderId);
    const hunterName  = vampireLeft ? this.nameOrId(r.defenderId) : this.nameOrId(r.attackerId);

    return vampireLeft ? `${vampireName} vs ${hunterName}` : `${hunterName} vs ${vampireName}`;
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
    return (p?.role === 'VAMPIRE') ? 'red' : 'blue';
  }
  getRole(p?: SPlayer): 'VAMPIRE'|'HUNTER'|'SERVANT'|undefined {
    return p?.role ;
  }
  diceAsset(dice: string | undefined, color: 'red'|'blue'): string {
    const d = (dice || 'D6').toLowerCase();
    return `/assets/dices/${d}-${color}.png`;
  }
  roleIcon(role?: 'VAMPIRE'|'HUNTER'|'SERVANT'|undefined, name?: 'sword'|'armor'): string {
    return role === 'SERVANT' ? `/assets/icons/HUNTER-${name}.png` : `/assets/icons/${role}-${name}.png`;
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

  getCombatResultText(): string | null {
    const r = this.currentCombat; if (!r) return null;
    const atkP = this.getPlayer(r.attackerId);
    const defP = this.getPlayer(r.defenderId);
    if (r.attackerRoll == null || r.defenderRoll == null) return null;

    const atkMod = this.totalModForDisplay(r.attackerId, 'ATTACK');
    const defMod = this.totalModForDisplay(r.defenderId, 'DEFENSE');
    const dmg = Math.max(0, (r.attackerRoll + atkMod) - (r.defenderRoll + defMod));

    const an = atkP?.username || r.attackerId;
    const dn = defP?.username || r.defenderId;
    return dmg > 0 ? `${an} inflige ${dmg} dégâts à ${dn}` : `${dn} pare l’attaque de ${an}`;
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
  setImageBackground(modal:'weather'|'location'|'bite'|'corruption'|'construction'): string | null {

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
    }
    return 'none';
  }

  // chemin de l'icône météo
  weatherIconSrc(ws?: string | null): string {
    if (!ws) return '';
    return `/assets/weather/icon-${ws.toLowerCase()}.png`;
  }

  actionIconSrc(source: string): string {
    // fallback simple
    if (!source || !source.startsWith('ACTION:')) {
      return '/assets/icons/action-hunter-icon.png';
    }

    // source = "ACTION:FOSSE" -> on prend "FOSSE"
    const parts = source.split(':');
    const code = parts[1] || '';

    // Liste des actions qui sont forcément jouées par les chasseurs
    const hunterActions = [
      'FUMIGATION_AIL',
      'PISTEUR',
      'FEU_DE_CAMP',
      'FILET',
      'FOSSE',
    ];

    const isHunter = hunterActions.includes(code);

    return isHunter
      ? '/assets/icons/action-hunter-icon.png'
      : '/assets/icons/action-vampire-icon.png';
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
      p => (p.role === 'HUNTER' || p.role === 'SERVANT') && p.id !== this.meId
    );
    // garder les serviteurs en premier puis les chasseurs
    // return list.sort((a, b) => (a.role === b.role ? 0 : a.role === 'SERVANT' ? -1 : 1));
    return list;
  }
  get canPlay(): boolean {
    if (!this.game || !this.me || !this.selectedLocation) return false;
    const phase = this.game.phase;
    if (this.me.role === 'HUNTER') return phase === 'PHASE1';
    if (this.me.role === 'VAMPIRE' || this.me.role === 'SERVANT') return phase === 'PHASE2';
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
      default: return c;
    }
  }

  /** INIT
   * fait 1 refresh au montage, puis s'abonne au WebSocket.
   */
  ngOnInit() {
    this.route.paramMap.subscribe(pm => {
      const id = pm.get('id'); 
      if (!id) { this.showError('Identifiant...'); return; }

      this.unsubscribeGameTopic?.();
      this.gameId = id;

      // 1) D’abord WS
      this.unsubscribeGameTopic = this.live.subscribeGame(this.gameId, ev => this.onLiveEvent(ev));

      // 2) Puis snapshot initial (autorité)
      this.api.getGame(this.gameId).subscribe({
        next: snap => {
          const previous = null;

          this.game = snap;
          this.handleWeatherReveal(snap);

          // Instables
          this.recomputeUnstableChoices();

          // Modale pièges
          this.syncTrapFromSnapshot(snap);

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

      case 'PHASE_CHANGED': {
        const next = (event?.payload?.phase as Phase | undefined) ?? undefined;

        // Gestion du timer PREPHASE3 AVANT le snapshot détaillé
        if (next === 'PREPHASE3') {
          // Si on est déjà dans un sous-flow "effets de lieu", on ne lance pas le timer
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

            this.syncTrapFromSnapshot(g);

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

      case 'LOBBY_UPDATED': {
        // si status == CREATED, refresh pour voir les pseudos/players en live
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
                  this.game = g;
                  this.bumpHistoryScroll();
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
          next: g => this.game = g,
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

      case 'ACTION_USED': {
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
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
            this.syncTrapFromSnapshot(g);
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
            this.syncTrapFromSnapshot(g); // met à jour trapRoll + breakdown dans la modale
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
        this.api.getGame(this.gameId).subscribe({
          next: g => {
            this.game = g;
            this.syncTrapFromSnapshot(g); // currentAction null => ferme la modale
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

            this.syncTrapFromSnapshot(g);
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
  }

  preparedGarlicForThisRaid = false;

  playSelected() {
    if (!this.game || !this.selectedLocation) return;

    const g  = this.game;
    const me = this.me;
    const hasPisteur = this.myActions().includes('PISTEUR');

    const isHunterPhase1 = (me?.role === 'HUNTER' && g.phase === 'PHASE1');

    // Cas spécial : combo Fumigation + Pisteur (chasseur en PHASE1)
    if (isHunterPhase1 && this.preparedGarlicForThisRaid && hasPisteur) {
      const useTracker = window.confirm(
        "Voulez-vous également utiliser Pisteur pour traquer le vampire ?"
      );

      // 1) On joue le lieu dans tous les cas
      this.api.selectLocation(g.id, this.selectedLocation).subscribe({
        next: _ => {
          // 2) Si le joueur a répondu OUI → on utilise Pisteur juste après
          if (useTracker) {
            this.api.useAction(g.id, 'PISTEUR').subscribe({
              error: e => this.showError(e)
            });
          }
        },
        error: e => this.showError(e)
      });

      // On nettoie l’état local
      this.preparedGarlicForThisRaid = false;
      this.selectedLocation = null;
      return;
    }

    // Comportement normal : juste jouer le lieu et fin de phase auto
    this.api.selectLocation(g.id, this.selectedLocation).subscribe({
      error: e => this.showError(e)
    });

    this.selectedLocation = null;
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

  canUsePotionNow(_pot: string): boolean {
    const g = this.game;
    if (!g) return false;

    // Web app : fenêtre unique de préparation avant les duels
    if (g.phase === 'PREPHASE3' && g.hasUpcomingCombat) {
      return this.imInUpcomingCombat();
    }

    // Jamais en PHASE3
    return false;
  }

  // Suis-je (moi) sur un lieu face-up où il y aura un combat (ennemi = vampire OU serviteur) ?
  imInUpcomingCombat(): boolean {
    const game = this.game;
    if (!game) return false;

    const faceUp = (game.center || []).filter(cb => cb.faceUp);
    if (faceUp.length === 0) return false;

    const harvestMap: Record<string,string> = (game as any).unstableHarvestLocByPlayer || {};

    const allLocs = Array.from(new Set(faceUp.map(cb => cb.card)));
    const combatLocs = new Set<string>();

    for (const loc of allLocs) {
      const idsOnLoc = faceUp.filter(cb => cb.card === loc).map(cb => cb.playerId);
      const playersOnLoc = idsOnLoc
        .map(id => game.players.find(p => p.id === id))
        .filter((p): p is SPlayer => !!p);

      const hasEnemy  = playersOnLoc.some(p => this.isEnemy(p));
      const hasHunter = playersOnLoc.some(p =>
        p.role === 'HUNTER' && p.hp > 0 && !harvestMap[p.id]   // <-- exclusion récolteur
      );

      if (hasEnemy && hasHunter) combatLocs.add(loc);
    }

    const myFaceUpCard = faceUp.find(cb => cb.playerId === this.meId)?.card;
    // si je suis récolteur, jamais combat pour moi
    if (harvestMap[this.meId]) return false;

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

  usePotion(type: string) {
    if (!this.game) return;
    this.api.usePotion(this.game.id, type).subscribe({
      error: e => this.showError(e)
    });
  }

  onPotionClick(pot: string){
    if (!this.canUsePotionNow(pot)) return;
    this.usePotion(pot);
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

  canUseActionNow(_action: string): boolean {
    const game = this.game;
    if (!game) return false;
        
    const ws = game.weather?.status;
    if(game.phase === 'PREPHASE3' && (_action === "FEU_DE_CAMP" || _action === "FILET" || _action === "FOSSE") && (ws === 'DUSK' || ws === 'NIGHT_DARK' || ws === 'NIGHT_CLEAR')) return true
    if(game.phase === 'PREPHASE3' && (_action === "FILET" || _action === "FOSSE")) return true;
    if(game.phase === 'PHASE1' && (_action === "FUMIGATION_AIL" || _action === "PISTEUR")) return true;

    return false
  }

  actionLabelFr(id: string): string {
    switch (id) {
      case 'FUMIGATION_AIL': return 'Fumigation d\'ail';
      case 'PISTEUR':        return 'Pisteur';
      case 'FEU_DE_CAMP': return 'Feu de camp';
      case 'FILET':        return 'Filet';
      case 'FOSSE': return 'Fosse';
      default: return id;
    }
  }

  useAction(type: string) {
    if (!this.game) return;
    this.api.useAction(this.game.id, type).subscribe({
      next: _g => {
        if (type === 'FUMIGATION_AIL') {
          // On mémorise qu’on a préparé une fumigation pour CE raid
          this.preparedGarlicForThisRaid = true;
        }
      },
      error: e => this.showError(e)
    });
  }

  onActionClick(action: string){
    if (!this.canUseActionNow(action)) return;
    this.useAction(action);
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
    if (!this.game || !this.trapSelectedTargetId || this.trapMode !== 'NET') {
      return;
    }
    if (!this.isTrapActor) return;
    if (this.trapResolving) return;

    this.trapResolving = true;

    this.api.resolveNet(this.gameId, this.trapSelectedTargetId).subscribe({
      next: () => {
        this.trapResolving = false;
      },
      error: e => {
        this.trapResolving = false;
        this.showError(e);
      }
    });
  }

  onPitRoll() {
    if (!this.game || this.trapMode !== 'PIT') return;
    if (this.trapResolving) return;
    if (!this.isTrapActor) return; // doit être la victime courante

    const current = this.currentPitTarget;
    if (!current) return;

    this.trapResolving = true;

    this.api.resolvePit(this.gameId).subscribe({
      next: () => {
        // Le résultat arrive via ACTION_ROLLED + getGame
        this.trapResolving = false;
      },
      error: e => {
        this.trapResolving = false;
        this.showError(e);
      }
    });
  }

  get isTrapActor(): boolean {
    const meId = this.me?.id;
    if (!meId) return false;

    if (this.trapMode === 'NET') {
      // Filet : acteur = chasseur propriétaire
      return this.trapOwnerId === meId;
    }

    if (this.trapMode === 'PIT') {
      // Fosse : acteur = victime courante
      const current = this.currentPitTarget;
      return !!current && current.id === meId;
    }

    return false;
  }

  // Nom de la cible (pour tout le monde)
  get trapTargetName(): string | null {
    const targetId = this.trapSelectedTargetId;
    if (!targetId || !this.game) return null;
    const p = this.game.players.find(pl => pl.id === targetId);
    return p?.username ?? targetId;
  }

  get currentPitTarget(): SPlayer | null {
    if (!this.trapEnemies || !this.trapEnemies.length) return null;

    // priorité à targetId venant du back
    if (this.trapSelectedTargetId) {
      const found = this.trapEnemies.find(p => p.id === this.trapSelectedTargetId);
      if (found) return found;
    }

    return this.trapEnemies[this.trapCurrentIndex] ?? null;
  }

  private syncTrapFromSnapshot(g: GameSnapshot) {
    const ts = g.currentAction;

    // 1) Pas d'action ou pas un piège -> on ferme
    if (!ts || (ts.mode !== 'NET' && ts.mode !== 'PIT')) {
      this.trapMode = null;
      this.trapOwnerId = null;
      this.trapLocation = null;
      this.trapSelectedTargetId = null;
      this.trapRoll = null;
      this.trapBreakdownLines = [];
      this.trapEnemies = [];
      this.trapCurrentIndex = 0;
      this.showActionModal = false;
      return;
    }

    // 2) Action de piège : synchronisation de base
    this.trapMode = ts.mode;
    this.trapOwnerId = ts.ownerId;
    this.trapLocation = ts.location;
    this.trapSelectedTargetId = ts.targetId ?? null;
    this.trapRoll = ts.roll ?? null;
    this.trapBreakdownLines = ts.breakdownLines ?? [];

    // Recalcule les ennemis sur le lieu (pour les deux modes)
    if (this.trapLocation && g.players) {
      this.trapEnemies = this.playersOnLocation(this.trapLocation)
        .filter(p => p.role === 'VAMPIRE' || p.role === 'SERVANT');
    } else {
      this.trapEnemies = [];
    }

    // 🔹 Pour FOSSE uniquement : index courant basé sur targetId
    if (this.trapMode === 'PIT') {
      if (this.trapSelectedTargetId && this.trapEnemies.length) {
        const idx = this.trapEnemies.findIndex(p => p.id === this.trapSelectedTargetId);
        this.trapCurrentIndex = idx >= 0 ? idx : 0;
      } else {
        this.trapCurrentIndex = 0;
      }
    } else {
      this.trapCurrentIndex = 0;
    }

    this.showActionModal = true;
  }

  selectTrapTarget(id: string) {
    // Filet uniquement, par design (Fosse n’a pas de ciblage manuel chez toi)
    if (this.trapMode !== 'NET') return;
    if (!this.isTrapActor || this.trapResolving || this.trapRoll !== null) return;

    this.trapResolving = true; // on réutilise ce flag pour désactiver les boutons pendant l’appel

    this.api.setNetTarget(this.gameId, id).subscribe({
      next: () => {
        this.trapResolving = false;
        // On met aussi à jour localement pour feedback instantané
        this.trapSelectedTargetId = id;
        // Le snapshot “officiel” arrivera via l’event ACTION_STARTED
      },
      error: e => {
        this.trapResolving = false;
        this.showError(e);
      }
    });
  }

  get actionOwner() {
    const g = this.game;
    if (!g || !g.currentAction) return null;
    return g.players.find(p => p.id === g.currentAction?.ownerId) ?? null;
  }

  get trapDiceColor(): 'red' | 'blue' {
    if (this.trapMode === 'NET') return 'blue';
    if (this.trapMode === 'PIT') return 'red';
    return 'blue';
  }

  //====== Corruption ======/
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
  rollBiteNow() {
    if (!this.game) return;
    this.api.rollCorruption(this.game.id).subscribe({
      error: e => this.showError(e)
    });
  }
  biteResultText(): string {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b || b.roll == null) return '';

    const attacker = this.getPlayer?.(b.attackerId)?.username ?? 'Le vampire';
    const target   = this.getPlayer?.(b.targetId)?.username   ?? 'le chasseur';

    // règle simple : > 3 = morsure réussie
    return (b.roll > 3)
      ? `${target} est mordu.`
      : `${attacker} échoue sa tentative de morsure.`;
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

  canRollBite(): boolean {
    const g: any = this.game;
    const b = g?.currentBite;
    if (!b) return false;
    return b.attackerId === this.meId && (b.roll == null);
  }
  rollCorruption(){
    if (!this.game) return;
    this.api.rollCorruption(this.game.id).subscribe({
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
    this.shopOpen = true;
    this.waitingDone = false;
    this.myOffer = {};
  }

  private closeShop() {
    console.log('[SHOP] closeShop() – phase actuelle :', this.game?.phase, 'deadline:', (this.game as any)?.phase4DeadlineMillis);
    this.shopOpen = false;
    this.waitingDone = false;
    this.stopPhase4Timer();
  }

  private syncShopVisibilityFromSnapshot(): void {
    console.log('[SHOP] sync from snapshot – phase =', this.game?.phase, 'shopOpen =', this.shopOpen);
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

    const left = this.deckSize(snapshot.decks?.potions);
    if (left <= 0) return false;

    return me.water >= 4 && me.herbs >= 3;
  }

  get canBuyVampAction() {
    const me = this.me; 
    const snapshot = this.game;
    if (!me || !snapshot) return false;

    const left = this.deckSize(snapshot.decks?.actionsVamp);
    if (left <= 0) return false;

    return me.souls >= 50;
  }

  get canBuyHunterAction() {
    const me = this.me; 
    const snapshot = this.game;
    if (!me || !snapshot) return false;

    const left = this.deckSize(snapshot.decks?.actionsHunters);
    if (left <= 0) return false;

    return me.gold >= 50;
  }

  get canBuySilver() {
    const me = this.me;
    return !!me && me.role === 'HUNTER' && me.gold >= 50;
  }

  iAmA(t: STrade): boolean { return t.aId === this.me?.id; }
  otherIdFromTrade(t: STrade): string { return this.iAmA(t) ? t.bId : t.aId; }


  // UI Maintenance actions
  // --- Boutique --- //
  onBuyPotion() { 
    this.api.buyPotion(this.gameId).subscribe({ 
      next: () => {},
      error: e => this.showError(e) 
    }); 
  }
  onBuyAction() { 
    this.api.buyAction(this.gameId).subscribe({ 
      next: () => {},
      error: e => this.showError(e) 
    }); 
  }
  onBuySilver(qty: number) {
    this.api.buySilver(this.gameId, qty).subscribe({ 
      next: () => {},
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
      return this.game.players.filter((p: SPlayer) => p.role === 'HUNTER' && p.id !== this.me!.id);
    }
    if (this.me!.role === 'VAMPIRE') {
      return this.game.players.filter((p: SPlayer) => p.role === 'SERVANT');
    }
    return this.game.players.filter((p: SPlayer) => p.role === 'VAMPIRE');
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
      // ⬇️ (REMIS) je ne montre pas un trade si MON statut est cancel/refuse
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

  onChooseInfra(infra: 'SAWMILL' | 'MINE' | 'LIBRARY') {
    this.buildChoice = infra;
    this.buildModalOpen = false;
    this.buildConfirmModalOpen = true;
  }

  cancelBuild() {
    this.buildConfirmModalOpen = false;
    this.buildChoice = null;
  }

  // Image de fond pour la modale de confirmation
  getInfraImage(infra: 'SAWMILL' | 'MINE' | 'LIBRARY'): string {
    // adapte les chemins à tes assets réels
    return infra === 'SAWMILL'
      ? 'assets/locations/sawmill.png'
      : 'assets/locations/mine.png';
  }

  getInfraConfirmText(infra: 'SAWMILL' | 'MINE' | 'LIBRARY'): string {
    if (infra === 'SAWMILL') {
      return 'Se déplacer à la Forêt pour construire la Scierie ?';
    } 
    if (infra === 'MINE'){
      return 'Se déplacer à la Carrière pour construire la Mine ?';
    }
    if (infra === 'LIBRARY'){
      return 'Se déplacer au Manoir pour construire la Bibliothèque ?';
    }
    return '';
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
      default:
        return '';
    }
  }

  private syncLocationEffectFromSnapshot(g: GameSnapshot, previous?: GameSnapshot | null) {
    // Si aucun effet de lieu en cours → on reset tout
    if (!g.locationEffectPending || !g.locationEffectInfra) {
      this.effectChoice = null;
      this.resetLocationActionUi();
      return;
    }

    const prevOwner = previous?.locationEffectOwnerId;
    const ownerChanged = !!prevOwner && prevOwner !== g.locationEffectOwnerId;

    // Si le serveur a déjà un choix -> on s'aligne dessus
    if (g.locationEffectChoice) {
      this.effectChoice = g.locationEffectChoice;
    } else if (ownerChanged) {
      // Nouveau joueur en cours de résolution → on reset la sélection locale
      this.effectChoice = null;
    }

    // On repart d'un état d'action propre (la modale de choix reste gérée par le template)
    this.resetLocationActionUi();

    // Pour l'instant : seulement LIBRARY a des effets interactifs
    if (g.locationEffectInfra !== 'LIBRARY') {
      return;
    }

    // --- THEFT : modale d'action pour choisir cible + slot ---
    if (g.locationEffectChoice === 'THEFT') {
      // La modale d'action est visible pour tout le monde (actif + spectateurs)
      this.locationActionKind = 'THEFT';
      this.locationActionModalOpen = true;

      // Seul le propriétaire de l’effet a besoin de la liste des cibles + slots
      if (this.isLocationEffectOwner) {
        const targets = this.computeTheftTargets(g);
        this.theftTargets = targets;
      } else {
        // Spectateurs : pas besoin de targets, ils voient juste le texte "choisit secrètement..."
        this.theftTargets = [];
      }

      return;
    }

    // --- OMEN : on ouvre la modale d'action si des cartes sont préparées ---
    if (g.locationEffectChoice === 'OMEN') {
      const cards = g.libraryOmenCards ?? [];

      if (cards.length > 0) {
        this.locationActionKind = 'OMEN';
        this.locationActionModalOpen = true;

        if (this.isLocationEffectOwner) {
          this.omenCards = cards;

          // Si nouvelle séquence ou taille différente → reset des placements
          if (!this.omenPlacements || this.omenPlacements.length !== cards.length) {
            this.omenPlacements = cards.map(() => null);
          }
        } else {
          // Observateurs : ils ne voient pas le détail des cartes
          this.omenCards = [];
          this.omenPlacements = [];
        }
      }

      return;
    }

    // STUDY ou autres effets futurs non interactifs : rien à faire ici.
  }

  onEffectOptionClick(choice: 'STUDY' | 'THEFT' | 'OMEN') {
    if (!this.canChooseLocationEffect) return;
    this.effectChoice = choice;
  }

  chooseLocationEffect() {
    if (!this.game || !this.effectChoice || !this.canChooseLocationEffect) return;

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

}
