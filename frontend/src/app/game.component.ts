import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, Game, Player, CenterPlay } from './api.service';

@Component({
  standalone: true,
  selector: 'app-game',
  imports: [CommonModule],
  template: `
  <main class="container" style="max-width:1000px;margin:1rem auto">
    <button (click)="back()">← Retour Lobby</button>
    <h2>Raid {{ game?.raid }} — Phase: {{ game?.phase || '...' }}</h2>

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
        <div><b>{{ p.nickname || p.id }}</b> (Chasseur)</div>
      </div>
    </section>

    <!-- LIGNE MILIEU -->
    <div style="display:grid; grid-template-columns: 1fr 2fr 1fr; gap:.5rem; align-items:start;">
      <!-- gauche: stats vampire -->
      <section *ngIf="hasVampire" style="border:1px solid #ddd; padding:.5rem">
        <h3>Vampire</h3>
        <div>Joueur : {{ vampirePlayer.nickname }}</div>
        <div>Pioche actions: {{ game?.vampActionsLeft }} (défausse: {{ game?.vampActionsDiscard }})</div>
      </section>

      <!-- centre: plateau -->
      <section style="border:1px solid #ddd; padding:.5rem; min-height:180px">
        <h3>Plateau central</h3>
        <div *ngIf="!game?.center?.length" style="color:#999">Aucune carte jouée pour l’instant</div>
        <div *ngFor="let cp of game?.center" style="margin:.25rem 0">
          <span *ngIf="cp.faceUp; else back">
            {{ nicknameOf(cp.playerId) }}: {{ labelLieu(cp.card) }}
          </span>
          <ng-template #back>
            <i>Carte face cachée</i>
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
      <h3>{{ me.nickname || 'anonyme' }} — {{ isVampire ? 'Vampire' : 'Chasseur' }}</h3>
      <div>Votre main (lieux):</div>
      <div style="display:flex; gap:.5rem; flex-wrap:wrap; margin-top:.5rem">
        <button *ngFor="let c of (me.hand || [])"
                [class.selected]="selectedLocation===c"
                (click)="selectLocation(c)"
                style="padding:.5rem 1rem; border:1px solid #ccc; cursor:pointer">
          {{ labelLieu(c) }}
        </button>
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

  meId = sessionStorage.getItem('playerId') || '';
  nickname = sessionStorage.getItem('nick') || '';

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

  nicknameOf(id: string): string {
    if (!this.game) return id;
    const p = this.game.players.find(x => x.id === id);
    if (p?.nickname) return p.nickname;
    if (id === this.meId && this.nickname) return this.nickname;
    return id;
    // (plus tard, on pourra faire une vraie map id->nickname côté back si besoin)
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
      next: g => this.game = g,
      error: e => this.showError(e)
    });
  }

  selectLocation(c: string){ this.selectedLocation = c; }

  back(){ this.router.navigateByUrl('/'); }

  private showError(e:any){
    try{ this.errorMsg = e?.error?.message || 'Erreur'; } catch { this.errorMsg='Erreur'; }
    setTimeout(()=>this.errorMsg='', 4000);
  }
}
