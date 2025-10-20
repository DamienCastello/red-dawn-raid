import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError } from 'rxjs/operators';
import { throwError } from 'rxjs';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const t = sessionStorage.getItem('authToken') || localStorage.getItem('authToken');
  if (t) req = req.clone({ setHeaders: { Authorization: `Bearer ${t}` } });

  return next(req).pipe(
    catchError(err => {
      if (err?.status === 401) {
        sessionStorage.removeItem('authToken');
        localStorage.removeItem('authToken');
        router.navigate(['/auth']);
      }
      return throwError(() => err);
    })
  );
};