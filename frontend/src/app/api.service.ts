import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

type Health = { status: string };

export type Phase = 'PHASE0'|'PHASE1'|'PHASE2'| 'PREPHASE3' | 'PHASE3'|'PHASE4';

export type Player = {
  id: string;
  username: string;
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

/** 
 * Détermine l’URL base de l’API selon le host courant (Option A : domaines séparés).
 * - Dev (localhost / 127.0.0.1): http://localhost:8080/api
 * - Préprod (front): red-dawn-raid-preprod.castello.ovh → back: red-dawn-raid-preprod-backend.castello.ovh
 * - Prod (front): red-dawn-raid.castello.ovh → back: red-dawn-raid-backend.castello.ovh
 */
function computeApiBase(): string {
  const host = window?.location?.host || '';
  // Dev local: Angular tourne en 4200 généralement
  if (host.includes('localhost') || host.startsWith('127.')) {
    return 'http://localhost:8080/api';
  }
  // Préprod
  if (host.includes('red-dawn-raid-preprod.castello.ovh')) {
    return 'https://red-dawn-raid-preprod-backend.castello.ovh/api';
  }
  // Prod
  if (host.includes('red-dawn-raid.castello.ovh')) {
    return 'https://red-dawn-raid-backend.castello.ovh/api';
  }
  // Fallback
  return 'http://localhost:8080/api';
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = computeApiBase();

  // Games
  health()             { return this.http.get<Health>(`${this.base}/health`); }
  listGames()          { return this.http.get<Game[]>(`${this.base}/games`); }
  createGame()         { return this.http.post<Game>(`${this.base}/games`, {}); }
  getGame(id: string)  { return this.http.get<Game>(`${this.base}/games/${id}`); }
  joinGame(id: string) {
    return this.http.post<JoinResponse>(`${this.base}/games/${id}/join`, {});
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

    // Auth
  signup(username: string, password: string) {
    return this.http.post<{authToken:string, userId:string, username:string}>(`${this.base}/auth/signup`, { username, password });
  }
  login(username: string, password: string) {
    return this.http.post<{authToken:string, userId:string, username:string}>(`${this.base}/auth/login`,  { username, password });
  }
}
