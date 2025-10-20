import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService, Game } from './api.service';

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

  games: Game[] = [];
  selected?: Game;
  username = '';
  errorMsg = '';

  private lastSelectedStatus?: string;
  private poll?: any;

  ngOnInit(){
    this.list();
    this.username = sessionStorage.getItem('username') ?? '';

    // Auto-sélection UNE FOIS (si j'avais déjà une partie)
    const myGameId = this.currentGameId;
    if (myGameId) {
      this.api.getGame(myGameId).subscribe({
        next: g => {
          this.selected = g;
          this.lastSelectedStatus = g.status; // mémorise le statut pour détecter CREATED->ACTIVE

          // ➜ CHANGEMENT : on NE redirige PAS ici même si ACTIVE.
          // La redirection automatique reste gérée uniquement par le polling CREATED->ACTIVE.
        },
        error: () => {}
      });
    }

    // Polling: rafraîchit la partie sélectionnée et NE redirige que lors du passage CREATED->ACTIVE
    this.poll = setInterval(() => {
      if (!this.selected) return;
      this.api.getGame(this.selected.id).subscribe({
        next: g => {
          const wasActive = this.lastSelectedStatus === 'ACTIVE';
          const nowActive = g.status === 'ACTIVE';

          this.selected = g;

          // Redirection automatique UNIQUEMENT quand ça vient de démarrer
          if (!wasActive && nowActive && this.alreadyInSelected) {
            this.router.navigate(['/game', g.id]);
          }
          this.lastSelectedStatus = g.status;
        }
      });
    }, 2000);
  }

  ngOnDestroy(){ if(this.poll) clearInterval(this.poll); }

  private showError(e:any){
    try{ this.errorMsg = e?.error?.message || 'Erreur'; }catch{ this.errorMsg='Erreur'; }
    setTimeout(()=>this.errorMsg='',4000);
  }

  // ---- identité côté front (temporaire tant qu’on garde le storage pour l’auth)
  private get myUserId(): string {
    return sessionStorage.getItem('userId') ?? '';
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
            this.selected = mine;
            this.lastSelectedStatus = mine.status;

            // (optionnel) synchro du storage tant qu’on l’utilise encore
            sessionStorage.setItem('gameId', mine.id);
            sessionStorage.setItem('playerId', this.myUserId);

            // ➜ CHANGEMENT : on NE redirige PAS ici même si la game est déjà ACTIVE.
            // La navigation se fera au clic “Reprendre la partie” ou via le polling CREATED->ACTIVE.
          }
        }
      },
      error: e => this.showError(e)
    });
  }

  // Sélection d'une game dans la liste (ne navigue PAS)
  pick(g: Game){
    this.selected = g;
    this.lastSelectedStatus = g.status;

    // ➜ CHANGEMENT : pas de navigation automatique même si je suis dedans et ACTIVE.
    // L’utilisateur doit cliquer “Reprendre la partie”.
  }

  create(){
    this.api.createGame().subscribe({
      next: g => { this.selected = g; this.lastSelectedStatus = g.status; this.list(); },
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
  join() {
    if (!this.selected) return;

    // Si je suis déjà dans cette partie -> goToGame
    if (this.alreadyInSelected) { this.goToGame(); return; }

    // Si j'ai déjà une autre partie -> bloqué par l'UI (détection serveur + fallback storage)
    if (this.inOtherGameSelected) { this.showError('Vous avez déjà rejoint une autre partie.'); return; }

    this.api.joinGame(this.selected.id).subscribe({
      next: r => {
        this.selected = r.game;
        this.lastSelectedStatus = r.game.status;

        // (optionnel) synchro storage pour compat avec l’existant
        sessionStorage.setItem('playerId', r.playerId);
        sessionStorage.setItem('username', this.currentUsername);
        sessionStorage.setItem('gameId', this.selected!.id);

        this.list();
      },
      error: e => this.showError(e)
    });
  }

  start(){
    if(!this.selected) return;
    this.api.startGame(this.selected.id).subscribe({
      next: g => {
        this.selected = g;
        this.lastSelectedStatus = g.status;
        this.list();
      },
      error: e => this.showError(e)
    });
  }
}
