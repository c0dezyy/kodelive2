// SpectatorCamera Xbox bot
// Logs into your Bedrock/Geyser server with a REAL Microsoft/Xbox account using the
// official device-code login (you open a link and approve; no password is ever typed here).
// The plugin (camera-accounts + auto-enroll) takes over once the account joins.

const bedrock = require('bedrock-protocol')
const http = require('http')
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const cfg = {
  host: process.env.MC_HOST || '127.0.0.1',
  port: parseInt(process.env.MC_PORT || '19132', 10),
  version: process.env.MC_VERSION || null,          // e.g. 1.21.80 (optional)
  label: process.env.ACCOUNT_LABEL || 'camera-bot', // only names the cached login
  authDir: process.env.AUTH_DIR || './auth',        // on Railway: mount a volume at /data and use /data/auth
  webhook: process.env.DISCORD_WEBHOOK || '',       // optional: posts the sign-in link to Discord
  statusPort: parseInt(process.env.PORT || '3000', 10),
  statusToken: process.env.STATUS_TOKEN || ''       // optional: protects the status page (?token=...)
}

const state = { phase: 'starting', pendingAuth: null, lastError: '', since: Date.now() }

// ---- optional: restore cached login from a secret (used by GitHub Actions) ----
function seedAuthFromEnv () {
  const b64 = process.env.AUTH_CACHE_B64
  if (!b64) return
  if (fs.existsSync(cfg.authDir) && fs.readdirSync(cfg.authDir).length > 0) return
  fs.mkdirSync(cfg.authDir, { recursive: true })
  const tmp = path.join(require('os').tmpdir(), 'auth-seed.tgz')
  fs.writeFileSync(tmp, Buffer.from(b64, 'base64'))
  // archive was created from inside the folder: tar -C auth -czf - . | base64
  execSync(`tar -xzf "${tmp}" -C "${cfg.authDir}"`)
  fs.unlinkSync(tmp)
  console.log('[bot] restored cached login from AUTH_CACHE_B64')
}

// ---- status page (shows the Microsoft sign-in link + code) ----
function startStatusServer () {
  http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x')
    if (url.pathname === '/health') { res.end('ok'); return }
    if (cfg.statusToken && url.searchParams.get('token') !== cfg.statusToken) {
      res.statusCode = 401; res.end('Unauthorized. Use /?token=YOURTOKEN'); return
    }
    const a = state.pendingAuth
    const authBlock = a
      ? `<div class="box"><h2>Sign in needed</h2>
         <p>1. Open <a href="${a.url}" target="_blank">${a.url}</a></p>
         <p>2. Enter code <b class="code">${a.code}</b></p>
         <p>3. Sign in with the Microsoft account you want as the camera.</p></div>`
      : ''
    res.setHeader('Content-Type', 'text/html; charset=utf-8')
    res.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <meta http-equiv="refresh" content="5"><title>Xbox camera bot</title>
      <style>body{font-family:system-ui;background:#0d1117;color:#e6edf3;padding:18px}
      .box{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:14px;margin:12px 0}
      .code{font-size:28px;color:#f0883e;letter-spacing:3px}a{color:#58a6ff}</style>
      <h1>Xbox camera bot</h1>
      <div class="box">Status: <b>${state.phase}</b><br>Server: ${cfg.host}:${cfg.port}
      ${state.lastError ? '<br>Last error: ' + String(state.lastError).replace(/</g, '&lt;') : ''}</div>
      ${authBlock}`)
  }).listen(cfg.statusPort, () => console.log(`[bot] status page on port ${cfg.statusPort}`))
}

async function notify (text) {
  if (!cfg.webhook) return
  try {
    await fetch(cfg.webhook, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: text })
    })
  } catch (e) { console.log('[bot] webhook failed:', e.message) }
}

// ---- connection loop ----
let delay = 5000
let retrying = false

function connect () {
  state.phase = 'connecting'
  console.log(`[bot] connecting to ${cfg.host}:${cfg.port} ...`)
  fs.mkdirSync(cfg.authDir, { recursive: true })

  const opts = {
    host: cfg.host,
    port: cfg.port,
    username: cfg.label,
    offline: false,            // real Xbox login
    profilesFolder: cfg.authDir,
    onMsaCode: (data) => {
      const url = data.verification_uri || data.verificationUri || 'https://www.microsoft.com/link'
      const code = data.user_code || data.userCode || '(see message)'
      state.pendingAuth = { url, code }
      state.phase = 'waiting for Microsoft sign-in'
      console.log('\n=== MICROSOFT SIGN-IN ===')
      console.log(`Open ${url} and enter code ${code}`)
      console.log('Use a spare Microsoft account, not your main one.\n')
      notify(`Xbox camera bot needs sign-in: open ${url} and enter code ${code}`)
    }
  }
  if (cfg.version) opts.version = cfg.version
  if (process.env.OFFLINE === 'true') { opts.offline = true; opts.username = cfg.label } // only for Geyser auth-type: offline

  let client
  try {
    client = bedrock.createClient(opts)
  } catch (e) {
    state.lastError = e.message
    console.log('[bot] failed to start:', e.message)
    return retry()
  }

  client.on('spawn', () => {
    state.phase = 'online (camera body active)'
    state.pendingAuth = null
    state.lastError = ''
    delay = 5000
    console.log('[bot] spawned. The plugin now drives this account.')
    notify('Xbox camera bot is online.')
  })
  client.on('kick', (p) => {
    state.lastError = 'kicked: ' + (p && (p.message || JSON.stringify(p)))
    console.log('[bot]', state.lastError)
  })
  client.on('error', (e) => {
    state.lastError = e && e.message
    console.log('[bot] error:', state.lastError)
  })
  client.on('close', () => {
    state.phase = 'disconnected'
    console.log('[bot] disconnected')
    retry()
  })
}

function retry () {
  if (retrying) return
  retrying = true
  console.log(`[bot] reconnecting in ${Math.round(delay / 1000)}s`)
  setTimeout(() => { retrying = false; connect() }, delay)
  delay = Math.min(delay * 2, 60000)
}

seedAuthFromEnv()
startStatusServer()
connect()

// GitHub Actions: stop cleanly before the job time limit so the auth cache can be saved
if (process.env.MAX_RUNTIME_MIN) {
  setTimeout(() => { console.log('[bot] max runtime reached, exiting'); process.exit(0) },
    parseInt(process.env.MAX_RUNTIME_MIN, 10) * 60 * 1000)
}
