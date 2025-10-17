import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';

export const gameGuard: CanActivateFn = (route) => {
  const id = route.paramMap.get('id');
  const my = sessionStorage.getItem('gameId');

  // Autorisé SEULEMENT si c’est la game où tu es inscrit
  if (id && my && id === my) return true;

  // Sinon, retour lobby
  const router = inject(Router);
  router.navigateByUrl('/');
  return false;
};