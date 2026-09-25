import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Analytics, Conversation, Ticket } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { Badge } from '../shared/badge';
import { label, percent, relative } from '../shared/format';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, Badge],
  template: `
    <div class="page-header">
      <div>
        <h1>Hola, {{ firstName() }}</h1>
        <p class="muted">{{ auth.isCustomer() ? 'Bienvenido al centro de ayuda de ' + auth.user()?.organizationName
                                             : 'Resumen de la atención de hoy.' }}</p>
      </div>
      <a class="btn btn-primary" routerLink="/inbox">{{ auth.isCustomer() ? 'Ir a mis conversaciones' : 'Abrir inbox' }}</a>
    </div>

    @if (auth.isStaff()) {
      <div class="stats">
        <a class="card stat hero" routerLink="/inbox"><span>En cola de agentes</span><strong>{{ queue().length }}</strong></a>
        <a class="card stat" routerLink="/inbox"><span>Asignadas a mí</span><strong>{{ mine().length }}</strong></a>
        <a class="card stat" routerLink="/tickets"><span>Tickets abiertos</span><strong>{{ openTickets() }}</strong></a>
        @if (analytics(); as a) {
          <a class="card stat" routerLink="/analytics"><span>Resolución por la IA (30 días)</span>
            <strong>{{ percent(a.overview.aiResolutionRate) }}</strong></a>
        }
      </div>
      <div class="card">
        <h2>Esperando a un agente</h2>
        <ul class="simple-list">
          @for (c of queue(); track c.id) {
            <li><a [routerLink]="['/inbox', c.id]"><strong>{{ c.customer?.fullName }}</strong></a>
              <span class="muted small">{{ label(c.handoffReason) }} · {{ relative(c.handoffAt ?? c.lastMessageAt) }}</span></li>
          } @empty {
            <li class="muted">No hay conversaciones esperando. La IA está atendiendo al resto.</li>
          }
        </ul>
      </div>
    } @else {
      <div class="card">
        <h2>Mis tickets</h2>
        <ul class="simple-list">
          @for (t of tickets(); track t.id) {
            <li><a routerLink="/tickets" [queryParams]="{ open: t.id }">{{ t.subject }}</a> <app-badge [value]="t.status" /></li>
          } @empty {
            <li class="muted">No tienes tickets abiertos.</li>
          }
        </ul>
      </div>
    }
  `,
})
export class DashboardPage implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);

  protected readonly queue = signal<Conversation[]>([]);
  protected readonly mine = signal<Conversation[]>([]);
  protected readonly tickets = signal<Ticket[]>([]);
  protected readonly openTickets = signal(0);
  protected readonly analytics = signal<Analytics | null>(null);
  protected readonly firstName = computed(() => this.auth.user()?.fullName.split(' ')[0] ?? '');
  protected readonly label = label;
  protected readonly percent = percent;
  protected readonly relative = relative;

  ngOnInit(): void {
    if (this.auth.isStaff()) {
      this.api.conversations({ view: 'QUEUE', size: 10 }).subscribe((p) => this.queue.set(p.content));
      this.api.conversations({ view: 'MINE', size: 50 }).subscribe((p) =>
        this.mine.set(p.content.filter((c) => c.status !== 'CLOSED' && c.status !== 'RESOLVED')));
      this.api.tickets({ status: 'OPEN', size: 1 }).subscribe((p) => this.openTickets.set(p.totalElements));
      if (this.auth.canSeeAnalytics()) {
        this.api.analytics().subscribe((a) => this.analytics.set(a));
      }
    } else {
      this.api.tickets({ size: 10 }).subscribe((p) => this.tickets.set(p.content));
    }
  }
}
