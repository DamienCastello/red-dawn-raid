import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';

// === Types du snapshot (côté back) ===
export type GameSnapshot = {
  id: string; status: string; raid: number; phase: Phase;
  weather: { roll: number|null; status: string|null; nameFr: string|null; descFr: string|null } | null;

  players: Array<{
    id: string; username: string; role: Player['role'];
    hp: number; corruption: number;
    attackDice: string; defenseDice: string;
    wood: number; herbs: number; stone: number; iron: number;
    water: number; gold: number; souls: number; silver: number;
    hand: Player['hand']; actions: Player['actions'];
    potions: Player['potions']; elixirs: Player['elixirs'];
  }>;

  center: Array<{ playerId: string; card: string; faceUp: boolean }>;
  raidEffects: { [playerId: string]: RaidEffectsView };
  raidMods: Record<string, RawStatMod[]>;
  hasUpcomingCombat: boolean;
  readyForPhase3: string[];

  decks: DecksView;
  phase4DeadlineMillis?: number|null;
  readyForNextRaid?: string[];
  trades?: TradeView[];

  currentBite?: { attackerId: string; targetId: string; location: string; roll: number|null; armorRoll: number|null; resolvedAtMillis: number|null } | null;

  combatsQueue: RoundFight[];
  currentCombatIndex: number | null;
  currentCombat: RoundFight | null;

  unstableEligibleTargets?: Record<string, string[]>;
  unstableEligibleLocations?: Record<string, string[]>;
  unstableTargetByPlayer?: Record<string, string>;
  unstableHarvestLocByPlayer?: Record<string, string>;

  currentAction?: {
    mode: 'NET' | 'PIT';
    ownerId: string;
    location: string;
    targetId: string | null;
    roll: number | null;
    breakdownLines: string[];
    resolvedAtMillis: number | null;
  } | null;

  garlicBlockedLocations: string[];
  campfireLocations: string[];
  netHunters: string[];
  pitHunters: string[];
  builtInfras: ('SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE')[];

  locationEffectPending: boolean;
  locationEffectChoice: 'STUDY' | 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'ALCHEMY' | 'RARE_ALCHEMY' | 'EXPLOSION' | 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING' | 'HEAL' | 'CORRUPT_SOULS' | 'CORRUPT' | 'PURIFY_WATER' | 'FORGE' | null;
  locationEffectOwnerId: string | null;
  locationEffectInfra: 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE' | null;
  libraryOmenCards: string[] | null;
  monsters?: Monster[];
  ballroomBloodWaltz: boolean;
  ballroomWaltzRolls: number[];
  ballroomWaltzBest: number;
  altarCorrupted: boolean;

  history: Array<{ ts: number; raid: number; phase: Phase; text: string }>;
  messages: string[];
  ts: number; whoami: string;
};

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
  armorRoll?: number | null;
  resolvedAtMillis?: number | null;
}

type Health = { status: string };

export type Phase = 'PHASE0'|'PHASE1'|'PHASE2'|'PREPHASE3'|'PHASE3'|'PHASE4';

export interface RaidEffectsView {
  focus: boolean;
  leech: boolean;
}

export type RoundFight = {
  id: string;
  location: string;
  attackerId: string;
  defenderId: string;
  attackerRoll: number | null;
  defenderRoll: number | null;
  attackerFirstRoll: number | null;
  defenderFirstRoll: number | null;
  attackerReroll?: number | null;
  defenderReroll?: number | null;
  resolvedAtMillis: number | null;
  breakdownLines: string[];
};

