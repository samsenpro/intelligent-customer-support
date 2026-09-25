import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/errors';

type Mode = 'login' | 'organization' | 'customer';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <div class="auth-page">
      <div class="card auth-card">
        <h1 class="brand-title">SupportMind <span>AI</span></h1>
        <p class="muted">Atención al cliente con IA, RAG y agentes humanos.</p>
        <div class="tabs" role="tablist">
          <button type="button" role="tab" [class.active]="mode() === 'login'" (click)="switch('login')">Entrar</button>
          <button type="button" role="tab" [class.active]="mode() === 'organization'"
                  (click)="switch('organization')">Nueva empresa</button>
          <button type="button" role="tab" [class.active]="mode() === 'customer'"
                  (click)="switch('customer')">Soy cliente</button>
        </div>

        <form class="form" (ngSubmit)="submit()">
          @if (mode() !== 'login') {
            <label>Nombre completo
              <input name="fullName" [(ngModel)]="fullName" required maxlength="120" autocomplete="name">
            </label>
          }
          @if (mode() === 'organization') {
            <label>Nombre de la empresa
              <input name="organizationName" [(ngModel)]="organizationName" required maxlength="120">
            </label>
          }
          @if (mode() === 'customer') {
            <label>Código de la empresa
              <input name="organizationSlug" [(ngModel)]="organizationSlug" required placeholder="acme-store">
            </label>
          }
          <label>Email
            <input name="email" type="email" [(ngModel)]="email" required autocomplete="email">
          </label>
          <label>Contraseña
            <input name="password" type="password" [(ngModel)]="password" required
                   [attr.autocomplete]="mode() === 'login' ? 'current-password' : 'new-password'">
          </label>
          @if (mode() !== 'login') {
            <p class="hint">Mínimo 12 caracteres, con letras y números.</p>
          }
          @if (error()) {
            <div class="alert alert-error" role="alert">{{ error() }}</div>
          }
          <button class="btn btn-primary" type="submit" [disabled]="loading()">
            {{ loading() ? 'Un momento…' : mode() === 'login' ? 'Entrar' : 'Crear cuenta' }}
          </button>
        </form>
        @if (mode() === 'login') {
          <p class="hint demo">Demo: <code>admin&#64;acme-store.example</code>, <code>agent&#64;acme-store.example</code>
            o <code>customer&#64;acme-store.example</code> con la contraseña de <code>DEMO_USER_PASSWORD</code>.</p>
        }
      </div>
    </div>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly mode = signal<Mode>('login');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected email = '';
  protected password = '';
  protected fullName = '';
  protected organizationName = '';
  protected organizationSlug = '';

  protected switch(mode: Mode): void {
    this.mode.set(mode);
    this.error.set(null);
  }

  protected submit(): void {
    this.loading.set(true);
    this.error.set(null);
    const request = this.mode() === 'login'
      ? this.auth.login(this.email, this.password)
      : this.auth.register({
          email: this.email,
          password: this.password,
          fullName: this.fullName,
          ...(this.mode() === 'organization'
            ? { organizationName: this.organizationName }
            : { organizationSlug: this.organizationSlug.trim().toLowerCase() }),
        });
    request.subscribe({
      next: () => void this.router.navigate(['/']),
      error: (err) => {
        this.error.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }
}
