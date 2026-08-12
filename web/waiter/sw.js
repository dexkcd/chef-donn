importScripts("../common/sw-core.js");

donnServiceWorker({
  cacheName: "chefdonn-waiter-v1",
  appAssets: ["./", "./index.html", "./app.js", "./app.css", "./manifest.webmanifest", "./icons/icon-192.png", "./icons/icon-512.png"],
});
