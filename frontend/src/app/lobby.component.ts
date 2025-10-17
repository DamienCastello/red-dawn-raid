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
          <a href="#" (click)="$event.preventDefault(); pick(g)">{{ g.id }} — {{ g.status }} ({{ g.players.length }} joueurs)</a>
        </li>
      </ul>
    </div>

    <div *ngIf="selected" style="margin-top:1rem">
      <h3>Partie sélectionnée</h3>
      <p><b>{{ selected.id }}</b> — {{ selected.status }}</p>

      <label>Pseudo:
        <input [(ngModel)]="nickname" placeholder="Votre pseudo" />
      </label>
      <button (click)="join()" [disabled]="!nickname.trim()">Rejoindre</button>

      <button (click)="start()" [disabled]="!selected || selected.status !== 'CREATED'">Démarrer (min 2 joueurs)</button>
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

  private poll?: any;
  ngOnInit(){
    this.list();
    this.nickname = sessionStorage.getItem('nick') ?? '';
    this.poll = setInterval(() => {
      if (this.selected) {
        this.api.getGame(this.selected.id).subscribe({
          next: g => {
            this.selected = g;
            if (g.status === 'ACTIVE') this.router.navigate(['/game', g.id]);
          }
        });
      }
    }, 2000);
  }
  ngOnDestroy(){ if(this.poll) clearInterval(this.poll); }

  private showError(e:any){ try{this.errorMsg = e?.error?.message || 'Erreur';}catch{this.errorMsg='Erreur';}
    setTimeout(()=>this.errorMsg='',4000); }

  list(){ this.api.listGames().subscribe({ next: gs => this.games = gs, error: e=>this.showError(e) }); }
  pick(g: Game){ this.selected = g; }
  create(){ this.api.createGame().subscribe({ next: g => { this.selected = g; this.list(); }, error:e=>this.showError(e) }); }

  join() {
    if (!this.selected || !this.nickname.trim()) return;
    const nick = this.nickname.trim();
    this.api.joinGame(this.selected.id, nick).subscribe({
      next: r => {
        this.selected = r.game;
        sessionStorage.setItem('playerToken', r.playerToken);
        sessionStorage.setItem('playerId', r.playerId);
        sessionStorage.setItem('nick', nick);
        this.list();
      },
      error: e => this.showError(e)
    });
  }

  start(){
    if(!this.selected) return;
    this.api.startGame(this.selected.id).subscribe({
      next: g => { this.selected = g; this.list(); if(g.status==='ACTIVE'){ this.router.navigate(['/game', g.id]); } },
      error: e => this.showError(e)
    });
  }
}
