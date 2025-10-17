import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, Game } from './api.service';

@Component({
  standalone: true,
  selector: 'app-game',
  imports: [CommonModule],
  template: `
  <main class="container" style="max-width:720px;margin:2rem auto">
    <button (click)="back()">← Retour Lobby</button>
    <h1>Partie {{ gameId }}</h1>
    <p><b>Vous êtes :</b> {{ nickname || '(anonyme)' }}</p>

    <div *ngIf="errorMsg" style="background:#fee;border:1px solid #f99;padding:.5rem;margin:.5rem 0">
      {{ errorMsg }}
    </div>

    <section *ngIf="game">
      <p>Status: {{ game.status }} — Round: {{ game.round }}</p>
      <p>Joueurs ({{ game.players.length }}): {{ game.players.join(', ') }}</p>
    </section>
  </main>
  `
})
export class GameComponent {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  gameId = '';
  game?: Game;
  nickname = sessionStorage.getItem('nick') ?? '';
  errorMsg = '';
  private timer?: any;

  ngOnInit() {
    this.gameId = this.route.snapshot.paramMap.get('id') || '';
    this.refresh();
    // petit polling pour suivre l’état (2s)
    this.timer = setInterval(() => this.refresh(), 2000);
  }
  ngOnDestroy(){ if(this.timer) clearInterval(this.timer); }

  private showError(e:any){ try{this.errorMsg = e?.error?.message || 'Erreur';}catch{this.errorMsg='Erreur';}
    setTimeout(()=>this.errorMsg='',4000); }

  refresh(){ if(!this.gameId) return;
    this.api.getGame(this.gameId).subscribe({ next: g => this.game = g, error: e => this.showError(e) }); }

  back(){ this.router.navigateByUrl('/'); }
}
