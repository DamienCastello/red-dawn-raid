import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService, LobbyGame } from './services/api.service';

import { LiveService, GameEvent } from './services/live.service';
import { AssetPreloaderService } from './services/asset-preloader.service';

@Component({
  standalone: true,
  selector: 'app-lobby',
  imports: [CommonModule, FormsModule],
  template: `
  <div class="page">
    <header class="hero">

      <!-- Titre complet (>= 421px) -->
      <h1 class="rdr-title title-full" aria-label="Red Dawn Raid">
        <span class="fire">R</span><span class="burn">e</span><span class="burn">d</span>
        <span class="gap"></span>
        <span class="fire">D</span><span class="burn">a</span><span class="burn">w</span><span class="burn">n</span>
        <span class="gap"></span>
        <span class="fire">R</span><span class="burn">a</span><span class="burn">i</span><span class="fire">d</span>
      </h1>

      <!-- Titre compact (<= 420px) -->
      <h1 class="rdr-title title-short" aria-label="RDR">
        <span class="fire">R</span><span class="fire">D</span><span class="fire">R</span>
      </h1>

      <div class="page-name">Lobby</div>
    </header>

    <main class="card card-wide">
      <button class="btn btn-ghost logout" (click)="logout()">Déconnexion</button>

      <div *ngIf="errorMsg" class="alert">
        {{ errorMsg }}
      </div>

      <div class="row top-actions">
        <button class="btn btn-primary" (click)="create()" [disabled]="cannotCreateGame">
          Créer une partie
        </button>
        <button class="btn" (click)="list()">Lister</button>
      </div>

      <div class="section" *ngIf="games.length">
        <h3 class="h3">Parties</h3>
        <div class="games-scroll">
          <ul class="list">
            <li *ngFor="let g of games" class="list-item">
              <a class="link"
                href="#"
                (click)="$event.preventDefault(); pick(g)"
                [class.link-strong]="isInGame(g)">
                <span class="mono">{{ g.id }}</span> — {{ g.status }}
                <ng-container *ngIf="g.status !== 'ENDED'">
                  <span class="muted">({{ activePlayersCount(g) }} joueurs)</span>
                </ng-container>
              </a>
            </li>
          </ul>
        </div>
      </div>

      <div *ngIf="selected" class="section">
        <h3 class="h3">Partie sélectionnée</h3>
        <p class="line"><b class="mono">{{ selected.id }}</b> — {{ selected.status }}</p>

        <ng-container *ngIf="selected.status !== 'ENDED'">

          <!-- Cas 1 : je suis déjà dans CETTE partie -->
          <ng-container *ngIf="alreadyInSelected; else notInSelected">
            <p class="muted">Vous êtes déjà dans cette partie.</p>

            <div class="row action-row">
              <button class="btn btn-primary" (click)="goToGame()" [disabled]="!isSelectedActive">
                Reprendre la partie
              </button>

              <button *ngIf="alreadyInSelected && isSelectedCreated"
                      class="btn"
                      (click)="start()"
                      [disabled]="activePlayersCount(selected) < 2">
                Démarrer la partie
              </button>

              <button *ngIf="alreadyInSelected && isSelectedCreated"
                      class="btn"
                      (click)="leaveCreated()">
                Quitter la partie
              </button>
            </div>

            <div *ngIf="isSelectedCreated" class="section">
              <h4 class="h4">Joueurs</h4>
              <ul class="list">
                <li *ngFor="let p of lobbySelectedPlayers" class="list-item player-line">
                  <span>{{ p.username }}</span>
                  <span class="role-choice">
                    <ng-container *ngIf="canEditRole(p); else roleBadge">
                      <button class="btn btn-mini role-btn" [class.active]="!wantsVampire(p)"
                              (click)="setRole(p, 'HUNTER')">Chasseur</button>
                      <button class="btn btn-mini role-btn" [class.active]="wantsVampire(p)"
                              (click)="setRole(p, 'VAMPIRE')">Vampire</button>
                    </ng-container>
                    <ng-template #roleBadge>
                      <span class="role-badge">{{ wantsVampire(p) ? 'Vampire' : 'Chasseur' }}</span>
                    </ng-template>
                  </span>
                  <button *ngIf="p.bot" class="btn btn-ghost btn-mini" (click)="removeBot(p.id)">
                    Retirer
                  </button>
                </li>
              </ul>
              <small class="muted" style="display:block;">
                Le vampire est tiré au sort parmi ceux qui choisissent « Vampire »
                (aléatoire si personne ne le veut).
              </small>
              <div class="row" style="margin-top:.6rem;">
                <button class="btn"
                        (click)="addBot()"
                        [disabled]="activePlayersCount(selected) >= 7">
                  🤖 Ajouter un bot
                </button>
              </div>
            </div>

            <small *ngIf="isSelectedCreated" class="muted" style="display:block; margin-top:.5rem;">
              En attente du démarrage…
            </small>
          </ng-container>

          <!-- Cas 2 : je NE suis PAS dans la partie sélectionnée -->
          <ng-template #notInSelected>
            <ng-container *ngIf="inOtherGameSelected; else canJoinHere">
              <p class="warn">
                Vous avez déjà rejoint une autre partie ({{ myActiveGameId }}). Impossible de joindre celle-ci.
              </p>
              <button class="btn" disabled>Rejoindre</button>
            </ng-container>

            <ng-template #canJoinHere>
              <p>Vous rejoindrez en tant que <b>{{ currentUsername }}</b>.</p>
              <button class="btn btn-primary" (click)="join()" [disabled]="selected.status !== 'CREATED' || activePlayersCount(selected) >= 7">
                Rejoindre
              </button>
            </ng-template>
          </ng-template>

        </ng-container>
      </div>

      <div *ngIf="selected?.status === 'ENDED' && endedSnap" class="section ended">
        <p><b>Vainqueur :</b> {{ endedSnap.winnerSide }}</p>
        <p><b>Participants :</b> {{ endedSnap.players.length }}</p>

        <h4 class="h4">Joueurs</h4>
        <ul class="list">
          <li *ngFor="let p of endedSnap.players" class="list-item">
            {{ p.username }} — {{ p.role }} —
            <span class="muted" *ngIf="p.leftGame">a quitté</span>
            <span class="muted" *ngIf="!p.leftGame && p.hp <= 0">mort</span>
            <span class="muted" *ngIf="!p.leftGame && p.hp > 0">vivant</span>
            <span class="muted">(PV: {{ p.hp }})</span>
          </li>
        </ul>
      </div>
    </main>

    <!-- OVERLAY STARTING -->
    <div *ngIf="showStartingOverlay" class="overlay">
      <div class="overlay-card">
        <h2 style="margin:0 0 .5rem 0;">Chargement de la partie…</h2>

        <p style="margin:.25rem 0; opacity:.9;">
          Ressources sur ce client :
          <b>{{ localAssetsDone ? 'OK' : 'en cours…' }}</b>
        </p>

        <p style="margin:.25rem 0; opacity:.9;">
          Joueurs prêts (serveur) :
          <b>{{ readyStartCount }} / {{ readyStartTotal }}</b>
        </p>

        <div class="ready-grid" *ngIf="selected">
          <div class="ready-col">
            <div class="ready-title">✅ Prêts</div>
            <ul class="ready-list">
              <li *ngFor="let p of readyPlayers">
                {{ p.username || p.id }}
                <span *ngIf="p.id === myUserId" class="me-tag">(moi)</span>
              </li>
              <li *ngIf="readyPlayers.length === 0" class="muted">Personne</li>
            </ul>
          </div>

          <div class="ready-col">
            <div class="ready-title">⏳ En attente</div>
            <ul class="ready-list">
              <li *ngFor="let p of waitingPlayers">
                {{ p.username || p.id }}
                <span *ngIf="p.id === myUserId" class="me-tag">(moi)</span>
              </li>
              <li *ngIf="waitingPlayers.length === 0" class="muted">Personne</li>
            </ul>
          </div>
        </div>

        <div style="margin-top:.75rem; font-size:.95rem; opacity:.8;">
          La partie démarre automatiquement dès que tout le monde a fini de charger.
        </div>
      </div>
    </div>
  </div>
  `,
  styles: [`
    @import url('https://fonts.googleapis.com/css?family=Amethysta');
    @import url('https://fonts.googleapis.com/css?family=Caesar+Dressing');

    :host, .page { box-sizing: border-box; }
    *, *::before, *::after { box-sizing: border-box; }

    :host{
      display:block;
      min-height:100vh;
      color:#fff;

      background-color: rgba(255,255,255,.03);
      background-image: linear-gradient(to bottom, #111, #0c0c0c);
      background-attachment: fixed;
    }

    .page{
      min-height:100vh;
      color:#fff;
      padding: 3.25rem 1rem 3rem;
      display:flex;
      flex-direction:column;
      align-items:center;
      gap: 1.5rem;

      /* même fond que :host */
      background-color: rgba(255,255,255,.03);
      background-image: linear-gradient(to bottom, #111, #0c0c0c);
    }

    .hero{
      width:min(900px, 92vw);
      text-align:center;
      position: relative;
      z-index: 2;
    }

    .page-name{
      margin-top:.85rem;
      font-size: .95rem;
      letter-spacing: .22em;
      text-transform: uppercase;
      opacity:.85;
    }

    .card{
      width:min(720px, 92vw);
      border-radius: 14px;
      background: rgba(255,255,255,.02);
      border: 1px solid rgba(255,255,255,.10);
      padding: 1.1rem 1.15rem 1.15rem;
      box-shadow: 0 10px 30px rgba(0,0,0,.35);
      position:relative;
    }

    .card-wide{
      width:min(860px, 92vw);
    }

    .logout{
      position: fixed;
      right: 24px;
      top: 16px;
      z-index: 2;
    }

    .row{
      display:flex;
      gap:.6rem;
      align-items:center;
      flex-wrap:wrap;
    }

    .btn{
      padding:.62rem .8rem;
      border-radius: 10px;
      border: 1px solid rgba(255,255,255,.18);
      background: rgba(255,255,255,.06);
      color:#fff;
      cursor:pointer;
      transition: transform .05s ease, background .15s ease, border-color .15s ease;
      min-width: 0;
    }

    .btn:hover{
      background: rgba(255,255,255,.09);
      border-color: rgba(255,255,255,.26);
    }
    .btn:active{ transform: translateY(1px); }

    .btn:disabled{
      opacity:.55;
      cursor:not-allowed;
    }

    .btn-primary{
      background: rgba(255,255,255,.12);
      border-color: rgba(255,255,255,.26);
    }

    .btn-ghost{
      background: rgba(0,0,0,.25);
      border-color: rgba(255,255,255,.16);
    }

    .alert{
      margin:.75rem 0;
      padding:.65rem .75rem;
      border-radius: 12px;
      border: 1px solid rgba(255,180,180,.35);
      background: rgba(179,0,0,.12);
      color: #ffb4b4;
    }

    .section{ margin-top: 1.05rem; }

    .h3, .h4{
      margin:.2rem 0 .6rem;
      letter-spacing:.08em;
      text-transform: uppercase;
      font-weight:600;
      opacity:.9;
    }

    .line{ margin:.25rem 0 .75rem; }

    .muted{ opacity:.75; }
    .warn{ color:#ffd1a8; }

    .list{
      list-style:none;
      margin:0;
      padding:0;
      display:flex;
      flex-direction:column;
      gap:.35rem;
    }

    .list-item{
      padding:.5rem .6rem;
      border-radius: 12px;
      border: 1px solid rgba(255,255,255,.08);
      background: rgba(0,0,0,.18);
      overflow-wrap: anywhere;
    }

    /* Scroll uniquement sur la liste des parties */
    .games-scroll{
      max-height: 42vh;
      overflow: auto;
      padding-right: .25rem;
      -webkit-overflow-scrolling: touch;
    }

    .games-scroll .list{
      padding-right: .15rem;
    }

    @media (max-width: 560px){
      .games-scroll{ max-height: 36vh; }
    }

    .link{
      color:#fff;
      text-decoration:none;
      display:block;
    }
    .link:hover{ text-decoration: underline; }
    .link-strong{ font-weight:700; }

    .mono{
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
      letter-spacing: .02em;
    }

    .ended{
      border-color: rgba(255,255,255,.16);
      background: rgba(255,255,255,.03);
    }

    .overlay{
      position:fixed;
      inset:0;
      background:rgba(0,0,0,.65);
      display:flex;
      align-items:center;
      justify-content:center;
      z-index:9999;
    }

    .overlay-card{
      background:#111;
      color:#fff;
      padding:1rem 1.25rem;
      border-radius:12px;
      width:min(520px, 92vw);
      border: 1px solid rgba(255,255,255,.12);
      box-shadow: 0 10px 30px rgba(0,0,0,.45);
    }

    /* --- TITRE + flammes --- */
    .rdr-title{
      margin:0;
      padding:.7rem 1rem;
      border-radius:14px;
      background: transparent;

      font-family: 'Amethysta', serif;
      text-align:center;
      line-height: 1.2;
      text-transform: uppercase;
      letter-spacing: .22em;
      white-space:nowrap;
      display:inline-block;
    }

    .title-full{ font-size: 1.75rem; }
    .title-short{ font-size: 1.75rem; display:none; }

    .rdr-title span{
      font-family: 'Caesar Dressing', cursive;
      font-size: 2.3em;
      text-transform: lowercase;
      vertical-align: middle;
      letter-spacing: .12em;
      display:inline-block;
      margin: 0 .06em;
      color: #000;
    }

    .gap{
      width: .55em;
      margin: 0;
      letter-spacing: 0;
      color: transparent;
      text-shadow: none !important;
      animation: none !important;
    }

    .fire{ animation: fireAnim 1s ease-in-out infinite alternate; }
    .burn{ animation: fireAnim .65s ease-in-out infinite alternate; }

    @keyframes fireAnim{
      0% { text-shadow:
        0 0 20px #fefcc9,
        10px -10px 30px #feec85,
        -20px -20px 40px #ffae34,
        20px -40px 50px #ec760c,
        -20px -60px 60px #cd4606,
        0 -80px 70px #973716,
        10px -90px 80px #451b0e;
      }
      100% { text-shadow:
        0 0 20px #fefcc9,
        10px -10px 30px #fefcc9,
        -20px -20px 40px #feec85,
        22px -42px 60px #ffae34,
        -22px -58px 50px #ec760c,
        0 -82px 80px #cd4606,
        10px -90px 80px #973716;
      }
    }

    /* =========================
       RESPONSIVE
       ========================= */

    /* Vers 680px : on réduit le titre progressivement */
    @media (max-width: 680px){
      .title-full{ font-size: 1.55rem; }
      .rdr-title span{ font-size: 2.05em; }
      .rdr-title{ letter-spacing: .18em; }
      .page{ padding-top: 2.4rem; }
      .logout{ right: 16px; top: 12px; }
    }

    @media (max-width: 560px){
      .title-full{ font-size: 1.38rem; }
      .rdr-title span{ font-size: 1.9em; }
      .rdr-title{ letter-spacing: .15em; }

      .card{ padding: 1rem 1rem 1.05rem; }
      .logout{ position: static; width: 100%; margin-bottom: .75rem; }
    }

    @media (max-width: 480px){
      .title-full{ font-size: 1.24rem; }
      .rdr-title span{ font-size: 1.75em; }
      .rdr-title{ letter-spacing: .12em; }
      .page{ padding: 2.1rem .75rem 2.4rem; }
    }

    /* <= 420px : afficher RDR */
    @media (max-width: 420px){
      .title-full{ display:none; }
      .title-short{ display:inline-block; }

      .title-short{ font-size: 1.55rem; }
      .title-short span{ font-size: 2.2em; }
      .rdr-title{ letter-spacing: .16em; }

      .page-name{ letter-spacing: .18em; }
    }

    /* Action buttons : si c'est très étroit, forcer une "pile" */
    @media (max-width: 360px){
      .top-actions{
        flex-direction: column;
        align-items: stretch;
      }
      .top-actions .btn{
        width: 100%;
      }
      .action-row{
        flex-direction: column;
        align-items: stretch;
      }
      .action-row .btn{
        width: 100%;
      }
    }

    /* Ultra petit (genre 200px) : on garde tout empilable + safe */
    @media (max-width: 240px){
      .page{ padding-left: .5rem; padding-right: .5rem; }
      .btn{ width: 100%; }
    }

    .ready-grid{
      margin-top: .75rem;
      display: flex;
      gap: .9rem;
      align-items: flex-start;
    }

    .ready-col{
      flex: 1;
      border: 1px solid rgba(255,255,255,.10);
      background: rgba(0,0,0,.18);
      border-radius: 12px;
      padding: .6rem .7rem;
    }

    .ready-title{
      font-weight: 700;
      letter-spacing: .06em;
      text-transform: uppercase;
      font-size: .85rem;
      opacity: .9;
      margin-bottom: .35rem;
    }

    .ready-list{
      margin: 0;
      padding-left: 1rem;
      font-size: .95rem;
    }

    .me-tag{
      opacity: .75;
      margin-left: .35rem;
      font-size: .9em;
    }

    .player-line{
      display:flex;
      justify-content:space-between;
      align-items:center;
      gap:.5rem;
    }

    .btn-mini{
      padding:.25rem .55rem;
      font-size:.85rem;
    }

    /* Choix du rôle (lobby) */
    .role-choice{ display:flex; align-items:center; gap:.3rem; margin-left:auto; }
    .role-btn{ opacity:.55; }
    .role-btn.active{
      opacity:1;
      border-color:#b23;
      box-shadow:0 0 0 1px #b23 inset;
    }
    .role-badge{
      font-size:.8rem; opacity:.7; padding:.1rem .4rem;
      border:1px solid #333; border-radius:.4rem;
    }

    @media (max-width: 520px){
      .ready-grid{ flex-direction: column; }
      .player-line{ flex-wrap:wrap; }
    }
  `]
})
export class LobbyComponent {
  private api = inject(ApiService);
  private router = inject(Router);
  private live = inject(LiveService);

