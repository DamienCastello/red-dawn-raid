import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from './services/api.service';
import { AssetPreloaderService } from './services/asset-preloader.service';

@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
  <div class="page">

    <!-- Statues décoratives -->
    <img class="side side-left"
         src="/assets/auth-side.png"
         alt=""
         aria-hidden="true" />

    <img class="side side-right"
         src="/assets/auth-side.png"
         alt=""
         aria-hidden="true" />

    <header class="hero">

      <!-- Titre complet (>= 421px) -->
      <h1 class="rdr-title title-full" aria-label="Red Dawn Raid">
        <span class="fire">R</span><span class="burn">e</span><span class="burn">d</span>
        <span class="gap"></span>
        <span class="fire">D</span><span class="burn">a</span><span class="burn">w</span><span class="burn">n</span>
        <span class="gap"></span>
        <span class="fire">R</span><span class="burn">a</span><span class="burn">i</span><span class="fire">d</span>
      </h1>

      <!-- Titre compact (<= 420px) -->
      <h1 class="rdr-title title-short" aria-label="RDR">
        <span class="fire">R</span><span class="fire">D</span><span class="fire">R</span>
      </h1>

      <div class="page-name">Auth</div>
    </header>

    <main class="card">
      <form [formGroup]="form" (ngSubmit)="login()">
        <label class="lbl">User</label>
        <input formControlName="username" class="input" autocomplete="username" />
        <div *ngIf="form.controls.username.invalid && form.controls.username.touched" class="err">
          Requis
        </div>

        <label class="lbl" style="margin-top:.75rem">Password</label>
        <input type="password" formControlName="password" class="input" autocomplete="current-password" />
        <div *ngIf="form.controls.password.invalid && form.controls.password.touched" class="err">
          Requis
        </div>

        <div class="row auth-actions" style="margin-top:1rem">
          <button class="btn" type="button" (click)="signup()" [disabled]="loading">Signup</button>
          <button class="btn btn-primary" type="submit" [disabled]="loading">Login</button>
        </div>
      </form>

      <button *ngIf="isLocalDev"
              (click)="wipeAll()"
              class="btn btn-danger"
              style="margin-top:1rem">
        🧨 Vider toute la base (local)
      </button>

      <!-- =========================
       DANGER ZONE (à supprimer avant release)
       ========================= -->
      <button *ngIf="enableDangerWipe"
              (click)="wipeAllEnv()"
              class="btn btn-danger"
              style="margin-top:.75rem">
        🧨 Vider toute la base ({{ envName }})
      </button>

      <p *ngIf="error" class="err" style="margin-top:1rem">{{error}}</p>
    </main>
  </div>
  `,
  styles: [`
    @import url('https://fonts.googleapis.com/css?family=Amethysta');
    @import url('https://fonts.googleapis.com/css?family=Caesar+Dressing');

    :host, .page { box-sizing: border-box; }
    *, *::before, *::after { box-sizing: border-box; }

    :host{
      display:block;
      min-height:100vh;
      color:#fff;

      background-color: rgba(255,255,255,.03);
      background-image: linear-gradient(to bottom, #111, #0c0c0c);
      background-attachment: fixed;
    }

    .page{
      height: 100dvh;
      height: 100vh;
      padding: 2.25rem 1rem 1.5rem;
      display:flex;
      flex-direction:column;
      align-items:center;
      justify-content:flex-start;
      gap: 1.5rem;
      position: relative;
      overflow: hidden;

      /* même fond que :host */
      background-color: rgba(255,255,255,.03);
      background-image: linear-gradient(to bottom, #111, #0c0c0c);
    }

    /* --- Images statue (gauche + droite) --- */
    .side{
      position: fixed;
      bottom: -25px;
      height: 50vh;
      width: auto;
      max-height: 560px;
      opacity: .95;
      pointer-events: none;
      user-select: none;
      z-index: 1;
      filter: drop-shadow(0 14px 35px rgba(0,0,0,.6));
    }

    .side-left{
      left: 5px;
      transform: translateX(-6%);
    }

    .side-right{
      right: 5px;
      transform: scaleX(-1) translateX(-6%);
    }

    @media (max-width: 720px){
      .side{ height: 34vh; opacity: .45; }
    }
    @media (max-width: 520px){
      .side{ display:none; }
    }

    .hero{
      width:min(720px, 92vw);
      text-align:center;
      position: relative;
      z-index: 2;
    }

    .page-name{
      margin-top:.85rem;
      font-size: .95rem;
      letter-spacing: .22em;
      text-transform: uppercase;
      opacity:.85;
      position: relative;
      z-index: 2;
    }

    .card{
      width:min(400px, 92vw);
      border-radius: 14px;
      background: rgba(255,255,255,.02);
      border: 1px solid rgba(255,255,255,.10);
      padding: 1.1rem 1.15rem 1.15rem;
      box-shadow: 0 10px 30px rgba(0,0,0,.35);
      position: relative;
      z-index: 2;
    }

    .lbl{
      display:block;
      margin:.25rem 0 .25rem;
      font-size:.95rem;
      opacity:.9;
    }

    .input{
      width:95%;
      padding:.65rem .7rem;
      margin:.15rem 0 0;
      border-radius: 10px;
      border: 1px solid rgba(255,255,255,.18);
      background: rgba(0,0,0,.25);
      color:#fff;
      outline:none;
    }

    .input:focus{
      border-color: rgba(255,255,255,.35);
      box-shadow: 0 0 0 3px rgba(255,255,255,.08);
    }

    .row{
      display:flex;
      gap:.6rem;
      align-items:center;
      justify-content:space-between;
    }

    .btn{
      flex:1;
      padding:.62rem .75rem;
      border-radius: 10px;
      border: 1px solid rgba(255,255,255,.18);
      background: rgba(255,255,255,.06);
      color:#fff;
      cursor:pointer;
      transition: transform .05s ease, background .15s ease, border-color .15s ease;
      min-width: 0; /* évite des débordements bizarres */
    }

    .btn:hover{
      background: rgba(255,255,255,.09);
      border-color: rgba(255,255,255,.26);
    }
    .btn:active{ transform: translateY(1px); }

    .btn:disabled{
      opacity:.55;
      cursor:not-allowed;
    }

    .btn-primary{
      background: rgba(255,255,255,.12);
      border-color: rgba(255,255,255,.26);
    }

    .btn-danger{
      flex: initial;
      width: 100%;
      background: #b30000;
      border-color: rgba(255,255,255,.15);
    }
    .btn-danger:hover{ background:#c00000; }

    .err{
      color:#ffb4b4;
      font-size:.92rem;
      margin-top:.35rem;
    }

    /* --- TITRE + flammes --- */
    .rdr-title{
      margin:0;
      padding:.7rem 1rem;
      border-radius:14px;
      background: transparent;
      font-family: 'Amethysta', serif;
      text-align:center;
      line-height: 1.2;
      text-transform: uppercase;
      letter-spacing: .22em;
      white-space:nowrap;
      display:inline-block;
    }

    /* Taille de base du "bloc titre" (on va la réduire avec les medias) */
    .title-full{ font-size: 1.75rem; }
    .title-short{ font-size: 1.75rem; display:none; }

    .rdr-title span{
      font-family: 'Caesar Dressing', cursive;
      font-size: 2.3em;
      text-transform: lowercase;
      vertical-align: middle;
      letter-spacing: .12em;
      display:inline-block;
      margin: 0 .06em;
      color: #000;
    }

    .gap{
      width: .55em;
      margin: 0;
      letter-spacing: 0;
      color: transparent;
      text-shadow: none !important;
      animation: none !important;
    }

    .fire{ animation: fireAnim 1s ease-in-out infinite alternate; }
    .burn{ animation: fireAnim .65s ease-in-out infinite alternate; }

    @keyframes fireAnim{
      0% { text-shadow:
        0 0 20px #fefcc9,
        10px -10px 30px #feec85,
        -20px -20px 40px #ffae34,
        20px -40px 50px #ec760c,
        -20px -60px 60px #cd4606,
        0 -80px 70px #973716,
        10px -90px 80px #451b0e;
      }
      100% { text-shadow:
        0 0 20px #fefcc9,
        10px -10px 30px #fefcc9,
        -20px -20px 40px #feec85,
        22px -42px 60px #ffae34,
        -22px -58px 50px #ec760c,
        0 -82px 80px #cd4606,
        10px -90px 80px #973716;
      }
    }

    /* =========================
       RESPONSIVE
       ========================= */

    /* Vers 680px : on réduit un peu, puis un peu plus */
    @media (max-width: 680px){
      .title-full{ font-size: 1.55rem; }
      .rdr-title span{ font-size: 2.05em; }
      .rdr-title{ letter-spacing: .18em; }
      .page{ padding-top: 1.9rem; }
    }

    @media (max-width: 560px){
      .title-full{ font-size: 1.38rem; }
      .rdr-title span{ font-size: 1.9em; }
      .rdr-title{ letter-spacing: .15em; }
    }

    @media (max-width: 480px){
      .title-full{ font-size: 1.24rem; }
      .rdr-title span{ font-size: 1.75em; }
      .rdr-title{ letter-spacing: .12em; }
    }

    /* <= 420px : afficher RDR à la place du titre complet */
    @media (max-width: 420px){
      .title-full{ display:none; }
      .title-short{ display:inline-block; }

      .title-short{ font-size: 1.55rem; }
      .title-short span{ font-size: 2.2em; }
      .rdr-title{ letter-spacing: .16em; }
    }

    /* Très petit (genre 200px) : boutons sur 2 lignes */
    @media (max-width: 240px){
      .auth-actions{
        flex-direction: column;
        align-items: stretch;
      }
      .auth-actions .btn{
        width: 100%;
      }
    }
  `]
})
export class AuthComponent {
  private api = inject(ApiService);
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private assets = inject(AssetPreloaderService);

  constructor() {
    this.assets.start();
  }

  loading = false;
  error = '';

  form = this.fb.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  signup() { this.submit('signup'); }
  login() { this.submit('login'); }

  isLocalDev = location.origin.startsWith('http://localhost') || location.origin.startsWith('http://127.0.0.1');

  wipeAll() {
    if (!this.isLocalDev) return;
    const sure = confirm('⚠️ Tu es sûr ? Cela va VIDER TOUTE la base locale.');
    if (!sure) return;

    this.api.wipeDb().subscribe({
      next: () => alert('Base vidée ✅'),
      error: (e) => alert('Erreur wipe: ' + (e?.error?.message || e.message || 'inconnue'))
    });
  }

  // =========================
  // DANGER ZONE (à supprimer avant release)
  // =========================
  enableDangerWipe = false;

  envName = location.origin;

  // =========================
  // DANGER ZONE (à supprimer avant release)
  // =========================
  wipeAllEnv() {
    const env = this.envName;

    const sure1 = confirm(`⚠️ DANGER: Tu vas VIDER TOUTE LA BASE sur:\n${env}`);
    if (!sure1) return;

    const sure2 = prompt(`Tape EXACTEMENT: WIPE ${env}`);
    if (sure2 !== `WIPE ${env}`) {
      alert("Annulé (mauvaise confirmation).");
      return;
    }

    this.api.wipeDbEnv().subscribe({
      next: () => alert(`Base vidée ✅ (${env})`),
      error: (e) => alert('Erreur wipe: ' + (e?.error?.message || e.message || 'inconnue'))
    });
  }

  private submit(kind: 'signup' | 'login') {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const { username, password } = this.form.value as { username: string; password: string };
    this.loading = true; this.error = '';

    const req$ = kind === 'signup' ? this.api.signup(username, password) : this.api.login(username, password);
    req$.subscribe({
      next: (r) => {
        sessionStorage.setItem('authToken', r.authToken);
        sessionStorage.setItem('userId', r.userId);
        sessionStorage.setItem('username', r.username);
        this.router.navigateByUrl('/lobby');
      },
      error: (e) => { this.error = e?.error?.message || 'Erreur'; this.loading = false; }
    });
  }
}