export type Player = {
  id: string;
  username: string;
  role: 'VAMPIRE'|'HUNTER'|'SERVANT';
  hand: string[];
  potions: string[];
  elixirs: string[];
  actions: string[];
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

type Monster = {
    id: string;
    type: 'REVENANT'|'GARGOYLE'|'ABERRATION';
    hp: number;
    attackDice: string;
    defenseDice: string;
    location: string;
  };

export type CenterBoard = {
  playerId: string;
  card: string;
  faceUp: boolean;
};

// --- Types Phase 4 --- //
export type TradeStatus = 'PENDING'|'CONFIRMED'|'REFUSED'|'CANCELLED';
export type TradeSide   = 'HUNTERS'|'VAMP_SIDE';

export interface TradeView {
  id: string;
  side: TradeSide;
  aId: string;
  bId: string;
  offerA: Record<string, number>;
  offerB: Record<string, number>;
  statusA: TradeStatus;
  statusB: TradeStatus;
  updatedAt: number;
}

export interface Pile {
  deck: number;
  discard: number;
}

export interface DecksView {
  actionsVamp: Pile;
  actionsHunters: Pile;
  potions: Pile;
  elixirs: Pile;
}

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
  prePhaseDeadlineMillis: number;                       // fin de fenêtre PREPHASE3 (ms epoch)
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
  raidMods: Record<string, RawStatMod[]>;               // buffs/debuffs par joueur (affichage)
  unstableEligibleTargets?: Record<string, string[]>;   // instableId -> [targetIds]
  unstableTargetByPlayer?: Record<string, string>;      // instableId -> chosenTargetId
  currentBite?: Bite | null;                            // morsure en cours (après dégâts)
};

export type BiteAttempt = {
  id: string;
  attackerId: string;
  targetId: string;
  location: string;
  roll?: number|null;
  resolvedAtMillis?: number|null;
};

