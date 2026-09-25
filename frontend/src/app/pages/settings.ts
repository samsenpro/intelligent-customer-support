import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Agent, AgentStatus, AiSettings, Category, Member, NoContextAction, Organization } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/errors';
import { Badge } from '../shared/badge';
import { dateTime, label } from '../shared/format';

@Component({
  selector: 'app-settings',
  imports: [FormsModule, Badge],
  template: `
    <div class="page-header">
      <div>
        <h1>Configuración</h1>
        <p class="muted">{{ organization()?.name }} · código para el registro de clientes: <code>{{ organization()?.slug }}</code></p>
      </div>
    </div>
    @if (message()) { <div class="alert alert-ok">{{ message() }}</div> }
    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    @if (me(); as agent) {
      <div class="card">
        <h2>Mi disponibilidad</h2>
        <div class="filters">
          @for (s of agentStatuses; track s) {
            <button type="button" class="btn btn-small" [class.btn-primary]="agent.status === s" (click)="setStatus(agent, s)">{{ label(s) }}</button>
          }
          <span class="muted small">{{ agent.activeConversations }} conversaciones activas (máximo {{ agent.maxActiveConversations }})</span>
        </div>
      </div>
    }

    @if (settings(); as s) {
      <form class="card form" (ngSubmit)="saveSettings()">
        <h2>Comportamiento de la IA</h2>
        <fieldset [disabled]="!admin()" class="settings-grid">
          <label class="checkbox"><input type="checkbox" name="autoReply" [(ngModel)]="s.autoReplyEnabled">
            La IA responde automáticamente a los clientes</label>
          <label>Umbral de confianza ({{ s.confidenceThreshold }})
            <input type="range" name="threshold" min="0" max="1" step="0.05" [(ngModel)]="s.confidenceThreshold">
            <span class="hint">Por debajo, la respuesta no se envía y la conversación pasa a un agente.</span>
          </label>
          <label>Si la base de conocimiento no tiene la respuesta
            <select name="noContext" [(ngModel)]="s.noContextAction">
              @for (a of noContextActions; track a) { <option [value]="a">{{ label(a) }}</option> }
            </select>
          </label>
          <label>Respuestas fallidas seguidas antes de derivar
            <input type="number" name="maxFailed" min="1" max="10" [(ngModel)]="s.maxFailedAiAnswers">
          </label>
          <div class="checkbox-group">
            <span>Categorías que siempre atiende un humano</span>
            @for (c of categories; track c) {
              <label class="checkbox"><input type="checkbox" [checked]="s.sensitiveCategories.includes(c)"
                                             (change)="toggleCategory(c)"> {{ label(c) }}</label>
            }
          </div>
          <label class="checkbox"><input type="checkbox" name="urgent" [(ngModel)]="s.handoffOnUrgent">
            Derivar a un humano los mensajes y tickets urgentes</label>
          <label class="checkbox"><input type="checkbox" name="autoTicket" [(ngModel)]="s.autoTicketEnabled">
            Crear tickets automáticamente al clasificar mensajes</label>
          <label class="checkbox"><input type="checkbox" name="signup" [(ngModel)]="s.customerSignupEnabled">
            Permitir que los clientes se registren en el portal</label>
          <label>Resumir conversaciones a partir de N mensajes
            <input type="number" name="summary" min="4" max="200" [(ngModel)]="s.summaryAfterMessages">
          </label>
        </fieldset>
        @if (admin()) {
          <div><button class="btn btn-primary" type="submit">Guardar configuración</button></div>
        } @else {
          <p class="muted small">Solo un administrador puede cambiar esta configuración.</p>
        }
      </form>
    }

    @if (admin()) {
      <div class="card">
        <h2>Equipo</h2>
        <form class="form-grid" (ngSubmit)="createMember()">
          <label>Nombre<input name="memberName" [(ngModel)]="member.fullName" required></label>
          <label>Email<input name="memberEmail" type="email" [(ngModel)]="member.email" required></label>
          <label>Contraseña inicial<input name="memberPassword" type="password" [(ngModel)]="member.password" required autocomplete="new-password"></label>
          <label>Rol
            <select name="memberRole" [(ngModel)]="member.role">
              <option value="AGENT">Agente</option><option value="SUPERVISOR">Supervisor</option><option value="ADMIN">Administrador</option>
            </select>
          </label>
          <div class="form-actions"><button class="btn btn-primary" type="submit">Añadir miembro</button></div>
        </form>
        <div class="table-wrapper">
          <table>
            <thead><tr><th>Nombre</th><th>Email</th><th>Rol</th><th>Último acceso</th></tr></thead>
            <tbody>
              @for (m of members(); track m.id) {
                <tr><td>{{ m.fullName }}</td><td>{{ m.email }}</td><td>{{ label(m.role) }}</td><td>{{ dateTime(m.lastLoginAt) }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }

    @if (agents().length) {
      <div class="card">
        <h2>Agentes</h2>
        <div class="table-wrapper">
          <table>
            <thead><tr><th>Agente</th><th>Estado</th><th>Carga</th></tr></thead>
            <tbody>
              @for (a of agents(); track a.id) {
                <tr><td>{{ a.displayName }}</td><td><app-badge [value]="a.status" /></td>
                  <td>{{ a.activeConversations }} / {{ a.maxActiveConversations }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }
  `,
})
export class SettingsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  protected readonly admin = computed(() => this.auth.isAdmin());
  protected readonly organization = signal<Organization | null>(null);
  protected readonly settings = signal<AiSettings | null>(null);
  protected readonly members = signal<Member[]>([]);
  protected readonly agents = signal<Agent[]>([]);
  protected readonly me = signal<Agent | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected member = { fullName: '', email: '', password: '', role: 'AGENT' };
  protected readonly categories: Category[] = ['GENERAL', 'BILLING', 'SHIPPING', 'PRODUCT', 'TECHNICAL', 'ACCOUNT',
    'SECURITY', 'LEGAL'];
  protected readonly noContextActions: NoContextAction[] = ['ASK_MORE_INFO', 'HANDOFF', 'INFORM'];
  protected readonly agentStatuses: AgentStatus[] = ['AVAILABLE', 'BUSY', 'OFFLINE'];
  protected readonly label = label;
  protected readonly dateTime = dateTime;

  ngOnInit(): void {
    this.api.organization().subscribe({
      next: (organization) => {
        this.organization.set(organization);
        this.settings.set({ ...organization.aiSettings, sensitiveCategories: [...organization.aiSettings.sensitiveCategories] });
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
    this.api.myAgent().subscribe({ next: (agent) => this.me.set(agent) });
    if (this.auth.canSeeAnalytics()) {
      this.api.agents().subscribe({ next: (agents) => this.agents.set(agents) });
    }
    if (this.admin()) {
      this.loadMembers();
    }
  }

  protected toggleCategory(category: Category): void {
    const s = this.settings();
    if (!s) {
      return;
    }
    s.sensitiveCategories = s.sensitiveCategories.includes(category)
      ? s.sensitiveCategories.filter((c) => c !== category) : [...s.sensitiveCategories, category];
  }

  protected saveSettings(): void {
    const s = this.settings();
    if (!s) {
      return;
    }
    this.api.updateAiSettings({ ...s, confidenceThreshold: Number(s.confidenceThreshold) }).subscribe({
      next: (organization) => {
        this.organization.set(organization);
        this.done('Configuración guardada.');
      },
      error: (err) => this.fail(err),
    });
  }

  protected setStatus(agent: Agent, status: AgentStatus): void {
    this.api.updateAgent(agent.id, { status }).subscribe({
      next: (updated) => this.me.set(updated),
      error: (err) => this.fail(err),
    });
  }

  protected createMember(): void {
    this.api.createMember(this.member).subscribe({
      next: () => {
        this.member = { fullName: '', email: '', password: '', role: 'AGENT' };
        this.loadMembers();
        this.done('Miembro añadido.');
      },
      error: (err) => this.fail(err),
    });
  }

  private loadMembers(): void {
    this.api.members().subscribe({ next: (page) => this.members.set(page.content) });
  }

  private done(message: string): void {
    this.error.set(null);
    this.message.set(message);
    setTimeout(() => this.message.set(null), 3000);
  }

  private fail(err: unknown): void {
    this.message.set(null);
    this.error.set(errorMessage(err));
  }
}
