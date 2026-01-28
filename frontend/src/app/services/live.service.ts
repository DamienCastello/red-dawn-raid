import { Injectable } from '@angular/core';
import { Client, IMessage, IFrame, StompHeaders } from '@stomp/stompjs';
import { TradeView } from './api.service';

export type GameEvent =
  { type: 'LOBBY_UPDATED'; gameId: string; payload: { gameId: string; status: string; players: { id: string; username: string; leftGame?: boolean }[], readyForStart: string[] }; ts: number }
  | { type: 'GAME_CREATED'; gameId: string; payload: { gameId: string; status: string; players: { id: string; username: string; leftGame?: boolean }[], readyForStart: string[] }; ts: number }
  | { type: 'GAME_DELETED'; gameId: string; payload: { gameId: string }; ts: number }
  | { type: 'PHASE_CHANGED'; gameId: string; payload: { phase: string; raid: number }; ts: number }
  | { type: 'WEATHER_ROLLED'; gameId: string; payload: { roll: number; status: string; nameFr: string; descFr: string }; ts: number }
  | { type: 'LOCATION_SELECTED'; gameId: string; payload: { playerId: string; card: string }; ts: number }
  | { type: 'MESSAGE'; gameId: string; payload: { text: string }; ts: number }
  | { type: 'READY_UPDATED'; gameId: string; payload: { playerId: string; ready: number; total: number }; ts: number }
  | { type: 'RAID_MODS_UPDATED'; gameId: string; payload: { mods?: Record<string, any[]>; playerId?: string }; ts: number }
  | { type: 'CENTER_REVEALED'; gameId: string; payload: {}; ts: number }
  | { type: 'LOCATION_STARTED'; gameId: string; payload: { ownerId: string; username: string; infra: string }; ts: number }
  | { type: 'LOCATION_USED'; gameId: string; payload: { choice: 'STUDY' | 'THEFT' | 'OMEN' | 'EXPERIMENT' | 'ALCHEMY' | 'RARE_ALCHEMY' | 'EXPLOSION' | 'DEATH_DANCE' | 'SNEAK_ATTACK' | 'BLOOD_WALTZ' | 'LOOTING' | 'HEAL' | 'CORRUPT_SOULS' | 'CORRUPT' | 'PURIFY_WATER' | 'FORGE'; playerId: string; username: string; infra: string | null }; ts: number }
  | { type: 'DRAFT_UPDATED'; gameId: string; payload: { infra: string | null; choice: string | null; ownerId: string | null; monsterType: 'REVENANT' | 'GARGOYLE' | 'ABERRATION' | null; location: string | null; }; ts: number; }
  | { type: 'POTION_USED'; gameId: string; payload: { playerId: string; type: string }; ts: number }
  | { type: 'ACTION_USED'; gameId: string; payload: { playerId: string; type: string }; ts: number }
  | { type: 'INFRA_BUILT'; gameId: string; payload: { builderId: string; infra: string }; ts: number }
  | { type: 'ACTION_STARTED'; gameId: string; payload: { mode: 'NET' | 'PIT' | 'MARCHAND_BONUS_BUY'; ownerId: string; location: string; targetId: string | null }; ts: number }
  | { type: 'ACTION_ROLLED'; gameId: string; payload: { mode: 'NET' | 'PIT'; ownerId: string; targetId: string; roll: number }; ts: number }
  | { type: 'ACTION_RESOLVED'; gameId: string; payload: { mode: 'NET' | 'PIT'; ownerId: string; targetId?: string | null }; ts: number }
  | { type: 'UNSTABLE_ASSIGNED'; gameId: string; payload: { unstableId: string; kind: 'TARGET' | 'HARVEST' | 'NOTHING'; value: string }; ts: number }
  | { type: 'DICE_ROLLED'; gameId: string; payload: { roundId: string; rollerId: string; side: 'ATTACK' | 'DEFENSE'; roll: number }; ts: number }
  | { type: 'COMBAT_RESOLVED'; gameId: string; payload: { roundId: string; dmg: number; defenderId: string; defenderHp: number; breakdown: string[] }; ts: number }
  | { type: 'BITE_STARTED'; gameId: string; payload: { attackerId: string; targetId: string; location: string }; ts: number }
  | { type: 'BITE_ROLLED'; gameId: string; payload: { roll: number; attackerId: string; targetId: string; newCorruption?: number | null; becameServant: boolean; isSacredRosaryUsed: boolean; }; ts: number }
  | { type: 'BITE_RESOLVED'; gameId: string; payload: { attackerId: string; targetId: string; location: string }; ts: number }
  | { type: 'POTION_BOUGHT'; gameId: string; payload: { playerId: string; type: string; pool: number }; ts: number }
  | { type: 'ACTION_BOUGHT'; gameId: string; payload: { playerId: string; type: string; pool: number }; ts: number }
  | { type: 'STUFF_BOUGHT'; gameId: string; payload: { playerId: string; item: string; }; ts: number }
  | { type: 'SILVER_BOUGHT'; gameId: string; payload: { playerId: string; qty: number; cost: number }; ts: number }
  | { type: 'HOLY_WATER_BOUGHT'; gameId: string; payload: { playerId: string; costWater: number; costGold: number; }; ts: number }
  | { type: 'TRACKING_BOUGHT'; gameId: string; payload: { playerId: string; costGold: number; }; ts: number }
  | { type: 'RESOURCE_SOLD'; gameId: string; payload: { playerId: string; res: string; qty: number; gain: number }; ts: number }
  | { type: 'TRANSMUTED'; gameId: string; payload: { playerId: string; recipe: 'WOOD_TO_IRON' | 'IRON_TO_WOOD' | 'TRINITY_TO_SOULS' }; ts: number }
  | { type: 'TRADE_SYNC'; gameId: string; payload: TradeView; ts: number }
  | { type: 'TRADE_DELETED'; gameId: string; payload: { id: string }; ts: number }
  | { type: 'BANK_UPDATED'; gameId: string; payload: { playerId: string }; ts: number }
  | { type: 'PHASE4_READY_UPDATED'; gameId: string; payload: { playerId: string; ready: number; total: number }; ts: number };