  games: LobbyGame[] = [];
  selected?: LobbyGame;
  username = '';
  errorMsg = '';

  private lastSelectedStatus: string | undefined;

  private assets = inject(AssetPreloaderService);
  private unsubscribeLobby?: () => void;
  private unsubscribeSelectedGame?: () => void;

  private endedCleanupDone = false;

  private cleanupEndedGames(gs: LobbyGame[]) {
    if (this.endedCleanupDone) return;
    this.endedCleanupDone = true;

    const mineEnded = gs.filter(g =>
      g.status === 'ENDED' &&
      (g.players || []).some((p: any) => p.id === this.myUserId && !p.leftGame)
    );

    if (!mineEnded.length) return;

    let pending = mineEnded.length;

    for (const g of mineEnded) {
      this.api.leaveGame(g.id).subscribe({
        next: () => {
          pending--;
          if (pending <= 0) {
            const stored = sessionStorage.getItem('gameId');
            if (stored && mineEnded.some(x => x.id === stored)) {
              this.clearCurrentGameStorage();
            }
            this.list();
          }
        },
        error: () => {
          pending--;
          if (pending <= 0) {
            this.list();
          }
        }
      });
    }
  }

  private presenceTimer: any = null;
  private presenceGameId: string | null = null;

  private startPresence(gameId: string) {
    if (this.presenceTimer && this.presenceGameId === gameId) return;
    this.stopPresence();
    this.presenceGameId = gameId;

    this.api.presence(gameId).subscribe({ error: () => { } });

    this.presenceTimer = setInterval(() => {
      this.api.presence(gameId).subscribe({ error: () => { } });
    }, 15_000);
  }

