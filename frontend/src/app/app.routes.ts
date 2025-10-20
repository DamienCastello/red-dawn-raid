// app.routes.ts
import { Routes } from '@angular/router';
import { AuthComponent } from './auth.component';
import { LobbyComponent } from './lobby.component';
import { GameComponent } from './game.component';
import { authGuard } from './auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'auth' },
  { path: 'auth', component: AuthComponent },
  { path: 'lobby', component: LobbyComponent, canActivate: [authGuard] },
  { path: 'game/:id', component: GameComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: 'auth' }
];
