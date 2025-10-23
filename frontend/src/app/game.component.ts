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

    <!-- BOARD HAUT: chasseurs -->
    <section
      style="display:grid; gap:.5rem; grid-template-columns: repeat(auto-fit,minmax(160px,1fr)); padding:.5rem; border:1px solid #ddd; margin:.5rem 0">

      <div *ngFor="let p of hunterPlayers; trackBy: trackById"
          [style.opacity]="isCurrent(p) ? 1 : .9"
          style="padding:.5rem; border:1px dashed #bbb; background:#f7f7ff; border-radius:8px">

        <div class="player-strip">
          <div class="name">{{ p.username || p.id }}</div>
          <div class="hp">
            <img class="hp-heart" [src]="heartIconFor(p)" alt="HP"/>
            <span class="hp-value">{{ p.hp }}</span>
          </div>
        </div>

        <div class="equip-grid">
          <!-- Arme -->
          <div class="equip-card">
            <ng-container *ngIf="!isEquipMissing(p.id,'weapon'); else hunterWeaponText">
              <img class="equip-img"
                  [src]="equipWeaponImg(p)"
                  (error)="markEquipMissing(p.id,'weapon')"
                  alt="Arme"/>
            </ng-container>
            <ng-template #hunterWeaponText>
              <div class="equip-text">Arme : {{ p.attackDice || 'D6' }}</div>
            </ng-template>
            <span class="equip-badge">{{ p.attackDice || 'D6' }}</span>
          </div>

          <!-- Armure -->
          <div class="equip-card">
            <ng-container *ngIf="!isEquipMissing(p.id,'armor'); else hunterArmorText">
              <img class="equip-img"
                  [src]="equipArmorImg(p)"
                  (error)="markEquipMissing(p.id,'armor')"
                  alt="Armure"/>
            </ng-container>
            <ng-template #hunterArmorText>
              <div class="equip-text">Armure : {{ p.defenseDice || 'D6' }}</div>
            </ng-template>
            <span class="equip-badge">{{ p.defenseDice || 'D6' }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- LIGNE MILIEU -->
    <div style="display:grid; grid-template-columns: 1fr 2fr 1fr; gap:.5rem; align-items:start;">
      <!-- gauche: stats vampire -->
      <section *ngIf="hasVampire" style="border:1px solid #ddd; padding:.5rem; background:#fefefe">
        <h3 style="margin-top:0">Vampire</h3>

        <div class="player-strip">
          <div class="name">{{ vampirePlayer.username || vampirePlayer.id }}</div>
          <div class="hp">
            <img class="hp-heart" [src]="heartIconFor(vampirePlayer)" alt="HP"/>
            <span class="hp-value">{{ vampirePlayer.hp }}</span>
          </div>
        </div>

        <div class="equip-grid">
          <!-- Arme -->
          <div class="equip-card">
            <ng-container *ngIf="!isEquipMissing(vampirePlayer.id,'weapon'); else vampWeaponText">
              <img class="equip-img"
                  [src]="equipWeaponImg(vampirePlayer)"
                  (error)="markEquipMissing(vampirePlayer.id,'weapon')"
                  alt="Arme"/>
            </ng-container>
            <ng-template #vampWeaponText>
              <div class="equip-text">Arme : {{ vampirePlayer.attackDice || 'D6' }}</div>
            </ng-template>
            <span class="equip-badge">{{ vampirePlayer.attackDice || 'D6' }}</span>
          </div>

          <!-- Armure -->
          <div class="equip-card">
            <ng-container *ngIf="!isEquipMissing(vampirePlayer.id,'armor'); else vampArmorText">
              <img class="equip-img"
                  [src]="equipArmorImg(vampirePlayer)"
                  (error)="markEquipMissing(vampirePlayer.id,'armor')"
                  alt="Armure"/>
            </ng-container>
            <ng-template #vampArmorText>
              <div class="equip-text">Armure : {{ vampirePlayer.defenseDice || 'D6' }}</div>
            </ng-template>
            <span class="equip-badge">{{ vampirePlayer.defenseDice || 'D6' }}</span>
          </div>
        </div>

        <div style="margin-top:.6rem; color:#555">
          Pioche actions: {{ game?.vampActionsLeft }} (défausse: {{ game?.vampActionsDiscard }})
        </div>
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
      <div class="player-strip">
        <div class="name">
          {{ me.username || 'anonyme' }} — {{ isVampire ? 'Vampire' : 'Chasseur' }}
        </div>
        <div class="hp">
          <img class="hp-heart" [src]="heartIconFor(me)" alt="HP"/>
          <span class="hp-value">{{ me.hp }}</span>
        </div>
      </div>

      <div class="equip-grid" style="margin-top:.5rem">
        <div class="equip-card">
          <ng-container *ngIf="!isEquipMissing(me.id,'weapon'); else meWeaponText">
            <img class="equip-img"
                [src]="equipWeaponImg(me)"
                (error)="markEquipMissing(me.id,'weapon')"
                alt="Arme"/>
          </ng-container>
          <ng-template #meWeaponText>
            <div class="equip-text">Arme : {{ me.attackDice || 'D6' }}</div>
          </ng-template>
          <span class="equip-badge">{{ me.attackDice || 'D6' }}</span>
        </div>

        <div class="equip-card">
          <ng-container *ngIf="!isEquipMissing(me.id,'armor'); else meArmorText">
            <img class="equip-img"
                [src]="equipArmorImg(me)"
                (error)="markEquipMissing(me.id,'armor')"
                alt="Armure"/>
          </ng-container>
          <ng-template #meArmorText>
            <div class="equip-text">Armure : {{ me.defenseDice || 'D6' }}</div>
          </ng-template>
          <span class="equip-badge">{{ me.defenseDice || 'D6' }}</span>
        </div>
      </div>

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
  <!-- === MODALE ACTION (joueur concerné) — AJOUT === -->
  <div *ngIf="showActionModal && currentCombat as r" class="modal-backdrop">
    <div class="modal">
      <h3 style="margin-top:0">
        {{ (getPlayer(r.attackerId)?.username || r.attackerId) }}
        vs
        {{ (getPlayer(r.defenderId)?.username || r.defenderId) }}
      </h3>

      <div class="content action">
        <!-- On affiche le dé du joueur courant, avec icône -->
        <ng-container *ngIf="waitingForMyRoll as side">
          <ng-container *ngIf="side==='ATTACK'; else defenseSide">
            <img class="icon-side" [src]="roleIcon(getRole(getPlayer(r.attackerId)),'sword')" alt="attaque"/>
            <div class="dice-wrap">
              <img class="dice-big"
                  [src]="diceAsset(getPlayer(r.attackerId)?.attackDice, roleColorOf(getPlayer(r.attackerId)))"
                  alt="dice"/>
              <div class="dice-overlay" *ngIf="r.attackerRoll != null">{{ r.attackerRoll }}</div>
            </div>
          </ng-container>
          <ng-template #defenseSide>
            <img class="icon-side" [src]="roleIcon(getRole(getPlayer(r.defenderId)),'armor')" alt="défense"/>
            <div class="dice-wrap">
              <img class="dice-big"
                  [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice, roleColorOf(getPlayer(r.defenderId)))"
                  alt="dice"/>
              <div class="dice-overlay" *ngIf="r.defenderRoll != null">{{ r.defenderRoll }}</div>
            </div>
          </ng-template>
        </ng-container>
      </div>

      <div class="footer">
        <button (click)="rollNow()">Jeter le dé</button>
      </div>
    </div>
  </div>

  <!-- === MODALE SPECTATEUR === -->
  <div *ngIf="showSpectatorModal && currentCombat as r" class="modal-backdrop">
    <div class="modal">
      <h3 style="margin-top:0">
        {{ (getPlayer(r.attackerId)?.username || r.attackerId) }}
        vs
        {{ (getPlayer(r.defenderId)?.username || r.defenderId) }}
      </h3>

      <div class="content spect">
        <div class="side">
          <img class="icon-small" [src]="roleIcon(getRole(getPlayer(r.attackerId)),'sword')" alt="attaque"/>
          <div class="dice-wrap">
            <img class="dice"
                [src]="diceAsset(getPlayer(r.attackerId)?.attackDice, roleColorOf(getPlayer(r.attackerId)))"
                alt="dice"/>
            <div class="dice-overlay" *ngIf="r.attackerRoll != null">{{ r.attackerRoll }}</div>
          </div>
        </div>
        <div class="side">
          <img class="icon-small" [src]="roleIcon(getRole(getPlayer(r.defenderId)),'armor')" alt="défense"/>
          <div class="dice-wrap">
            <img class="dice"
                [src]="diceAsset(getPlayer(r.defenderId)?.defenseDice, roleColorOf(getPlayer(r.defenderId)))"
                alt="dice"/>
            <div class="dice-overlay" *ngIf="r.defenderRoll != null">{{ r.defenderRoll }}</div>
          </div>
        </div>
      </div>

      <div class="result" *ngIf="getCombatResultText() as txt">{{ txt }}</div>
      <div class="footer" *ngIf="!getCombatResultText()">Les adversaires s’affrontent…</div>
    </div>
  </div>
  `,
  styles: [`
  .selected{ outline:2px solid #000 }

    /* --- Bandeau joueur --- */
  .player-strip{
    display:flex; align-items:center; justify-content:space-between;
    gap:.75rem; padding:.4rem .6rem; border:1px solid #ddd; border-radius:8px;
    background:#fff;
  }
  .player-strip .name{ font-weight:700; }
  .player-strip .hp{ display:flex; align-items:center; gap:.35rem; }
  .hp-heart{ width:22px; height:22px; display:block; }
  .hp-value{ font-weight:700; min-width:2ch; text-align:right; }

  /* --- Grille équipements --- */
  .equip-grid{
    display:grid; grid-template-columns:1fr 1fr; gap:.5rem; margin-top:.5rem;
  }
  .equip-card{
    position:relative; border:1px solid #ddd; border-radius:10px; overflow:hidden;
    background:linear-gradient(180deg,#f8f9fb,#eef1f5);
    min-height:110px; display:flex; align-items:center; justify-content:center;
    padding:.5rem;
  }
  .equip-img{
    max-width:100%; max-height:120px; object-fit:contain; display:block;
    filter: drop-shadow(0 2px 4px rgba(0,0,0,.08));
  }
  .equip-badge{
    position:absolute; bottom:6px; right:6px;
    font:700 12px/1 system-ui, sans-serif;
    padding:.25rem .45rem; border-radius:999px;
    background:#111; color:#fff; opacity:.9;
  }

  /* --- Fallback texte quand l'image manque --- */
  .equip-text{
    font: 600 14px/1.2 system-ui, sans-serif;
    color:#333; text-align:center;
  }


  /* AJOUT pour modales */
  .modal-backdrop{
    position: fixed; inset: 0; background: rgba(0,0,0,.5);
    display: flex; align-items: center; justify-content: center; z-index: 1000;
  }
  .modal{
    background: #fff; padding: 1rem; border-radius: 8px; width: min(680px, 95vw);
    box-shadow: 0 10px 30px rgba(0,0,0,.2);
  }
  .content.spect{
    display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; align-items: center; justify-items: center;
  }
  .content.action{
    display: flex; align-items: center; justify-content: center; min-height: 260px; gap: .75rem;
  }
  .side{ position: relative; display: grid; gap: .25rem; justify-items: center; }
  .dice{ width: 180px; height: auto; }
  .dice-big{ width: 240px; height: auto; }
  .icon-small{ width: 120px; height: 180px; opacity: .8; }
  .icon-side{ width: 120px; height: 180px; opacity: .9; }
  .dice-wrap{ position: relative; }
  .dice-overlay{
    position: absolute; top: 50%; left: 50%;
    transform: translate(-50%, -50%);
    font: 700 42px/1 system-ui, sans-serif;
    color: #111; text-shadow: 0 1px 2px rgba(255,255,255,.6);
  }
  .result{ margin-top: .75rem; font-weight: 600; text-align: center; }
  .footer{ margin-top: .75rem; text-align: center; color: #666; }
`]
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

  // === Helpers combat ===
  get currentCombat() {
    return this.game?.currentCombat || null;
  }
  get waitingForMyRoll(): 'ATTACK'|'DEFENSE'|null {
    const r = this.currentCombat; if (!r) return null;
    if (r.attackerId === this.meId && (r.attackerRoll == null)) return 'ATTACK';
    if (r.defenderId === this.meId && (r.defenderRoll == null)) return 'DEFENSE';
    return null;
  }
  get showActionModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.waitingForMyRoll && !!this.currentCombat;
  }
  get showSpectatorModal(): boolean {
    return this.game?.phase === 'PHASE3' && !!this.currentCombat && !this.waitingForMyRoll;
  }
  getPlayer(id: string): Player | undefined {
    return this.game?.players.find(p => p.id === id);
  }
  roleColorOf(p?: Player): 'red'|'blue' {
    return (p?.role === 'VAMPIRE') ? 'red' : 'blue';
  }
  getRole(p?: Player): 'VAMPIRE'|'HUNTER'|undefined {
    return p?.role ;
  }
  diceAsset(dice: string | undefined, color: 'red'|'blue'): string {
    const d = (dice || 'D6').toLowerCase();
    return `/assets/dices/${d}-${color}.png`;
  }
  roleIcon(role: 'VAMPIRE' | 'HUNTER' | undefined, name: 'sword'|'armor'): string {
    console.log("r:", role, "n:", name)
    return `/assets/icons/${role}-${name}.png`;
  }
  getCombatResultText(): string | null {
    const r = this.currentCombat; if (!r) return null;
    const atkP = this.getPlayer(r.attackerId);
    const defP = this.getPlayer(r.defenderId);
    if (r.attackerRoll == null || r.defenderRoll == null) return null;
    const dmg = Math.max(0, r.attackerRoll - r.defenderRoll);
    const an = atkP?.username || r.attackerId;
    const dn = defP?.username || r.defenderId;
    if (dmg > 0) return `${an} inflige ${dmg} dégâts à ${dn}`;
    return `${dn} pare l’attaque de ${an}`;
  }
  rollNow(){
    if (!this.game) return;
    this.api.rollDice(this.game.id).subscribe({
      next: g => this.game = g,
      error: e => this.showError(e)
    });
  }

  // --- Assets helpers (cœurs + cartes équipement) ---
  heartIconFor(p?: Player): string {
    const role = (p?.role || 'HUNTER').toUpperCase();
    return `/assets/icons/${role}-hearth.png`;
  }
  equipWeaponImg(p?: Player): string {
    const dice = (p?.attackDice || 'D6').toLowerCase();
    return `/assets/equipment/weapon-${dice}.png`;
  }
  equipArmorImg(p?: Player): string {
    const dice = (p?.defenseDice || 'D6').toLowerCase();
    return `/assets/equipment/armor-${dice}.png`;
  }

  // --- Gestion du fallback texte si image absente ---
  private equipMissing = new Map<string, { weapon?: boolean; armor?: boolean }>();

  markEquipMissing(playerId: string, slot: 'weapon'|'armor'){
    const cur = this.equipMissing.get(playerId) || {};
    cur[slot] = true;
    this.equipMissing.set(playerId, cur);
  }
  isEquipMissing(playerId: string, slot: 'weapon'|'armor'): boolean {
    const e = this.equipMissing.get(playerId);
    return !!e && !!e[slot];
  }

  trackById(_i: number, p: Player) { return p.id; }

  // --- HP helpers (pour une jauge plus tard) ---
  maxHpOf(p: Player): number {
    if (p.role === 'VAMPIRE') {
      const hunters = (this.game?.players ?? []).filter(x => x.role === 'HUNTER').length;
      return 20 + hunters * 10;
    }
    return 20;
  }
  hpPercent(p: Player): number {
    const max = this.maxHpOf(p);
    const cur = Math.max(0, Math.min(p.hp ?? 0, max));
    return Math.round((cur / max) * 100);
  }

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
