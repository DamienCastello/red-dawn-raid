import { Routes } from '@angular/router';
import { LobbyComponent } from './lobby.component';
import { GameComponent } from './game.component';

export const routes: Routes = [
  { path: '', component: LobbyComponent },
  { path: 'game/:id', component: GameComponent },
  { path: '**', redirectTo: '' }
];