import { Component, inject, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, Game, Player, StatMod } from './api.service';

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
            <span class="mod-chip" *ngFor="let m of mods">
              <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                <img class="weather-ico" [src]="weatherIconSrc(game?.weatherStatus)" alt="icône météo" />
              </div>

              <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
              </div>

              <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                <img class="mod-ico" [src]="actionIconSrc(p)" alt="action"/>
              </div>
              <span class="chip-val">{{ chipOf(m) }}</span>
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
          <span class="mod-chip" *ngFor="let m of modsForDisplay(vampirePlayer)">
            <div
              class="mods-badge-weather"
              *ngIf="m.source?.startsWith('WEATHER:')"
            >
              <img
                class="weather-ico"
                [src]="weatherIconSrc(game?.weatherStatus)"
                alt="icône météo"
              />
            </div>

            <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
              <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
            </div>

            <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
              <img class="mod-ico" [src]="actionIconSrc(vampirePlayer)" alt="action"/>
            </div>
            <span class="chip-val">{{ chipOf(m) }}</span>
          </span>
        </div>

        <div style="margin-top:.6rem; color:#555">
          Pioche actions: {{ game?.vampActionsLeft }} (défausse: {{ game?.vampActionsDiscard }})
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
                <button (click)="skipNow()" [disabled]="hasSkipped" title="Signaler que vous avez fini vos actions">
                  J’ai fini
                </button>
                <small *ngIf="hasSkipped" style="margin-left:.5rem; color:#666">En attente des autres…</small>
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
                {{ usernameOf(cp.playerId) }}: {{ labelLieu(cp.card) }}
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

      <!-- droite: stats chasseurs + potions -->
      <section class="panel">
        <h3>Chasseurs & Potions</h3>
        <div>Pioche actions: {{ game?.hunterActionsLeft }} (défausse: {{ game?.hunterActionsDiscard }})</div>
        <div>Pioche potions: {{ game?.potionsLeft }} (défausse: {{ game?.potionsDiscard }})</div>
      </section>
    </div>

    <!-- BOARD BAS: moi -->
    <section *ngIf="me && game" class="board-wide my-board">
      <div class="player-strip">
        <div class="name">
          {{ me.username || 'anonyme' }} — {{ isVampire ? 'Vampire' : 'Chasseur' }}
        </div>
        <div class="hp">
          <img class="hp-heart" [src]="heartIconFor(me)" alt="HP"/>
          <span class="hp-value">{{ me.hp }}</span>
        </div>
      </div>
      <div class="res-board" *ngIf="me as m" style="display:flex;gap:.5rem;align-items:center;margin:.25rem 0;">
        <span>Ressources&nbsp;:</span>
        <span title="Bois">🪵 {{ m.wood || 0 }}</span>
        <span title="Herbe médicinale">🌿 {{ m.herbs || 0 }}</span>
        <span title="Pierre">🪨 {{ m.stone || 0 }}</span>
        <span title="Fer">⛓️ {{ m.iron || 0 }}</span>
        <span title="Eau pure">💧 {{ m.water || 0 }}</span>
        <span *ngIf="m.role==='HUNTER'" title="Or">🪙 {{ m.gold || 0 }}</span>
        <span *ngIf="m.role==='VAMPIRE'" title="Âmes déchues">🕯️ {{ m.souls || 0 }}</span>
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
          <span class="mod-chip" *ngFor="let m of myMods">
            <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
              <img class="weather-ico" [src]="weatherIconSrc(game.weatherStatus)" alt="icône météo" />
            </div>

            <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
              <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
            </div>

            <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
              <img class="mod-ico" [src]="actionIconSrc(me)" alt="action"/>
            </div>
            <span class="chip-val">{{ chipOf(m) }}</span>
          </span>
        </div>
      </ng-container>

     <div class="hand">
        <!-- En-têtes sur la même ligne -->
        <div class="hand-head">
          <div class="hand-title">Votre main (lieux)</div>
          <div class="hand-title" *ngIf="myPotions().length > 0">Mes potions</div>
        </div>

        <!-- Deux colonnes : lieux | potions -->
        <div class="hand-body">
          <!-- Colonne LIEUX : style inchangé -->
          <div class="hand-col">
            <div class="hand-cards">
              <button *ngFor="let c of (me?.hand || [])"
                      [class.selected]="selectedLocation===c"
                      (click)="selectLocation(c)"
                      style="padding:.5rem 1rem; border:1px solid #ccc; cursor:pointer">
                {{ labelLieu(c) }}
              </button>
            </div>
          </div>

          <!-- Colonne POTIONS : mêmes cartes/visuel que lieux + info au survol -->
          <div class="hand-col">
            <div class="hand-cards">
              <button *ngFor="let pot of myPotions()"
                      class="card-btn"
                      [class.is-disabled]="!canUsePotionNow(pot)"
                      [attr.aria-disabled]="!canUsePotionNow(pot) ? true : null"
                      (click)="onPotionClick(pot)"
                      [title]="canUsePotionNow(pot)
                      ? 'Utiliser maintenant (préparation au combat)'
                      : 'Disponible uniquement en PREPHASE3 si vous participez à un combat'">
                {{ potionLabelFr(pot) }}
              </button>
            </div>
          </div>
        </div>

        <!-- Bouton jouer (lieu sélectionné) -->
        <div style="margin-top:.5rem">
          <button (click)="playSelected()" [disabled]="!canPlay">Jouer cette carte</button>
        </div>
      </div>
    </section>
  </main>

  <!-- === MODALE METEO (PHASE0) === -->
  <div *ngIf="canShowWeatherModal()" class="modal-backdrop">
    <div
      class="modal weather-modal"
      [ngClass]="{ 'with-bg': weatherBgActive }"
      [style.backgroundImage]="weatherBgActive ? setImageBackgroundCss('weather') : null"
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
              *ngIf="game?.weatherStatus && game?.weatherRoll != null"
              [style.transform]="weatherIconTransform()"
            >
              <img
                class="weather-ico"
                [src]="weatherIconSrc(game?.weatherStatus)"
                alt="icône météo"
              />
            </div>
          </div>

          <div class="dice-wrap" style="margin-top:1rem"
              [attr.data-digits]="(game?.weatherRoll ?? 0) > 9 ? 2 : 1">
            <img class="dice" src="/assets/dices/d12-red.png" alt="d12"/>
            <div class="dice-overlay" *ngIf="game?.weatherRoll != null">
              {{ game?.weatherRoll }}
            </div>
          </div>
        </div>

        <div class="footer">
          <ng-container *ngIf="isMeVampire() && game?.weatherRoll == null; else waitWeather">
            <button (click)="rollWeather()" class="btn-primary">Jeter le dé</button>
          </ng-container>
          <ng-template #waitWeather>
            <span *ngIf="game?.weatherRoll == null">En attente du tirage…</span>
            <span class="weather-footer" *ngIf="game?.weatherRoll != null">{{ game?.weatherStatusNameFr }}</span>
          </ng-template>
        </div>
      </ng-container>

      <!-- APRES le délai : fond météo + texte -->
      <ng-template #weatherResult>
        <div class="content weather result">
          <div class="weather-badge">{{ game?.weatherStatusNameFr }}</div>
          <p class="weather-desc">{{ game?.weatherDescriptionFr }}</p>
        </div>
      </ng-template>
    </div>
  </div>
  <!-- === MODALE ACTION (joueur concerné) — AJOUT === -->
  <div *ngIf="showActionModal && currentCombat as r" class="modal-backdrop">
    <div class="modal location-modal" [style.backgroundImage]="setImageBackgroundCss('location')">
      <h3 style="margin-top:0" class="bg-badge">
        {{ modalTitle(r) }}
      </h3>

      <div class="content action">
        <!-- On affiche le dé du joueur courant, avec icône -->
        <ng-container *ngIf="waitingForMyRoll as side">
          <ng-container *ngIf="side==='ATTACK'; else defenseSide">
            <!-- ATTAQUANT -->
            <div class="icon-bubble oval">
              <div class="icon-halo" [ngClass]="(getPlayer(r.attackerId)?.role==='VAMPIRE') ? 'round' : 'oval'">
                <img class="icon-side" [src]="roleIcon(getRole(getPlayer(r.attackerId)),'sword')" alt="attaque"/>
              </div>
            </div>
            <div class="dice-wrap">
              <img class="dice-big"
                  [src]="diceAsset(getPlayer(r.attackerId)?.attackDice, roleColorOf(getPlayer(r.attackerId)))"
                  alt="dice"/>
              <div class="dice-overlay" *ngIf="r.attackerRoll != null">{{ r.attackerRoll }}</div>
            </div>
            <ng-container *ngIf="modsForStat(getPlayer(r.attackerId), 'ATTACK') as atkMods">
              <div class="mods-row" *ngIf="atkMods.length">
                <span class="mod-chip" *ngFor="let m of atkMods">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weatherStatus)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(getPlayer(r.attackerId))" alt="action"/>
                  </div>
                  <span class="chip-val">{{ chipOf(m) }}</span>
                </span>
              </div>
            </ng-container>
          </ng-container>
          <ng-template #defenseSide>
            <!-- DEFENSEUR -->
            <div class="icon-bubble oval">
              <div class="icon-halo oval">
                <img class="icon-side" [src]="roleIcon(getRole(getPlayer(r.defenderId)),'armor')" alt="défense"/>
              </div>
            </div>
            <div class="dice-wrap">
              <img class="dice-big"
                  [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice, roleColorOf(getPlayer(r.defenderId)))"
                  alt="dice"/>
              <div class="dice-overlay" *ngIf="r.defenderRoll != null">{{ r.defenderRoll }}</div>
            </div>
            <ng-container *ngIf="modsForStat(getPlayer(r.defenderId), 'DEFENSE') as defMods">
              <div class="mods-row" *ngIf="defMods.length">
                <span class="mod-chip" *ngFor="let m of defMods">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weatherStatus)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(getPlayer(r.defenderId))" alt="action"/>
                  </div>
                  <span class="chip-val">{{ chipOf(m) }}</span>
                </span>
              </div>
            </ng-container>
          </ng-template>
        </ng-container>
      </div>

      <div class="footer">
        <button (click)="rollNow()">Jeter le dé</button>
      </div>
    </div>
  </div>

  <!-- === MODALE SPECTATEUR === -->
  <div *ngIf="showSpectatorModal && currentCombat as r" class="modal-backdrop">
    <div class="modal location-modal spectate" [style.backgroundImage]="setImageBackgroundCss('location')">
      <h3 style="margin-top:0" class="bg-badge">
        {{ modalTitle(r) }}
      </h3>

      <div class="content spect">
        <!-- Côté attaquant -->
        <div class="side">
          <!-- Colonne mods à GAUCHE -->
          <ng-container *ngIf="modsForStat(getPlayer(r.attackerId), 'ATTACK') as atkMods">
            <div class="mods-col left" *ngIf="atkMods.length">
              <div class="mods-row">
                <span class="mod-chip" *ngFor="let m of atkMods">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weatherStatus)" alt="icône météo" />
                  </div>

                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(getPlayer(r.attackerId))" alt="action"/>
                  </div>
                  <span class="chip-val">{{ chipOf(m) }}</span>
                </span>
              </div>
            </div>
          </ng-container>

          <div class="icon-halo oval" [ngClass]="(getPlayer(r.attackerId)?.role==='VAMPIRE') ? 'round' : 'oval'">
            <img class="icon-side" [src]="roleIcon(getRole(getPlayer(r.attackerId)),'sword')" alt="attaque"/>
          </div>
          <div class="dice-wrap">
            <img class="dice"
                [src]="diceAsset(getPlayer(r.attackerId)?.attackDice, roleColorOf(getPlayer(r.attackerId)))"
                alt="dice"/>
            <div class="dice-overlay" *ngIf="r.attackerRoll != null">{{ r.attackerRoll }}</div>
          </div>
        </div>

        <!-- Côté défenseur -->
        <div class="side">
          <!-- Colonne mods à DROITE -->
          <ng-container *ngIf="modsForStat(getPlayer(r.defenderId), 'DEFENSE') as defMods">
            <div class="mods-col right" *ngIf="defMods.length">
              <div class="mods-row">
                <span class="mod-chip" *ngFor="let m of defMods">
                  <div class="mods-badge-weather" *ngIf="m.source?.startsWith('WEATHER:')">
                    <img class="weather-ico" [src]="weatherIconSrc(game?.weatherStatus)" alt="icône météo" />
                  </div>
                  
                  <div class="mods-badge-potion" *ngIf="m.source?.startsWith('POTION:')">
                    <img class="mod-ico" src="/assets/icons/potion-icon.png" alt="potion"/>
                  </div>

                  <div class="mods-badge-action" *ngIf="m.source?.startsWith('ACTION:')">
                    <img class="mod-ico" [src]="actionIconSrc(getPlayer(r.defenderId))" alt="action"/>
                  </div>
                  <span class="chip-val">{{ chipOf(m) }}</span>
                </span>
              </div>
            </div>
          </ng-container>

          <div class="icon-halo oval">
            <img class="icon-side" [src]="roleIcon(getRole(getPlayer(r.defenderId)),'armor')" alt="défense"/>
          </div>
          <div class="dice-wrap">
            <img class="dice"
                [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice, roleColorOf(getPlayer(r.defenderId)))"
                alt="dice"/>
            <div class="dice-overlay" *ngIf="r.defenderRoll != null">{{ r.defenderRoll }}</div>
          </div>
        </div>
      </div>

      <div class="breakdown bg-badge" *ngIf="currentCombat?.breakdownLines as lines">
        <div *ngFor="let line of lines">{{ line }}</div>
      </div>
      <div class="result bg-badge" *ngIf="getCombatResultText() as txt">{{ txt }}</div>
      <div class="footer bg-badge" *ngIf="!getCombatResultText()">Les adversaires s’affrontent…</div>
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

  .hand-body{
    display:grid;
    grid-template-columns: 1fr 1fr;
    gap: 1rem;
    align-items: start;
  }

  .hand-col{ min-width: 0; }

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
  .content.spect{
    position: relative;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 1rem;
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

  /* Les chips en colonne */
  .mods-col .mod-chip{
    display: inline-block;
    white-space: nowrap;
  }
  .breakdown { text-align: center; }
  .result{ margin-top: .75rem; font-weight: 600; text-align: center; }
  .modal .footer{ margin-top: .75rem; text-align: center; color: #eeeeeeff; }

  /* MODALE ACTION */
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
  .modal.location-modal .bg-badge{
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
  .modal.location-modal .content.action{
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 1rem;
    flex-wrap: wrap;
    transform: translateY(-50px);
  }

  /* Les chips de la modale action passent sur une nouvelle ligne et ne perturbent pas l’alignement */
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
    
  .modal.location-modal .icon-halo{
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

  .modal.location-modal .icon-halo::before{
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

  /* L'image passe au-dessus du halo */
  .modal.location-modal .icon-halo .icon-side{
    position: relative;
    z-index: 1;
    width: 120px;
    height: auto;
    opacity: .95;
  }

  .modal.location-modal .icon-halo.oval::before{
    border-radius: 50% / 35%;
  }
  .modal.location-modal .icon-halo.round::before{
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

  .mods-badge-weather, .mods-badge-potion, .mods-badge-action{
    width: 28px; height: 28px;
    background: #ddddddff;
    border-radius: 999px;
    opacity: .92;
  }

  /* Icône de potion et d'action à l'intérieur du badge */
  .mods-badge-potion .mod-ico,
  .mods-badge-action .mod-ico{
    width: 28px;
    height: 28px;
    display: block;
    object-fit: contain;
  }

  .weather-footer{color: white; font-weight: bold;}
  .wheel{ width: 400px; height:auto; opacity:.95; }
  .btn-primary{ padding:.5rem 1rem; font-weight:600; }
`]
})
export class GameComponent {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  manyModsForDebug(p: Player, times = 3) {
  const base = this.modsForDisplay(p);
  return Array(times).fill(base).flat();
}

  gameId = '';
  game?: Game;
  errorMsg = '';
  remainingPrePhaseSeconds = 0;
  hasSkipped = false;

  meId = sessionStorage.getItem('userId') || '';
  username = sessionStorage.getItem('username') || '';

  selectedLocation: string | null = null;
  private timer?: any;

  // --- Météo: états/temporisations contrôlées côté client ---
  weatherBgActive = false;                // quand true => on affiche le fond météo
  private weatherTimer?: any;             // timer pour le délai AVANT fond
  private weatherPostTimer?: any;         // timer après affichage du fond
  private lastWeatherRollSeen: number | null = null;

  // Hold pour garder la modale visible même si phase != PHASE0
  private weatherModalHold = false;

  // règle tes durées ici (mets très grand pour debug)
  private static readonly WEATHER_PRE_BG_MS  = 4000;   // délai roue -> fond météo
  private static readonly WEATHER_POST_BG_MS = 5000;      // durée d'affichage garantie après fond

  // roue = 12 pales => 30° par pale. Offset pour aligner la pale.
  private static readonly WHEEL_DEG_PER_FACE = 30;
  private static readonly WHEEL_BASE_OFFSET = 15;
  private static readonly WEATHER_ICON_RADIUS = 120;

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
  modsForStat(p: Player | undefined, stat: 'ATTACK'|'DEFENSE'): StatMod[] {
    if (!p || !this.game?.raidMods) return [];
    const list = this.game.raidMods[p.id] || [];
    const weatherActive = this.isWeatherActive();
    return list.filter(m =>
      m.stat === stat &&
      (weatherActive || !(m.source?.startsWith('WEATHER:')))
    );
  }

  // Mods à AFFICHER (tous stats confondues)
  modsForDisplay(p?: Player): StatMod[] {
    if (!p || !this.game?.raidMods) return [];
    const list = this.game.raidMods[p.id] || [];
    const weatherActive = this.isWeatherActive();
    return list.filter(m =>
      weatherActive || !(m.source?.startsWith('WEATHER:'))
    );
  }

  private isWeatherActive(): boolean {
    const g = this.game;
    return !!g && g.weatherRoll != null && !!g.weatherStatus;
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

  get currentCombat() {
    return this.game?.currentCombat || null;
  }
  get waitingForMyRoll(): 'ATTACK'|'DEFENSE'|null {
    const r = this.currentCombat; if (!r) return null;
    if (r.attackerId === this.meId && (r.attackerRoll == null)) return 'ATTACK';
    if (r.defenderId === this.meId && (r.defenderRoll == null)) return 'DEFENSE';
    return null;
  }
  get showActionModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.waitingForMyRoll && !!this.currentCombat;
  }
  get showSpectatorModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.currentCombat && !this.waitingForMyRoll;
  }
  getPlayer(id: string): Player | undefined {
    return this.game?.players.find(p => p.id === id);
  }
  roleColorOf(p?: Player): 'red'|'blue' {
    return (p?.role === 'VAMPIRE') ? 'red' : 'blue';
  }
  getRole(p?: Player): 'VAMPIRE'|'HUNTER'|undefined {
    return p?.role ;
  }
  diceAsset(dice: string | undefined, color: 'red'|'blue'): string {
    const d = (dice || 'D6').toLowerCase();
    return `/assets/dices/${d}-${color}.png`;
  }
  roleIcon(role: 'VAMPIRE' | 'HUNTER' | undefined, name: 'sword'|'armor'): string {
    console.log("r:", role, "n:", name)
    return `/assets/icons/${role}-${name}.png`;
  }

  private totalModForDisplay(pId: string, stat: 'ATTACK'|'DEFENSE'): number {
    const list = this.game?.raidMods?.[pId] || [];
    return list.reduce((sum, m) => sum + (m.stat === stat ? m.amount : 0), 0);
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

  rollNow(){
    if (!this.game) return;
    this.api.rollDice(this.game.id).subscribe({
      next: g => this.game = g,
      error: e => this.showError(e)
    });
  }

  // --- Assets helpers (cœurs + cartes équipement) ---
  heartIconFor(p?: Player): string {
    const role = (p?.role || 'HUNTER').toUpperCase();
    return `/assets/icons/${role}-hearth.png`;
  }

  trackById(_i: number, p: Player) { return p.id; }

  // --- HP helpers (pour une jauge plus tard) ---
  maxHpOf(p: Player): number {
    if (p.role === 'VAMPIRE') {
      const hunters = (this.game?.players ?? []).filter(x => x.role === 'HUNTER').length;
      return 20 + hunters * 10;
    }
    return 20;
  }
  hpPercent(p: Player): number {
    const max = this.maxHpOf(p);
    const cur = Math.max(0, Math.min(p.hp ?? 0, max));
    return Math.round((cur / max) * 100);
  }

  // Helpers (lecture via players[])
  get me(): Player | undefined {
    return this.game?.players.find(p => p.id === this.meId);
  }
  get isVampire(): boolean {
    return this.me?.role === 'VAMPIRE';
  }
  get hasVampire(): boolean {
    return !!this.game && this.game.players.some(p => p.role === 'VAMPIRE');
  }
  get vampirePlayer(): Player {
    return this.game!.players.find(p => p.role === 'VAMPIRE')!;
  }
  get hunterPlayers(): Player[] {
    return (this.game?.players ?? []).filter(p => p.role === 'HUNTER' && p.id !== this.meId);
  }

  get canPlay(): boolean {
    if (!this.game || !this.me || !this.selectedLocation) return false;
    const phase = this.game.phase;
    if (this.me.role === 'HUNTER') return phase === 'PHASE1';
    if (this.me.role === 'VAMPIRE') return phase === 'PHASE2';
    return false;
  }

  usernameOf(id: string): string {
    if (!this.game) return id;
    const p = this.game.players.find(x => x.id === id);
    if (p?.username) return p.username;
    if (id === this.meId && this.username) return this.username;
    return id;
    // (plus tard, on pourra faire une vraie map id->username côté back si besoin)
  }

  isCurrent(_p: Player){ return false; } // on branchera plus tard

  labelLieu(c: string){
    switch(c){
      case 'forest': return 'Forêt';
      case 'quarry': return 'Carrière';
      case 'lake': return 'Lac';
      case 'manor': return 'Manoir';
      default: return c;
    }
  }

  ngOnInit() {
    this.gameId = this.route.snapshot.paramMap.get('id') || '';
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 2000);
  }
  ngOnDestroy(){ 
    if(this.timer) clearInterval(this.timer);
    if(this.weatherTimer) clearTimeout(this.weatherTimer);
    if(this.weatherPostTimer) clearTimeout(this.weatherPostTimer);
  }

  refresh(){
    if(!this.gameId) return;
    this.api.getGame(this.gameId).subscribe({
      next: g => {
        this.game = g;
        this.handleWeatherReveal(g);
        this.bumpHistoryScroll();
        // PREPHASE3: calcule le compte à rebours
        if (this.game?.phase === 'PREPHASE3' && this.game.prePhaseDeadlineMillis) {
          const msLeft = this.game.prePhaseDeadlineMillis - Date.now();
          this.remainingPrePhaseSeconds = Math.max(0, Math.ceil(msLeft / 1000));
        } else {
          this.remainingPrePhaseSeconds = 0;
          this.hasSkipped = false; // reset si on change de phase
        }
      },
      error: e => this.showError(e)
    });
  }

  selectLocation(c: string){ this.selectedLocation = c; }

  playSelected(){
    if (!this.game || !this.selectedLocation) return;
    this.api.selectLocation(this.game.id, this.selectedLocation).subscribe({
      next: g => { this.game = g; this.selectedLocation = null; },
      error: e => this.showError(e)
    });
  }

  skipNow(){
    if (!this.game) return;
    this.hasSkipped = true;
    this.api.skipPrePhase3(this.game.id).subscribe({
      next: g => { this.game = g; /* eventuellement on recalcule timer */ },
      error: e => this.showError(e)
    });
  }

  chipOf(m: StatMod): string {
    // Affiche au format "ATK+1" / "DEF-2"
    const short = m.stat === 'ATTACK' ? 'ATK' : 'DEF';
    const sign = m.amount > 0 ? `+${m.amount}` : `${m.amount}`;
    return `${short}${sign}`;
  }

  back(){ this.router.navigate(['/lobby']); }

  private showError(e:any){
    try{ this.errorMsg = e?.error?.message || 'Erreur'; } catch { this.errorMsg='Erreur'; }
    setTimeout(()=>this.errorMsg='', 4000);
  }

  // Meteo
  isMeVampire(): boolean {
    return this.me?.role === 'VAMPIRE';
  }

  canShowWeatherModal(): boolean {
    const g = this.game;
    if (!g) return false;
    const notBefore = g.weatherModalNotBeforeMillis || 0;

    // Visible si: (a) on est encore en PHASE0 ET après notBefore
    //          OU (b) on a un hold client actif (pré ou post fond)
    const showByPhase = (g.phase === 'PHASE0' && Date.now() >= notBefore);
    return showByPhase || this.weatherModalHold;
  }

  rollWeather(){
    if (!this.game) return;
    this.api.rollWeather(this.game.id).subscribe({
      next: g => {this.game = g; this.handleWeatherReveal(g);},
      error: e => this.showError(e)
    });
  }

  // Image de fond une fois la météo tirée
  setImageBackgroundCss(modal:'weather'|'location'): string | null {

    if (modal === 'weather') {
      const ws = this.game?.weatherStatus;
      if (!ws || this.game?.weatherRoll == null) return null;
      console.log("check 1: ", `url('/assets/weather/bg-${ws.toLowerCase()}.png')`)
      return `url('/assets/weather/bg-${ws.toLowerCase()}.png')`;
    }
    if (modal === 'location') {

      const loc = this.game?.currentCombat?.location?.toLowerCase();
                console.log("loc", `url('/assets/locations/${loc}.png')`)
      return loc ? `url('/assets/locations/${loc}.png')` : 'none';
    }
    return 'none';
  }

  // on reste sur la roue tant qu'on n'a pas activé le bg météo
  isWeatherPreReveal(): boolean {
    const hasRoll = this.game?.weatherRoll != null;
    return !hasRoll || !this.weatherBgActive;
  }

  // calculer la transform pour poser l'icône sur la pale correspondante
  weatherIconTransform(): string {
    const roll = Math.max(1, Math.min(12, this.game?.weatherRoll ?? 1));
    const angle = (roll - 1) * GameComponent.WHEEL_DEG_PER_FACE + GameComponent.WHEEL_BASE_OFFSET;
    const r = GameComponent.WEATHER_ICON_RADIUS;
    // centre ➜ rotation vers la pale ➜ translation radiale ➜ remise à l'horizontale
    return `translate(-50%, -50%) rotate(${angle}deg) translate(0, -${r}px) rotate(${-angle}deg)`;
  }

  // chemin de l'icône météo
  weatherIconSrc(ws?: string | null): string {
    if (!ws) return '';
    return `/assets/weather/icon-${ws.toLowerCase()}.png`;
  }

  // pilote le délai: quand on "voit" un nouveau résultat, attente 4s avant d'activer le bg
  private handleWeatherReveal(g: Game){
    const roll = g.weatherRoll ?? null;

    // reset si pas de tirage
    if (roll == null){
      this.lastWeatherRollSeen = null;
      this.weatherBgActive = false;
      this.weatherModalHold = false;
      if (this.weatherTimer)     { clearTimeout(this.weatherTimer);     this.weatherTimer = undefined; }
      if (this.weatherPostTimer) { clearTimeout(this.weatherPostTimer); this.weatherPostTimer = undefined; }
      return;
    }

    // nouveau tirage détecté
    if (this.lastWeatherRollSeen !== roll){
      this.lastWeatherRollSeen = roll;

      // on force l'affichage de la modale (même si PHASE0 se termine)
      this.weatherModalHold = true;
      this.weatherBgActive  = false;

      if (this.weatherTimer) clearTimeout(this.weatherTimer);
      if (this.weatherPostTimer) clearTimeout(this.weatherPostTimer);

      // délai avant d'activer le fond météo
      this.weatherTimer = setTimeout(() => {
        this.weatherBgActive = true;

        // (facultatif) petit hold après fond pour être sûr qu'on le voit
        if (GameComponent.WEATHER_POST_BG_MS > 0){
          this.weatherPostTimer = setTimeout(() => {
            this.weatherModalHold = false;
          }, GameComponent.WEATHER_POST_BG_MS);
        } else {
          // sinon, on relâche tout de suite le hold:
          this.weatherModalHold = false;
        }
      }, GameComponent.WEATHER_PRE_BG_MS);
    }
  }

  //actions & potions
  // afficher mes potions (IDs)
  myPotions(): string[] {
    const g = this.game;
    if (!g) return [];
    const map = (g as any).potionsByPlayer || {};
    return map[this.meId] || [];
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

  // Suis-je posé sur un lieu où un combat va avoir lieu ?
  private imInUpcomingCombat(): boolean {
    const g = this.game; if (!g) return false;
    const vamp = g.players.find(p => p.role === 'VAMPIRE'); if (!vamp) return false;

    // Lieux révélés en PREPHASE3 où vamp + ≥1 chasseur sont ensemble
    const combatLocs = new Set<string>();
    for (const cb of (g.center || [])) {
      if (!cb.faceUp) continue;
      if (cb.playerId === vamp.id) {
        const loc = cb.card;
        const hunterThere = (g.center || []).some(c2 =>
          c2.card === loc &&
          g.players.find(p => p.id === c2.playerId)?.role === 'HUNTER'
        );
        if (hunterThere) combatLocs.add(loc);
      }
    }

    // Moi, suis-je sur un de ces lieux ?
    return (g.center || []).some(cb => cb.playerId === this.meId && combatLocs.has(cb.card));
  }

  potionLabelFr(id: string): string {
    switch (id) {
      case 'FORCE': return 'Potion de force';
      case 'ENDURANCE': return 'Potion d’endurance';
      case 'VIE': return 'Potion de vie';
      default: return id;
    }
  }

  usePotion(id: string){
    if (!this.game) return;
    this.api.usePotion(this.game.id, id).subscribe({
      next: g => this.game = g,
      error: e => this.showError(e)
    });
  }

  actionIconSrc(p?: Player): string {
    if (!p) return '/assets/icons/action-hunter-icon.png';
    return p.role === 'VAMPIRE'
      ? '/assets/icons/action-vampire-icon.png'
      : '/assets/icons/action-hunter-icon.png';
  }

  onPotionClick(pot: string){
    if (!this.canUsePotionNow(pot)) return;
    this.usePotion(pot);
  }

}
