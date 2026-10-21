// SpectatorCamera relay.
// The Minecraft plugin PUSHES live data to this service (outbound only, so the Minecraft host needs no open port).
// This service serves the watch page to any browser. Deploy it on Railway from GitHub and share its link.

const http = require('http')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const PORT = parseInt(process.env.PORT || '3000', 10)
const PUSH_KEY = process.env.PUSH_KEY || ''        // secret the plugin uses to push (relay.key in config.yml)
const VIEW_TOKEN = process.env.VIEW_TOKEN || ''    // token in the watch link (empty = anyone with the URL can watch)
const STALE_MS = parseInt(process.env.STALE_MS || '10000', 10)

if (PUSH_KEY.length < 8) {
  console.error('Set PUSH_KEY (at least 8 characters). It must match relay.key in the plugin config.yml')
  process.exit(1)
}

const INDEX = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8')
const OFFLINE_STATE = JSON.stringify({ mode: 'OFFLINE', now: 'OFFLINE', target: '-', viewers: 0, players: [] })
const NO_OVERLAY = JSON.stringify({ enabled: false })

let latest = { state: OFFLINE_STATE, overlay: NO_OVERLAY, at: 0 }
let connected = false

function safeEq (a, b) {
  const x = Buffer.from(String(a || ''))
  const y = Buffer.from(String(b))
  return x.length === y.length && crypto.timingSafeEqual(x, y)
}

function viewOk (url) {
  return !VIEW_TOKEN || safeEq(url.searchParams.get('token'), VIEW_TOKEN)
}

function readBody (req, limit) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => {
      data += c
      if (data.length > limit) { reject(new Error('too large')); req.destroy() }
    })
    req.on('end', () => resolve(data))
    req.on('error', reject)
  })
}

function send (res, code, type, body) {
  res.statusCode = code
  res.setHeader('Content-Type', type)
  res.setHeader('Cache-Control', 'no-store')
  res.end(body)
}

http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x')

    if (url.pathname === '/health') return send(res, 200, 'text/plain', 'ok')

    // plugin -> relay
    if (req.method === 'POST' && url.pathname === '/push') {
      if (!safeEq(req.headers['x-relay-key'], PUSH_KEY)) return send(res, 401, 'text/plain', 'bad key')
      let j
      try { j = JSON.parse(await readBody(req, 65536)) } catch (e) { return send(res, 400, 'text/plain', 'bad json') }
      if (!j || typeof j.state !== 'object') return send(res, 400, 'text/plain', 'missing state')
      latest = {
        state: JSON.stringify(j.state),
        overlay: JSON.stringify(j.overlay && typeof j.overlay === 'object' ? j.overlay : { enabled: false }),
        at: Date.now()
      }
      if (!connected) { connected = true; console.log('[relay] plugin connected') }
      res.statusCode = 204
      return res.end()
    }

    // browser -> relay
    if (!viewOk(url)) return send(res, 401, 'text/plain', 'Unauthorized. Open /?token=YOUR_VIEW_TOKEN')

    if (url.pathname === '/api/state') {
      const fresh = Date.now() - latest.at < STALE_MS
      if (!fresh && connected) { connected = false; console.log('[relay] plugin lost (no data)') }
      return send(res, 200, 'application/json', fresh ? latest.state : OFFLINE_STATE)
    }
    if (url.pathname === '/api/overlay') return send(res, 200, 'application/json', latest.overlay)

    if (url.pathname === '/') return send(res, 200, 'text/html; charset=utf-8', INDEX)

    send(res, 404, 'text/plain', 'Not found')
  } catch (e) {
    send(res, 500, 'text/plain', 'error')
  }
}).listen(PORT, () => console.log(`[relay] listening on ${PORT}`))
