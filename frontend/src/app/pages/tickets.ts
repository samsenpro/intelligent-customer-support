import { Component, computed, effect, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Agent, Ticket, TicketEvent, TicketPriority, TicketStatus } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/errors';
import { Badge } from '../shared/badge';
import { dateTime, label } from '../shared/format';

@Component({
  selector: 'app-tickets',
  imports: [FormsModule, RouterLink, Badge],
  template: `
    <div class="page-header">
      <div>
        <h1>{{ staff() ? 'Tickets' : 'Mis tickets' }}</h1>
        <p class="muted">Creados por el equipo, por los clientes o automáticamente por la IA.</p>
      </div>
      <button class="btn btn-primary" type="button" (click)="creating.set(!creating())">Nuevo ticket</button>
    </div>

    @if (creating()) {
      <form class="card form-grid" (ngSubmit)="create()">
        <label class="wide">Asunto<input name="subject" [(ngModel)]="form.subject" required maxlength="200"></label>
        <label class="wide">Descripción<textarea name="description" [(ngModel)]="form.description" rows="3" required maxlength="8000"></textarea></label>
        @if (staff()) {
          <label>ID del cliente<input name="customerId" [(ngModel)]="form.customerId" placeholder="UUID del cliente" required></label>
        }
        <label>Prioridad
          <select name="priority" [(ngModel)]="form.priority">
            @for (p of priorities; track p) { <option [value]="p">{{ label(p) }}</option> }
          </select>
        </label>
        <div class="form-actions"><button class="btn btn-primary" type="submit">Crear</button></div>
      </form>
    }
    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    <div class="card">
      <div class="filters">
        <select class="select-small" [(ngModel)]="status" (ngModelChange)="load()" aria-label="Estado">
          <option value="">Todos los estados</option>
          @for (s of ticketStatuses; track s) { <option [value]="s">{{ label(s) }}</option> }
        </select>
        <select class="select-small" [(ngModel)]="priority" (ngModelChange)="load()" aria-label="Prioridad">
          <option value="">Todas las prioridades</option>
          @for (p of priorities; track p) { <option [value]="p">{{ label(p) }}</option> }
        </select>
        @if (staff()) {
          <label class="checkbox"><input type="checkbox" [(ngModel)]="mine" (ngModelChange)="load()"> Asignados a mí</label>
        }
      </div>
      <div class="table-wrapper">
        <table>
          <thead><tr><th>Asunto</th>@if (staff()) { <th>Cliente</th> }<th>Prioridad</th><th>Estado</th><th>Categoría</th><th>Origen</th><th>Creado</th></tr></thead>
          <tbody>
            @for (t of tickets(); track t.id) {
              <tr class="clickable" [class.selected-row]="t.id === selected()?.ticket?.id" (click)="open(t.id)">
                <td><strong>{{ t.subject }}</strong></td>
                @if (staff()) { <td>{{ t.customer?.fullName ?? '—' }}</td> }
                <td><app-badge [value]="t.priority" /></td>
                <td><app-badge [value]="t.status" /></td>
                <td>{{ label(t.category) }}</td>
                <td>{{ label(t.source) }}</td>
                <td>{{ dateTime(t.createdAt) }}</td>
              </tr>
            } @empty {
              <tr><td class="empty" [attr.colspan]="staff() ? 7 : 6">No hay tickets.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </div>

    @if (selected(); as s) {
      <div class="card ticket-detail">
        <div class="page-header">
          <div>
            <h2>{{ s.ticket.subject }}</h2>
            <div class="chips"><app-badge [value]="s.ticket.status" /><app-badge [value]="s.ticket.priority" />
              <span class="chip">{{ label(s.ticket.category) }}</span><span class="chip">{{ label(s.ticket.source) }}</span></div>
          </div>
          <button class="btn btn-small" type="button" (click)="selected.set(null)">Cerrar</button>
        </div>
        <p class="pre">{{ s.ticket.description }}</p>
        @if (s.ticket.conversationId) {
          <p><a [routerLink]="['/inbox', s.ticket.conversationId]">Ver conversación</a></p>
        }
        @if (staff() && s.ticket.status !== 'CLOSED') {
          <div class="filters">
            <label>Estado
              <select [ngModel]="s.ticket.status" (ngModelChange)="update({ status: $event })">
                @for (st of ticketStatuses; track st) { <option [value]="st">{{ label(st) }}</option> }
              </select>
            </label>
            <label>Prioridad
              <select [ngModel]="s.ticket.priority" (ngModelChange)="update({ priority: $event })">
                @for (p of priorities; track p) { <option [value]="p">{{ label(p) }}</option> }
              </select>
            </label>
            @if (agents().length) {
              <label>Asignado a
                <select [ngModel]="s.ticket.assignedAgent?.id ?? ''" (ngModelChange)="assign($event)">
                  <option value="">Sin asignar</option>
                  @for (a of agents(); track a.id) { <option [value]="a.id">{{ a.displayName }}</option> }
                </select>
              </label>
            }
          </div>
        }
        <h3>Historial</h3>
        <ol class="history">
          @for (event of s.history; track event.id) {
            <li><span class="muted small">{{ dateTime(event.createdAt) }}</span> ·
              <strong>{{ event.field }}</strong>: {{ event.oldValue ? label(event.oldValue) + ' → ' : '' }}{{ label(event.newValue) }}
              @if (!event.actorUserId) { <span class="chip chip-ai">IA</span> }
            </li>
          }
        </ol>
      </div>
    }
  `,
})
export class TicketsPage implements OnInit {
  /** Ticket a abrir al entrar (?open=id, desde una conversación). */
  readonly open$ = input<string | undefined>(undefined, { alias: 'open' });

  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly staff = computed(() => this.auth.isStaff());
  protected readonly tickets = signal<Ticket[]>([]);
  protected readonly agents = signal<Agent[]>([]);
  protected readonly selected = signal<{ ticket: Ticket; history: TicketEvent[] } | null>(null);
  protected readonly creating = signal(false);
  protected readonly error = signal<string | null>(null);
  protected status: TicketStatus | '' = '';
  protected priority: TicketPriority | '' = '';
  protected mine = false;
  protected form = { subject: '', description: '', customerId: '', priority: 'MEDIUM' as TicketPriority };
  protected readonly ticketStatuses: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED'];
  protected readonly priorities: TicketPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
  protected readonly label = label;
  protected readonly dateTime = dateTime;

