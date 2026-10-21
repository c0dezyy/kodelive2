# SpectatorCamera Suite

Three parts that work together (or separately):

| Folder | What it is | Runs on |
|---|---|---|
| `plugin/` | Paper plugin: virtual spectator camera, auto-follow, cinematic shots, patrol, live web preview | Your Minecraft server |
| `relay/` | Tiny service that gives you the public watch link, no open port needed on your host | Railway (or Render free) |
| `xbox-bot/` | Bedrock bot that logs in with a real Xbox account and becomes the camera body | Your PC, Railway, or any VPS (not GitHub Actions) |
| `streamer/` | FFmpeg streamer to TikTok / YouTube / Twitch (each on or off), overlays, manual key panel | Railway (or any Docker host / PC) |

## Repo structure

```
spectatorcamera/
├── README.md
├── .gitignore
├── .github/workflows/
│   └── build-plugin.yml        # builds the plugin .jar and publishes it under Releases
├── render.yaml                 # one-click free deploy of the relay on Render
├── docs/
│   ├── SETUP.md                # complete checklist, start here
│   └── STREAMING.md            # platforms, overlays, no-stream-key alternatives
├── plugin/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/codezyy/spectatorcamera/
│       │   ├── SpectatorCameraPlugin.java   # commands, auto-enroll, events
│       │   ├── Camera.java                  # target picking, shots, patrol, smoothing
│       │   └── WebPreview.java              # embedded web server + radar page
│       └── resources/ (plugin.yml, config.yml)
├── relay/
│   ├── server.js  index.html  package.json  Dockerfile  railway.json  .env.example
│   └── sync-page.py            # regenerates index.html from the plugin page
├── xbox-bot/
│   ├── bot.js  package.json  Dockerfile  railway.json  .env.example
└── streamer/
    ├── stream.js               # FFmpeg supervisor + control panel
    ├── package.json  Dockerfile  railway.json  .env.example
    └── assets/                 # your loop.mp4, logo.png, pip.mp4, music.mp3
```

## How it works

1. **The plugin** runs a tick loop. Each tick it picks a target player (switching every `switch-seconds`),
   computes a camera position (follow / orbit / cinematic shots: behind, front, side, high, spinning),
   pulls the camera in front of walls with a raycast, smooths it, and teleports every "viewer" to it.
   When nobody is online it flies your patrol points (or orbits spawn).
2. **A viewer** is any player in camera mode: someone who typed `/cam join`, or a **camera account**.
   Camera accounts are listed in `camera-accounts`. When one logs in and `auto-enroll: true`, the plugin
   puts it in spectator mode and drives it. Nobody has to type anything.
3. **The Xbox bot** is a real Bedrock client. It signs in through Microsoft's device-code login, joins
   through Geyser, and sits there so the plugin has a body to move.
4. **The web preview** (on a normal server: `http://SERVER_IP:8765/?token=...`; on shared hosts: the relay link) shows a live top-down radar: camera, view cone, players,
   plus your overlays (text, picture, video). `/cam link` gives players and admins the link; `&clean=1` hides the buttons
   for streaming. `/api/state` serves the same data as JSON.
5. **The streamer** feeds FFmpeg a source (video file/URL, image, or a captured browser showing the
   preview page), adds overlays, and pushes it to every enabled platform (TikTok, YouTube, Twitch) over RTMP.
   Overlay text can update live from `/api/state`. A web panel lets you paste stream keys by hand.

> Honest limit: the bot and the preview page do not render the 3D game view. The preview is a 2D radar.
> To put real first-person gameplay on TikTok you need a real game client running on a PC and captured
> with OBS or FFmpeg. The streamer's `web` mode streams the radar page.

## Step 1: Put it on GitHub

```bash
cd spectatorcamera
git init
git add .
git commit -m "SpectatorCamera suite"
git branch -M main
git remote add origin https://github.com/YOUR_USER/spectatorcamera.git
git push -u origin main
```
Make the repo **private** (it will hold workflow logs and settings). Never commit `.env`, `auth/` or stream keys.

