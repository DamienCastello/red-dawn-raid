import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService, LobbyGame } from './api.service';

import { LiveService, GameEvent } from './live.service';

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
            <button (click)="join()">Rejoindre</button>
          </ng-template>
        </ng-template>

        <button (click)="start()" [disabled]="!selected || selected.status !== 'CREATED'" style="margin-left:.5rem">
          Démarrer (min 2 joueurs)
        </button>
          <button *ngIf="isSelectedCreated" (click)="leaveCreated()" style="margin-left:.5rem">
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

  private unsubscribeLobby?: () => void;
  private unsubscribeSelectedGame?: () => void;

  ngOnInit() {
    this.list(); // hydrate la liste une fois

    // WS global lobby
    this.unsubscribeLobby = this.live.subscribeLobby((e) => this.onLobbyEvent(e));
  }

  ngOnDestroy(){
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
      if (g.status === 'ACTIVE' && iAmIn) {
        this.router.navigate(['/game', g.id]);
        return;
      }

      if (this.selected?.id === g.id) {
        this.selected = { ...this.selected, status: g.status, players: g.players } as any;
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

  private asListItem(e: Extract<GameEvent, {type:'GAME_CREATED'|'LOBBY_UPDATED'}>) {
    return {
      id: e.payload.gameId,
      status: e.payload.status,
      players: e.payload.players // adapte à ton type (compte, usernames…)
    } as any; // GameListItem
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
            sessionStorage.setItem('gameId', g.id);
            this.list(); // refresh lobby list
          },
          error: e => this.showError(e)
        });

        this.list(); 
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
        // Mise à jour locale optimiste
        const me = { id: this.myUserId, username: this.currentUsername };

        // on part de l'état le plus frais dispo (si quelqu'un a mis à jour entre-temps)
        const current = this.selected?.players ?? sel.players ?? [];
        const already = current.some(p => p.id === me.id);

        if (!already) {
          const updated = {
            ...(this.selected ?? sel),
            players: [...current, me]
          } as any;

          this.selected = updated;
          this.upsertInList({ id: updated.id, players: updated.players });
        }

        // storage tant qu’on garde ce fallback
        sessionStorage.setItem('gameId', selId);
      },
      error: e => this.showError(e)
    });
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
