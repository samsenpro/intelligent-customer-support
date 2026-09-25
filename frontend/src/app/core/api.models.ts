/** Tipos de la API REST del backend (Spring Boot). */

export type Role = 'ADMIN' | 'SUPERVISOR' | 'AGENT' | 'CUSTOMER';
export type SenderType = 'CUSTOMER' | 'AGENT' | 'AI' | 'SYSTEM';
export type ConversationStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_CUSTOMER' | 'RESOLVED' | 'CLOSED';
export type Channel = 'WEB' | 'WHATSAPP' | 'EMAIL' | 'API';
export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING' | 'RESOLVED' | 'CLOSED';
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type Category = 'GENERAL' | 'BILLING' | 'SHIPPING' | 'PRODUCT' | 'TECHNICAL' | 'ACCOUNT' | 'SECURITY' | 'LEGAL';
export type KnowledgeType = 'FAQ' | 'ARTICLE' | 'POLICY' | 'MANUAL' | 'PRODUCT' | 'PROCEDURE';
export type KnowledgeStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type IndexStatus = 'NOT_INDEXED' | 'PENDING' | 'INDEXED' | 'FAILED';
export type NoContextAction = 'ASK_MORE_INFO' | 'HANDOFF' | 'INFORM';
export type AgentStatus = 'AVAILABLE' | 'BUSY' | 'OFFLINE';

export interface User {
  id: string;
  organizationId: string;
  organizationName: string;
  organizationSlug: string;
  email: string;
  fullName: string;
  role: Role;
  agentId: string | null;
  customerId: string | null;
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Problem {
  code: string;
  detail: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

export interface CustomerRef {
  id: string;
  fullName: string;
  email: string | null;
}

export interface AgentRef {
  id: string;
  displayName: string;
}

export interface Conversation {
  id: string;
  customer: CustomerRef | null;
  assignedAgent: AgentRef | null;
  status: ConversationStatus;
  channel: Channel;
  subject: string | null;
  aiEnabled: boolean;
  handoffReason: string | null;
  handoffAt: string | null;
  lastIntent: string | null;
  lastCategory: string | null;
  lastSentiment: string | null;
  lastAiConfidence: number | null;
  messageCount: number;
  lastMessage: { senderType: SenderType; preview: string; createdAt: string } | null;
  lastMessageAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AiSource {
  documentId: string;
  title: string;
  documentType: string;
  score: number;
}

export interface MessageAnalysis {
  intent: string;
  category: Category;
  priority: TicketPriority;
  confidence: number;
  strategy: string;
  sentiment?: string;
  sentimentConfidence?: number;
}

export interface MessageMetadata {
  analysis?: MessageAnalysis;
  ai?: {
    status: string;
    confidence: number;
    sources: AiSource[];
    model: string;
    promptVersion: string;
    degraded: boolean;
    replyId?: string;
  };
  systemEvent?: 'AI_HANDOFF_REQUESTED' | 'AI_UNAVAILABLE';
  handoffReason?: string;
  aiConfidence?: number;
  suggestion?: { suggestionId: string; edited: boolean };
}

export interface Message {
  id: string;
  conversationId: string;
  senderType: SenderType;
  senderId: string | null;
  senderName: string;
  content: string;
  metadata: MessageMetadata;
  createdAt: string;
}

export interface HandoffInfo {
  reason: string;
  handoffAt: string;
  aiConfidence: number | null;
  customer: CustomerRef | null;
  previousAiResponses: Message[];
}

export interface ConversationDetail {
  conversation: Conversation;
  messages: Message[];
  summary: string | null;
  openTicket: Ticket | null;
  handoff: HandoffInfo | null;
  aiProcessing: boolean;
}

export interface Suggestion {
  id: string;
  conversationId: string;
  suggestedResponse: string;
  confidence: number;
  sources: AiSource[];
  status: 'PENDING' | 'ACCEPTED' | 'EDITED' | 'REJECTED';
  model: string | null;
  createdAt: string;
}

export interface Ticket {
  id: string;
  conversationId: string | null;
  customer: CustomerRef | null;
  assignedAgent: AgentRef | null;
  subject: string;
  description: string;
  priority: TicketPriority;
  status: TicketStatus;
  category: Category;
  source: 'MANUAL' | 'AI_HANDOFF' | 'AUTO_CLASSIFICATION';
  createdAt: string;
  updatedAt: string;
  resolvedAt: string | null;
}

export interface TicketEvent {
  id: string;
  actorUserId: string | null;
  field: string;
  oldValue: string | null;
  newValue: string | null;
  createdAt: string;
}

export interface Customer {
  id: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  externalRef: string | null;
  hasPortalAccount: boolean;
  createdAt: string;
}

export interface Agent {
  id: string;
  userId: string;
  displayName: string;
  status: AgentStatus;
  maxActiveConversations: number;
  activeConversations: number;
}

export interface KnowledgeSummary {
  id: string;
  title: string;
  type: KnowledgeType;
  status: KnowledgeStatus;
  indexStatus: IndexStatus;
  chunkCount: number;
  contentLength: number;
  indexedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocument extends Omit<KnowledgeSummary, 'contentLength'> {
  content: string;
  embeddingModel: string | null;
  indexError: string | null;
}

export interface SearchHit {
  documentId: string;
  title: string;
  documentType: string;
  chunkIndex: number;
  content: string;
  score: number;
}

export interface AiSettings {
  autoReplyEnabled: boolean;
  confidenceThreshold: number;
  noContextAction: NoContextAction;
  maxFailedAiAnswers: number;
  sensitiveCategories: Category[];
  handoffOnUrgent: boolean;
  autoTicketEnabled: boolean;
  customerSignupEnabled: boolean;
  summaryAfterMessages: number;
}

export interface Organization {
  id: string;
  name: string;
  slug: string;
  aiSettings: AiSettings;
  createdAt: string;
}

export interface Member {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  enabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface Analytics {
  range: { from: string; to: string };
  overview: {
    totalConversations: number;
    openTickets: number;
    resolvedTickets: number;
    averageResponseTimeSeconds: number | null;
    aiHandledConversations: number;
    aiResolutionRate: number | null;
    humanHandoffRate: number | null;
    averageAiConfidence: number | null;
    totalMessages: number;
  };
  messagesPerDay: { date: string; total: number; customer: number; ai: number; agent: number }[];
  ticketsByPriority: Record<TicketPriority, number>;
  ticketsByCategory: Record<Category, number>;
}
