import { Routes } from '@angular/router';
import { LobbyComponent } from './lobby.component';
import { GameComponent } from './game.component';
import { gameGuard } from './game.guard';

export const routes: Routes = [
  { path: '', component: LobbyComponent },
  { path: 'game/:id', component: GameComponent, canActivate: [gameGuard] },
  { path: '**', redirectTo: '' }
];