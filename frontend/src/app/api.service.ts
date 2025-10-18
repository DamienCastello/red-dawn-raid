import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

type Health = { status: string };

export type Phase = 'PHASE0'|'PHASE1'|'PHASE2'| 'PREPHASE3' | 'PHASE3'|'PHASE4';

export type Player = {
  id: string;
  nickname: string;
  role: 'VAMPIRE'|'HUNTER';
  hand: string[];
};

// Compat centre: selon ton back c’est encore "candidateId".
// On supporte les deux sans casser.
export type CenterBoard = {
  playerId: string;
  card: string;
  faceUp: boolean;
};

export type Game = {
  id: string;
  status: string;
  raid: number;
  phase: Phase;
  players: Player[];
  center: CenterBoard[];
  // compteurs
  vampActionsLeft: number; vampActionsDiscard: number;
  hunterActionsLeft: number; hunterActionsDiscard: number;
  potionsLeft: number;    potionsDiscard: number;
  // --- Step 3 ---
  messages: string[];
  prePhaseDeadlineMillis: number;  // fin de fenêtre PREPHASE3 (ms epoch)
};

export type JoinResponse = { game: Game; playerId: string; playerToken: string };

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = '/api';

  health()             { return this.http.get<Health>(`${this.base}/health`); }
  listGames()          { return this.http.get<Game[]>(`${this.base}/games`); }
  createGame()         { return this.http.post<Game>(`${this.base}/games`, {}); }
  getGame(id: string)  { return this.http.get<Game>(`${this.base}/games/${id}`); }
  joinGame(id: string, nickname: string) {
    return this.http.post<JoinResponse>(`${this.base}/games/${id}/join`, { nickname });
  }
  startGame(id: string) {
    return this.http.post<Game>(`${this.base}/games/${id}/start`, {}); // token via interceptor
  }
  selectLocation(id: string, card: string) {
    return this.http.post<Game>(`${this.base}/games/${id}/select-location`, { card });
  }
  skipPrePhase3(id: string) {
    return this.http.post<Game>(`${this.base}/games/${id}/skip`, {});
  }
}
