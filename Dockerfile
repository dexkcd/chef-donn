# The hub image. Runs on the restaurant's Windows PC under Docker Desktop, and
# on anything else Docker runs on — there is nothing platform-specific here.
#
# Node 22 is the floor: the event log uses node:sqlite, which is built in, so
# the image needs no compiler and no native module rebuild on any platform.
FROM node:22-alpine

# openssl issues the hub's own CA and server certificate on first boot. The
# apps must be served over HTTPS or browsers withhold service workers, and
# without those the tablets cannot install the apps or open them offline.
RUN apk add --no-cache openssl

WORKDIR /app

# Only `ws` is a real dependency; installing before the source copy keeps the
# layer cached across code changes.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY shared/ ./shared/
COPY server/src/ ./server/src/
COPY web/ ./web/

ENV NODE_ENV=production \
    CHEFDONN_PORT=8577 \
    CHEFDONN_HTTPS_PORT=8443 \
    CHEFDONN_DATA=/data

# The order log AND the hub's certificate authority live here. Mount it —
# losing this volume loses the day's service history, and forces every tablet
# to install a new certificate.
VOLUME ["/data"]
EXPOSE 8443 8577

# Service is unusable if the hub is down, so let Docker see failures and
# restart rather than leave the tablets staring at a dead screen. Verified
# against the hub's own CA rather than skipping verification, so a broken
# certificate registers as the outage it is.
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD node -e "const https=require('https'),fs=require('fs');https.get({host:'127.0.0.1',port:process.env.CHEFDONN_HTTPS_PORT||8443,path:'/healthz',ca:fs.readFileSync(process.env.CHEFDONN_DATA+'/tls/ca.crt')},r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"

CMD ["node", "server/src/main.js"]
