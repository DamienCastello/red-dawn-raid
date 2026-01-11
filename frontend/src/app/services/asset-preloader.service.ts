import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AssetPreloaderService {
  private started = false;
  private done = false;

  private preloaded = new Set<string>();
  private promise: Promise<void> | null = null;

  isDone(): boolean {
    return this.done;
  }

  /** Démarre une seule fois (continue en fond tant que l’onglet reste ouvert). */
  start(): void {
    if (this.started) return;
    this.started = true;

    this.promise = this.run()
      .catch(err => console.warn('[assets] preload failed', err))
      .finally(() => { this.done = true; });
  }

  /** Permet d’attendre la fin (ex: dans GameComponent). */
  waitDone(): Promise<void> {
    this.start();
    return this.promise ?? Promise.resolve();
  }

  private preloadOne(url: string): Promise<void> {
    if (!url || this.preloaded.has(url)) return Promise.resolve();
    this.preloaded.add(url);

    return new Promise((resolve) => {
      const img = new Image();
      let finished = false;

      const finish = () => {
        if (finished) return;
        finished = true;
        resolve();
      };

      img.onload = finish;
      img.onerror = finish;
      img.src = url;

      const anyImg = img as any;
      if (typeof anyImg.decode === 'function') {
        anyImg.decode().then(finish).catch(finish);
      }
    });
  }

  private async preloadMany(urls: string[], concurrency = 24): Promise<void> {
    const list = (urls || []).filter(Boolean);
    let i = 0;

    const workers = Array.from({ length: concurrency }, async () => {
      while (i < list.length) {
        const url = list[i++];
        await this.preloadOne(url);
      }
    });

    await Promise.all(workers);
  }

  private async run(): Promise<void> {
    // force-cache : utilise ce que le navigateur a déjà si dispo
    const urls: string[] = await fetch('/assets/assets-manifest.json', { cache: 'force-cache' })
      .then(r => r.json())
      .catch(() => []);

    await this.preloadMany(urls, 24);
  }
}
