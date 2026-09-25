import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Agent,
  AgentStatus,
  AiSettings,
  Analytics,
  Channel,
  Conversation,
  ConversationDetail,
  ConversationStatus,
  Customer,
  HandoffInfo,
  KnowledgeDocument,
  KnowledgeStatus,
  KnowledgeSummary,
  KnowledgeType,
  Member,
  Message,
  Organization,
  Page,
  SearchHit,
  Suggestion,
  Ticket,
  TicketEvent,
  TicketPriority,
  TicketStatus,
} from './api.models';

type Params = Record<string, string | number | boolean | null | undefined>;

/** Cliente de la API REST. Todas las rutas usan el prefijo /api/v1. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  // ---------------------------------------------------------------- conversaciones

  conversations(params: Params): Observable<Page<Conversation>> {
    return this.http.get<Page<Conversation>>('/api/v1/conversations', { params: toParams(params) });
  }

  conversation(id: string): Observable<ConversationDetail> {
    return this.http.get<ConversationDetail>(`/api/v1/conversations/${id}`);
  }

  createConversation(body: { customerId?: string; channel?: Channel; subject?: string; message?: string }) {
    return this.http.post<Conversation>('/api/v1/conversations', body);
  }

  sendMessage(conversationId: string, content: string): Observable<Message> {
    return this.http.post<Message>(`/api/v1/conversations/${conversationId}/messages`, { content }, {
      // La misma clave en un reintento nunca crea el mensaje dos veces
      headers: { 'X-Idempotency-Key': crypto.randomUUID() },
    });
  }

  updateConversation(id: string, body: { status?: ConversationStatus; aiEnabled?: boolean }) {
    return this.http.patch<Conversation>(`/api/v1/conversations/${id}`, body);
  }

  assignConversation(id: string, agentId?: string) {
    return this.http.post<Conversation>(`/api/v1/conversations/${id}/assign`, agentId ? { agentId } : {});
  }

  handoff(id: string): Observable<HandoffInfo> {
    return this.http.get<HandoffInfo>(`/api/v1/conversations/${id}/handoff`);
  }

  // ---------------------------------------------------------------- IA

  requestAiReply(id: string) {
    return this.http.post<{ status: string }>(`/api/v1/conversations/${id}/ai/reply`, {});
  }

  suggest(id: string): Observable<Suggestion> {
    return this.http.post<Suggestion>(`/api/v1/conversations/${id}/ai/suggest`, {});
  }

  acceptSuggestion(id: string, suggestionId: string, content?: string): Observable<Message> {
    return this.http.post<Message>(`/api/v1/conversations/${id}/ai/suggestions/${suggestionId}/accept`,
      content ? { content } : {});
  }

  rejectSuggestion(id: string, suggestionId: string): Observable<Suggestion> {
    return this.http.post<Suggestion>(`/api/v1/conversations/${id}/ai/suggestions/${suggestionId}/reject`, {});
  }

  // ---------------------------------------------------------------- tickets

  tickets(params: Params): Observable<Page<Ticket>> {
    return this.http.get<Page<Ticket>>('/api/v1/tickets', { params: toParams(params) });
  }

  ticket(id: string): Observable<{ ticket: Ticket; history: TicketEvent[] }> {
    return this.http.get<{ ticket: Ticket; history: TicketEvent[] }>(`/api/v1/tickets/${id}`);
  }

  createTicket(body: { conversationId?: string; customerId?: string; subject: string; description: string;
                       priority?: TicketPriority }) {
    return this.http.post<Ticket>('/api/v1/tickets', body);
  }

  updateTicket(id: string, body: { status?: TicketStatus; priority?: TicketPriority; assignedAgentId?: string;
                                   unassign?: boolean }) {
    return this.http.patch<Ticket>(`/api/v1/tickets/${id}`, body);
  }

  // ---------------------------------------------------------------- clientes y agentes

  customers(params: Params): Observable<Page<Customer>> {
    return this.http.get<Page<Customer>>('/api/v1/customers', { params: toParams(params) });
  }

  createCustomer(body: { fullName: string; email?: string; phone?: string; externalRef?: string }) {
    return this.http.post<Customer>('/api/v1/customers', body);
  }

  agents(): Observable<Agent[]> {
    return this.http.get<Agent[]>('/api/v1/agents');
  }

  myAgent(): Observable<Agent> {
    return this.http.get<Agent>('/api/v1/agents/me');
  }

  updateAgent(id: string, body: { status?: AgentStatus; maxActiveConversations?: number }) {
    return this.http.patch<Agent>(`/api/v1/agents/${id}`, body);
  }

  // ---------------------------------------------------------------- base de conocimiento

  knowledge(params: Params): Observable<Page<KnowledgeSummary>> {
    return this.http.get<Page<KnowledgeSummary>>('/api/v1/knowledge', { params: toParams(params) });
  }

  knowledgeDocument(id: string): Observable<KnowledgeDocument> {
    return this.http.get<KnowledgeDocument>(`/api/v1/knowledge/${id}`);
  }

  saveKnowledge(id: string | null, body: { title: string; content: string; type: KnowledgeType;
                                            status: KnowledgeStatus }) {
    return id ? this.http.put<KnowledgeDocument>(`/api/v1/knowledge/${id}`, body)
      : this.http.post<KnowledgeDocument>('/api/v1/knowledge', body);
  }

  reindex(id: string) {
    return this.http.post<KnowledgeDocument>(`/api/v1/knowledge/${id}/reindex`, {});
  }

  searchKnowledge(q: string): Observable<{ query: string; embeddingModel: string; results: SearchHit[] }> {
    return this.http.get<{ query: string; embeddingModel: string; results: SearchHit[] }>('/api/v1/knowledge/search',
      { params: toParams({ q, topK: 5 }) });
  }

  // ---------------------------------------------------------------- organización y analítica

  organization(): Observable<Organization> {
    return this.http.get<Organization>('/api/v1/organizations/me');
  }

  updateAiSettings(settings: AiSettings): Observable<Organization> {
    return this.http.put<Organization>('/api/v1/organizations/me/ai-settings', settings);
  }

  members(): Observable<Page<Member>> {
    return this.http.get<Page<Member>>('/api/v1/organizations/me/users', { params: toParams({ size: 100 }) });
  }

  createMember(body: { email: string; password: string; fullName: string; role: string }) {
    return this.http.post<Member>('/api/v1/organizations/me/users', body);
  }

  analytics(from?: string, to?: string): Observable<Analytics> {
    return this.http.get<Analytics>('/api/v1/analytics', { params: toParams({ from, to }) });
  }
}

function toParams(params: Params): HttpParams {
  let result = new HttpParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      result = result.set(key, String(value));
    }
  }
  return result;
}
