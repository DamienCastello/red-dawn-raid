import { HttpInterceptorFn } from '@angular/common/http';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const t = sessionStorage.getItem('playerToken');
  if (t) req = req.clone({ setHeaders: { Authorization: `Bearer ${t}` } });
  return next(req);
};
