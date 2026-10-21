FROM node:20-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg xvfb chromium fonts-dejavu-core ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . .
# On Railway add a Volume mounted at /data so saved stream keys / settings survive restarts
ENV DATA_DIR=/data
CMD ["node", "stream.js"]
