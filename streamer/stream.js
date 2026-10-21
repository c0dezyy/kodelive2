// Multi-platform streamer: TikTok LIVE, YouTube Live, Twitch (each can be true/false).
// Source: loop a video, a picture, or capture a web page (the SpectatorCamera preview).
// Overlays: live text, a picture, a picture-in-picture video.
// Control: environment variables AND a small web panel where you can paste stream keys manually.
// Open-source stack: Node.js + FFmpeg (+ Xvfb + Chromium for "web" mode). No npm packages needed.

const { spawn } = require('child_process')
const fs = require('fs')
const os = require('os')
const path = require('path')
const http = require('http')
const qs = require('querystring')
const crypto = require('crypto')

const env = (k, d) => (process.env[k] !== undefined && process.env[k] !== '' ? process.env[k] : d)

const DATA_DIR = env('DATA_DIR', './data')
const SETTINGS_FILE = path.join(DATA_DIR, 'settings.json')
const PANEL_PORT = parseInt(env('PORT', '3000'), 10)
const GENERATED_TOKEN = !process.env.PANEL_TOKEN
const PANEL_TOKEN = env('PANEL_TOKEN', crypto.randomBytes(12).toString('hex'))
const TEXT_FILE = path.join(os.tmpdir(), 'overlay.txt')
const DRY_RUN = env('DRY_RUN', 'false') === 'true'

const PLATFORMS = {
  tiktok: { label: 'TikTok', url: '' },                                  // paste Server URL from TikTok
  youtube: { label: 'YouTube', url: 'rtmp://a.rtmp.youtube.com/live2' },
  twitch: { label: 'Twitch', url: 'rtmp://live.twitch.tv/app' }
}

// settings that only come from environment variables
const fixed = {
  fps: parseInt(env('FPS', '30'), 10),
  bitrate: env('VIDEO_BITRATE', '2500k'),
  music: env('MUSIC', ''),
  useSourceAudio: env('USE_SOURCE_AUDIO', 'false') === 'true',
  textSize: parseInt(env('OVERLAY_TEXT_SIZE', '36'), 10),
  textY: env('OVERLAY_TEXT_Y', 'h-th-120'),
  image: env('OVERLAY_IMAGE', ''),
  imageW: env('OVERLAY_IMAGE_WIDTH', '200'),
  imageX: env('OVERLAY_IMAGE_X', '20'),
  imageY: env('OVERLAY_IMAGE_Y', '20'),
  pip: env('OVERLAY_VIDEO', ''),
  pipW: env('OVERLAY_VIDEO_WIDTH', '240'),
  pipX: env('OVERLAY_VIDEO_X', 'W-w-20'),
  pipY: env('OVERLAY_VIDEO_Y', '20'),
  stateUrl: env('PREVIEW_STATE_URL', ''),
  font: env('FONT_FILE', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf')
}

// ---------- settings (env defaults, overridden by what you save in the panel) ----------
function envDefaults () {
  const d = {
    mode: env('SOURCE_MODE', 'video'),      // video | image | web
    source: env('SOURCE', 'assets/loop.mp4'),
    res: env('RESOLUTION', '720x1280'),     // 720x1280 portrait (TikTok) | 1280x720 landscape (YouTube/Twitch)
    text: env('OVERLAY_TEXT', 'Now watching: {target}'),
    platforms: {}
  }
  for (const [id, p] of Object.entries(PLATFORMS)) {
    const U = id.toUpperCase()
    const key = env(`${U}_STREAM_KEY`, '')
    d.platforms[id] = {
      enabled: env(`${U}_ENABLED`, key ? 'true' : 'false') === 'true',
      url: env(`${U}_RTMP_URL`, p.url),
      key,
      res: env(`${U}_RESOLUTION`, '')       // optional per-platform resolution
    }
  }
  return d
}

function loadSettings () {
  const d = envDefaults()
  try {
    const saved = JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'))
    for (const k of ['mode', 'source', 'res', 'text']) if (saved[k] !== undefined) d[k] = saved[k]
    for (const id of Object.keys(PLATFORMS)) {
      if (saved.platforms && saved.platforms[id]) Object.assign(d.platforms[id], saved.platforms[id])
    }
  } catch (e) { /* nothing saved yet */ }
  return d
}

function saveSettings () {
  fs.mkdirSync(DATA_DIR, { recursive: true })
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2), { mode: 0o600 })
}

let settings = loadSettings()

// ---------- logging (stream keys are never printed) ----------
const logs = []
function redact (s) {
  for (const p of Object.values(settings.platforms)) {
    if (p.key && p.key.length > 3) s = s.split(p.key).join('***')
  }
  return s
}
function log (line) {
  const l = `${new Date().toISOString().slice(11, 19)} ${redact(String(line))}`
  logs.push(l.slice(0, 400))
  if (logs.length > 40) logs.shift()
  console.log(l)
}

