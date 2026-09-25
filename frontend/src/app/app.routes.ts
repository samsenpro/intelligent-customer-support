import { Routes } from '@angular/router';

import { authGuard, guestGuard, roleGuard } from './core/auth.guard';
import { AnalyticsPage } from './pages/analytics';
import { CustomersPage } from './pages/customers';
import { DashboardPage } from './pages/dashboard';
import { InboxPage } from './pages/inbox';
import { KnowledgePage } from './pages/knowledge';
import { LoginPage } from './pages/login';
import { SettingsPage } from './pages/settings';
import { Shell } from './pages/shell';
import { TicketsPage } from './pages/tickets';

const STAFF = ['ADMIN', 'SUPERVISOR', 'AGENT'] as const;

export const routes: Routes = [
  { path: 'login', component: LoginPage, canActivate: [guestGuard], title: 'SupportMind AI · Acceso' },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', component: DashboardPage, title: 'SupportMind AI · Inicio' },
      { path: 'inbox', component: InboxPage, title: 'SupportMind AI · Conversaciones' },
      { path: 'inbox/:id', component: InboxPage, title: 'SupportMind AI · Conversación' },
      { path: 'tickets', component: TicketsPage, title: 'SupportMind AI · Tickets' },
      { path: 'customers', component: CustomersPage, canActivate: [roleGuard(...STAFF)],
        title: 'SupportMind AI · Clientes' },
      { path: 'knowledge', component: KnowledgePage, canActivate: [roleGuard(...STAFF)],
        title: 'SupportMind AI · Base de conocimiento' },
      { path: 'analytics', component: AnalyticsPage, canActivate: [roleGuard('ADMIN', 'SUPERVISOR')],
        title: 'SupportMind AI · Analítica' },
      { path: 'settings', component: SettingsPage, canActivate: [roleGuard(...STAFF)],
        title: 'SupportMind AI · Configuración' },
    ],
  },
  { path: '**', redirectTo: '' },
];
