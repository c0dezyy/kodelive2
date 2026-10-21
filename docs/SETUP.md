# Setup guide (GitHub + Railway)

## What runs where

| Piece | Where it runs |
|---|---|
| Code, plugin builds, jar downloads | **GitHub** (repo, Actions, Releases) |
| Plugin | Your Minecraft host (PixelForge) |
| Watch link (relay) | **Railway**, service from this repo, folder `relay` |
| Xbox bot (optional) | **Railway**, folder `xbox-bot` |
| Streamer to TikTok/YouTube/Twitch (optional) | **Railway**, folder `streamer` |

Railway bills by usage and the free credit changes, so check its pricing page. The relay is tiny. The bot costs more,
and the streamer costs the most (start it in `video` mode, `web` mode needs about 2 vCPUs).

## Step 1: Create the GitHub repo
1. github.com, **New repository**, name `spectatorcamera`, Private is fine.
2. Unzip `spectatorcamera-repo.zip` on a PC and upload everything. Keep the `.github` folder.
3. Open the **Actions** tab and enable workflows if asked.

## Step 2: Get the plugin jar
1. The **Build plugin** workflow runs when you push (or run it by hand in Actions).
2. Open the repo's **Releases** page, **Latest build**, download the `.jar` (works on a phone).
3. Upload it to your server's `plugins/` folder and restart.

## Step 3: Deploy the relay on Railway (your watch link)
1. railway.com, sign in with GitHub, **New Project, Deploy from GitHub repo**, pick `spectatorcamera`.
2. Open the service, **Settings, Root Directory** = `relay`.
3. **Variables**: `PUSH_KEY` (secret, 8+ letters/numbers) and `VIEW_TOKEN` (letters/numbers, goes in the link).
4. **Settings, Networking, Generate Domain**. Copy the URL, like `https://spectatorcamera-relay-production.up.railway.app`.

## Step 4: Connect the plugin to the relay
Edit `plugins/SpectatorCamera/config.yml`:
```yaml
web:
  enabled: false
relay:
  enabled: true
  url: "https://YOUR-RELAY.up.railway.app"
  key: "SAME_AS_PUSH_KEY"
  view-token: "SAME_AS_VIEW_TOKEN"
```
Server console: `cam reload`, then `cam link`. Open the link on your phone. You should see the live map.

## Step 5: Check it works
- The link shows the map (not OFFLINE)
- A player moves and the map follows them
- `cam mode cinematic` changes the camera style

## Optional A: Xbox camera bot (Railway)
Not needed for the link. It only gives the camera a body in the game.
1. In the same Railway project: **New, GitHub Repo**, same repo, **Root Directory** = `xbox-bot`.
2. **Variables**: `MC_HOST` and `MC_PORT` (the Bedrock address and port a Bedrock player would type). Optional: `MC_VERSION`, `DISCORD_WEBHOOK`.
3. **Settings, Volumes**: add a volume mounted at `/data`. **Networking**: Generate Domain.
4. Deploy. Open the bot's domain: it shows a Microsoft link and a code. Sign in with a SPARE Microsoft account.
5. Server console: `cam account add <botname>` (without the `.`), then `cam status` should show viewers=1.
If it cannot connect from Railway, run it on a PC instead: `cd xbox-bot && npm install && node bot.js`.

## Optional B: Stream to TikTok / YouTube / Twitch (Railway)
1. **New, GitHub Repo**, same repo, **Root Directory** = `streamer`. Add a volume at `/data` and generate a domain.
2. **Variables**: `PANEL_TOKEN`, `SOURCE_MODE=web`,
   `SOURCE=https://YOUR-RELAY.up.railway.app/?token=VIEW_TOKEN&clean=1`
   (or `SOURCE_MODE=video`, `SOURCE=assets/loop.mp4` with your file in `streamer/assets/`).
3. Open `https://STREAMER-DOMAIN/?token=PANEL_TOKEN`, tick TikTok / YouTube / Twitch, paste each stream key, tap **Save & Start**.
4. Overlays and no-stream-key alternatives: `docs/STREAMING.md`.

## Updating later
Push changes to GitHub. Plugin changes rebuild the jar in Releases. Railway redeploys the services from the repo.
To avoid redeploying all three on every push, open each service's **Settings, Watch Paths** and set `/relay/**`, `/xbox-bot/**`, `/streamer/**`.

## Commands
`cam link` | `cam mode auto|follow|orbit|cinematic|patrol` | `cam follow <player>` | `cam next` |
`cam addpoint` / `cam clearpoints` | `cam account add|remove|list <name>` | `cam reload`

## Troubleshooting
| Problem | Fix |
|---|---|
| Link says OFFLINE | `relay.key` must equal `PUSH_KEY`; check `relay.url`; look for "Relay push failed" in the server console |
| Link says Unauthorized | Use the link from `cam link` (it includes the token) |
| `cam link` shows YOUR_SERVER_IP | Set `relay.enabled: true` and `relay.url`, then `cam reload` |
| Railway deploy fails | Open the service's Deployments, then the logs, and send me the error |
| Bot won't connect | Check Bedrock address/port, set `MC_VERSION`, or run it from a PC |
| Stream fails | Regenerate the stream key; read the log at the bottom of the streamer panel |
