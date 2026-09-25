import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, of, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';

/** Correlation ID por petición: se puede seguir en los logs de Angular -> Java -> Python -> LLM. */
export function newCorrelationId(): string {
  return crypto.randomUUID().replaceAll('-', '');
}

/**
 * Añade el access token y un correlation ID a las llamadas a la API. Si no hay token (página recién
 * cargada) o el servidor responde 401 (token caducado), lo renueva y repite la petición una vez.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/')) {
    return next(request);
  }
  const traced = request.clone({ setHeaders: { 'X-Correlation-Id': newCorrelationId() } });
  if (request.url.startsWith('/api/v1/auth/') && !request.url.endsWith('/me')) {
    return next(traced);
  }
  const auth = inject(AuthService);
  const send = (token: string | null) => next(withToken(traced, token));
  const currentToken = auth.token();
  const token$ = currentToken ? of(currentToken) : auth.refresh().pipe(catchError(() => of(null)));

  return token$.pipe(
    switchMap((token) =>
      send(token).pipe(
        catchError((error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 401 && token) {
            return auth.refresh().pipe(switchMap((renewed) => send(renewed)));
          }
          if (error instanceof HttpErrorResponse && error.status === 401) {
            auth.logout();
          }
          return throwError(() => error);
        }),
      ),
    ),
  );
};

function withToken(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}
