const CACHE = 'homepad-companion-v25';
const ASSETS = ['/', '/styles.css', '/app.js', '/manifest.webmanifest', '/icon.svg'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key)))));
  self.clients.claim();
});

self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (url.pathname.startsWith('/api/')) return;
  event.respondWith(fetch(event.request).then(response => {
    const copy = response.clone();
    caches.open(CACHE).then(cache => cache.put(event.request, copy));
    return response;
  }).catch(() => caches.match(event.request)));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil(clients.matchAll({type: 'window', includeUncontrolled: true}).then(windows => {
    const existing = windows.find(windowClient => 'focus' in windowClient);
    return existing ? existing.focus() : clients.openWindow('/#visitors');
  }));
});

// The native APK will use FCM. This handler also leaves the web client ready
// for standards-based Web Push if a subscription provider is added later.
self.addEventListener('push', event => {
  const data = event.data ? event.data.json() : {};
  event.waitUntil(self.registration.showNotification(data.title ?? '방문 호출', {
    body: data.body ?? '현관 호출이 감지되었습니다.',
    icon: '/icon.svg',
    tag: data.tag ?? 'homepad-visitor-ring',
    renotify: true,
    data,
  }));
});