type AnyEvent = GameEvent & { ts?: number };

@Injectable({ providedIn: 'root' })
export class LiveService {
  private client: Client;

  constructor() {
    this.client = new Client({
      webSocketFactory: () => new WebSocket(this.buildWsUrl()),
      reconnectDelay: 3000,
      onConnect: (_frame: IFrame) => { },
      onStompError: (frame: IFrame) => {
        console.error('[STOMP] Broker error', frame.headers as StompHeaders, frame.body);
      },
      onWebSocketError: (event: Event) => {
        console.error('[STOMP] WebSocket error', event);
      }
    });
  }

  /** Construit ws://…/ws ou wss://…/ws selon le contexte */
  /** Construit ws://…/ws ou wss://…/ws selon le contexte */
  private buildWsUrl(): string {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const isDevPort = location.port === '4200';
    const host = location.hostname;
    const port = isDevPort ? '8080' : (location.port || '');
    const hostPort = port ? `${host}:${port}` : host;
    return `${scheme}://${hostPort}/ws`;
  }

  /** Assure l’activation du client STOMP (sans garantir que la connexion est déjà établie) */
  private ensureActivated(): void {
    if (!this.client.active) this.client.activate();
  }

  subscribeLobby(handle: (event: GameEvent) => void): () => void {
    this.ensureActivated();
    const destination = `/topic/lobby`;
    let canceled = false;
    let unsub: (() => void) | undefined;

    const doSubscribe = () => {
      if (canceled) return;
      const sub = this.client.subscribe(destination, (msg: any) => {
        try {
          const e = JSON.parse(msg.body) as GameEvent;
          handle(e);
        } catch { }
      });
      unsub = () => { try { sub.unsubscribe(); } catch { } };
    };

    if (this.client.connected) doSubscribe();
    else {
      const prev = this.client.onConnect;
      this.client.onConnect = (frame: any) => {
        try { prev?.(frame); } catch { }
        doSubscribe();
        this.client.onConnect = prev;
      };
    }
    return () => { canceled = true; unsub?.(); };
  }

  /**
   * Abonnement au topic d'une partie.
   * - handleEvent: callback pour chaque événement
   * - opts.ignoreOlderThanTs: ignore les events dont ts <= ce timestamp
   */
  subscribeGame(
    gameId: string,
    handleEvent: (event: GameEvent) => void,
    opts?: { ignoreOlderThanTs?: number }
  ): () => void {
    this.ensureActivated();

    const destination = `/topic/games/${gameId}`;
    let canceled = false;
    let unsubscribeFn: (() => void) | undefined;

    const doSubscribe = () => {
      if (canceled) return;
      const subscription = this.client.subscribe(destination, (message: IMessage) => {
        try {
          const parsed: AnyEvent = JSON.parse(message.body);
          if (opts?.ignoreOlderThanTs && typeof parsed.ts === 'number' && parsed.ts <= opts.ignoreOlderThanTs) {
            return; // on ignore les vieux events arrivés avec retard
          }
          handleEvent(parsed as GameEvent);
        } catch (error) {
          console.warn('[WS] payload parse error', error);
        }
      });
      unsubscribeFn = () => {
        try { subscription.unsubscribe(); } catch { /* ignore */ }
      };
    };

    if (this.client.connected) {
      doSubscribe();
    } else {
      const previousOnConnect = this.client.onConnect;
      this.client.onConnect = (frame: IFrame) => {
        try { previousOnConnect?.(frame); } catch { }
        doSubscribe();
        this.client.onConnect = previousOnConnect;
      };
    }

    return () => { canceled = true; unsubscribeFn?.(); };
  }
}
