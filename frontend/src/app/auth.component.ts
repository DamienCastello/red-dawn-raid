import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from './api.service';

@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
  <div class="container" style="max-width:400px;margin:4rem auto">
    <h2>Red Dawn Raid — Auth</h2>

    <form [formGroup]="form" (ngSubmit)="login()">
      <label>Username</label>
      <input formControlName="username" class="input" />
      <div *ngIf="form.controls.username.invalid && form.controls.username.touched" class="err">Requis</div>

      <label>Mot de passe</label>
      <input type="password" formControlName="password" class="input" />
      <div *ngIf="form.controls.password.invalid && form.controls.password.touched" class="err">Requis</div>

      <div style="display:flex; gap:.5rem; margin-top:1rem">
        <button type="button" (click)="signup()" [disabled]="loading">Signup</button>
        <button type="submit" [disabled]="loading">Login</button>
      </div>
    </form>

    <p *ngIf="error" class="err" style="margin-top:1rem">{{error}}</p>
  </div>
  `,
  styles: [`.input{width:100%;padding:.5rem;margin:.25rem 0}.err{color:#c00;font-size:.9rem}`]
})
export class AuthComponent {
  private api = inject(ApiService);
  private fb = inject(FormBuilder);
  private router = inject(Router);

  loading = false;
  error = '';

  form = this.fb.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  signup(){ this.submit('signup'); }
  login(){ this.submit('login'); }

  private submit(kind:'signup'|'login'){
    if (this.form.invalid){ this.form.markAllAsTouched(); return; }
    const { username, password } = this.form.value as {username:string;password:string};
    this.loading = true; this.error = '';

    const req$ = kind==='signup' ? this.api.signup(username, password) : this.api.login(username, password);
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