  private stopPresence() {
    if (this.presenceTimer) clearInterval(this.presenceTimer);
    this.presenceTimer = null;
    this.presenceGameId = null;
  }

  // --- Boot STARTING (barrière dans le lobby) ---
  localAssetsDone = false;
  private bootReadySentForGameId: string | null = null;

  get isSelectedStarting(): boolean { return this.selected?.status === 'STARTING'; }

  get readyStartCount(): number {
    return (this.selected?.readyForStart?.length ?? 0);
  }
  get readyStartTotal(): number {
    return this.activePlayersCount(this.selected);
  }

  get showStartingOverlay(): boolean {
    return !!this.selected && this.isSelectedStarting && this.alreadyInSelected;
  }

  // "inFlight" = appel en cours (évite spam)
  private bootReadyInFlightForGameId: string | null = null;

  // retry simple en cas de réseau flaky
  private bootReadyRetryTimer: any = null;

  private isMeReadyForStart(g?: LobbyGame): boolean {
    const rf: any[] = (g as any)?.readyForStart ?? [];
    return rf.some(x => (typeof x === 'string' ? x : x?.id) === this.myUserId);
  }

  private ensureBootReadyIfNeeded(gameId: string) {
    // déjà confirmé OK
    if (this.bootReadySentForGameId === gameId) return;
    // appel déjà en cours
    if (this.bootReadyInFlightForGameId === gameId) return;

    // On n’envoie que si on est dans la bonne game
    const iAmIn = this.games.some(g => g.id === gameId && this.isInGame(g));
    if (!iAmIn) return;

    this.assets.waitDone()
      .catch(() => { })
      .finally(() => {
        this.localAssetsDone = true;

        // Si le serveur dit déjà que je suis prêt => on verrouille "sent"
        if (this.isMeReadyForStart(this.selected)) {
          this.bootReadySentForGameId = gameId;
          return;
        }

        this.bootReadyInFlightForGameId = gameId;

        this.api.bootReady(gameId).subscribe({
          next: () => {
            this.bootReadyInFlightForGameId = null;
            this.bootReadySentForGameId = gameId; // seulement après succès
          },
          error: (e) => {
            console.warn('bootReady failed', e);
            this.bootReadyInFlightForGameId = null;

            // retry (utile quand ça timeout chez un pote)
            if (this.bootReadyRetryTimer) clearTimeout(this.bootReadyRetryTimer);
            this.bootReadyRetryTimer = setTimeout(() => {
              this.ensureBootReadyIfNeeded(gameId);
            }, 2000);
          }
        });
      });
  }