export type RawStatMod = {
  stat: 'ATTACK'|'DEFENSE'|'MULTIPLE'|'INSTABLE'|'SERVITEUR'|'FOCALISATION';
  amount: number;
  source: string;
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
  
  getGame(id: string) {
    return this.http.get<GameSnapshot>(`${this.base}/games/${id}`);
  }

  joinGame(id: string) {
    return this.http.post<JoinResponse>(`${this.base}/games/${id}/join`, {});
  }
  startGame(id: string) {
    return this.http.post<Game>(`${this.base}/games/${id}/start`, {}); // token via interceptor
  }

  selectLocation(id: string, card: string) {
    return this.http.post<void>(`${this.base}/games/${id}/select-location`, { card });
  }
  
  skipPrePhase3(id: string) {
    return this.http.post<void>(`${this.base}/games/${id}/skip`, {});
  }

  rollDice(gameId: string) {
    return this.http.post<void>(`${this.base}/games/${gameId}/roll`, {});
  }

  combatContinue(gameId: string) {
    return this.http.post<void>(`${this.base}/games/${gameId}/combat/continue`, {});
  }

  rollWeather(id: string){
    return this.http.post<void>(`${this.base}/games/${id}/weather/roll`, {});
  }

  usePotion(gameId: string, type: string){
    return this.http.post<void>(`${this.base}/games/${gameId}/potions/use`, { type });
  }

  useAction(gameId: string, type: string){
    return this.http.post<void>(`${this.base}/games/${gameId}/actions/use`, { type });
  }

  setNetTarget(gameId: string, targetId: string) {
    const params = new HttpParams().set('targetId', targetId);
    return this.http.post<void>(`${this.base}/games/${gameId}/actions/net/target`, null, { params });
  }

  resolveNet(gameId: string, targetId: string) {
    const params = new HttpParams().set('targetId', targetId);
    return this.http.post<void>(`${this.base}/games/${gameId}/actions/net/resolve`, null, { params });
  }

  resolvePit(gameId: string) {
    return this.http.post<void>(`${this.base}/games/${gameId}/actions/pit/resolve`, null);
  }

  rollCorruption(gameId: string){
    return this.http.post<void>(`${this.base}/games/${gameId}/corruption/roll`, {});
  }

  assignUnstableTarget(gameId: string, unstableId: string, targetId: string) {
    const params = new HttpParams()
      .set('unstableId', unstableId)
      .set('targetId', targetId);
    return this.http.post<void>(`${this.base}/games/${gameId}/unstable/assign-target`, null, { params });
  }

  assignUnstableHarvest(gameId: string, unstableId: string, loc: string) {
    const params = new HttpParams()
      .set('unstableId', unstableId)
      .set('loc', loc);
    return this.http.post<void>(`${this.base}/games/${gameId}/unstable/assign-harvest`, null, { params });
  }

  assignUnstableNothing(gameId: string, unstableId: string) {
    const params = new HttpParams().set('unstableId', unstableId);
    return this.http.post<void>(`${this.base}/games/${gameId}/unstable/assign-nothing`, null, { params });
  }

  advancePhase(id: string, to: 'PHASE0'|'PHASE1'|'PHASE2'|'PREPHASE3'|'PHASE3'|'PHASE4') {
    return this.http.post<void>(`${this.base}/games/${id}/advance?to=${to}`, {});
  }

  // -------- Phase 4: actions joueur --------
  buyPotion(gameId: string) {
    return this.http.post<void>(`${this.base}/games/${gameId}/shop/buy-potion`, {});
  }

  buyAction(gameId: string) {
    return this.http.post<void>(`${this.base}/games/${gameId}/shop/buy-action`, {});
  }

  buySilver(gameId: string, qty = 1) {
    return this.http.post<void>(`${this.base}/games/${gameId}/shop/buy-silver?qty=${qty}`, {});
  }

  sellResource(gameId: string, res: 'wood'|'herbs'|'stone'|'iron'|'water', qty = 1) {
    return this.http.post<void>(`${this.base}/games/${gameId}/shop/sell`, { res, qty });
  }

  finishPhase4(gameId: string) {
    return this.http.post<void>(`${this.base}/games/${gameId}/phase4/finish`, {});
  }

  transmute(gameId: string, recipe: 'WOOD_TO_IRON'|'IRON_TO_WOOD'|'TRINITY_TO_SOULS') {
    return this.http.post<void>(`${this.base}/games/${gameId}/transmutation/do`, { recipe });
  }

  // -------- Trades --------
  tradeOffer(gameId: string, targetId: string, offer: Record<string, number>) {
    return this.http.post<void>(`${this.base}/games/${gameId}/trade/offer`, { targetId, offer });
  }

  tradeAction(gameId: string, action: 'confirm'|'refuse'|'cancel', targetId: string) {
    return this.http.post<void>(`${this.base}/games/${gameId}/trade/${action}?targetId=${targetId}`, {});
  }

  planConstruction(gameId: string, infra: 'SAWMILL' | 'MINE' | 'LIBRARY' | 'LABORATORY' | 'BALLROOM' | 'ALTAR' | 'FORGE') {
    const params = new HttpParams().set('infra', infra);
    return this.http.post<void>(`${this.base}/games/${gameId}/plan-construction`, null, { params });
  }

  chooseLocationEffect(gameId: string, 
    choice: 
      'STUDY' 
      | 'THEFT' 
      | 'OMEN' 
      | 'EXPERIMENT' 
      | 'ALCHEMY' 
      | 'RARE_ALCHEMY' 
      | 'EXPLOSION' 
      | 'DEATH_DANCE'
      | 'SNEAK_ATTACK'
      | 'BLOOD_WALTZ'
      | 'LOOTING'
      | 'HEAL' 
      | 'CORRUPT_SOULS' 
      | 'CORRUPT' 
      | 'PURIFY_WATER'
      | 'FORGE') {
    const params = new HttpParams().set('choice', choice);
    return this.http.post<void>(`${this.base}/games/${gameId}/location-effect`, null, { params });
  }

  resolveLibraryTheft(gameId: string, targetId: string, slotIndex: number) {
    return this.http.post<void>(
      `${this.base}/games/${gameId}/effect-theft`,
      { targetId, slotIndex }
    );
  }

  resolveLibraryOmen(gameId: string, placements: ('TOP' | 'BOTTOM')[]) {
    return this.http.post<void>(
      `${this.base}/games/${gameId}/effect-omen`,
      { placements }
    );
  }

  resolveLaboratoryExperiment(
    gameId: string,
    monsterType: 'REVENANT'|'GARGOYLE'|'ABERRATION',
    location: string
  ) {
    return this.http.post<void>(
      `${this.base}/games/${gameId}/effect-experiment`,
      { type: monsterType, location }
    );
  }

  resolveAltarHeal(gameId: string, targetId: string) {
    const params = new HttpParams().set('targetId', targetId);
    return this.http.post<void>(
      `${this.base}/games/${gameId}/effect-heal`,
      null,
      { params }
    );
  }

  resolveAltarCorrupt(gameId: string, targetId: string) {
    const params = new HttpParams().set('targetId', targetId);
    return this.http.post<void>(
      `${this.base}/games/${gameId}/effect-corrupt`,
      null,
      { params }
    );
  }

  resolveForge(gameId: string, equipCode: string) {
    return this.http.post<void>(
      `${this.base}/games/${gameId}/effect-forge`,
      { equipCode }
    );
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
