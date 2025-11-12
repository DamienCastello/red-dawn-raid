import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService, Game } from './api.service';
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

    <button (click)="create()">Créer une partie</button>
    <button (click)="list()">Lister</button>

    <div style="margin-top:1rem" *ngIf="games.length">
      <h3>Parties</h3>
      <ul>
        <li *ngFor="let g of games">
          <a href="#" (click)="$event.preventDefault(); pick(g)"
             [style.fontWeight]="isInGame(g) ? 'bold' : 'normal'">
            {{ g.id }} — {{ g.status }} ({{ g.players.length }} joueurs)
          </a>
        </li>
      </ul>
    </div>

    <div *ngIf="selected" style="margin-top:1rem">
      <h3>Partie sélectionnée</h3>
      <p><b>{{ selected.id }}</b> — {{ selected.status }}</p>

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
            Vous avez déjà rejoint une autre partie ({{ currentGameId }}). Impossible de joindre celle-ci.
          </p>
          <button disabled>Rejoindre</button>
        </ng-container>

        <ng-template #canJoinHere>
          <p>Vous rejoindrez en tant que <b>{{ currentUsername }}</b>.</p>
          <button (click)="join()">Rejoindre</button>
        </ng-template>
      </ng-template>

      <button (click)="start()" [disabled]="!selected || selected.status !== 'CREATED'">
        Démarrer (min 2 joueurs)
      </button>
    </div>
  </main>
  `
})
export class LobbyComponent {
  private api = inject(ApiService);
  private router = inject(Router);
    private live = inject(LiveService);

  games: Game[] = [];
  selected?: Game;
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

  onSelect(g: Game){ // ou ta méthode équivalente
    this.selected = g;
    this.lastSelectedStatus = g.status;

    // (re)abonnement au topic de la partie sélectionnée
    this.unsubscribeSelectedGame?.();
    this.unsubscribeSelectedGame = this.live.subscribeGame(g.id, (ev) => {
      if (ev.type === 'PHASE_CHANGED') {
        // Si je suis DÉJÀ joueur de cette partie et qu’elle vient de démarrer, je redirige
        if (this.alreadyInSelected && this.lastSelectedStatus !== 'ACTIVE') {
          this.router.navigate(['/game', g.id]);
        }
        // même si je ne redirige pas, je mets le statut local à jour
        this.lastSelectedStatus = 'ACTIVE';
        // et j’update la tuile
        if (this.selected?.id === g.id) this.selected = { ...this.selected, status: 'ACTIVE' } as any;
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

      // si c’est la partie actuellement sélectionnée, mets à jour le panneau de droite
      if (this.selected?.id === g.id) {
        this.selected = { ...this.selected, status: g.status, players: g.players } as any;
        this.lastSelectedStatus = g.status;
      }
      return;
    }
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
  isInGame(g?: Game): boolean {
    if (!g) return false;
    return (g.players || []).some(p => p.id === this.myUserId);
  }

  list(){
    this.api.listGames().subscribe({
      next: gs => {
        this.games = gs;

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

  pick(g: Game){
    this.onSelect(g);  // s’abonner au /topic/games/{id} de la sélection
  }

  create(){
    this.api.createGame().subscribe({
      next: g => { this.onSelect(g); this.list(); }, // abonné au topic de la partie créée
      error: e => this.showError(e)
    });
  }

  get currentUsername(): string { return sessionStorage.getItem('username') ?? ''; }
  // ---- états dérivés (petite API lisible pour le template)
  get currentGameId(): string | null { return sessionStorage.getItem('gameId'); }
  get alreadyInSelected(): boolean {
    // vérité serveur en priorité, storage en secours (pour compat avec l’existant)
    return this.isInGame(this.selected) ||
           (!!this.selected && this.currentGameId === this.selected.id);
  }
  get inOtherGameSelected(): boolean {
    // ➜ AMÉLIORATION : détecte aussi via le serveur si je suis déjà dans une autre partie
    if (!this.selected) return false;
    const inAnotherByServer = this.games.some(x => x.id !== this.selected!.id && this.isInGame(x));
    const storageSaysOther = !!this.currentGameId && this.currentGameId !== this.selected.id;
    return inAnotherByServer || storageSaysOther;
  }
  get isSelectedCreated(): boolean { return this.selected?.status === 'CREATED'; }
  get isSelectedActive(): boolean { return this.selected?.status === 'ACTIVE'; }

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
}