## Step 2: Build and install the plugin

- Build: the **Build plugin** workflow runs on every push. Open the repo's **Releases** page, **Latest build**, and download the `.jar`
  (works from a phone). It also appears under the run's Artifacts.
  Or locally: `cd plugin && mvn package` (Java 21).
- Needs Paper 1.21.x. For Bedrock players install **Geyser** and **Floodgate** too.
- Put the jar in `plugins/`, start the server once, stop it, edit `plugins/SpectatorCamera/config.yml`:
  - `web.token`: set a long random string
  - `camera-accounts`: add your bot's name without the Floodgate `.` prefix (after step 5)
  - `auto-enroll: true`
- Open TCP port **8765** if you want the preview from outside, or use a tunnel (see docs/STREAMING.md, part D).
- Set `web.public-url` to the address people should open. Run `/cam link` to get the watch link.
- Overlays: put an image or video in `plugins/SpectatorCamera/overlay/`, set `overlay.image` / `overlay.video` / `overlay.text`
  in config.yml, then `/cam reload`.

Commands (all work from the server console too; permission `spectatorcamera.admin`):

| Command | Does |
|---|---|
| `/cam link` | Shows the browser watch link (admins also get the clean OBS/streamer link) |
| `/cam join [player]`, `/cam leave [player]` | Enter/leave camera view |
| `/cam mode auto\|follow\|orbit\|cinematic\|patrol` | Choose behaviour (auto = cinematic with players, patrol when empty) |
| `/cam follow <player>`, `/cam next` | Lock a target / switch target |
| `/cam addpoint` (or `<world> <x> <y> <z>` from console), `/cam clearpoints` | Patrol route |
| `/cam account add\|remove\|list <name>` | Manage camera accounts |
| `/cam status`, `/cam reload` | Info / reload config |

## Step 2B: Get a working watch link with no open port (recommended for shared hosts)

Many hosts (PixelForge, etc.) will not let you open the web port. The relay avoids that: the plugin sends data **out**
to a small service on Railway, and that service gives you the public link.

1. Railway: **New Project, Deploy from GitHub repo**, pick this repo, **Settings, Root Directory** = `relay`.
2. **Variables**: `PUSH_KEY` = a secret of 8+ letters/numbers, `VIEW_TOKEN` = another one (the token in your watch link).
3. **Settings, Networking, Generate Domain**. Copy the URL, for example `https://cam-relay.up.railway.app`.
4. On your Minecraft server edit `plugins/SpectatorCamera/config.yml`:
   ```yaml
   web:
     enabled: false            # not needed in relay mode
   relay:
     enabled: true
     url: "https://cam-relay.up.railway.app"
     key: "SAME_AS_PUSH_KEY"
     view-token: "SAME_AS_VIEW_TOKEN"
   ```
5. Run `cam reload`, then `cam link`. It prints your working link. Open it on your phone.
   If the page says OFFLINE, check that `key` equals `PUSH_KEY` and the URL has no typo (see the server console).
6. Overlays in relay mode use https image/video URLs (local files are not available through the relay).

