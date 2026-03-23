// 가디언 AI - Service Worker
const CACHE_NAME = 'guardian-ai-v2';
const ASSETS = ['./index.html', './manifest.json'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE_NAME).then(c => c.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  e.respondWith(
    caches.match(e.request).then(r => r || fetch(e.request))
  );
});

// 백그라운드 알림
self.addEventListener('message', e => {
  if (e.data?.type === 'EMERGENCY') {
    self.registration.showNotification('가디언 AI - 긴급 알림', {
      body: e.data.message,
      icon: '🛡',
      vibrate: [500, 200, 500, 200, 500],
      requireInteraction: true,
      tag: 'emergency'
    });
  }
});
