import { Component, computed, effect, ElementRef, inject, input, OnDestroy, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ConversationDetail, ConversationStatus, Message, Suggestion } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/errors';
import { EventStreamService, StreamEvent } from '../core/event-stream.service';
import { Badge } from '../shared/badge';
import { dateTime, intentLabel, label, percent, relative, time } from '../shared/format';

interface Draft {
  replyId: string;
  text: string;
}

/**
 * Conversación en tiempo real. Los mensajes llegan por SSE y la respuesta de la IA se muestra
 * fragmento a fragmento mientras el LLM la genera (ai.delta), hasta que el mensaje definitivo
 * (message.created) la sustituye.
 */
@Component({
  selector: 'app-conversation-panel',
  imports: [FormsModule, RouterLink, Badge],
  template: `
    @if (detail(); as d) {
      <section class="chat">
        <header class="chat-header">
          <div>
            <h2>{{ staff() ? (d.conversation.customer?.fullName ?? 'Cliente') : (d.conversation.subject ?? 'Conversación') }}</h2>
            <div class="chips">
              <app-badge [value]="d.conversation.status" />
              <span class="chip">{{ label(d.conversation.channel) }}</span>
              @if (d.conversation.aiEnabled) {
                <span class="chip chip-ai">Atiende la IA</span>
              } @else {
                <span class="chip chip-human">{{ d.conversation.assignedAgent ? 'Atiende ' + d.conversation.assignedAgent.displayName : 'En cola de agentes' }}</span>
              }
              @if (staff() && d.conversation.subject) {
                <span class="muted small">{{ d.conversation.subject }}</span>
              }
            </div>
          </div>
          <div class="header-actions">
            @if (staff()) {
              @if (!d.conversation.assignedAgent || d.conversation.assignedAgent.id !== auth.user()?.agentId) {
                <button class="btn btn-small" type="button" (click)="assignToMe()" [disabled]="busy()">Tomar</button>
              }
              @if (!d.conversation.aiEnabled && d.conversation.status !== 'CLOSED') {
                <button class="btn btn-small" type="button" (click)="setAi(true)" [disabled]="busy()">Devolver a la IA</button>
              }
              <button class="btn btn-small" type="button" (click)="askAi()"
                      [disabled]="busy() || aiTyping() || d.conversation.status === 'CLOSED'">Pedir respuesta a la IA</button>
              <select class="select-small" [ngModel]="d.conversation.status" (ngModelChange)="setStatus($event)"
                      aria-label="Estado de la conversación">
                @for (status of statuses; track status) {
                  <option [value]="status">{{ label(status) }}</option>
                }
              </select>
            } @else if (d.conversation.status !== 'RESOLVED' && d.conversation.status !== 'CLOSED') {
              <button class="btn btn-small" type="button" (click)="setStatus('RESOLVED')">Marcar como resuelta</button>
            }
          </div>
        </header>

        <div class="messages" #scroller>
          @for (message of messages(); track message.id) {
            @if (message.senderType === 'SYSTEM') {
              <div class="system-note" [class.system-warn]="message.metadata.systemEvent === 'AI_UNAVAILABLE'">
                @if (message.metadata.systemEvent === 'AI_UNAVAILABLE') {
                  <strong>AI temporarily unavailable</strong> ·
                }
                {{ message.content }}
                @if (staff() && message.metadata.handoffReason) {
                  <span class="small">({{ label(message.metadata.handoffReason) }})</span>
                }
              </div>
            } @else {
              <article class="bubble" [class]="'bubble bubble-' + message.senderType.toLowerCase()">
                <div class="bubble-meta">
                  <span class="sender-tag" [class]="'sender-tag tag-' + message.senderType.toLowerCase()">{{ label(message.senderType) }}</span>
                  <span class="sender-name">{{ message.senderName }}</span>
                  <time [attr.datetime]="message.createdAt" [title]="dateTime(message.createdAt)">{{ time(message.createdAt) }}</time>
                </div>
                <p>{{ message.content }}</p>
                @if (message.metadata.ai; as ai) {
                  <div class="ai-meta">
                    <span>Confianza {{ percent(ai.confidence) }}</span>
                    @if (ai.degraded) { <span class="warn-text">respuesta extractiva (LLM no disponible)</span> }
                    @if (staff() && ai.model) { <span class="muted">{{ ai.model }} · {{ ai.promptVersion }}</span> }
                  </div>
                  @if (staff() && ai.sources.length) {
                    <div class="sources">
                      <span>Fuentes:</span>
                      @for (source of ai.sources; track source.documentId) {
                        <span class="source" [title]="'Similitud ' + percent(source.score)">{{ source.title }}</span>
                      }
                    </div>
                  }
                }
                @if (staff() && message.metadata.analysis; as analysis) {
                  <div class="analysis">
                    <span class="chip">{{ intentLabel(analysis.intent) }}</span>
                    <span class="chip">{{ label(analysis.category) }}</span>
                    <app-badge [value]="analysis.priority" />
                    @if (analysis.sentiment) { <app-badge [value]="analysis.sentiment" /> }
                  </div>
                }
                @if (message.metadata.suggestion; as s) {
                  <div class="ai-meta muted">Sugerencia de la IA {{ s.edited ? 'editada' : 'aceptada' }} por el agente</div>
                }
              </article>
            }
          }
          @if (draft(); as current) {
            <article class="bubble bubble-ai streaming">
              <div class="bubble-meta"><span class="sender-tag tag-ai">IA</span><span class="sender-name">Escribiendo…</span></div>
              <p>{{ current.text }}<span class="cursor"></span></p>
            </article>
          } @else if (aiTyping()) {
            <div class="typing"><span></span><span></span><span></span> La IA está buscando en la base de conocimiento…</div>
          }
        </div>

        <form class="composer" (ngSubmit)="send()">
          <textarea name="draft" [(ngModel)]="text" rows="2" maxlength="4000" (keydown.enter)="onEnter($event)"
                    [disabled]="d.conversation.status === 'CLOSED'"
                    [placeholder]="d.conversation.status === 'CLOSED' ? 'La conversación está cerrada' : 'Escribe un mensaje…'"></textarea>
          <button class="btn btn-primary" type="submit" [disabled]="!text.trim() || sending() || d.conversation.status === 'CLOSED'">Enviar</button>
        </form>
        @if (error()) { <div class="alert alert-error">{{ error() }}</div> }
      </section>

      @if (staff()) {
        <aside class="side">
          @if (d.handoff; as h) {
            <div class="card side-card handoff">
              <h3>Derivación de la IA</h3>
              <p><strong>{{ label(h.reason) }}</strong></p>
              <dl class="facts">
                <dt>Confianza IA</dt><dd>{{ h.aiConfidence !== null ? percent(h.aiConfidence) : '—' }}</dd>
                <dt>Cuándo</dt><dd>{{ relative(h.handoffAt) }}</dd>
                <dt>Respuestas IA</dt><dd>{{ h.previousAiResponses.length }}</dd>
              </dl>
            </div>
          }

          <div class="card side-card">
            <h3>Sugerencia de respuesta</h3>
            @if (suggestion(); as s) {
              <textarea class="suggestion-text" [(ngModel)]="suggestionText" rows="6"></textarea>
              <div class="ai-meta"><span>Confianza {{ percent(s.confidence) }}</span></div>
              @if (s.sources.length) {
                <div class="sources">
                  @for (source of s.sources; track source.documentId) { <span class="source">{{ source.title }}</span> }
                </div>
              }
              <div class="row-actions">
                <button class="btn btn-primary btn-small" type="button" (click)="acceptSuggestion()" [disabled]="busy()">
                  {{ suggestionText.trim() !== s.suggestedResponse ? 'Enviar editada' : 'Enviar' }}
                </button>
                <button class="btn btn-small" type="button" (click)="rejectSuggestion()" [disabled]="busy()">Descartar</button>
              </div>
            } @else {
              <p class="muted small">La IA propone un borrador con la conversación y la base de conocimiento. Nunca se envía sin tu revisión.</p>
              <button class="btn btn-small" type="button" (click)="suggest()" [disabled]="busy()">
                {{ suggesting() ? 'Generando…' : 'Sugerir respuesta' }}
              </button>
            }
          </div>

          <div class="card side-card">
            <h3>Ticket</h3>
            @if (d.openTicket; as t) {
              <p><a [routerLink]="['/tickets']" [queryParams]="{ open: t.id }">{{ t.subject }}</a></p>
              <div class="chips"><app-badge [value]="t.status" /><app-badge [value]="t.priority" /><span class="chip">{{ label(t.category) }}</span></div>
              <p class="muted small">{{ label(t.source) }}</p>
            } @else {
              <p class="muted small">Sin ticket abierto.</p>
              <button class="btn btn-small" type="button" (click)="createTicket()" [disabled]="busy()">Crear ticket</button>
            }
          </div>

          @if (d.summary) {
            <div class="card side-card">
              <h3>Resumen</h3>
              <p class="small">{{ d.summary }}</p>
            </div>
          }

          <div class="card side-card">
            <h3>Cliente</h3>
            <p><strong>{{ d.conversation.customer?.fullName }}</strong></p>
            <p class="muted small">{{ d.conversation.customer?.email ?? 'Sin email' }}</p>
            @if (d.conversation.lastSentiment) {
              <p class="small">Último sentimiento: <app-badge [value]="d.conversation.lastSentiment" /></p>
            }
          </div>
        </aside>
      }
    } @else if (error()) {
      <div class="alert alert-error">{{ error() }}</div>
    } @else {
      <p class="muted">Cargando conversación…</p>
    }
  `,
})
export class ConversationPanel implements OnDestroy {
  readonly conversationId = input.required<string>();
  readonly changed = output<void>();

