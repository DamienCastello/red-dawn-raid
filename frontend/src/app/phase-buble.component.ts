import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
import { NotifyService } from './services/notif.service';

@Component({
  selector: 'app-phase-bubble',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="text as t" class="phase-bubble">
      <span class="phase-bubble__inner">{{ t }}</span>
    </div>
  `,
  styles: [`
.phase-bubble{
position: fixed;
left: 50%;
top: 50%;
z-index: 1200;

/* départ bas -> centre -> bas : on se place "au centre"
    et on translate vers le bas avec --travel */
--travel: clamp(180px, 30vh, 320px);

transform: translate(-50%, calc(-50% + var(--travel)));
opacity: 0;

animation: phaseFly 4s both;
pointer-events: none;
}

.phase-bubble__inner{
display: inline-block;
padding: .55rem .9rem;
border-radius: 999px;
background: rgba(0,0,0,.55);
color: #fff;
border: 1px solid rgba(255,255,255,.16);
backdrop-filter: blur(6px);
box-shadow: 0 12px 30px rgba(0,0,0,.35);
font: 700 14px/1.2 system-ui, sans-serif;
text-align: center;
max-width: min(560px, 90vw);
}

/* Astuce : easing différent à l’aller et au retour
via animation-timing-function sur des keyframes */
@keyframes phaseFly{
0%{
    opacity: 0;
    transform: translate(-50%, calc(-50% + var(--travel)));
    animation-timing-function: cubic-bezier(.10,.90,.20,1.00); /* rapide puis ralentit */
}
18%{ opacity: 1; }

50%{
    opacity: 1;
    transform: translate(-50%, -50%);
    animation-timing-function: cubic-bezier(.70,0,.85,.25); /* repart lent puis accélère */
}

82%{ opacity: 1; }

100%{
    opacity: 0;
    transform: translate(-50%, calc(-50% + var(--travel)));
}
}
`]
})
export class PhaseBubbleComponent implements OnInit, OnDestroy {
  text: string | null = null;
  private sub?: Subscription;

  constructor(private notify: NotifyService) {}

  ngOnInit() {
    this.sub = this.notify.phase$.subscribe(n => this.text = n?.text ?? null);
  }

  ngOnDestroy() { this.sub?.unsubscribe(); }
}
