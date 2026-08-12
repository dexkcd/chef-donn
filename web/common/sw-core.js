// Shared service-worker behaviour for the three apps (classic worker script,
// pulled in with importScripts).
//
// The apps must open on a tablet that has lost the WiFi — a waiter walking out
// of range and back must not meet a blank screen. So the shell is precached and
// served stale-while-revalidate: instant offline start, and a hub upgrade lands
// on the next load. Live order data never goes through here — that arrives over
// the WebSocket and is folded from the event log.

/* global self, caches, fetch, clients */

const SHELL = [
  "../common/tokens.css",
  "../common/base.css",
  "../common/ui.js",
  "../common/sw-core.js",
  "../../shared/domain.js",
  "../../shared/decide.js",
  "../../shared/protocol.js",
  "../../shared/client.js",
  "../fonts/archivo_variable.woff2",
  "../fonts/barlow_regular.woff2",
  "../fonts/barlow_semibold.woff2",
  "../fonts/barlow_bold.woff2",
  "../fonts/barlowcondensed_semibold.woff2",
  "../fonts/barlowcondensed_bold.woff2",
  "../fonts/ibmplexmono_regular.woff2",
  "../fonts/ibmplexmono_semibold.woff2",
  "../fonts/ibmplexmono_bold.woff2",
];

function donnServiceWorker({ cacheName, appAssets = [] }) {
  const assets = [...appAssets, ...SHELL].map((url) => new URL(url, self.location).toString());

  self.addEventListener("install", (event) => {
    event.waitUntil(
      caches
        .open(cacheName)
        // addAll is all-or-nothing; cache individually so one missing optional
        // asset can't leave the tablet with no offline shell at all.
        .then((cache) => Promise.all(assets.map((url) => cache.add(url).catch(() => {}))))
        .then(() => self.skipWaiting())
    );
  });

  self.addEventListener("activate", (event) => {
    event.waitUntil(
      caches
        .keys()
        .then((keys) => Promise.all(keys.filter((key) => key !== cacheName).map((key) => caches.delete(key))))
        .then(() => self.clients.claim())
    );
  });

  self.addEventListener("fetch", (event) => {
    const { request } = event;
    if (request.method !== "GET") return;
    const url = new URL(request.url);
    if (url.origin !== self.location.origin) return;
    // Hub liveness must never be answered from a cache.
    if (url.pathname === "/healthz") return;

    event.respondWith(
      caches.open(cacheName).then(async (cache) => {
        const cached = await cache.match(request, { ignoreSearch: true });
        const network = fetch(request)
          .then((response) => {
            if (response.ok) cache.put(request, response.clone());
            return response;
          })
          .catch(() => null);

        if (cached) {
          event.waitUntil(network); // revalidate in the background
          return cached;
        }
        const fresh = await network;
        if (fresh) return fresh;
        // Cold cache and no hub: at least answer navigations with the shell.
        return (
          (request.mode === "navigate" ? await cache.match("./index.html") : null) ??
          new Response("Offline", { status: 503, headers: { "content-type": "text/plain" } })
        );
      })
    );
  });
}

self.donnServiceWorker = donnServiceWorker;
