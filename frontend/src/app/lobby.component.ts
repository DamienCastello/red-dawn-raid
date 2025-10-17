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
             [style.fontWeight]="currentGameId === g.id ? 'bold' : 'normal'">
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
        <!-- Si j'ai déjà une autre partie rejointe, on bloque "Join" -->
        <ng-container *ngIf="inOtherGameSelected; else canJoinHere">
          <p style="color:#b55">
            Vous avez déjà rejoint une autre partie ({{ currentGameId }}). Impossible de joindre celle-ci.
          </p>
          <button disabled>Rejoindre</button>
        </ng-container>

        <!-- Sinon je peux rejoindre ici (champ + bouton) -->
        <ng-template #canJoinHere>
          <label>Pseudo:
            <input [(ngModel)]="nickname" placeholder="Votre pseudo" />
          </label>
          <button (click)="join()" [disabled]="!nickname.trim()">Rejoindre</button>
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
  nickname = '';
  errorMsg = '';

  private lastSelectedStatus?: string;
  private poll?: any;

  ngOnInit(){
    this.list();
    this.nickname = sessionStorage.getItem('nick') ?? '';

    // Auto-sélection UNE FOIS (si j'avais déjà une partie)
    const myGameId = this.currentGameId;
    if (myGameId) {
      this.api.getGame(myGameId).subscribe({
        next: g => {
          this.selected = g;
          this.lastSelectedStatus = g.status; // mémorise le statut pour détecter CREATED->ACTIVE
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

  list(){
    this.api.listGames().subscribe({
      next: gs => this.games = gs,
      error: e => this.showError(e)
    });
  }

  // Sélection d'une game dans la liste (ne navigue PAS)
  pick(g: Game){
    this.selected = g;
    this.lastSelectedStatus = g.status;
  }

  create(){
    this.api.createGame().subscribe({
      next: g => { this.selected = g; this.lastSelectedStatus = g.status; this.list(); },
      error: e => this.showError(e)
    });
  }

  // ---- états dérivés (petite API lisible pour le template) ----
  get currentGameId(): string | null { return sessionStorage.getItem('gameId'); }
  get alreadyInSelected(): boolean { return !!this.selected && this.currentGameId === this.selected.id; }
  get inOtherGameSelected(): boolean {
    return !!this.selected && !!this.currentGameId && this.currentGameId !== this.selected.id;
  }
  get isSelectedCreated(): boolean { return this.selected?.status === 'CREATED'; }
  get isSelectedActive(): boolean { return this.selected?.status === 'ACTIVE'; }

  // Naviguer vers la game courante (si sélectionnée)
  goToGame(){
    if (this.selected) this.router.navigate(['/game', this.selected.id]);
  }

  // Rejoindre la game sélectionnée (si autorisé)
  join() {
    if (!this.selected || !this.nickname.trim()) return;

    // Si je suis déjà dans cette partie -> goToGame
    if (this.alreadyInSelected) { this.goToGame(); return; }

    // Si j'ai déjà une autre partie -> bloqué par l'UI (déjà géré), double-sécurité :
    if (this.inOtherGameSelected) { this.showError('Vous avez déjà rejoint une autre partie.'); return; }

    const nick = this.nickname.trim();
    this.api.joinGame(this.selected.id, nick).subscribe({
      next: r => {
        this.selected = r.game;
        this.lastSelectedStatus = r.game.status;
        sessionStorage.setItem('playerToken', r.playerToken);
        sessionStorage.setItem('playerId', r.playerId);
        sessionStorage.setItem('nick', nick);
        sessionStorage.setItem('gameId', this.selected!.id);
        this.list();
      },
      error: e => this.showError(e)
    });
  }

  // Démarrer (créateur ou joueur autorisé). On navigue IMMÉDIATEMENT pour l’auteur du start
  start(){
    if(!this.selected) return;
    this.api.startGame(this.selected.id).subscribe({
      next: g => {
        this.selected = g;
        this.lastSelectedStatus = g.status;
        this.list();
        // L’auteur du "Start" est nécessairement dans la partie -> nav immédiate.
        if (this.alreadyInSelected && g.status === 'ACTIVE') {
          this.router.navigate(['/game', g.id]);
        }
        // Les autres onglets/joueurs seront redirigés par le polling CREATED->ACTIVE.
      },
      error: e => this.showError(e)
    });
  }
}