const isUrl = (s) => /^https?:\/\//i.test(s)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const validRes = (r) => /^\d{3,4}x\d{3,4}$/.test(r)

// ---------- live overlay text ----------
function writeText (t) {
  const tmp = TEXT_FILE + '.tmp'
  fs.writeFileSync(tmp, t)
  fs.renameSync(tmp, TEXT_FILE) // atomic: ffmpeg never reads a half-written file
}

async function refreshText () {
  let t = settings.text || ' '
  if (fixed.stateUrl) {
    try {
      const s = await (await fetch(fixed.stateUrl)).json()
      t = t
        .split('{target}').join(s.target && s.target !== '-' ? s.target : 'patrolling')
        .split('{mode}').join(s.now || s.mode || '')
        .split('{players}').join(String((s.players || []).length))
    } catch (e) {
      t = t.split('{target}').join('...').split('{mode}').join('').split('{players}').join('?')
    }
  } else {
    t = t.split('{target}').join('').split('{mode}').join('').split('{players}').join('')
  }
  writeText(t.trim() || ' ')
}

// ---------- ffmpeg ----------
function buildArgs (s, res, dests) {
  const [W, H] = res.split('x').map(Number)
  const a = ['-hide_banner', '-loglevel', 'warning', '-nostats']
  let idx = 0
  const loopIn = (src) => ['-stream_loop', '-1', '-re', '-i', src]

  if (s.mode === 'video') a.push(...loopIn(s.source))
  else if (s.mode === 'image') a.push('-loop', '1', '-framerate', String(fixed.fps), '-re', '-i', s.source)
  else a.push('-f', 'x11grab', '-framerate', String(fixed.fps), '-video_size', s.res, '-i', ':99.0')
  const mainIdx = idx++

  let imgIdx = -1
  if (fixed.image) { a.push('-loop', '1', '-framerate', String(fixed.fps), '-i', fixed.image); imgIdx = idx++ }
  let pipIdx = -1
  if (fixed.pip) { a.push(...loopIn(fixed.pip)); pipIdx = idx++ }

  let audioMap
  if (fixed.music) { a.push(...loopIn(fixed.music)); audioMap = `${idx++}:a` }
  else if (s.mode === 'video' && fixed.useSourceAudio) audioMap = `${mainIdx}:a`
  else { a.push('-f', 'lavfi', '-i', 'anullsrc=r=44100:cl=stereo'); audioMap = `${idx++}:a` }

  const f = []
  f.push(`[${mainIdx}:v]scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},setsar=1,fps=${fixed.fps}[base]`)
  let last = 'base'
  if (imgIdx >= 0) {
    f.push(`[${imgIdx}:v]scale=${fixed.imageW}:-1[img]`)
    f.push(`[${last}][img]overlay=${fixed.imageX}:${fixed.imageY}[v1]`); last = 'v1'
  }
  if (pipIdx >= 0) {
    f.push(`[${pipIdx}:v]scale=${fixed.pipW}:-1[pip]`)
    f.push(`[${last}][pip]overlay=${fixed.pipX}:${fixed.pipY}:shortest=0[v2]`); last = 'v2'
  }
  f.push(`[${last}]drawtext=fontfile=${fixed.font}:textfile=${TEXT_FILE}:reload=1:fontsize=${fixed.textSize}` +
    `:fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=14:x=(w-text_w)/2:y=${fixed.textY}[vout]`)

  a.push('-filter_complex', f.join(';'))
  a.push('-map', '[vout]', '-map', audioMap)
  const br = parseInt(fixed.bitrate, 10)
  a.push('-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency', '-pix_fmt', 'yuv420p',
    '-b:v', fixed.bitrate, '-maxrate', fixed.bitrate, '-bufsize', `${br * 2}k`, '-g', String(fixed.fps * 2),
    '-c:a', 'aac', '-b:a', '128k', '-ar', '44100', '-shortest')

  const target = (d) => `${d.url.replace(/\/+$/, '')}/${d.key}`
  if (dests.length === 1) a.push('-f', 'flv', target(dests[0]))
  else a.push('-f', 'tee', dests.map((d) => `[f=flv:onfail=ignore]${target(d)}`).join('|')) // one failing platform won't stop the others
  return a
}

// ---------- process control ----------
let running = false
let runId = 0
const procs = new Set()

