import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
import { NotifyService } from './services/notif.service';

@Component({
  selector: 'app-toast',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="text as t" class="toast">{{ t }}</div>
  `,
styles: [`
.toast{
  position: fixed;
  right: 16px;
  left: auto;
  bottom: 16px;

  z-index: 21000; /* <-- au-dessus des modales */

  /* plus gros */
  padding: .75rem 1rem;
  border-radius: 14px;

  background: rgba(21,26,36,.88);
  color: rgba(255,255,255,.94);
  border: 1px solid rgba(255,255,255,.14);
  backdrop-filter: blur(10px);
  box-shadow: 0 14px 34px rgba(0,0,0,.38);

  font: 650 14px/1.25 system-ui, sans-serif;
  max-width: min(520px, 92vw);

  /* plus lent */
  animation: toastInOut 6s both;

  pointer-events: none;
  will-change: transform, opacity;
}

@keyframes toastInOut{
  0%{ opacity:0; transform: translateY(12px); }
  12%{ opacity:1; transform: translateY(0); }
  88%{ opacity:1; transform: translateY(0); }
  100%{ opacity:0; transform: translateY(12px); }
}
`]
})
export class ToastComponent implements OnInit, OnDestroy {
  text: string | null = null;
  private sub?: Subscription;

  constructor(private notify: NotifyService) {}

  ngOnInit() {
    this.sub = this.notify.toast$.subscribe(n => this.text = n?.text ?? null);
  }

  ngOnDestroy() { this.sub?.unsubscribe(); }
}