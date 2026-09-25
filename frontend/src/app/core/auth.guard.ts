import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Role } from './api.models';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () =>
  inject(AuthService).isAuthenticated() ? true : inject(Router).createUrlTree(['/login']);

export const guestGuard: CanActivateFn = () =>
  inject(AuthService).isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;

/** Pantallas por rol. La API aplica las mismas reglas: esto solo evita mostrar pantallas inútiles. */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return () => {
    const role = inject(AuthService).role();
    return role && roles.includes(role) ? true : inject(Router).createUrlTree(['/']);
  };
}
