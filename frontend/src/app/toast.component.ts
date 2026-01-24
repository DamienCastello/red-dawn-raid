import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
import { NotifyService, UiNotif } from './services/notif.service';

@Component({
  selector: 'app-toast',
  standalone: true,
  imports: [CommonModule],
  template: `
    <ng-container *ngIf="notif as n">
      <div class="toast"
           [attr.data-toast-id]="n.id"
           [class.toast--hunter]="n.tone === 'HUNTER'"
           [class.toast--vamp]="n.tone === 'VAMP'"
           [style.animationDuration]="(n.durationMs ?? 3000) + 'ms'">
        {{ n.text }}
      </div>
    </ng-container>
  `,
  styles: [`
.toast{
  position: fixed;
  right: 16px;
  left: auto;
  bottom: 16px;
  z-index: 21000;

  padding: .95rem 1.15rem;
  border-radius: 16px;

  background: linear-gradient(135deg, rgba(21,26,36,.92), rgba(21,26,36,.78));
  color: rgba(255,255,255,.96);
  border: 1px solid rgba(255,255,255,.18);
  backdrop-filter: blur(10px);
  box-shadow: 0 16px 42px rgba(0,0,0,.45);

  font: 750 15px/1.25 system-ui, sans-serif;
  max-width: min(560px, 92vw);

  animation-name: toastInOut;
  animation-timing-function: ease;
  animation-fill-mode: both;

  pointer-events: none;
  will-change: transform, opacity;
}

.toast--hunter{
  background: linear-gradient(135deg, rgba(35,110,255,.40), rgba(21,26,36,.88));
  border-color: rgba(120,180,255,.35);
}

.toast--vamp{
  background: linear-gradient(135deg, rgba(255,60,60,.38), rgba(21,26,36,.88));
  border-color: rgba(255,140,140,.35);
}

@keyframes toastInOut{
  0%{ opacity:0; transform: translateY(14px) scale(.98); }
  12%{ opacity:1; transform: translateY(0) scale(1); }
  88%{ opacity:1; transform: translateY(0) scale(1); }
  100%{ opacity:0; transform: translateY(14px) scale(.985); }
}
  `]
})
export class ToastComponent implements OnInit, OnDestroy {
  notif: UiNotif | null = null;
  private sub?: Subscription;

  constructor(private notify: NotifyService) {}

  ngOnInit() {
    this.sub = this.notify.toast$.subscribe(n => this.notif = n);
  }

  ngOnDestroy() { this.sub?.unsubscribe(); }
}