  constructor() {
    effect(() => {
      const id = this.open$();
      if (id) {
        this.open(id);
      }
    });
  }

  ngOnInit(): void {
    this.load();
    if (this.auth.canSeeAnalytics()) {
      this.api.agents().subscribe({ next: (agents) => this.agents.set(agents) });
    }
  }

  protected load(): void {
    this.api.tickets({ status: this.status, priority: this.priority, assignedToMe: this.mine || null, size: 100 })
      .subscribe({
        next: (page) => this.tickets.set(page.content),
        error: (err) => this.error.set(errorMessage(err)),
      });
  }

  protected open(id: string): void {
    this.api.ticket(id).subscribe({
      next: (detail) => this.selected.set(detail),
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected update(change: { status?: TicketStatus; priority?: TicketPriority }): void {
    const current = this.selected();
    if (current) {
      this.save(current.ticket.id, change);
    }
  }

  protected assign(agentId: string): void {
    const current = this.selected();
    if (current) {
      this.save(current.ticket.id, agentId ? { assignedAgentId: agentId } : { unassign: true });
    }
  }

  private save(id: string, change: object): void {
    this.api.updateTicket(id, change).subscribe({
      next: () => {
        this.error.set(null);
        this.open(id);
        this.load();
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected create(): void {
    this.api.createTicket({
      subject: this.form.subject,
      description: this.form.description,
      priority: this.form.priority,
      customerId: this.staff() ? this.form.customerId : undefined,
    }).subscribe({
      next: (ticket) => {
        this.creating.set(false);
        this.form = { subject: '', description: '', customerId: '', priority: 'MEDIUM' };
        this.load();
        this.open(ticket.id);
        void this.router.navigate([], { queryParams: {} });
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }
}
