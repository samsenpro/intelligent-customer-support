/** Formatos y etiquetas en español para la interfaz. */

const DATE_TIME = new Intl.DateTimeFormat('es', { dateStyle: 'medium', timeStyle: 'short' });
const TIME = new Intl.DateTimeFormat('es', { hour: '2-digit', minute: '2-digit' });
const RELATIVE = new Intl.RelativeTimeFormat('es', { numeric: 'auto' });

export function dateTime(value: string | null | undefined): string {
  return value ? DATE_TIME.format(new Date(value)) : '—';
}

export function time(value: string | null | undefined): string {
  return value ? TIME.format(new Date(value)) : '';
}

export function relative(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  const seconds = Math.round((new Date(value).getTime() - Date.now()) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 60) return RELATIVE.format(seconds, 'second');
  if (abs < 3600) return RELATIVE.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return RELATIVE.format(Math.round(seconds / 3600), 'hour');
  return RELATIVE.format(Math.round(seconds / 86400), 'day');
}

export function percent(value: number | null | undefined, digits = 0): string {
  return value === null || value === undefined ? '—' : `${(value * 100).toFixed(digits)} %`;
}

export function duration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  if (seconds < 60) return `${seconds.toFixed(1)} s`;
  if (seconds < 3600) return `${(seconds / 60).toFixed(1)} min`;
  return `${(seconds / 3600).toFixed(1)} h`;
}

export const LABELS: Record<string, string> = {
  // Estados de conversación y ticket
  OPEN: 'Abierta', IN_PROGRESS: 'En curso', WAITING_CUSTOMER: 'Esperando cliente', RESOLVED: 'Resuelta',
  CLOSED: 'Cerrada', WAITING: 'En espera',
  // Prioridades
  LOW: 'Baja', MEDIUM: 'Media', HIGH: 'Alta', URGENT: 'Urgente',
  // Categorías
  GENERAL: 'General', BILLING: 'Facturación', SHIPPING: 'Envíos', PRODUCT: 'Producto', TECHNICAL: 'Técnica',
  ACCOUNT: 'Cuenta', SECURITY: 'Seguridad', LEGAL: 'Legal',
  // Remitentes
  CUSTOMER: 'Cliente', AGENT: 'Agente', AI: 'IA', SYSTEM: 'Sistema',
  // Roles
  ADMIN: 'Administrador', SUPERVISOR: 'Supervisor',
  // Canales
  WEB: 'Web', WHATSAPP: 'WhatsApp', EMAIL: 'Email', API: 'API',
  // Sentimiento
  POSITIVE: 'Positivo', NEUTRAL: 'Neutral', NEGATIVE: 'Negativo',
  // Motivos de derivación
  CUSTOMER_REQUESTED_HUMAN: 'El cliente pidió un humano', LOW_CONFIDENCE: 'Confianza baja',
  SENSITIVE_CATEGORY: 'Categoría sensible', REPEATED_FAILED_ANSWERS: 'Respuestas fallidas repetidas',
  URGENT_TICKET: 'Urgente', NO_RELEVANT_CONTEXT: 'Sin información en la base de conocimiento',
  AI_UNAVAILABLE: 'IA no disponible', AGENT_TOOK_OVER: 'Un agente tomó la conversación',
  // Base de conocimiento
  DRAFT: 'Borrador', PUBLISHED: 'Publicado', ARCHIVED: 'Archivado', NOT_INDEXED: 'Sin indexar',
  PENDING: 'Indexando', INDEXED: 'Indexado', FAILED: 'Error',
  FAQ: 'FAQ', ARTICLE: 'Artículo', POLICY: 'Política', MANUAL: 'Manual', PROCEDURE: 'Procedimiento',
  // Origen de tickets
  MANUAL_SOURCE: 'Manual', AI_HANDOFF: 'Derivación de la IA', AUTO_CLASSIFICATION: 'Clasificación automática',
  // Acción sin contexto
  ASK_MORE_INFO: 'Pedir más información', HANDOFF: 'Derivar a un agente', INFORM: 'Informar que no sabe',
  // Agentes
  AVAILABLE: 'Disponible', BUSY: 'Ocupado', OFFLINE: 'Desconectado',
};

export function label(value: string | null | undefined): string {
  if (!value) return '—';
  return LABELS[value] ?? value.replaceAll('_', ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
}

/** Intención del clasificador en texto legible. */
export function intentLabel(intent: string | null | undefined): string {
  const intents: Record<string, string> = {
    GREETING: 'Saludo', GENERAL_QUESTION: 'Pregunta general', PRODUCT_INFO: 'Info de producto',
    ORDER_STATUS: 'Estado de pedido', SHIPPING_ISSUE: 'Problema de envío', REFUND_REQUEST: 'Reembolso',
    BILLING_QUESTION: 'Facturación', PAYMENT_ISSUE: 'Problema de pago', CANCELLATION: 'Cancelación',
    TECHNICAL_ISSUE: 'Problema técnico', ACCOUNT_ACCESS: 'Acceso a la cuenta', FRAUD_REPORT: 'Posible fraude',
    COMPLAINT: 'Queja', LEGAL_REQUEST: 'Solicitud legal', HUMAN_REQUEST: 'Pide un humano',
  };
  return intent ? intents[intent] ?? label(intent) : '—';
}
