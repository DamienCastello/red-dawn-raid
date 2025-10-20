import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, Game, Player } from './api.service';

@Component({
  standalone: true,
  selector: 'app-game',
  imports: [CommonModule],
  template: `
  <main class="container" style="max-width:1000px;margin:1rem auto">
    <button (click)="back()">← Retour Lobby</button>
    <h2>Raid {{ game?.raid }} — {{ game?.phase || '...' }}</h2>

    <div *ngIf="game?.phase==='PHASE0'" style="padding:.5rem;background:#eef;border:1px solid #99f;margin:.5rem 0">
      Sélection météo...
    </div>

    <div *ngIf="errorMsg" style="background:#fee;border:1px solid #f99;padding:.5rem;margin:.5rem 0">
      {{ errorMsg }}
    </div>

    <!-- BOARD HAUT: chasseurs (autres que moi) -->
    <section style="display:grid; gap:.5rem; grid-template-columns: repeat(auto-fit,minmax(160px,1fr)); padding:.5rem; border:1px solid #ddd; margin:.5rem 0">
      <div *ngFor="let p of hunterPlayers" [style.opacity]="isCurrent(p) ? 1 : .8"
           style="padding:.5rem; border:1px dashed #bbb; background:#f7f7ff">
        <div><b>{{ p.username || p.id }}</b> (Chasseur)</div>
      </div>
    </section>

    <!-- LIGNE MILIEU -->
    <div style="display:grid; grid-template-columns: 1fr 2fr 1fr; gap:.5rem; align-items:start;">
      <!-- gauche: stats vampire -->
      <section *ngIf="hasVampire" style="border:1px solid #ddd; padding:.5rem">
        <h3>Vampire</h3>
        <div>Joueur : {{ vampirePlayer.username }}</div>
        <div>Pioche actions: {{ game?.vampActionsLeft }} (défausse: {{ game?.vampActionsDiscard }})</div>
      </section>

      <!-- centre: plateau -->
      <section style="border:1px solid #ddd; padding:.5rem; min-height:180px">
        <h3>Plateau central</h3>
        <!-- Messages Step 3 -->
        <div *ngIf="(game?.messages?.length || 0) > 0" style="margin:.5rem 0; padding:.5rem; background:#f8f8ff; border:1px solid #ccd">
          <div *ngFor="let m of game?.messages" style="margin:.15rem 0">{{ m }}</div>
        </div>
        <!-- Bandeau PREPHASE3 -->
        <div *ngIf="game?.phase==='PREPHASE3'" style="margin:.5rem 0; padding:.5rem; background:#fffbe6; border:1px solid #e6c200">
          <b>Préparation au combat</b> — Le combat commence bientôt.
          <span *ngIf="remainingPrePhaseSeconds > 0">  ({{ remainingPrePhaseSeconds }}s)</span>
          <div *ngIf="me" style="margin-top:.5rem">
            <button (click)="skipNow()" [disabled]="hasSkipped" title="Signaler que vous avez fini vos actions">J’ai fini</button>
            <small *ngIf="hasSkipped" style="margin-left:.5rem; color:#666">En attente des autres…</small>
          </div>
        </div>

        <div *ngIf="!game?.center?.length" style="color:#999">Aucune carte jouée pour l’instant</div>
        <div *ngFor="let cp of game?.center" style="margin:.25rem 0">
          <span *ngIf="cp.faceUp; else back">
            {{ usernameOf(cp.playerId) }}: {{ labelLieu(cp.card) }}
          </span>
          <ng-template #back>
            <i>Carte face cachée ({{ usernameOf(cp.playerId) }})</i>
          </ng-template>
        </div>
      </section>

      <!-- droite: stats chasseurs + potions -->
      <section style="border:1px solid #ddd; padding:.5rem">
        <h3>Chasseurs & Potions</h3>
        <div>Pioche actions: {{ game?.hunterActionsLeft }} (défausse: {{ game?.hunterActionsDiscard }})</div>
        <div>Pioche potions: {{ game?.potionsLeft }} (défausse: {{ game?.potionsDiscard }})</div>
      </section>
    </div>

    <!-- BOARD BAS: moi -->
    <section *ngIf="me && game" style="border:2px solid #444; padding:.5rem; margin:1rem 0; background:#fafafa">
      <h3>{{ me.username || 'anonyme' }} — {{ isVampire ? 'Vampire' : 'Chasseur' }}</h3>
      <div>Votre main (lieux):</div>
      <div style="display:flex; gap:.5rem; flex-wrap:wrap; margin-top:.5rem">
        <button *ngFor="let c of (me.hand || [])"
                [class.selected]="selectedLocation===c"
                (click)="selectLocation(c)"
                style="padding:.5rem 1rem; border:1px solid #ccc; cursor:pointer">
          {{ labelLieu(c) }}
        </button>
      </div>
      <div style="margin-top:.5rem">
        <button (click)="playSelected()" [disabled]="!canPlay">Jouer cette carte</button>
      </div>
    </section>
  </main>
  `,
  styles: [`.selected{ outline:2px solid #000 }`]
})
export class GameComponent {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  gameId = '';
  game?: Game;
  errorMsg = '';
  remainingPrePhaseSeconds = 0;
  hasSkipped = false;

  meId = sessionStorage.getItem('userId') || '';
  username = sessionStorage.getItem('username') || '';

  selectedLocation: string | null = null;
  private timer?: any;

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
      case 'foret': return 'Forêt';
      case 'carriere': return 'Carrière';
      case 'lac': return 'Lac';
      case 'manoir': return 'Manoir';
      default: return c;
    }
  }

  ngOnInit() {
    this.gameId = this.route.snapshot.paramMap.get('id') || '';
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 2000);
  }
  ngOnDestroy(){ if(this.timer) clearInterval(this.timer); }

  refresh(){
    if(!this.gameId) return;
    this.api.getGame(this.gameId).subscribe({
      next: g => {
        this.game = g;
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

 back(){ this.router.navigate(['/lobby']); }

  private showError(e:any){
    try{ this.errorMsg = e?.error?.message || 'Erreur'; } catch { this.errorMsg='Erreur'; }
    setTimeout(()=>this.errorMsg='', 4000);
  }
}