  ngOnInit() {
    this.assets.start();
    this.assets.waitDone().then(() => this.localAssetsDone = true).catch(() => this.localAssetsDone = true);
    this.bootReadySentForGameId = null;

    this.list();
    this.unsubscribeLobby = this.live.subscribeLobby((e) => this.onLobbyEvent(e));
  }

  ngOnDestroy() {
    this.stopPresence();
    this.unsubscribeLobby?.();
    this.unsubscribeSelectedGame?.();
    if (this.bootReadyRetryTimer) clearTimeout(this.bootReadyRetryTimer);
  }

  private showError(e: any) {
    try { this.errorMsg = e?.error?.message || 'Erreur'; } catch { this.errorMsg = 'Erreur'; }
    setTimeout(() => this.errorMsg = '', 4000);
  }

  // expose l'id pour le template
  get myUserId(): string {
    return sessionStorage.getItem('userId') ?? '';
  }

  // joueurs actifs (pas leftGame)
  private get activePlayers(): any[] {
    const ps: any[] = (this.selected as any)?.players ?? [];
    return ps.filter(p => !p.leftGame);
  }

  // normalise readyForStart -> tableau d'IDs (string)
  private get readyIds(): string[] {
    const rf: any[] = (this.selected as any)?.readyForStart ?? [];
    return rf
      .map(x => (typeof x === 'string' ? x : x?.id))
      .filter(Boolean);
  }

