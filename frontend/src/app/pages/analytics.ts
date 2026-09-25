import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Analytics } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/errors';
import { duration, label, percent } from '../shared/format';

interface Column {
  date: string;
  x: number;
  segments: { key: string; y: number; height: number; value: number }[];
  total: number;
  customer: number;
  ai: number;
  agent: number;
}

const SERIES = [
  { key: 'customer', label: 'Cliente', color: 'var(--series-1)' },
  { key: 'ai', label: 'IA', color: 'var(--series-2)' },
  { key: 'agent', label: 'Agente', color: 'var(--series-3)' },
] as const;

const CHART = { width: 720, height: 220, top: 12, bottom: 24, left: 36 };
const BAR_MAX = 18;
const GAP = 2;

@Component({
  selector: 'app-analytics',
  imports: [FormsModule],
  template: `
    <div class="page-header">
      <div>
        <h1>Analítica</h1>
        <p class="muted">Métricas de negocio de la organización (UTC).</p>
      </div>
      <form class="filters range" (ngSubmit)="load()">
        <label>Desde<input type="date" name="from" [(ngModel)]="from"></label>
        <label>Hasta<input type="date" name="to" [(ngModel)]="to"></label>
        <button class="btn" type="submit">Aplicar</button>
      </form>
    </div>
    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    @if (data(); as d) {
      <div class="stats">
        <div class="card stat hero"><span>Conversaciones</span><strong>{{ d.overview.totalConversations }}</strong></div>
        <div class="card stat"><span>Tickets abiertos</span><strong>{{ d.overview.openTickets }}</strong></div>
        <div class="card stat"><span>Tickets resueltos</span><strong>{{ d.overview.resolvedTickets }}</strong></div>
        <div class="card stat"><span>Tiempo medio de respuesta</span><strong>{{ duration(d.overview.averageResponseTimeSeconds) }}</strong></div>
        <div class="card stat"><span>Resolución por la IA</span><strong>{{ percent(d.overview.aiResolutionRate) }}</strong>
          <small class="muted">de {{ d.overview.aiHandledConversations }} conversaciones con IA</small></div>
        <div class="card stat"><span>Derivación a humanos</span><strong>{{ percent(d.overview.humanHandoffRate) }}</strong></div>
        <div class="card stat"><span>Confianza media de la IA</span><strong>{{ percent(d.overview.averageAiConfidence) }}</strong></div>
        <div class="card stat"><span>Mensajes</span><strong>{{ d.overview.totalMessages }}</strong></div>
      </div>

      <div class="card viz">
        <h2>Mensajes por día</h2>
        <ul class="legend" aria-label="Leyenda">
          @for (s of series; track s.key) { <li><span class="swatch" [style.background]="s.color"></span>{{ s.label }}</li> }
        </ul>
        <div class="chart-wrap">
          <svg [attr.viewBox]="'0 0 ' + chart.width + ' ' + chart.height" role="img"
               aria-label="Mensajes por día, apilados por remitente" (mouseleave)="hover.set(null)">
            @for (tick of ticks(); track tick.value) {
              <line class="grid" [attr.x1]="chart.left" [attr.x2]="chart.width" [attr.y1]="tick.y" [attr.y2]="tick.y" />
              <text class="axis" [attr.x]="chart.left - 6" [attr.y]="tick.y + 4" text-anchor="end">{{ tick.value }}</text>
            }
            @for (col of columns(); track col.date; let i = $index) {
              <g (mouseenter)="hover.set(col)">
                <rect class="hit" [attr.x]="col.x - slot() / 2" [attr.y]="chart.top" [attr.width]="slot()"
                      [attr.height]="chart.height - chart.top - chart.bottom" />
                @for (seg of col.segments; track seg.key; let last = $last) {
                  <path [attr.d]="segmentPath(col.x, seg.y, seg.height, last)" [style.fill]="colorOf(seg.key)" />
                }
                @if (i % labelEvery() === 0) {
                  <text class="axis" [attr.x]="col.x" [attr.y]="chart.height - 6" text-anchor="middle">{{ col.date.slice(5) }}</text>
                }
              </g>
            }
          </svg>
          @if (hover(); as h) {
            <div class="tooltip" [style.left.%]="(h.x / chart.width) * 100">
              <strong>{{ h.date }}</strong>
              @for (s of series; track s.key) {
                <div><span class="swatch" [style.background]="s.color"></span>{{ s.label }}: {{ h[s.key] }}</div>
              }
              <div>Total: {{ h.total }}</div>
            </div>
          }
        </div>
        <details>
          <summary>Ver tabla</summary>
          <table>
            <thead><tr><th>Día</th><th>Cliente</th><th>IA</th><th>Agente</th><th>Total</th></tr></thead>
            <tbody>
              @for (day of d.messagesPerDay; track day.date) {
                @if (day.total) {
                  <tr><td>{{ day.date }}</td><td>{{ day.customer }}</td><td>{{ day.ai }}</td><td>{{ day.agent }}</td><td>{{ day.total }}</td></tr>
                }
              }
            </tbody>
          </table>
        </details>
      </div>

      <div class="grid-2">
        <div class="card viz">
          <h2>Tickets por prioridad</h2>
          @for (row of rows(d.ticketsByPriority); track row.key) {
            <div class="bar-row" [title]="label(row.key) + ': ' + row.value">
              <span>{{ label(row.key) }}</span>
              <div class="bar"><div [style.width.%]="row.width"></div></div>
              <span class="value">{{ row.value }}</span>
            </div>
          }
        </div>
        <div class="card viz">
          <h2>Tickets por categoría</h2>
          @for (row of rows(d.ticketsByCategory); track row.key) {
            <div class="bar-row" [title]="label(row.key) + ': ' + row.value">
              <span>{{ label(row.key) }}</span>
              <div class="bar"><div [style.width.%]="row.width"></div></div>
              <span class="value">{{ row.value }}</span>
            </div>
          }
        </div>
      </div>
    } @else if (!error()) {
      <p class="muted">Cargando métricas…</p>
    }
  `,
})
export class AnalyticsPage implements OnInit {
  private readonly api = inject(ApiService);

