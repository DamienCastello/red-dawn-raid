import { Injectable } from '@angular/core';
import { BehaviorSubject, Subject, concat, concatMap, map, of, timer } from 'rxjs';

export type ToastTone = 'HUNTER' | 'VAMP' | 'NEUTRAL';

export interface UiNotif {
  id: number;
  text: string;
  durationMs?: number;
  tone?: ToastTone;
}

@Injectable({ providedIn: 'root' })
export class NotifyService {
  private phaseIn$ = new Subject<Omit<UiNotif,'id'>>();
  private toastIn$ = new Subject<Omit<UiNotif,'id'>>();

  private phaseOut$ = new BehaviorSubject<UiNotif | null>(null);
  private toastOut$ = new BehaviorSubject<UiNotif | null>(null);

  readonly phase$ = this.phaseOut$.asObservable();
  readonly toast$ = this.toastOut$.asObservable();

  private seq = 0;

  constructor() {
    this.phaseIn$.pipe(
      concatMap(n => {
        const item: UiNotif = { id: ++this.seq, tone: 'NEUTRAL', ...n };
        return concat(
          of(item),
          timer(item.durationMs ?? 4000).pipe(map(() => null as UiNotif | null))
        );
      })
    ).subscribe(v => this.phaseOut$.next(v));

    this.toastIn$.pipe(
      concatMap(n => {
        const item: UiNotif = { id: ++this.seq, tone: 'NEUTRAL', ...n };
        return concat(
          of(item),
          timer(item.durationMs ?? 3000).pipe(map(() => null as UiNotif | null))
        );
      })
    ).subscribe(v => this.toastOut$.next(v));
  }

  phase(text: string, durationMs?: number, tone: UiNotif['tone'] = 'NEUTRAL') {
    this.phaseIn$.next({ text, durationMs, tone });
  }

  toast(text: string, durationMs?: number, tone: UiNotif['tone'] = 'NEUTRAL') {
    this.toastIn$.next({ text, durationMs, tone });
  }
}
