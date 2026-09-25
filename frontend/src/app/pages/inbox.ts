import { Component, computed, effect, inject, input, OnDestroy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Conversation, ConversationStatus } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/errors';
import { EventStreamService } from '../core/event-stream.service';
import { label, relative } from '../shared/format';
import { ConversationPanel } from './conversation-panel';

type View = 'ALL' | 'MINE' | 'QUEUE';

@Component({
  selector: 'app-inbox',
  imports: [FormsModule, RouterLink, ConversationPanel],
  template: `
    <div class="inbox">
      <section class="inbox-list">
        <header class="inbox-header">
          <h1>{{ staff() ? 'Conversaciones' : 'Mis conversaciones' }}</h1>
          @if (!staff()) {
            <button class="btn btn-primary btn-small" type="button" (click)="composing.set(!composing())">Nueva</button>
          }
        </header>
        @if (staff()) {
          <div class="segmented" role="tablist">
            @for (option of views; track option.value) {
              <button type="button" [class.active]="view() === option.value" (click)="setView(option.value)">
                {{ option.label }}
              </button>
            }
          </div>
          <select class="select-small full" [ngModel]="status()" (ngModelChange)="setStatus($event)" aria-label="Filtrar por estado">
            <option value="">Todos los estados</option>
            @for (s of statuses; track s) { <option [value]="s">{{ label(s) }}</option> }
          </select>
        }
        @if (composing()) {
          <form class="card new-conversation" (ngSubmit)="create()">
            <input name="subject" [(ngModel)]="subject" placeholder="Asunto (opcional)" maxlength="200">
            <textarea name="message" [(ngModel)]="message" rows="3" placeholder="¿En qué te podemos ayudar?" required maxlength="4000"></textarea>
            <button class="btn btn-primary btn-small" type="submit" [disabled]="!message.trim()">Enviar</button>
          </form>
        }
        @if (error()) { <div class="alert alert-error">{{ error() }}</div> }
        <ul class="conversation-list">
          @for (c of conversations(); track c.id) {
            <li>
              <a [routerLink]="['/inbox', c.id]" [class.selected]="c.id === id()">
                <div class="row-top">
                  <strong>{{ staff() ? (c.customer?.fullName ?? 'Cliente') : (c.subject ?? 'Conversación') }}</strong>
                  <span class="muted small">{{ relative(c.lastMessageAt ?? c.createdAt) }}</span>
                </div>
                <p class="preview">
                  @if (c.lastMessage) {
                    <span class="preview-sender">{{ label(c.lastMessage.senderType) }}:</span> {{ c.lastMessage.preview }}
                  } @else {
                    <span class="muted">Sin mensajes</span>
                  }
                </p>
                <div class="row-bottom">
                  @if (c.aiEnabled) {
                    <span class="chip chip-ai">IA</span>
                  } @else if (!c.assignedAgent) {
                    <span class="chip chip-queue">En cola</span>
                  } @else {
                    <span class="chip chip-human">{{ c.assignedAgent.displayName }}</span>
                  }
                  <span class="chip">{{ label(c.status) }}</span>
                  @if (staff() && c.lastSentiment === 'NEGATIVE') { <span class="chip chip-negative">Negativo</span> }
                  @if (staff() && c.handoffReason && c.handoffReason !== 'AGENT_TOOK_OVER') {
                    <span class="chip chip-handoff" [title]="label(c.handoffReason)">Derivada</span>
                  }
                </div>
              </a>
            </li>
          } @empty {
            <li class="empty">{{ view() === 'QUEUE' ? 'No hay conversaciones esperando un agente.' : 'No hay conversaciones.' }}</li>
          }
        </ul>
      </section>

      <section class="inbox-detail">
        @if (id(); as conversationId) {
          <app-conversation-panel [conversationId]="conversationId" (changed)="refresh()" />
        } @else {
          <div class="empty-state">
            <h2>Selecciona una conversación</h2>
            <p class="muted">Las respuestas de la IA aparecen en tiempo real, con sus fuentes y su nivel de confianza.</p>
          </div>
        }
      </section>
    </div>
  `,
})
export class InboxPage implements OnDestroy {
  /** ID de la conversación abierta (parámetro de ruta). */
  readonly id = input<string>();

  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly events = inject(EventStreamService);

  protected readonly staff = computed(() => this.auth.isStaff());
  protected readonly conversations = signal<Conversation[]>([]);
  protected readonly view = signal<View>('ALL');
  protected readonly status = signal<ConversationStatus | ''>('');
  protected readonly composing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected subject = '';
  protected message = '';

  protected readonly views: { value: View; label: string }[] = [
    { value: 'ALL', label: 'Todas' },
    { value: 'MINE', label: 'Mías' },
    { value: 'QUEUE', label: 'Cola' },
  ];
  protected readonly statuses: ConversationStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED'];
  protected readonly label = label;
  protected readonly relative = relative;

  private readonly disconnect: () => void;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      this.view();
      this.status();
      this.refresh();
    });
    // Cambios en cualquier conversación visible: nuevas, derivadas, respondidas...
    this.disconnect = this.events.connect('/api/v1/inbox/events', (event) => {
      if (event.type === 'conversation.updated' || event.type === 'message.created') {
        this.scheduleRefresh();
      }
    });
  }

  ngOnDestroy(): void {
    this.disconnect();
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
    }
  }

  protected setView(view: View): void {
    this.view.set(view);
  }

  protected setStatus(status: ConversationStatus | ''): void {
    this.status.set(status);
  }

  refresh(): void {
    this.api.conversations({ view: this.view(), status: this.status(), size: 50 }).subscribe({
      next: (page) => this.conversations.set(page.content),
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  private scheduleRefresh(): void {
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
    }
    this.refreshTimer = setTimeout(() => this.refresh(), 400);
  }

  protected create(): void {
    this.api.createConversation({ subject: this.subject || undefined, message: this.message }).subscribe({
      next: (conversation) => {
        this.subject = '';
        this.message = '';
        this.composing.set(false);
        this.refresh();
        void this.router.navigate(['/inbox', conversation.id]);
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }
}
