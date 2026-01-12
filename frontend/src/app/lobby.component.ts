import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService, LobbyGame } from './api.service';

import { LiveService, GameEvent } from './live.service';
import { AssetPreloaderService } from './services/asset-preloader.service';

@Component({
  standalone: true,
  selector: 'app-lobby',
  imports: [CommonModule, FormsModule],
  template: `
  <main class="container" style="max-width:720px;margin:2rem auto">
    <h1>Red Dawn Raid — Lobby</h1>

    <div *ngIf="errorMsg" style="background:#fee;border:1px solid #f99;padding:.5rem;margin:.5rem 0">
      {{ errorMsg }}
    </div>

    <button (click)="logout()" style="position: fixed; right: 40px; top: 10px;">Déconnexion</button>

    <button (click)="create()" [disabled]="cannotCreateGame">
      Créer une partie
    </button>
    <button (click)="list()" style="margin-left:.5rem">Lister</button>

    <div style="margin-top:1rem" *ngIf="games.length">
      <h3>Parties</h3>
      <ul>
        <li *ngFor="let g of games">
          <a href="#" (click)="$event.preventDefault(); pick(g)"
             [style.fontWeight]="isInGame(g) ? 'bold' : 'normal'">
            {{ g.id }} — {{ g.status }}
            <ng-container *ngIf="g.status !== 'ENDED'">
              ({{ activePlayersCount(g) }} joueurs)
            </ng-container>
          </a>
        </li>
      </ul>
    </div>

    <div *ngIf="selected" style="margin-top:1rem">
      <h3>Partie sélectionnée</h3>
      <p><b>{{ selected.id }}</b> — {{ selected.status }}</p>

      <ng-container *ngIf="selected.status !== 'ENDED'">
        <!-- Cas 1 : je suis déjà dans CETTE partie -->
        <ng-container *ngIf="alreadyInSelected; else notInSelected">
          <p style="color:#666">Vous êtes déjà dans cette partie.</p>
          <button (click)="goToGame()" [disabled]="!isSelectedActive">Reprendre la partie</button>
          <small *ngIf="isSelectedCreated" style="margin-left:.5rem; color:#888">
            En attente du démarrage…
          </small>
        </ng-container>

        <!-- Cas 2 : je NE suis PAS dans la partie sélectionnée -->
        <ng-template #notInSelected>
          <ng-container *ngIf="inOtherGameSelected; else canJoinHere">
            <p style="color:#b55">
              Vous avez déjà rejoint une autre partie ({{ myActiveGameId }}). Impossible de joindre celle-ci.
            </p>
            <button disabled>Rejoindre</button>
          </ng-container>

          <ng-template #canJoinHere>
            <p>Vous rejoindrez en tant que <b>{{ currentUsername }}</b>.</p>
            <button (click)="join()" [disabled]="selected.status !== 'CREATED'">Rejoindre</button>
          </ng-template>
        </ng-template>

          <button *ngIf="alreadyInSelected && isSelectedCreated"
                  (click)="start()"
                  [disabled]="activePlayersCount(selected) < 2"
                  style="margin-left:.5rem">
            Démarrer la partie
          </button>
          <button *ngIf="alreadyInSelected && isSelectedCreated"
                  (click)="leaveCreated()"
                  style="margin-left:.5rem">
            Quitter la partie
          </button>
      </ng-container>
    </div>
    <div *ngIf="selected?.status === 'ENDED' && endedSnap"
     style="margin-top:1rem; padding:.5rem; border:1px solid #ddd">
      <p><b>Vainqueur :</b> {{ endedSnap.winnerSide }}</p>
      <p><b>Participants :</b> {{ endedSnap.players.length }}</p>

      <h4>Joueurs</h4>
      <ul>
        <li *ngFor="let p of endedSnap.players">
          {{ p.username }} — {{ p.role }}
          —
          <span *ngIf="p.leftGame">a quitté</span>
          <span *ngIf="!p.leftGame && p.hp <= 0">mort</span>
          <span *ngIf="!p.leftGame && p.hp > 0">vivant</span>
          (PV: {{ p.hp }})
        </li>
      </ul>
    </div>
  </main>
  <!-- OVERLAY STARTING -->
  <div *ngIf="showStartingOverlay"
      style="position:fixed; inset:0; background:rgba(0,0,0,.65); display:flex; align-items:center; justify-content:center; z-index:9999;">
    <div style="background:#111; color:#fff; padding:1rem 1.25rem; border-radius:12px; width:min(520px, 92vw);">
      <h2 style="margin:0 0 .5rem 0;">Chargement de la partie…</h2>

      <p style="margin:.25rem 0; opacity:.9;">
        Ressources sur ce client :
        <b>{{ localAssetsDone ? 'OK' : 'en cours…' }}</b>
      </p>

      <p style="margin:.25rem 0; opacity:.9;">
        Joueurs prêts :
        <b>{{ readyStartCount }} / {{ readyStartTotal }}</b>
      </p>

      <div style="margin-top:.75rem; font-size:.95rem; opacity:.8;">
        La partie démarre automatiquement dès que tout le monde a fini de charger.
      </div>
    </div>
  </div>
  `
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

  private presenceTimer: any = null;
  private presenceGameId: string | null = null;

  private startPresence(gameId: string) {
    if (this.presenceTimer && this.presenceGameId === gameId) return;
    this.stopPresence();
    this.presenceGameId = gameId;

    this.presenceTimer = setInterval(() => {
      this.api.presence(gameId).subscribe({ error: () => {} });
    }, 15_000); // ping régulier (tu peux mettre 20s)
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

  // Prêts côté serveur
  get readyStartCount(): number {
    return (this.selected?.readyForStart?.length ?? 0);
  }
  get readyStartTotal(): number {
    return this.activePlayersCount(this.selected);
  }

  // Afficher l’overlay seulement si je suis dans la game et que la game est STARTING
  get showStartingOverlay(): boolean {
    return !!this.selected && this.isSelectedStarting && this.alreadyInSelected;
  }

  private ensureBootReadyIfNeeded(gameId: string) {
    // Déjà envoyé pour cette game ?
    if (this.bootReadySentForGameId === gameId) return;

    // On n’envoie que si je suis dans la partie sélectionnée ET si elle est en STARTING
    if (!this.selected || this.selected.id !== gameId) return;
    if (this.selected.status !== 'STARTING') return;
    if (!this.alreadyInSelected) return;

    // On attend la fin du preload local
    this.assets.waitDone()
      .catch(() => {}) // on ne bloque pas en cas d’échec (sinon tu ne démarres jamais)
      .finally(() => {
        this.localAssetsDone = true;

        // Marque avant l’appel (anti double click / double events)
        this.bootReadySentForGameId = gameId;

        this.api.bootReady(gameId).subscribe({
          next: () => {},
          error: e => {
            // si tu veux être strict : remettre à null pour retenter
            // mais je conseille de rester idempotent (côté back) et juste log
            console.warn('bootReady failed', e);
          }
        });
      });
  }

  ngOnInit() {
    this.assets.start(); // lance en fond (si pas déjà lancé depuis Auth)
    this.assets.waitDone().then(() => this.localAssetsDone = true).catch(() => this.localAssetsDone = true);
    this.bootReadySentForGameId = null;

    this.list(); // hydrate la liste une fois

    // WS global lobby
    this.unsubscribeLobby = this.live.subscribeLobby((e) => this.onLobbyEvent(e));
  }

  ngOnDestroy(){
    this.stopPresence();
    this.unsubscribeLobby?.();
    this.unsubscribeSelectedGame?.();
  }

  private showError(e:any){
    try{ this.errorMsg = e?.error?.message || 'Erreur'; }catch{ this.errorMsg='Erreur'; }
    setTimeout(()=>this.errorMsg='',4000);
  }

  // ---- identité côté front (temporaire tant qu’on garde le storage pour l’auth)
  private get myUserId(): string {
    return sessionStorage.getItem('userId') ?? '';
  }

  endedSnap: any | null = null; // ou GameSnapshot si tu veux typer

  onSelect(g: LobbyGame){
    this.selected = g;
    // reset overlay & anti-double-bootReady quand on change de sélection
    this.bootReadySentForGameId = null;
    this.localAssetsDone = false;

    // on remet à true si le preload est déjà fini (service singleton)
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

    // (re)abonnement au topic de la partie sélectionnée
    this.unsubscribeSelectedGame?.();
    this.unsubscribeSelectedGame = this.live.subscribeGame(g.id, (ev) => {
      if (ev.type === 'PHASE_CHANGED') {
        // ✅ pas de navigate ici (géré par LOBBY_UPDATED côté lobby global)
        this.lastSelectedStatus = 'ACTIVE';
        if (this.selected?.id === g.id) {
          this.selected = { ...this.selected, status: 'ACTIVE' } as any;
        }
      }
    });

    // si la partie est déjà STARTING au moment du select → on déclenche la barrière
    if (g.status === 'STARTING' && this.alreadyInSelected) {
      this.ensureBootReadyIfNeeded(g.id);
    }
  }

  private onLobbyEvent(e: GameEvent){
    if (e.type === 'GAME_CREATED') {
      // insère/rafraîchit l’entrée dans la liste
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

      // Si on quitte STARTING, on autorise un futur bootReady (évite lock)
      if (g.status !== 'STARTING' && this.bootReadySentForGameId === g.id) {
        this.bootReadySentForGameId = null;
      }

      // Si STARTING et dedans => déclenche boot-ready quand preload finit
      if (g.status === 'STARTING' && iAmIn) {
        // si cette game est sélectionnée, on met à jour selected + overlay
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

      // Navigation seulement quand ACTIVE
      if (g.status === 'ACTIVE' && iAmIn) {
        this.router.navigate(['/game', g.id]);
        return;
      }

      if (this.selected?.id === g.id) {
        this.selected = {
          ...this.selected,
          ...g,
          // garde une version safe même si un event foireux arrive
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

      // si le storage pointait dessus
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

  private upsertInList(item: any){
    const i = this.games.findIndex(x => x.id === item.id);
    if (i >= 0) this.games[i] = { ...this.games[i], ...item };
    else this.games.unshift(item);
    this.games = [...this.games];
  }


  // ➜ AMÉLIORATION : on s’appuie sur la vérité serveur (players[]) plutôt que sur le storage
  isInGame(g?: LobbyGame): boolean {
    if (!g) return false;

    // une partie finie ne bloque jamais
    if (g.status === 'ENDED') return false;

    return (g.players || []).some(p => p.id === this.myUserId && !p.leftGame);
  }


  list(){
    this.api.listGames().subscribe({
      next: gs => {
        this.games = gs;

        this.syncStorageWithServer();

        // ➜ AMÉLIORATION : auto-select la game où je suis déjà inscrit selon le serveur
        if (!this.selected) {
          const mine = gs.find(g => this.isInGame(g));
          if (mine) {
            this.onSelect(mine); // ✅ crée l’abonnement WS de suite
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

  pick(g: LobbyGame){
    this.onSelect(g);  // s’abonner au /topic/games/{id} de la sélection
  }

  create(){
    if (this.cannotCreateGame) {
      this.showError({ error: { message: "Vous êtes déjà dans une partie. Quittez-la avant d'en créer une autre." }});
      return;
    }

    this.api.createGame().subscribe({
      next: g => {
        this.onSelect(g);

        this.api.joinGame(g.id).subscribe({
          next: () => {
            // ✅ MAJ optimiste immédiate (comme join)
            this.optimisticJoinLocal(g.id);

            // ✅ un seul refresh (optionnel mais ok)
            this.list();
          },
          error: e => this.showError(e)
        });
      },
      error: e => this.showError(e)
    });
  }

  leaveCreated(){
    if (!this.selected) return;

    this.api.leaveGame(this.selected.id).subscribe({
      next: () => {
        this.unsubscribeSelectedGame?.(); // ✅ stop events de cette game
        this.clearCurrentGameStorage();
        this.list();
      },
      error: e => this.showError(e)
    });
  }

  get currentUsername(): string { return sessionStorage.getItem('username') ?? ''; }
  // ---- états dérivés (petite API lisible pour le template)
  get currentGameId(): string | null { return sessionStorage.getItem('gameId'); }

  // Tu es "dans une game" si le serveur le dit (myActiveGameId) OU si le storage a encore une gameId
  get cannotCreateGame(): boolean {
    return !!this.myActiveGameId || !!this.currentGameId;
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


  private clearCurrentGameStorage(){
    sessionStorage.removeItem('gameId');
    sessionStorage.removeItem('playerId');
  }

  // Ma game “active” côté serveur (joueur présent ET pas leftGame)
  get myActiveGameId(): string | null {
    const g = this.games.find(x => this.isInGame(x));
    return g?.id ?? null;
  }

  // On aligne le storage sur la vérité serveur (évite d’être “dupé”)
  private syncStorageWithServer(){
    const gid = this.myActiveGameId;
    if (gid) {
      sessionStorage.setItem('gameId', gid);
      sessionStorage.setItem('playerId', this.myUserId);
    } else {
      this.clearCurrentGameStorage();
    }
  }

  // Naviguer vers la game courante (si sélectionnée)
  goToGame(){
    if (this.selected) this.router.navigate(['/game', this.selected.id]);
  }

  // Rejoindre la game sélectionnée (si autorisé)
  join(){
    const sel = this.selected;
    if (!sel) return;
    const selId = sel.id;

    this.api.joinGame(selId).subscribe({
    next: () => {
      this.optimisticJoinLocal(selId);
    },
      error: e => this.showError(e)
    });
  }

  private optimisticJoinLocal(gameId: string) {
    const me = { id: this.myUserId, username: this.currentUsername, leftGame: false };

    // 1) update selected si c’est la bonne game
    if (this.selected?.id === gameId) {
      const current = this.selected.players ?? [];
      if (!current.some(p => p.id === me.id && !p.leftGame)) {
        this.selected = { ...this.selected, players: [...current, me] } as any;
      }
    }

    // 2) update games list
    const i = this.games.findIndex(x => x.id === gameId);
    if (i >= 0) {
      const current = this.games[i].players ?? [];
      const already = current.some(p => p.id === me.id && !(p as any).leftGame);
      if (!already) {
        this.games[i] = { ...this.games[i], players: [...current, me] } as any;
        this.games = [...this.games];
      }
    }

    // 3) storage
    sessionStorage.setItem('gameId', gameId);
    sessionStorage.setItem('playerId', this.myUserId);
  }

  start(){
    if (!this.selected) return;
    this.api.startGame(this.selected.id).subscribe({ error: e => this.showError(e) });
    // pas de navigate() ici : on laisse l’event WS piloter pour tous les onglets
  }

  logout() {
    // On regarde si je suis "dans une game" selon le serveur
    const gid = this.myActiveGameId; // déjà chez toi

    const doLogout = () => {
      // coupe la session
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

    // si CREATED => on quitte avant de logout
    if (g?.status === 'CREATED') {
      this.api.leaveGame(gid).subscribe({
        next: () => doLogout(),
        error: () => doLogout() // même si ça plante, on déconnecte quand même
      });
      return;
    }

    // si ACTIVE => on ne quitte pas, on logout direct
    doLogout();
  }
}
