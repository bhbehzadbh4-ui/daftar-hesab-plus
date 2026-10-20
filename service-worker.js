'use strict';
// Offline app shell for Daftar Hesab Plus v10.
// Build an absolute URL from the registration scope so installation also works
// when the app is deployed under a subdirectory (for example, a hosted preview).
const CACHE_PREFIX = 'daftarhesab-v10-offline-';
const CACHE_NAME = `${CACHE_PREFIX}v2`;
const SCOPE_URL = new URL(self.registration.scope);
const appUrl = (path) => new URL(path, SCOPE_URL).href;
const APP_URL = appUrl('index.html');
const APP_SHELL = [
  APP_URL,
  appUrl('manifest.webmanifest'),
  appUrl('icons/icon-192.png'),
  appUrl('icons/icon-512.png'),
  appUrl('icons/icon-512-maskable.png')
];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(APP_SHELL);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys
      .filter((key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME)
      .map((key) => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin || !url.href.startsWith(self.registration.scope)) return;

  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(request);
    if (cached) return cached;

    try {
      const response = await fetch(request);
      if (response && response.ok) {
        await cache.put(request, response.clone());
      }
      return response;
    } catch (error) {
      if (request.mode === 'navigate') {
        return (await cache.match(APP_URL)) || Response.error();
      }
      return Response.error();
    }
  })());
});