  get readyPlayers(): any[] {
    const ids = new Set(this.readyIds);
    return this.activePlayers.filter(p => ids.has(p.id));
  }

  get waitingPlayers(): any[] {
    const ids = new Set(this.readyIds);
    return this.activePlayers.filter(p => !ids.has(p.id));
  }

  endedSnap: any | null = null;

  onSelect(g: LobbyGame) {
    this.selected = g;

    this.bootReadySentForGameId = null;
    this.localAssetsDone = false;

    this.assets.waitDone()
      .then(() => this.localAssetsDone = true)
      .catch(() => this.localAssetsDone = true);

    this.lastSelectedStatus = g.status;

    this.endedSnap = null;
    if (g.status === 'ENDED') {
      this.api.getEndedSummary(g.id).subscribe({
        next: snap => this.endedSnap = snap,
        error: e => this.showError(e)
      });
    }

    this.unsubscribeSelectedGame?.();
    this.unsubscribeSelectedGame = this.live.subscribeGame(g.id, (ev) => {
      if (ev.type === 'PHASE_CHANGED') {
        this.lastSelectedStatus = 'ACTIVE';
        if (this.selected?.id === g.id) {
          this.selected = { ...this.selected, status: 'ACTIVE' } as any;
        }
      }
    });

    if (g.status === 'STARTING' && this.alreadyInSelected) {
      this.ensureBootReadyIfNeeded(g.id);
    }
  }

