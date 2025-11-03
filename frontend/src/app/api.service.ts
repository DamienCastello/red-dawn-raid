import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';

export interface HistoryItem {
  raid: number;
  phase: string; // "PHASE0"..."PHASE4"
  ts: number;
  text: string;
}

export interface Bite {
  id: string;
  attackerId: string;
  targetId: string;
  roll?: number | null;
  resolvedAtMillis?: number | null;
}

type Health = { status: string };

export type Phase = 'PHASE0'|'PHASE1'|'PHASE2'| 'PREPHASE3' | 'PHASE3'|'PHASE4';

export type RoundFight = {
  id: string;
  location: string;
  attackerId: string;
  defenderId: string;
  attackerRoll?: number | null;
  defenderRoll?: number | null;
  resolvedAtMillis?: number | null;
  breakdownLines?: string[];
};

export type Player = {
  id: string;
  username: string;
  role: 'VAMPIRE'|'HUNTER'|'SERVANT';
  hand: string[];
  hp: number;
  attackDice: string;
  defenseDice: string;  
  wood: number;
  herbs: number;
  stone: number;
  iron: number;
  water: number;
  gold: number;
  souls: number;
  silver: number;
  corruption: number;
};

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
  hasUpcomingCombat?: boolean;
  history?: HistoryItem[];
  // compteurs
  vampActionsLeft: number; vampActionsDiscard: number;
  hunterActionsLeft: number; hunterActionsDiscard: number;
  potionsLeft: number;    potionsDiscard: number;
  // --- Step 3 ---
  messages: string[];
  prePhaseDeadlineMillis: number;  // fin de fenêtre PREPHASE3 (ms epoch)
    // --- PHASE3 Combats ---
  combatsQueue?: RoundFight[];
  currentCombatIndex?: number | null;
  currentCombat?: RoundFight | null;
  currentCombatNextAdvanceAtMillis?: number;
  // --- METEO ---
  weatherModalNotBeforeMillis?: number;
  weatherRoll?: number|null;
  weatherStatus?: string|null;
  weatherStatusNameFr?: string|null;
  weatherDescriptionFr?: string|null;
  weatherPhaseDeadlineMillis?: number;
  weatherShowUntilMillis?: number;
  raidMods?: Record<string, StatMod[]>;  // buffs/debuffs par joueur (affichage)
  unstableEligibleTargets?: Record<string, string[]>;   // instableId -> [targetIds]
  unstableTargetByPlayer?: Record<string, string>;      // instableId -> chosenTargetId
  currentBite?: Bite | null;                            // morsure en cours (après dégâts)
};

export type BiteAttempt = {
  id: string;
  attackerId: string; // vampire
  targetId: string;   // chasseur
  location: string;
  roll?: number|null;
  resolvedAtMillis?: number|null;
};

export type StatMod = { 
  stat: 'ATTACK'|'DEFENSE'|'MULTIPLE'|'INSTABLE'|'SERVITEUR';
  amount: number;
  source: string;
  labelFr: string,
  displayOnly?: boolean;
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

  rollDice(gameId: string) {
    return this.http.post<Game>(`${this.base}/games/${gameId}/roll`, {});
  }

  rollWeather(id: string){
    return this.http.post<Game>(`${this.base}/games/${id}/weather/roll`, {});
  }

  usePotion(gameId: string, type: string){
    return this.http.post<Game>(`${this.base}/games/${gameId}/potions/use`, { type });
  }

  rollCorruption(gameId: string){
    return this.http.post<Game>(`${this.base}/games/${gameId}/corruption/roll`, {});
  }

  assignUnstableTarget(gameId: string, unstableId: string, targetId: string) {
    const params = new HttpParams()
      .set('unstableId', unstableId)
      .set('targetId', targetId);
    return this.http.post<Game>(`${this.base}/games/${gameId}/unstable/assign-target`, null, { params });
  }

  assignUnstableHarvest(gameId: string, unstableId: string, loc: string) {
    const params = new HttpParams()
      .set('unstableId', unstableId)
      .set('loc', loc);
    return this.http.post<Game>(`${this.base}/games/${gameId}/unstable/assign-harvest`, null, { params });
  }


    // Auth
  signup(username: string, password: string) {
    return this.http.post<{authToken:string, userId:string, username:string}>(`${this.base}/auth/signup`, { username, password });
  }
  login(username: string, password: string) {
    return this.http.post<{authToken:string, userId:string, username:string}>(`${this.base}/auth/login`,  { username, password });
  }
  wipeDb() {
    // petit param confirm pour éviter les clics involontaires
    return this.http.post(`${this.base}/dev/wipe?confirm=YES`, {});
  }
}
