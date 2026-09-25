import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { finalize, map, Observable, of, shareReplay, tap, throwError } from 'rxjs';

import { AuthResponse, Role, User } from './api.models';

const REFRESH_TOKEN_KEY = 'supportmind.refreshToken';
const USER_KEY = 'supportmind.user';

export interface RegisterData {
  email: string;
  password: string;
  fullName: string;
  organizationName?: string;
  organizationSlug?: string;
}

/**
 * Sesión del usuario. El access token (vida corta) solo vive en memoria; el refresh token se guarda
 * en localStorage para recuperar la sesión al recargar la página. Cada refresh lo rota en el servidor.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly accessToken = signal<string | null>(null);
  private refreshInFlight: Observable<string> | null = null;

  readonly user = signal<User | null>(readStoredUser());
  readonly isAuthenticated = computed(() => this.user() !== null);
  readonly role = computed<Role | null>(() => this.user()?.role ?? null);
  readonly isCustomer = computed(() => this.role() === 'CUSTOMER');
  readonly isStaff = computed(() => this.role() !== null && this.role() !== 'CUSTOMER');
  readonly isAdmin = computed(() => this.role() === 'ADMIN');
  readonly canSeeAnalytics = computed(() => this.role() === 'ADMIN' || this.role() === 'SUPERVISOR');

  token(): string | null {
    return this.accessToken();
  }

  login(email: string, password: string): Observable<void> {
    return this.http.post<AuthResponse>('/api/v1/auth/login', { email, password }).pipe(
      tap((response) => this.store(response)),
      map(() => undefined),
    );
  }

  register(data: RegisterData): Observable<void> {
    return this.http.post<AuthResponse>('/api/v1/auth/register', data).pipe(
      tap((response) => this.store(response)),
      map(() => undefined),
    );
  }

  /** Access token vigente, renovándolo si hace falta (lo usa también la conexión SSE). */
  validToken(): Observable<string> {
    const token = this.accessToken();
    return token ? of(token) : this.refresh();
  }

  /** Renueva el access token. Las peticiones simultáneas comparten un único refresh. */
  refresh(): Observable<string> {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
    if (!refreshToken) {
      this.logout();
      return throwError(() => new Error('No active session'));
    }
    this.refreshInFlight ??= this.http.post<AuthResponse>('/api/v1/auth/refresh', { refreshToken }).pipe(
      tap({
        next: (response) => this.store(response),
        error: () => this.logout(),
      }),
      map((response) => response.accessToken),
      finalize(() => (this.refreshInFlight = null)),
      shareReplay(1),
    );
    return this.refreshInFlight;
  }

  logout(): void {
    this.accessToken.set(null);
    this.user.set(null);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    void this.router.navigate(['/login']);
  }

  private store(response: AuthResponse): void {
    this.accessToken.set(response.accessToken);
    this.user.set(response.user);
    localStorage.setItem(REFRESH_TOKEN_KEY, response.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(response.user));
  }
}

function readStoredUser(): User | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw && localStorage.getItem(REFRESH_TOKEN_KEY) ? (JSON.parse(raw) as User) : null;
  } catch {
    return null;
  }
}
