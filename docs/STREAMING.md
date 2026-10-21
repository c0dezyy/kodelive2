# Streaming guide (TikTok, YouTube, Twitch, and no-key alternatives)

## A. The streamer service (`streamer/`)

One FFmpeg process encodes your source once and sends it to every enabled platform
(`TIKTOK_ENABLED`, `YOUTUBE_ENABLED`, `TWITCH_ENABLED`, each true/false).
If one platform drops, the others keep going. If you give a platform its own resolution
(for example TikTok portrait and YouTube landscape) it runs one encode per resolution, which uses more CPU.

Where to get the Server URL and Stream key:
- **TikTok:** in TikTok LIVE settings / LIVE Studio ("Go LIVE with streaming software"). Not every account has it.
- **YouTube:** YouTube Studio, Go live, Stream. Server URL is `rtmp://a.rtmp.youtube.com/live2`. Live streaming must be enabled on the channel first (can take up to a day).
- **Twitch:** Creator Dashboard, Settings, Stream. Server URL is `rtmp://live.twitch.tv/app`.

Two ways to enter keys:
1. **Variables** (Railway Variables): `TIKTOK_STREAM_KEY=...` etc. With `AUTOSTART=true` it streams on boot.
2. **Panel** (manual): open `https://YOUR-RAILWAY-URL/?token=PANEL_TOKEN`, paste the key, tick Enabled, press **Save & Start**.
   Saved keys live in the `/data` volume, so add a Railway volume at `/data`.
   The panel never shows saved keys again and the logs hide them.

## B. Overlays (text, picture, video)

Two places, use either or both:
1. **In the plugin page** (`plugins/SpectatorCamera/config.yml`, `overlay:` section). Drop files in
   `plugins/SpectatorCamera/overlay/` and write the file name, or use an https URL. Works in the normal watch link
   and in `web` mode streaming because the stream captures that page. Run `/cam reload`, the page updates within 10 seconds.
2. **In FFmpeg** (streamer variables): `OVERLAY_TEXT`, `OVERLAY_IMAGE`, `OVERLAY_VIDEO`, positions and sizes in `.env.example`.
   Use this for `video` and `image` modes. Text can show live data via `PREVIEW_STATE_URL`.

If you stream in `web` mode with the page overlays, leave the FFmpeg overlays empty to avoid doubling.

## C. TikTok without a stream key (manual alternatives)

A tool cannot log in to TikTok with your username and password. TikTok has no official way to do that, such tools
break its rules, and giving a password away risks losing the account. This project never asks for it.
Legitimate routes when you have LIVE access but no stream key:

1. **TikTok LIVE Studio (Windows, free):** open the clean watch link
   (`http://SERVER:8765/?token=TOKEN&clean=1`) in a browser window and add that window or your screen as a capture source in LIVE Studio.
   You can also stream your own video with its own overlays through OBS and expose it as a virtual camera.
   Check the options available in your version of LIVE Studio.
2. **OBS + a partner tool:** some guides say accounts that meet the requirements can sign in through StreamElements (SE.Live)
   or Streamlabs and stream from OBS without a manual key. Availability changes, so check their current docs.
3. **Phone:** use TikTok's own Go LIVE on your phone with its screen-sharing / gaming mode and show the watch link.
4. **Stream to YouTube or Twitch instead:** this tool supports both and needs no follower minimum for a key.

TikTok LIVE itself generally needs 1,000+ followers, so none of these work below the requirement.

## D. Making the watch link reachable

- **Easiest: the relay** (README step 2B). No open port needed. Use its link as the streamer's web source:
  `SOURCE_MODE=web`, `SOURCE=https://YOUR-RELAY.up.railway.app/?token=VIEW_TOKEN&clean=1`.
- Run `/cam link` in game or console. Set `web.public-url` in config.yml to the address people should use.
- No port forwarding? Run a tunnel on the server: `cloudflared tunnel --url http://localhost:8765`
  and put the printed https address in `web.public-url`.
- Keep `web.token` set and use only letters and numbers in it.
