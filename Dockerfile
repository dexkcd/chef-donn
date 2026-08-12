# The hub image. Runs on the restaurant's Windows PC under Docker Desktop, and
# on anything else Docker runs on — there is nothing platform-specific here.
#
# Node 22 is the floor: the event log uses node:sqlite, which is built in, so
# the image needs no compiler and no native module rebuild on any platform.
FROM node:22-alpine

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
    CHEFDONN_DATA=/data

# The order log lives here. Mount it — losing this volume loses the day's
# service history, which is the one thing this system must never do.
VOLUME ["/data"]
EXPOSE 8577

# Service is unusable if the hub is down, so let Docker see failures and
# restart rather than leave the tablets staring at a dead screen.
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.CHEFDONN_PORT||8577)+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server/src/main.js"]
