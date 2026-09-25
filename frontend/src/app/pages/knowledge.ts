import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { KnowledgeStatus, KnowledgeSummary, KnowledgeType, SearchHit } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/errors';
import { Badge } from '../shared/badge';
import { dateTime, label, percent } from '../shared/format';

interface Editor {
  id: string | null;
  title: string;
  content: string;
  type: KnowledgeType;
  status: KnowledgeStatus;
  indexError?: string | null;
}

@Component({
  selector: 'app-knowledge',
  imports: [FormsModule, Badge],
  template: `
    <div class="page-header">
      <div>
        <h1>Base de conocimiento</h1>
        <p class="muted">Solo los documentos publicados llegan al vector store y la IA puede usarlos para responder.</p>
      </div>
      @if (admin()) {
        <button class="btn btn-primary" type="button" (click)="edit(null)">Nuevo documento</button>
      }
    </div>
    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    @if (editor(); as e) {
      <form class="card form" (ngSubmit)="save()">
        <div class="form-grid">
          <label class="wide">Título<input name="title" [(ngModel)]="e.title" required maxlength="200"></label>
          <label>Tipo
            <select name="type" [(ngModel)]="e.type">
              @for (t of types; track t) { <option [value]="t">{{ label(t) }}</option> }
            </select>
          </label>
          <label>Estado
            <select name="status" [(ngModel)]="e.status">
              @for (s of statuses; track s) { <option [value]="s">{{ label(s) }}</option> }
            </select>
          </label>
        </div>
        <label>Contenido (texto o Markdown)
          <textarea name="content" [(ngModel)]="e.content" rows="14" required></textarea>
        </label>
        @if (e.indexError) { <div class="alert alert-error">Error de indexación: {{ e.indexError }}</div> }
        <div class="row-actions">
          <button class="btn btn-primary" type="submit" [disabled]="!e.title.trim() || !e.content.trim()">Guardar</button>
          @if (e.id && e.status === 'PUBLISHED') {
            <button class="btn" type="button" (click)="reindex(e.id)">Reindexar</button>
          }
          <button class="btn" type="button" (click)="editor.set(null)">Cancelar</button>
        </div>
      </form>
    }

    <div class="grid-2 knowledge-grid">
      <div class="card">
        <h2>Documentos</h2>
        <div class="table-wrapper">
          <table>
            <thead><tr><th>Título</th><th>Tipo</th><th>Estado</th><th>Vector store</th><th>Actualizado</th></tr></thead>
            <tbody>
              @for (d of documents(); track d.id) {
                <tr [class.clickable]="admin()" (click)="admin() && edit(d.id)">
                  <td><strong>{{ d.title }}</strong><div class="muted small">{{ d.contentLength }} caracteres</div></td>
                  <td>{{ label(d.type) }}</td>
                  <td><app-badge [value]="d.status" /></td>
                  <td><app-badge [value]="d.indexStatus" />
                    @if (d.indexStatus === 'INDEXED') { <span class="muted small"> {{ d.chunkCount }} fragmentos</span> }</td>
                  <td>{{ dateTime(d.updatedAt) }}</td>
                </tr>
              } @empty {
                <tr><td class="empty" colspan="5">Aún no hay documentos.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <h2>Probar la búsqueda semántica</h2>
        <form class="search-form" (ngSubmit)="search()">
          <input name="query" [(ngModel)]="query" placeholder="¿Cuánto tarda un reembolso?" maxlength="500">
          <button class="btn" type="submit" [disabled]="!query.trim()">Buscar</button>
        </form>
        @if (embeddingModel()) { <p class="muted small">Modelo de embeddings: {{ embeddingModel() }}</p> }
        <ol class="hits">
          @for (hit of hits(); track hit.documentId + '-' + hit.chunkIndex) {
            <li>
              <div class="row-top"><strong>{{ hit.title }}</strong><span class="muted small">similitud {{ percent(hit.score) }}</span></div>
              <p class="small">{{ hit.content }}</p>
            </li>
          }
        </ol>
      </div>
    </div>
  `,
})
export class KnowledgePage implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  protected readonly admin = computed(() => this.auth.isAdmin());
  protected readonly documents = signal<KnowledgeSummary[]>([]);
  protected readonly editor = signal<Editor | null>(null);
  protected readonly hits = signal<SearchHit[]>([]);
  protected readonly embeddingModel = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected query = '';
  protected readonly types: KnowledgeType[] = ['FAQ', 'ARTICLE', 'POLICY', 'MANUAL', 'PRODUCT', 'PROCEDURE'];
  protected readonly statuses: KnowledgeStatus[] = ['DRAFT', 'PUBLISHED', 'ARCHIVED'];
  protected readonly label = label;
  protected readonly dateTime = dateTime;
  protected readonly percent = percent;

  // La indexación es asíncrona: mientras haya documentos pendientes la lista se refresca sola
  private readonly poller = setInterval(() => {
    if (this.documents().some((d) => d.indexStatus === 'PENDING')) {
      this.load();
    }
  }, 2000);

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    clearInterval(this.poller);
  }

  protected load(): void {
    this.api.knowledge({ size: 100 }).subscribe({
      next: (page) => this.documents.set(page.content),
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected edit(id: string | null): void {
    if (!id) {
      this.editor.set({ id: null, title: '', content: '', type: 'FAQ', status: 'PUBLISHED' });
      return;
    }
    this.api.knowledgeDocument(id).subscribe({
      next: (d) => this.editor.set({ id: d.id, title: d.title, content: d.content, type: d.type, status: d.status,
                                     indexError: d.indexError }),
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected save(): void {
    const e = this.editor();
    if (!e) {
      return;
    }
    this.api.saveKnowledge(e.id, { title: e.title, content: e.content, type: e.type, status: e.status }).subscribe({
      next: () => {
        this.editor.set(null);
        this.error.set(null);
        this.load();
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected reindex(id: string): void {
    this.api.reindex(id).subscribe({
      next: () => {
        this.editor.set(null);
        this.load();
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected search(): void {
    this.api.searchKnowledge(this.query.trim()).subscribe({
      next: (result) => {
        this.hits.set(result.results);
        this.embeddingModel.set(result.embeddingModel);
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }
}