  protected readonly data = signal<Analytics | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly hover = signal<Column | null>(null);
  protected from = '';
  protected to = '';
  protected readonly series = SERIES;
  protected readonly chart = CHART;
  protected readonly label = label;
  protected readonly percent = percent;
  protected readonly duration = duration;

  private readonly max = computed(() => {
    const days = this.data()?.messagesPerDay ?? [];
    return niceMax(Math.max(1, ...days.map((d) => d.total)));
  });

  protected readonly slot = computed(() => {
    const count = this.data()?.messagesPerDay.length ?? 1;
    return (CHART.width - CHART.left) / Math.max(count, 1);
  });

  protected readonly labelEvery = computed(() => Math.ceil((this.data()?.messagesPerDay.length ?? 1) / 10));

  protected readonly ticks = computed(() => {
    const max = this.max();
    const plot = CHART.height - CHART.top - CHART.bottom;
    return [0, 0.5, 1].map((f) => ({ value: Math.round(max * f), y: CHART.top + plot * (1 - f) }));
  });

  protected readonly columns = computed<Column[]>(() => {
    const days = this.data()?.messagesPerDay ?? [];
    const plot = CHART.height - CHART.top - CHART.bottom;
    const baseline = CHART.height - CHART.bottom;
    return days.map((day, i) => {
      let y = baseline;
      const segments = SERIES.map((s) => day[s.key])
        .map((value, index) => ({ key: SERIES[index].key, value }))
        .filter((s) => s.value > 0)
        .map((s, index) => {
          const height = Math.max(1, (s.value / this.max()) * plot - (index > 0 ? GAP : 0));
          y -= height + (index > 0 ? GAP : 0);
          return { key: s.key, y, height, value: s.value };
        });
      return { date: day.date, x: CHART.left + this.slot() * (i + 0.5), segments, total: day.total,
               customer: day.customer, ai: day.ai, agent: day.agent };
    });
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.api.analytics(this.from || undefined, this.to || undefined).subscribe({
      next: (data) => {
        this.data.set(data);
        this.from = data.range.from;
        this.to = data.range.to;
        this.error.set(null);
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected colorOf(key: string): string {
    return SERIES.find((s) => s.key === key)?.color ?? 'var(--series-1)';
  }

  /** Columna con el extremo superior redondeado (4px) solo en el segmento de arriba; base recta. */
  protected segmentPath(x: number, y: number, height: number, top: boolean): string {
    const width = Math.min(BAR_MAX, this.slot() - 4);
    const left = x - width / 2;
    const r = top ? Math.min(4, height, width / 2) : 0;
    return `M${left},${y + height} V${y + r} Q${left},${y} ${left + r},${y} H${left + width - r} ` +
      `Q${left + width},${y} ${left + width},${y + r} V${y + height} Z`;
  }

  protected rows(values: Record<string, number>): { key: string; value: number; width: number }[] {
    const max = Math.max(1, ...Object.values(values));
    return Object.entries(values).map(([key, value]) => ({ key, value, width: (value / max) * 100 }));
  }
}

function niceMax(value: number): number {
  const magnitude = 10 ** Math.floor(Math.log10(value));
  for (const step of [1, 2, 5, 10]) {
    if (value <= step * magnitude) {
      return step * magnitude;
    }
  }
  return 10 * magnitude;
}