  protected readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly events = inject(EventStreamService);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  protected readonly detail = signal<ConversationDetail | null>(null);
  protected readonly messages = signal<Message[]>([]);
  protected readonly draft = signal<Draft | null>(null);
  protected readonly aiTyping = signal(false);
  protected readonly suggestion = signal<Suggestion | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly sending = signal(false);
  protected readonly suggesting = signal(false);
  protected readonly staff = computed(() => this.auth.isStaff());

  protected text = '';
  protected suggestionText = '';
  protected readonly statuses: ConversationStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED'];

  protected readonly label = label;
  protected readonly intentLabel = intentLabel;
  protected readonly percent = percent;
  protected readonly time = time;
  protected readonly dateTime = dateTime;
  protected readonly relative = relative;

  private disconnect: (() => void) | null = null;
  private reloadTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const id = this.conversationId();
      this.open(id);
    });
    // Desplaza al último mensaje cuando llegan mensajes o fragmentos nuevos
    effect(() => {
      this.messages();
      this.draft();
      queueMicrotask(() => {
        const element = this.scroller()?.nativeElement;
        if (element) {
          element.scrollTop = element.scrollHeight;
        }
      });
    });
  }

  ngOnDestroy(): void {
    this.disconnect?.();
    if (this.reloadTimer) {
      clearTimeout(this.reloadTimer);
    }
  }

  private open(id: string): void {
    this.disconnect?.();
    this.detail.set(null);
    this.messages.set([]);
    this.draft.set(null);
    this.suggestion.set(null);
    this.error.set(null);
    this.load(id);
    this.disconnect = this.events.connect(`/api/v1/conversations/${id}/events`, (event) => this.onEvent(event));
  }

  private load(id: string): void {
    this.api.conversation(id).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.messages.set(detail.messages);
        this.aiTyping.set(detail.aiProcessing && !this.draft());
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  private scheduleReload(): void {
    if (this.reloadTimer) {
      clearTimeout(this.reloadTimer);
    }
    this.reloadTimer = setTimeout(() => {
      this.load(this.conversationId());
      this.changed.emit();
    }, 250);
  }

  private onEvent(event: StreamEvent): void {
    switch (event.type) {
      case 'message.created': {
        const message = event.data as Message;
        this.messages.update((list) => list.some((m) => m.id === message.id) ? list : [...list, message]);
        if (message.senderType === 'AI' || message.senderType === 'SYSTEM') {
          this.draft.set(null);
        }
        if (message.senderType === 'CUSTOMER' && this.detail()?.conversation.aiEnabled) {
          this.aiTyping.set(true);
        }
        break;
      }
      case 'message.updated': {
        const { messageId, metadata } = event.data as { messageId: string; metadata: Message['metadata'] };
        this.messages.update((list) => list.map((m) => (m.id === messageId ? { ...m, metadata } : m)));
        break;
      }
      case 'ai.started':
        this.aiTyping.set(true);
        this.draft.set({ replyId: event.data.replyId, text: '' });
        break;
      case 'ai.delta':
        this.draft.update((current) => current && current.replyId === event.data.replyId
          ? { ...current, text: current.text + event.data.text } : { replyId: event.data.replyId, text: event.data.text });
        break;
      case 'ai.completed':
        this.draft.set(null);
        this.aiTyping.set(false);
        this.scheduleReload();
        break;
      case 'conversation.updated':
        this.scheduleReload();
        break;
    }
  }

  protected onEnter(event: Event): void {
    const keyboard = event as KeyboardEvent;
    if (!keyboard.shiftKey) {
      keyboard.preventDefault();
      this.send();
    }
  }

  protected send(): void {
    const content = this.text.trim();
    if (!content) {
      return;
    }
    this.sending.set(true);
    this.api.sendMessage(this.conversationId(), content).subscribe({
      next: (message) => {
        this.text = '';
        this.sending.set(false);
        this.messages.update((list) => list.some((m) => m.id === message.id) ? list : [...list, message]);
      },
      error: (err) => {
        this.sending.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }

  protected assignToMe(): void {
    this.run(this.api.assignConversation(this.conversationId()));
  }

  protected setAi(enabled: boolean): void {
    this.run(this.api.updateConversation(this.conversationId(), { aiEnabled: enabled }));
  }

  protected setStatus(status: ConversationStatus): void {
    this.run(this.api.updateConversation(this.conversationId(), { status }));
  }

  protected askAi(): void {
    this.aiTyping.set(true);
    this.run(this.api.requestAiReply(this.conversationId()), false);
  }

  protected createTicket(): void {
    const d = this.detail();
    const lastCustomer = [...this.messages()].reverse().find((m) => m.senderType === 'CUSTOMER');
    this.run(this.api.createTicket({
      conversationId: this.conversationId(),
      subject: d?.conversation.subject ?? 'Consulta de ' + (d?.conversation.customer?.fullName ?? 'cliente'),
      description: lastCustomer?.content ?? 'Creado desde la conversación',
    }));
  }

  protected suggest(): void {
    this.suggesting.set(true);
    this.busy.set(true);
    this.api.suggest(this.conversationId()).subscribe({
      next: (suggestion) => {
        this.suggestion.set(suggestion);
        this.suggestionText = suggestion.suggestedResponse;
        this.suggesting.set(false);
        this.busy.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err));
        this.suggesting.set(false);
        this.busy.set(false);
      },
    });
  }

  protected acceptSuggestion(): void {
    const suggestion = this.suggestion();
    if (!suggestion) {
      return;
    }
    const edited = this.suggestionText.trim() !== suggestion.suggestedResponse ? this.suggestionText.trim() : undefined;
    this.run(this.api.acceptSuggestion(this.conversationId(), suggestion.id, edited), true,
      () => this.suggestion.set(null));
  }

  protected rejectSuggestion(): void {
    const suggestion = this.suggestion();
    if (suggestion) {
      this.run(this.api.rejectSuggestion(this.conversationId(), suggestion.id), false, () => this.suggestion.set(null));
    }
  }

  private run(request: { subscribe: (observer: object) => unknown }, reload = true, done?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: () => {
        this.busy.set(false);
        done?.();
        if (reload) {
          this.scheduleReload();
        }
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.aiTyping.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }
}