function spawnQuiet (cmd, args, extraEnv) {
  const p = spawn(cmd, args, { stdio: 'ignore', env: { ...process.env, ...(extraEnv || {}) } })
  procs.add(p)
  p.on('exit', () => procs.delete(p))
  p.on('error', (e) => log(`[${cmd}] ${e.message}`))
  return p
}

function spawnFfmpeg (res, args) {
  const p = spawn('ffmpeg', args, { stdio: ['ignore', 'ignore', 'pipe'] })
  procs.add(p)
  p.stderr.on('data', (d) => log(`[ffmpeg ${res}] ${d.toString().trim().slice(0, 300)}`))
  p.on('exit', () => procs.delete(p))
  return p
}

async function startWebCapture (myRun) {
  const [W, H] = settings.res.split('x')
  spawnQuiet('Xvfb', [':99', '-screen', '0', `${W}x${H}x24`, '-nolisten', 'tcp'])
  await sleep(1500)
  if (myRun !== runId) return
  spawnQuiet('chromium', [
    '--no-sandbox', '--kiosk', `--window-size=${W},${H}`, '--window-position=0,0',
    '--user-data-dir=/tmp/chrome', '--autoplay-policy=no-user-gesture-required',
    '--disable-infobars', '--hide-scrollbars', '--force-device-scale-factor=1', settings.source
  ], { DISPLAY: ':99' })
  await sleep(5000) // let the page load
}

function enabledDests () {
  return Object.entries(settings.platforms)
    .filter(([, p]) => p.enabled && p.key)
    .map(([id, p]) => ({ id, label: PLATFORMS[id].label, url: p.url, key: p.key, res: validRes(p.res) ? p.res : settings.res }))
}

async function superviseGroup (myRun, res, dests) {
  while (running && myRun === runId) {
    const args = buildArgs(settings, res, dests)
    if (DRY_RUN) { log('DRY_RUN ffmpeg ' + args.join(' ')); return }
    log(`ffmpeg start ${res} -> ${dests.map((d) => d.label).join(', ')}`)
    const p = spawnFfmpeg(res, args)
    const code = await new Promise((r) => { p.on('exit', r); p.on('error', r) })
    if (!running || myRun !== runId) break
    log(`ffmpeg exited (${code}), restarting in 5s`)
    await sleep(5000)
  }
}

async function start () {
  if (running) return 'Already running.'
  const dests = enabledDests()
  if (!dests.length) return 'Nothing to start: enable a platform and enter its stream key.'
  if (!validRes(settings.res)) return 'Invalid resolution (use e.g. 720x1280).'
  if (settings.mode !== 'web' && !isUrl(settings.source) && !fs.existsSync(settings.source)) {
    return `Source not found: ${settings.source}. Upload it to the repo assets folder or use an http(s) URL.`
  }
  running = true
  const myRun = ++runId
  if (settings.mode === 'web') {
    await startWebCapture(myRun)
    if (myRun !== runId) return 'Stopped.'
  }
  const groups = new Map()
  for (const d of dests) {
    if (!groups.has(d.res)) groups.set(d.res, [])
    groups.get(d.res).push(d)
  }
  for (const [res, list] of groups) superviseGroup(myRun, res, list)
  return 'Started: ' + dests.map((d) => d.label).join(', ')
}

function stop () {
  running = false
  runId++
  for (const p of procs) { try { p.kill('SIGTERM') } catch (e) { /* already gone */ } }
  procs.clear()
  return 'Stopped.'
}

