import { Injectable } from '@angular/core';
import { Subject, concat, concatMap, map, of, timer } from 'rxjs';

export interface UiNotif {
  text: string;
  durationMs?: number;
}

@Injectable({ providedIn: 'root' })
export class NotifyService {
  private phaseIn$ = new Subject<UiNotif>();
  private toastIn$ = new Subject<UiNotif>();

  /** Émet un texte, puis null quand c’est terminé (permet au composant de se cacher). */
  readonly phase$ = this.phaseIn$.pipe(
    concatMap(n => concat(
      of(n),
      timer(n.durationMs ?? 4000).pipe(map(() => null))
    ))
  );

  readonly toast$ = this.toastIn$.pipe(
    concatMap(n => concat(
      of(n),
      timer(n.durationMs ?? 6000).pipe(map(() => null))
    ))
  );

  phase(text: string, durationMs?: number) {
    this.phaseIn$.next({ text, durationMs });
  }

  toast(text: string, durationMs?: number) {
    this.toastIn$.next({ text, durationMs });
  }
}