  private onLobbyEvent(e: GameEvent) {
    if (e.type === 'GAME_CREATED') {
      const g = this.asListItem(e);
      this.upsertInList(g);
      return;
    }

    if (e.type === 'LOBBY_UPDATED') {
      const g = this.asListItem(e);
      this.upsertInList(g);

      const iAmIn = (g.players || []).some((p: { id: string; leftGame?: boolean }) =>
        p.id === this.myUserId && !p.leftGame
      );

      if (iAmIn && (g.status === 'CREATED' || g.status === 'STARTING')) {
        this.startPresence(g.id);
      } else if (this.presenceGameId === g.id) {
        this.stopPresence();
      }

      if (g.status !== 'STARTING' && this.bootReadySentForGameId === g.id) {
        this.bootReadySentForGameId = null;
      }

      if (g.status === 'STARTING' && iAmIn) {
        if (this.selected?.id === g.id) {
          this.selected = { ...this.selected, ...g } as any;
        }
        this.ensureBootReadyIfNeeded(g.id);
      }

      if (g.status === 'ACTIVE' && iAmIn) {
        this.stopPresence();
        this.router.navigate(['/game', g.id]);
        return;
      }

      if (this.selected?.id === g.id) {
        this.selected = {
          ...this.selected,
          ...g,
          players: (g.players?.length ? g.players : (this.selected?.players ?? [])),
          readyForStart: (g.readyForStart?.length ? g.readyForStart : ((this.selected as any).readyForStart ?? [])),
        } as any;

        this.lastSelectedStatus = g.status;
      }
    }

    if (e.type === 'GAME_DELETED') {
      const gid = (e as any)?.payload?.gameId as string;

      this.games = this.games.filter(x => x.id !== gid);

      if (this.selected?.id === gid) {
        this.selected = undefined;
        this.endedSnap = null;
        this.unsubscribeSelectedGame?.();
        this.unsubscribeSelectedGame = undefined;
      }

      if (sessionStorage.getItem('gameId') === gid) {
        sessionStorage.removeItem('gameId');
        sessionStorage.removeItem('playerId');
      }
      return;
    }
  }

