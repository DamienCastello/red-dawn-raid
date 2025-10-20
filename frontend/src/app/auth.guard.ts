import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { Router } from '@angular/router';

export const authGuard: CanActivateFn = () => {
  const router = inject(Router);
  const t = sessionStorage.getItem('authToken') || localStorage.getItem('authToken');
  if (t) return true;
  router.navigate(['/auth']);
  return false;
};
