import { inject, Injectable, NgZone } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AuthService } from './auth.service';
import { newCorrelationId } from './auth.interceptor';

export interface StreamEvent {
  type: string;
  data: any;
}

/**
 * Cliente de Server-Sent Events con el token en la cabecera Authorization (EventSource no permite
 * cabeceras y el token nunca debe ir en la URL). Lee el stream con fetch y se reconecta con espera
 * creciente si la conexión se corta.
 */
@Injectable({ providedIn: 'root' })
export class EventStreamService {
  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  /** Abre el stream y devuelve la función para cerrarlo. */
  connect(path: string, onEvent: (event: StreamEvent) => void): () => void {
    const controller = new AbortController();
    let attempt = 0;

    const run = async (): Promise<void> => {
      while (!controller.signal.aborted) {
        try {
          const token = await firstValueFrom(this.auth.validToken());
          const response = await fetch(path, {
            headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream',
                       'X-Correlation-Id': newCorrelationId() },
            signal: controller.signal,
          });
          if (response.status === 401) {
            await firstValueFrom(this.auth.refresh());
            continue;
          }
          if (!response.ok || !response.body) {
            throw new Error(`HTTP ${response.status}`);
          }
          attempt = 0;
          await this.read(response.body, onEvent);
        } catch {
          if (controller.signal.aborted) {
            return;
          }
        }
        // Espera creciente (1 s, 2 s, 4 s... hasta 15 s) antes de reconectar
        attempt++;
        await new Promise((resolve) => setTimeout(resolve, Math.min(15_000, 1000 * 2 ** (attempt - 1))));
      }
    };
    void run();
    return () => controller.abort();
  }

  private async read(body: ReadableStream<Uint8Array>, onEvent: (event: StreamEvent) => void): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        return;
      }
      buffer += decoder.decode(value, { stream: true }).replaceAll('\r\n', '\n');
      let separator = buffer.indexOf('\n\n');
      while (separator >= 0) {
        const frame = buffer.slice(0, separator);
        buffer = buffer.slice(separator + 2);
        const event = parseFrame(frame);
        if (event) {
          this.zone.run(() => onEvent(event));
        }
        separator = buffer.indexOf('\n\n');
      }
    }
  }
}

function parseFrame(frame: string): StreamEvent | null {
  let type = 'message';
  const data: string[] = [];
  for (const line of frame.split('\n')) {
    if (line.startsWith(':')) {
      continue; // latido del servidor
    }
    const colon = line.indexOf(':');
    const field = colon >= 0 ? line.slice(0, colon) : line;
    const value = colon >= 0 ? line.slice(colon + 1).replace(/^ /, '') : '';
    if (field === 'event') {
      type = value;
    } else if (field === 'data') {
      data.push(value);
    }
  }
  if (!data.length) {
    return null;
  }
  try {
    return { type, data: JSON.parse(data.join('\n')) };
  } catch {
    return { type, data: data.join('\n') };
  }
}
