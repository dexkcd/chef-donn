// One server does everything: serves the three PWAs (static files from web/
// plus the shared domain modules under /shared/) and upgrades /ws to the
// event-stream WebSocket. No framework — a careful static handler and `ws` are
// the whole surface.
//
// Given TLS material it serves HTTPS, which is not cosmetic: browsers only
// expose service workers in a secure context, so without it the apps cannot be
// installed and cannot open offline.

import http from "node:http";
import https from "node:https";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { WebSocketServer } from "ws";
import { WS_PATH, welcome, eventsMsg } from "../../shared/protocol.js";

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".webmanifest": "application/manifest+json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
};

/** Resolves a URL path inside a root dir, refusing anything that escapes it. */
function resolveSafe(root, urlPath) {
  const resolved = path.normalize(path.join(root, urlPath));
  return resolved.startsWith(root + path.sep) || resolved === root ? resolved : null;
}

export function createWebServer({ engine, webDir, sharedDir, hubName = "Chef Donn's Hub", tls = null }) {
  const handler = async (req, res) => {
    if (req.method !== "GET" && req.method !== "HEAD") {
      res.writeHead(405).end();
      return;
    }
    let urlPath;
    try {
      urlPath = decodeURIComponent(new URL(req.url, "http://x").pathname);
    } catch {
      res.writeHead(400).end();
      return;
    }

    if (urlPath === "/healthz") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, seq: engine.currentSeq() }));
      return;
    }

    // /shared/* comes from the repo's shared/ dir — the same reduce/decide the
    // server itself runs; everything else from web/.
    const [root, rel] = urlPath.startsWith("/shared/")
      ? [sharedDir, urlPath.slice("/shared/".length)]
      : [webDir, urlPath];
    let filePath = resolveSafe(root, rel);
    if (!filePath) {
      res.writeHead(403).end();
      return;
    }
    if (urlPath.endsWith("/")) filePath = path.join(filePath, "index.html");

    try {
      const body = await readFile(filePath);
      res.writeHead(200, {
        "content-type": MIME[path.extname(filePath).toLowerCase()] ?? "application/octet-stream",
        // Local-network app shell: always revalidate so a hub upgrade reaches
        // the tablets on next load; the service worker handles offline.
        "cache-control": "no-cache",
      });
      res.end(req.method === "HEAD" ? undefined : body);
    } catch (err) {
      if (err.code === "EISDIR") {
        // Directory without trailing slash → redirect so relative URLs work.
        res.writeHead(301, { location: urlPath + "/" }).end();
      } else {
        res.writeHead(404, { "content-type": "text/plain" }).end("Not found");
      }
    }
  };

  const server = tls
    ? https.createServer({ key: tls.key, cert: tls.cert }, handler)
    : http.createServer(handler);

  const wss = new WebSocketServer({ server, path: WS_PATH });

  wss.on("connection", (ws) => {
    let unsubscribe = null;
    const send = (msg) => {
      if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
    };

    ws.on("message", (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      if (msg.type === "Hello") {
        send(welcome(hubName, engine.currentSeq()));
        // Catch-up then live. submit() is synchronous, so subscribing right
        // after the catch-up query leaves no gap in the stream.
        const catchUp = engine.eventsSince(msg.lastSeenSeq ?? 0);
        if (catchUp.length) send(eventsMsg(catchUp));
        unsubscribe?.();
        unsubscribe = engine.onEvent((envelope) => send(eventsMsg([envelope])));
      } else if (msg.type === "Submit" && msg.command) {
        send(engine.submit(msg.command));
      }
    });

    ws.on("close", () => unsubscribe?.());
    ws.on("error", () => {});
  });

  return {
    server,
    wss,
    listen: (...args) => server.listen(...args),
    /** Drops live sockets first — server.close() alone waits on them forever. */
    close() {
      return new Promise((resolve) => {
        for (const client of wss.clients) client.terminate();
        wss.close(() => server.close(resolve));
      });
    },
  };
}