  activePlayersCount(g?: LobbyGame): number {
    if (!g?.players?.length) return 0;
    return g.players.reduce((n, p) => n + (p.leftGame ? 0 : 1), 0);
  }

  private asListItem(e: any) {
    const id = e?.payload?.gameId as string;
    const prev = this.games.find(x => x.id === id);

    const players =
      Array.isArray(e?.payload?.players) ? e.payload.players :
        (prev?.players ?? []);

    const readyForStart =
      Array.isArray(e?.payload?.readyForStart) ? e.payload.readyForStart :
        ((prev as any)?.readyForStart ?? []);

    return {
      id,
      status: e?.payload?.status,
      players,
      readyForStart
    } as any;
  }

  private upsertInList(item: any) {
    const i = this.games.findIndex(x => x.id === item.id);
    if (i >= 0) this.games[i] = { ...this.games[i], ...item };
    else this.games.unshift(item);
    this.games = [...this.games];
  }

  isInGame(g?: LobbyGame): boolean {
    if (!g) return false;
    if (g.status === 'ENDED') return false;
    return (g.players || []).some(p => p.id === this.myUserId && !p.leftGame);
  }

  list() {
    this.api.listGames().subscribe({
      next: gs => {
        this.games = gs;

        this.cleanupEndedGames(gs);

        this.syncStorageWithServer();

        if (!this.selected) {
          const mine = gs.find(g => this.isInGame(g));
          if (mine) {
            this.onSelect(mine);
            if (mine.status === 'STARTING') {
              this.selected = mine;
              this.ensureBootReadyIfNeeded(mine.id);
            }
            sessionStorage.setItem('gameId', mine.id);
            sessionStorage.setItem('playerId', this.myUserId);
          }
        }
      },
      error: e => this.showError(e)
    });
  }

  pick(g: LobbyGame) {
    this.onSelect(g);
  }

  create() {
    if (this.cannotCreateGame) {
      this.showError({ error: { message: "Vous êtes déjà dans une partie. Quittez-la avant d'en créer une autre." } });
      return;
    }

    this.api.createGame().subscribe({
      next: g => {
        this.onSelect(g);

        this.api.joinGame(g.id).subscribe({
          next: () => {
            this.optimisticJoinLocal(g.id);
            this.list();
          },
          error: e => this.showError(e)
        });
      },
      error: e => this.showError(e)
    });
  }

  leaveCreated() {
    if (!this.selected) return;

    this.api.leaveGame(this.selected.id).subscribe({
      next: () => {
        this.unsubscribeSelectedGame?.();
        this.clearCurrentGameStorage();
        this.list();
      },
      error: e => this.showError(e)
    });
  }

  get currentUsername(): string { return sessionStorage.getItem('username') ?? ''; }
  get currentGameId(): string | null { return sessionStorage.getItem('gameId'); }

  get cannotCreateGame(): boolean {
    return !!this.myActiveGameId;
  }

