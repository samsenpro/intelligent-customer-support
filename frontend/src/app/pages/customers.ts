import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { Channel, Customer } from '../core/api.models';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/errors';
import { dateTime, label } from '../shared/format';

@Component({
  selector: 'app-customers',
  imports: [FormsModule],
  template: `
    <div class="page-header">
      <div>
        <h1>Clientes</h1>
        <p class="muted">Clientes con cuenta en el portal y contactos creados por el equipo.</p>
      </div>
      <button class="btn btn-primary" type="button" (click)="creating.set(!creating())">Nuevo cliente</button>
    </div>

    @if (creating()) {
      <form class="card form-grid" (ngSubmit)="create()">
        <label>Nombre<input name="fullName" [(ngModel)]="form.fullName" required maxlength="120"></label>
        <label>Email<input name="email" type="email" [(ngModel)]="form.email" maxlength="254"></label>
        <label>Teléfono<input name="phone" [(ngModel)]="form.phone" placeholder="+57 300 000 0000"></label>
        <label>Referencia externa<input name="externalRef" [(ngModel)]="form.externalRef" placeholder="CRM-1234"></label>
        <div class="form-actions"><button class="btn btn-primary" type="submit" [disabled]="!form.fullName.trim()">Guardar</button></div>
      </form>
    }
    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    <div class="card">
      <div class="filters">
        <input class="search" name="search" [(ngModel)]="search" (ngModelChange)="load()" placeholder="Buscar por nombre o email">
      </div>
      <div class="table-wrapper">
        <table>
          <thead><tr><th>Nombre</th><th>Email</th><th>Teléfono</th><th>Portal</th><th>Alta</th><th class="actions"></th></tr></thead>
          <tbody>
            @for (customer of customers(); track customer.id) {
              <tr>
                <td><strong>{{ customer.fullName }}</strong>
                  @if (customer.externalRef) { <span class="muted small"> · {{ customer.externalRef }}</span> }</td>
                <td>{{ customer.email ?? '—' }}</td>
                <td>{{ customer.phone ?? '—' }}</td>
                <td>{{ customer.hasPortalAccount ? 'Sí' : 'No' }}</td>
                <td>{{ dateTime(customer.createdAt) }}</td>
                <td class="actions">
                  <select class="select-small" #channel aria-label="Canal">
                    @for (c of channels; track c) { <option [value]="c">{{ label(c) }}</option> }
                  </select>
                  <button class="btn btn-small" type="button" (click)="startConversation(customer, $any(channel.value))">Iniciar conversación</button>
                </td>
              </tr>
            } @empty {
              <tr><td class="empty" colspan="6">No hay clientes.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
})
export class CustomersPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  protected readonly customers = signal<Customer[]>([]);
  protected readonly creating = signal(false);
  protected readonly error = signal<string | null>(null);
  protected search = '';
  protected form = { fullName: '', email: '', phone: '', externalRef: '' };
  protected readonly channels: Channel[] = ['EMAIL', 'WHATSAPP', 'API', 'WEB'];
  protected readonly dateTime = dateTime;
  protected readonly label = label;

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.api.customers({ search: this.search, size: 100 }).subscribe({
      next: (page) => this.customers.set(page.content),
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  protected create(): void {
    const { fullName, email, phone, externalRef } = this.form;
    this.api.createCustomer({ fullName, email: email || undefined, phone: phone || undefined,
                              externalRef: externalRef || undefined }).subscribe({
      next: () => {
        this.form = { fullName: '', email: '', phone: '', externalRef: '' };
        this.creating.set(false);
        this.error.set(null);
        this.load();
      },
      error: (err) => this.error.set(errorMessage(err)),
    });
  }

  /** Conversación atendida por un humano (el equipo escribe primero, p. ej. por email). */
  protected startConversation(customer: Customer, channel: Channel): void {
    this.api.createConversation({ customerId: customer.id, channel, subject: `Contacto con ${customer.fullName}` })
      .subscribe({
        next: (conversation) => void this.router.navigate(['/inbox', conversation.id]),
        error: (err) => this.error.set(errorMessage(err)),
      });
  }
}