(`render.yaml` is included if you ever want to host the relay on Render's free plan instead.)

## Step 3: Make sure Bedrock can reach your server

Geyser's UDP port (default **19132**) must be reachable from the internet, because Railway connects from outside.
Port-forward it if the server is at home. If your Geyser uses Floodgate, the bot's Xbox login is what it expects.

## Step 4: Get a spare Microsoft account

Create a separate Microsoft/Xbox account for the bot. Do not use your main account.
You never type a password into this project. You approve the login on Microsoft's own page.

## Step 5: Run the Xbox bot (optional)

You do NOT need the bot for the watch link. The camera works virtually and the link shows it. The bot only gives the
camera a body inside the game.

The bot must run on an always-on machine. **Not GitHub Actions:** GitHub's terms say Actions on its hosted runners
should not be used for activity unrelated to building, testing, deploying or publishing the repo's software, and
GitHub may restrict accounts that do. Jobs are also capped at 6 hours. Use one of these instead:

- **Your own PC** (free): install Node 18+, then `cd xbox-bot && npm install && node bot.js`.
- **Railway** (usage-based billing): service from this repo, Root Directory `xbox-bot`, variables `MC_HOST`, `MC_PORT`,
  a volume at `/data`, and a public domain for the status page.
- **Any VPS.** Same commands as the PC.

First run prints a Microsoft link and code (and shows them on the status page, or in Discord if `DISCORD_WEBHOOK` is set).
Sign in with a spare account. Then on the server console: `cam account add <botname>` (without the `.`).

## Step 6: Stream to TikTok, YouTube and Twitch

Each platform is a true/false switch, and you can enable any mix. Full details: `docs/STREAMING.md`.

1. Get a **Server URL** and **Stream key** for each platform you want
   (TikTok usually needs 1,000+ followers and the key is not available to every account; keys can expire).
2. Put media in `streamer/assets/` (`loop.mp4`, optional `logo.png`, `pip.mp4`, `music.mp3`) or use http(s) URLs.
   Only use media you own or may use.
3. Railway: **New Service**, same GitHub repo, **Settings, Root Directory** = `streamer`.
4. Add a **Volume** mounted at `/data` (keeps keys you save in the panel) and generate a public domain.
5. **Variables** (full list in `streamer/.env.example`):
   - `PANEL_TOKEN` = a long random string
   - `TIKTOK_ENABLED=true`, `YOUTUBE_ENABLED=false`, `TWITCH_ENABLED=false`
   - Source: `SOURCE_MODE=video`, `SOURCE=assets/loop.mp4`
     or the plugin page: `SOURCE_MODE=web`, `SOURCE=http://SERVER_IP:8765/?token=TOKEN&clean=1`
   - Overlays: `OVERLAY_TEXT`, `OVERLAY_IMAGE`, `OVERLAY_VIDEO`, `PREVIEW_STATE_URL`
6. Enter the keys one of two ways:
   - **Variables:** `TIKTOK_RTMP_URL` and `TIKTOK_STREAM_KEY`, `YOUTUBE_STREAM_KEY`, `TWITCH_STREAM_KEY`. It starts on boot.
   - **Manual panel:** open `https://YOUR-RAILWAY-URL/?token=PANEL_TOKEN`, paste the key, tick **Enabled**, tap **Save & Start**.
     Use **Stop** to end the stream.
7. Start with `video` mode. `web` mode runs Chromium + Xvfb + x264 and needs about 2 vCPUs.
   Test without streaming: set `DRY_RUN=true` and read the log.
8. Same image runs anywhere: `docker build -t streamer streamer && docker run --env-file streamer/.env -p 3000:3000 streamer`.

No stream key for TikTok? See `docs/STREAMING.md`, part C (LIVE Studio window capture, OBS partners, phone, or YouTube/Twitch).
A tool cannot and should not log in with your TikTok password.

Alternative: **OBS Studio** (open source) with a browser source pointed at the clean watch link does the same on a desktop.

## Troubleshooting

| Problem | Fix |
|---|---|
| Bot can't connect / version error | Set `MC_VERSION` to a Bedrock version both the library and Geyser support |
| Bot connects but camera is jerky | Raise `teleport-interval-ticks` to 3-4 in `config.yml` |
| Bot kicked for idling | Set `player-idle-timeout: 0` in `server.properties`/`paper-global.yml`, or send logs to get movement packets added |
| Preview says Unauthorized | Open it with `/?token=YOUR_TOKEN` |
| TikTok stream fails immediately | Regenerate stream key, check the RTMP URL, check `docker logs` / Railway logs |
| Text overlay missing | Make sure `OVERLAY_TEXT` is set; font path is `FONT_FILE` |

## Safety notes

- Spare Microsoft account only. Tokens in `auth/` are as sensitive as a password.
- Keep the repo private; keep tokens and stream keys in Variables/Secrets, never in code.
- Repeated unoriginal or looped content, and copyrighted music, can break TikTok's rules and get the stream removed.
- Untested code: expect to fix small things on first run, and send me the logs.