  get alreadyInSelected(): boolean {
    return this.isInGame(this.selected);
  }

  get inOtherGameSelected(): boolean {
    if (!this.selected) return false;
    return this.games.some(x => x.id !== this.selected!.id && this.isInGame(x));
  }

  get isSelectedCreated(): boolean { return this.selected?.status === 'CREATED'; }
  get isSelectedActive(): boolean { return this.selected?.status === 'ACTIVE'; }
  get myActiveGameIdView(): string | null { return this.myActiveGameId; }

  private clearCurrentGameStorage() {
    sessionStorage.removeItem('gameId');
    sessionStorage.removeItem('playerId');
  }

  get myActiveGameId(): string | null {
    const g = this.games.find(x => this.isInGame(x));
    return g?.id ?? null;
  }

  private syncStorageWithServer() {
    const gid = this.myActiveGameId;
    if (gid) {
      sessionStorage.setItem('gameId', gid);
      sessionStorage.setItem('playerId', this.myUserId);
    } else {
      this.clearCurrentGameStorage();
    }
  }

  goToGame() {
    if (this.selected) this.router.navigate(['/game', this.selected.id]);
  }

  join() {
    const sel = this.selected;
    if (!sel) return;

    const selId = sel.id;
    this.api.joinGame(selId).subscribe({
      next: () => this.optimisticJoinLocal(selId),
      error: e => this.showError(e)
    });
  }

  // Joueurs actifs de la partie sélectionnée (pour la liste du lobby)
  get lobbySelectedPlayers(): any[] {
    return this.activePlayers;
  }

  addBot() {
    if (!this.selected) return;
    this.api.addBot(this.selected.id).subscribe({ error: e => this.showError(e) });
  }

  removeBot(botId: string) {
    if (!this.selected) return;
    this.api.removeBot(this.selected.id, botId).subscribe({ error: e => this.showError(e) });
  }

  // --- Choix du rôle (lobby) : le sien ou celui d'un bot ---
  canEditRole(p: any): boolean {
    return !!p && (p.id === this.myUserId || !!p.bot);
  }
  wantsVampire(p: any): boolean {
    return p?.rolePreference === 'VAMPIRE';
  }
  setRole(p: any, role: 'HUNTER' | 'VAMPIRE') {
    if (!this.selected || !this.canEditRole(p)) return;
    if (this.wantsVampire(p) === (role === 'VAMPIRE')) return; // déjà ce rôle
    const prev = p.rolePreference;
    p.rolePreference = role; // optimiste (l'event lobby confirmera)
    this.api.setRolePreference(this.selected.id, p.id, role).subscribe({
      error: e => { p.rolePreference = prev; this.showError(e); }
    });
  }

  private optimisticJoinLocal(gameId: string) {
    const me = { id: this.myUserId, username: this.currentUsername, leftGame: false };

    if (this.selected?.id === gameId) {
      const current = this.selected.players ?? [];
      if (!current.some(p => p.id === me.id && !p.leftGame)) {
        this.selected = { ...this.selected, players: [...current, me] } as any;
      }
    }

    const i = this.games.findIndex(x => x.id === gameId);
    if (i >= 0) {
      const current = this.games[i].players ?? [];
      const already = current.some(p => p.id === me.id && !(p as any).leftGame);
      if (!already) {
        this.games[i] = { ...this.games[i], players: [...current, me] } as any;
        this.games = [...this.games];
      }
    }

    sessionStorage.setItem('gameId', gameId);
    sessionStorage.setItem('playerId', this.myUserId);
  }

  start() {
    if (!this.selected) return;
    this.api.startGame(this.selected.id).subscribe({ error: e => this.showError(e) });
  }

  logout() {
    const gid = this.myActiveGameId;

    const doLogout = () => {
      sessionStorage.removeItem('token');
      sessionStorage.removeItem('userId');
      sessionStorage.removeItem('username');
      sessionStorage.removeItem('gameId');
      sessionStorage.removeItem('playerId');
      this.router.navigate(['/auth']);
    };

    if (!gid) {
      doLogout();
      return;
    }

    const g = this.games.find(x => x.id === gid);

    if (g?.status === 'CREATED') {
      this.api.leaveGame(gid).subscribe({
        next: () => doLogout(),
        error: () => doLogout()
      });
      return;
    }

    doLogout();
  }
}