// ---------- control panel ----------
function tokenOk (t) {
  const a = Buffer.from(String(t || ''))
  const b = Buffer.from(PANEL_TOKEN)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

function panelHtml (msg) {
  const s = settings
  const platformBlocks = Object.entries(PLATFORMS).map(([id, p]) => {
    const c = s.platforms[id]
    return `<fieldset><legend>${p.label}</legend>
      <label class="chk"><input type="checkbox" name="${id}_enabled" ${c.enabled ? 'checked' : ''}> Enabled</label>
      <label>Server URL<input name="${id}_url" value="${esc(c.url)}"></label>
      <label>Stream key <small>${c.key ? '(saved, leave blank to keep)' : '(not set)'}</small>
        <input type="password" name="${id}_key" autocomplete="off"></label>
      <label>Resolution (optional)<input name="${id}_res" value="${esc(c.res)}" placeholder="${esc(s.res)}"></label>
    </fieldset>`
  }).join('')
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Streamer panel</title>
  <style>
    body{font-family:system-ui;background:#0d1117;color:#e6edf3;margin:0;padding:16px;max-width:560px}
    fieldset{border:1px solid #30363d;border-radius:10px;margin:12px 0;padding:10px 12px}
    label{display:block;margin:8px 0;font-size:14px} input,select{width:100%;box-sizing:border-box;padding:9px;margin-top:4px;
      background:#161b22;color:#e6edf3;border:1px solid #30363d;border-radius:6px;font-size:16px}
    .chk input{width:auto;margin-right:8px} button{padding:11px 16px;margin:4px 4px 4px 0;border-radius:8px;border:0;font-size:16px;
      background:#238636;color:#fff} button.stop{background:#da3633} button.alt{background:#30363d}
    .msg{background:#1f6feb33;border:1px solid #1f6feb;border-radius:8px;padding:8px 12px;margin:10px 0}
    pre{background:#161b22;border-radius:8px;padding:10px;font-size:12px;white-space:pre-wrap;word-break:break-all}
    a{color:#58a6ff}
  </style>
  <h2>Streamer panel</h2>
  <div>Status: <b>${running ? 'LIVE (running)' : 'stopped'}</b> | <a href="/?token=${esc(PANEL_TOKEN)}">refresh</a></div>
  ${msg ? `<div class="msg">${esc(msg)}</div>` : ''}
  <form method="POST" action="/save?token=${esc(PANEL_TOKEN)}">
    <fieldset><legend>Source</legend>
      <label>Mode<select name="mode">
        ${['video', 'image', 'web'].map((m) => `<option value="${m}" ${s.mode === m ? 'selected' : ''}>${m}</option>`).join('')}
      </select></label>
      <label>Source (file path, http(s) URL, or preview page URL for web mode)<input name="source" value="${esc(s.source)}"></label>
      <label>Resolution<input name="res" value="${esc(s.res)}"></label>
      <label>Overlay text ({target} {mode} {players})<input name="text" value="${esc(s.text)}"></label>
    </fieldset>
    ${platformBlocks}
    <button name="action" value="save" class="alt">Save</button>
    <button name="action" value="start">Save &amp; Start</button>
    <button name="action" value="stop" class="stop">Stop</button>
  </form>
  <h3>Log</h3><pre>${esc(logs.join('\n') || 'no log yet')}</pre>`
}

function readBody (req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => { data += c; if (data.length > 65536) { reject(new Error('too large')); req.destroy() } })
    req.on('end', () => resolve(data))
    req.on('error', reject)
  })
}

function startPanel () {
  http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://x')
      if (url.pathname === '/health') { res.end('ok'); return }
      if (!tokenOk(url.searchParams.get('token'))) {
        res.statusCode = 401; res.end('Unauthorized. Open /?token=YOUR_PANEL_TOKEN'); return
      }
      if (req.method === 'POST' && url.pathname === '/save') {
        const b = qs.parse(await readBody(req))
        let msg = ''
        if (b.action !== 'stop') {
          if (['video', 'image', 'web'].includes(b.mode)) settings.mode = b.mode
          if (b.source) settings.source = String(b.source).trim()
          if (validRes(String(b.res || ''))) settings.res = b.res
          settings.text = String(b.text || '')
          for (const id of Object.keys(PLATFORMS)) {
            const c = settings.platforms[id]
            c.enabled = b[`${id}_enabled`] === 'on'
            if (b[`${id}_url`]) c.url = String(b[`${id}_url`]).trim()
            if (b[`${id}_key`]) c.key = String(b[`${id}_key`]).trim()
            const r = String(b[`${id}_res`] || '').trim()
            c.res = validRes(r) ? r : ''
          }
          saveSettings()
          msg = 'Saved. '
        }
        if (b.action === 'start') { if (running) stop(); msg += await start() }
        if (b.action === 'stop') msg += stop()
        res.statusCode = 302
        res.setHeader('Location', `/?token=${encodeURIComponent(PANEL_TOKEN)}&msg=${encodeURIComponent(msg)}`)
        res.end()
        return
      }
      res.setHeader('Content-Type', 'text/html; charset=utf-8')
      res.end(panelHtml(url.searchParams.get('msg') || ''))
    } catch (e) {
      res.statusCode = 500; res.end('error')
    }
  }).listen(PANEL_PORT, () => log(`panel on port ${PANEL_PORT}`))
}

// ---------- boot ----------
function shutdown () {
  stop()
  setTimeout(() => process.exit(0), 1500)
}
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)

startPanel()
if (GENERATED_TOKEN) log(`PANEL_TOKEN not set. Temporary panel token: ${PANEL_TOKEN} (set PANEL_TOKEN to keep it fixed)`)
refreshText().catch(() => {})
setInterval(() => refreshText().catch(() => {}), 2000)

if (env('AUTOSTART', 'true') === 'true' && enabledDests().length) {
  start().then((m) => log(m))
} else {
  log('Idle. Open the panel, enter a stream key and press Save & Start.')
}
