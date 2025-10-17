import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

type Health = { status: string };
export type Game = { id: string; status: string; round: number; players: string[] };
export type JoinResponse = { game: Game; playerId: string; playerToken: string };

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = '/api';

  health()        { return this.http.get<Health>(`${this.base}/health`); }
  listGames()     { return this.http.get<Game[]>(`${this.base}/games`); }
  createGame()    { return this.http.post<Game>(`${this.base}/games`, {}); }
  getGame(id: string) { return this.http.get<Game>(`${this.base}/games/${id}`); }
  joinGame(id: string, nickname: string) {
    return this.http.post<JoinResponse>(`${this.base}/games/${id}/join`, { nickname });
  }
  startGame(id: string) {
    return this.http.post<Game>(`${this.base}/games/${id}/start`, {}); // token ajouté par l’interceptor
  }
}