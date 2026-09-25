import { Component, computed, input } from '@angular/core';

import { label } from './format';

/** Etiqueta de estado con color según su significado (estado, prioridad, sentimiento...). */
@Component({
  selector: 'app-badge',
  template: `<span class="badge" [class]="'badge badge-' + tone()">{{ text() }}</span>`,
})
export class Badge {
  readonly value = input.required<string | null | undefined>();
  readonly text = computed(() => label(this.value()));
  readonly tone = computed(() => TONES[this.value() ?? ''] ?? 'neutral');
}

const TONES: Record<string, string> = {
  OPEN: 'info', IN_PROGRESS: 'accent', WAITING_CUSTOMER: 'neutral', WAITING: 'neutral', RESOLVED: 'ok', CLOSED: 'muted',
  LOW: 'neutral', MEDIUM: 'info', HIGH: 'warn', URGENT: 'danger',
  POSITIVE: 'ok', NEUTRAL: 'neutral', NEGATIVE: 'danger',
  PUBLISHED: 'ok', DRAFT: 'neutral', ARCHIVED: 'muted',
  INDEXED: 'ok', PENDING: 'warn', FAILED: 'danger', NOT_INDEXED: 'muted',
  AVAILABLE: 'ok', BUSY: 'warn', OFFLINE: 'muted',
  AI_HANDOFF: 'accent', AUTO_CLASSIFICATION: 'info', MANUAL: 'neutral',
};
