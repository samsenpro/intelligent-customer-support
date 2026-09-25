import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from '../core/auth.service';
import { label } from '../shared/format';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  exact?: boolean;
}

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="layout">
      <aside class="sidebar">
        <a routerLink="/" class="brand">SupportMind <span>AI</span></a>
        <p class="org">{{ auth.user()?.organizationName }}</p>
        <nav>
          @for (item of nav(); track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active"
               [routerLinkActiveOptions]="{ exact: item.exact ?? false }">
              <span class="icon" aria-hidden="true">{{ item.icon }}</span>{{ item.label }}
            </a>
          }
        </nav>
        <div class="me">
          <div>
            <strong>{{ auth.user()?.fullName }}</strong>
            <span class="role">{{ roleLabel() }}</span>
          </div>
          <button class="btn btn-link" type="button" (click)="auth.logout()">Salir</button>
        </div>
      </aside>
      <main class="content">
        <router-outlet />
      </main>
    </div>
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly roleLabel = computed(() => label(this.auth.role()));

  protected readonly nav = computed<NavItem[]>(() => {
    if (this.auth.isCustomer()) {
      return [
        { path: '/', label: 'Inicio', icon: '⌂', exact: true },
        { path: '/inbox', label: 'Mis conversaciones', icon: '✉' },
        { path: '/tickets', label: 'Mis tickets', icon: '◈' },
      ];
    }
    const items: NavItem[] = [
      { path: '/', label: 'Dashboard', icon: '⌂', exact: true },
      { path: '/inbox', label: 'Inbox', icon: '✉' },
      { path: '/customers', label: 'Clientes', icon: '☺' },
      { path: '/tickets', label: 'Tickets', icon: '◈' },
      { path: '/knowledge', label: 'Base de conocimiento', icon: '▤' },
    ];
    if (this.auth.canSeeAnalytics()) {
      items.push({ path: '/analytics', label: 'Analítica', icon: '▥' });
    }
    items.push({ path: '/settings', label: 'Configuración', icon: '⚙' });
    return items;
  });
}
