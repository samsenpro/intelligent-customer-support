import { HttpErrorResponse } from '@angular/common/http';

import { Problem } from './api.models';

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Email o contraseña incorrectos.',
  EMAIL_ALREADY_REGISTERED: 'Ya existe una cuenta con ese email.',
  SIGNUP_NOT_ALLOWED: 'Esta organización no permite el registro de clientes.',
  RESOURCE_NOT_FOUND: 'No existe o no tienes acceso.',
  RATE_LIMIT_EXCEEDED: 'Demasiadas solicitudes seguidas. Espera un momento e inténtalo de nuevo.',
  INVALID_STATUS_TRANSITION: 'La operación no está permitida en el estado actual.',
  CONVERSATION_CLOSED: 'La conversación está cerrada.',
  OPEN_TICKET_ALREADY_EXISTS: 'La conversación ya tiene un ticket abierto.',
  SUGGESTION_ALREADY_REVIEWED: 'La sugerencia ya fue aceptada o rechazada.',
  AI_REPLY_IN_PROGRESS: 'La IA ya está respondiendo esta conversación.',
  AI_TEMPORARILY_UNAVAILABLE: 'AI temporarily unavailable: la IA no está disponible, un agente puede continuar.',
  CUSTOMER_EMAIL_ALREADY_EXISTS: 'Ya existe un cliente con ese email.',
  CONCURRENT_MODIFICATION: 'Otro usuario modificó este elemento. Recarga e inténtalo de nuevo.',
  ACCESS_DENIED: 'No tienes permiso para realizar esta acción.',
  SERVICE_UNAVAILABLE: 'El servicio no está disponible en este momento.',
};

/** Mensaje legible a partir de un error de la API (formato RFC 7807). */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'No se pudo conectar con el servidor.';
    }
    const problem = error.error as Partial<Problem> | null;
    if (problem?.code === 'VALIDATION_FAILED' && problem.errors?.length) {
      return problem.errors.map((e) => `${e.field}: ${e.message}`).join(' · ');
    }
    if (problem?.code && MESSAGES[problem.code]) {
      return MESSAGES[problem.code];
    }
    if (problem?.detail) {
      return problem.detail;
    }
  }
  return 'Ha ocurrido un error inesperado.';
}
