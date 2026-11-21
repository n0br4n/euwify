// EufyGuard Node service.
// Runs inside the app. Talks to Eufy with eufy-security-client and offers a
// small HTTP API on 127.0.0.1 for the Kotlin side of the app.
//
// Arguments (passed by the app):  --data <dir>  --secret <random string>
//   <dir>     private folder for Eufy session data and the api-port file
//   <secret>  every HTTP request must send it in the "X-Token" header
//
// Never log passwords, codes, tokens or the secret.

'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// ---------- arguments ----------
function arg(name) {
  const i = process.argv.indexOf('--' + name);
  return i > 0 ? process.argv[i + 1] : undefined;
}
const DATA_DIR = arg('data') || path.join(__dirname, 'data');
const SECRET = arg('secret');
if (!SECRET || SECRET.length < 32) {
  console.log('[eufyguard] missing or too short --secret, refusing to start');
  process.exit(2);
}
fs.mkdirSync(DATA_DIR, { recursive: true });
const SESSION_DIR = path.join(DATA_DIR, 'eufy-session');

// ---------- safe logging ----------
// Long tokens, emails and JSON values of sensitive keys are masked.
function redact(text) {
  return String(text)
    .replace(/("?(password|token|auth_token|cloud_token|verify_code|verifyCode|captcha\w*|secret|private_key|p2p_did|dsk_key|app_conn|wipn|login_hash|gtoken|user_id)"?\s*[:=]\s*)("[^"]*"|[^\s,}]+)/gi, '$1"***"')
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '***@***')
    .replace(/[A-Za-z0-9+/=_-]{32,}/g, '***');
}
// Lines also go to node-activity.log, which the app shows as "Recent activity".
const ACTIVITY_FILE = path.join(DATA_DIR, 'node-activity.log');
let activityWrites = 0;
function log(...parts) {
  const line = redact(parts.map(p => (p instanceof Error ? p.message : typeof p === 'string' ? p : JSON.stringify(p))).join(' ')).slice(0, 500);
  console.log('[eufyguard] ' + line);
  try {
    fs.appendFileSync(ACTIVITY_FILE, Date.now() + '\t' + line.replace(/\n/g, ' ') + '\n');
    if (++activityWrites % 50 === 0) {
      const lines = fs.readFileSync(ACTIVITY_FILE, 'utf8').split('\n').filter(Boolean);
      if (lines.length > 300) fs.writeFileSync(ACTIVITY_FILE, lines.slice(-300).join('\n') + '\n');
    }
  } catch (e) { /* logging must never break the service */ }
}
const libLogger = {
  trace() {}, debug() {}, info() {},
  warn: (m) => log('lib warn:', m),
  error: (m) => log('lib error:', m),
  fatal: (m) => log('lib fatal:', m),
};

// ---------- Eufy state ----------
let lib = null;           // the eufy-security-client module (loaded once)
let eufy = null;          // EufySecurity instance
let state = 'logged_out'; // logged_out | connecting | need_2fa | need_captcha | connected | error
let lastError = null;
let captcha = null;       // { id, image }  image is a data: URL

function setState(s, err) {
  state = s;
  lastError = err ? redact(err instanceof Error ? err.message : String(err)).slice(0, 300) : null;
  log('state ->', s, lastError ? '(' + lastError + ')' : '');
}

function loadLib() {
  if (!lib) {
    lib = require('eufy-security-client');
    log('eufy-security-client loaded');
  }
  return lib;
}

async function closeEufy() {
  if (eufy) {
    try { eufy.close(); } catch (e) { /* ignore */ }
    eufy = null;
  }
}

async function login({ email, password, country }) {
  if (!email || !password) throw httpError(400, 'email and password are required');
  country = String(country || 'NO').toUpperCase();
  if (!/^[A-Z]{2}$/.test(country)) throw httpError(400, 'country must be a 2-letter code like NO');

  await closeEufy();
  captcha = null;
  setState('connecting');
  const { EufySecurity } = loadLib();
  fs.mkdirSync(SESSION_DIR, { recursive: true });

  try {
    eufy = await EufySecurity.initialize({
    username: String(email),
    password: String(password),
    country,
    language: 'en',
    trustedDeviceName: 'EuWiFy',
    persistentDir: SESSION_DIR,
    p2pConnectionSetup: 2,       // QUICKEST
    pollingIntervalMinutes: 10,
    eventDurationSeconds: 10,
    acceptInvitations: false,
  }, libLogger);
  } catch (e) {
    eufy = null;
    setState('error', e);
    throw httpError(502, 'Eufy library could not start: ' + (e.message || e));
  }

  const me = eufy;
  me.on('connect', () => { if (eufy === me) { captcha = null; setState('connected'); } });
  me.on('close', () => { if (eufy === me && state === 'connected') setState('connecting'); });
  me.on('connection error', (e) => { if (eufy === me) setState('error', e); });
  me.on('tfa request', () => { if (eufy === me) setState('need_2fa'); });
  me.on('captcha request', (id, image) => {
    if (eufy === me) { captcha = { id, image }; setState('need_captcha'); }
  });
  me.on('station guard mode', (st, mode) => log('guard mode event:', st.getName(), '=', modeName(mode)));
  me.on('station connect', (st) => log('station connected:', st.getName()));
  me.on('station close', (st) => log('station connection closed:', st.getName()));

  me.connect().catch((e) => { if (eufy === me) setState('error', e); });
}

async function sendCode(options) {
  if (!eufy) throw httpError(409, 'not logged in');
  setState('connecting');
  eufy.connect({ ...options, force: false }).catch((e) => setState('error', e));
}

async function logout() {
  await closeEufy();
  captcha = null;
  fs.rmSync(SESSION_DIR, { recursive: true, force: true });
  setState('logged_out');
}

// ---------- devices and guard mode ----------
const MODES = { away: 0, home: 1, disarmed: 63 };
function modeName(v) {
  for (const [k, n] of Object.entries(MODES)) if (n === v) return k;
  return String(v);
}

function requireConnected() {
  if (!eufy || state !== 'connected') throw httpError(409, 'not connected (state: ' + state + ')');
}

async function listDevices() {
  requireConnected();
  const stations = await eufy.getStations();
  const devices = await eufy.getDevices();
  return {
    stations: stations.map(s => ({
      serial: s.getSerial(), name: s.getName(), model: s.getModel(),
      guardMode: modeName(s.getGuardMode()), currentMode: modeName(s.getCurrentMode()),
      p2pConnected: s.isConnected(),
    })),
    devices: devices.map(d => ({
      serial: d.getSerial(), name: d.getName(), model: d.getModel(), station: d.getStationSerial(),
    })),
  };
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// Only one mode change at a time; a second request waits for the first.
let modeQueue = Promise.resolve();
function setMode(stationSerial, modeKey, reconnect) {
  const run = modeQueue.then(() => setModeNow(stationSerial, modeKey, reconnect));
  modeQueue = run.catch(() => {});
  return run;
}

// Set the mode, then confirm it in one of two ways:
//  - "camera": the camera sends a guard-mode event with the new value, or
//  - "cloud":  no event came (e.g. the mode was already set), so we fetch fresh
//              data from Eufy's cloud and check the value there.
// The cached value alone is never trusted. Retries because the camera
// connection drops now and then.
async function setModeNow(stationSerial, modeKey, reconnect) {
  requireConnected();
  if (!(modeKey in MODES)) throw httpError(400, 'mode must be home, away or disarmed');
  const wanted = MODES[modeKey];
  const station = await eufy.getStation(String(stationSerial));
  const me = eufy;
  const before = station.getGuardMode();

  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      // After the phone's network changed (or a failed try), the old camera
      // connection is often dead without knowing it. Start a fresh one.
      if ((reconnect || attempt > 1) && station.isConnected()) {
        log('closing old station connection before attempt', attempt);
        station.close();
        for (let i = 0; i < 10 && station.isConnected(); i++) await sleep(300);
      }
      if (!station.isConnected()) {
        log('connecting to station, attempt', attempt);
        await me.connectToStation(station.getSerial());
        for (let i = 0; i < 20 && !station.isConnected(); i++) await sleep(500);
      }
      let onMode;
      const viaEvent = new Promise((resolve) => {
        onMode = (st, mode) => {
          if (st.getSerial() === station.getSerial() && mode === wanted) resolve(true);
        };
        me.on('station guard mode', onMode);
        setTimeout(() => resolve(false), 15000);
      });
      log('attempt', attempt + ': sending', modeKey.toUpperCase(), station.isConnected() ? '(camera connected)' : '(camera NOT connected)');
      let gotEvent = false;
      try {
        await me.setStationProperty(station.getSerial(), 'guardMode', wanted);
        gotEvent = await viaEvent;
      } finally {
        me.removeListener('station guard mode', onMode);
      }
      let via = gotEvent ? 'camera' : null;
      if (!via) {
        log('no reply from camera in 15 s, checking Eufy cloud');
        await me.refreshCloudData();
        if (station.getGuardMode() === wanted) via = 'cloud';
      }
      if (via) {
        log('mode confirmed by', via + ':', station.getName(), '=', modeKey, 'attempt', attempt);
        return { ok: true, confirmed: true, via, mode: modeKey, changed: before !== wanted, attempts: attempt };
      }
      log('no confirmation, attempt', attempt);
    } catch (e) {
      log('set mode failed, attempt', attempt, e);
    }
    if (attempt < 3) await sleep(3000);
  }
  return { ok: false, confirmed: false, mode: modeName(station.getGuardMode()), attempts: 3 };
}

// ---------- HTTP API ----------
function httpError(code, message) { const e = new Error(message); e.status = code; return e; }

function tokenOk(req) {
  const got = Buffer.from(String(req.headers['x-token'] || ''));
  const want = Buffer.from(SECRET);
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 16 * 1024) { reject(httpError(413, 'body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch (e) { reject(httpError(400, 'invalid JSON')); }
    });
    req.on('error', reject);
  });
}

const routes = {
  'GET /status': async () => ({
    state, error: lastError, captcha: state === 'need_captcha' ? captcha : null,
    nodeVersion: process.version,
    libVersion: lib ? require('eufy-security-client/package.json').version : null,
  }),
  'POST /login': async (b) => { await login(b); return { ok: true }; },
  'POST /verify': async (b) => {
    if (!/^\d{4,8}$/.test(String(b.code || ''))) throw httpError(400, 'code must be digits');
    await sendCode({ verifyCode: String(b.code) }); return { ok: true };
  },
  'POST /captcha': async (b) => {
    if (!captcha) throw httpError(409, 'no captcha pending');
    await sendCode({ captcha: { captchaId: captcha.id, captchaCode: String(b.code || '') } }); return { ok: true };
  },
  'POST /logout': async () => { await logout(); return { ok: true }; },
  'GET /devices': async () => listDevices(),
  'POST /mode': async (b) => setMode(b.station, String(b.mode || '').toLowerCase(), b.reconnect === true),
};

const server = http.createServer(async (req, res) => {
  const send = (code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj));
  };
  if (!tokenOk(req)) return send(401, { error: 'unauthorized' });
  const handler = routes[req.method + ' ' + req.url.split('?')[0]];
  if (!handler) return send(404, { error: 'not found' });
  try {
    const body = req.method === 'POST' ? await readBody(req) : {};
    send(200, await handler(body));
  } catch (e) {
    send(e.status || 500, { error: redact(e.message || String(e)).slice(0, 300) });
  }
});

// If the API cannot start, say so clearly and exit with an error code.
server.on('error', (e) => {
  log('FATAL: API could not start:', e.code || '', e);
  process.exit(3);
});

// Port 0 = let the system pick a free port. The app reads it from api-port.
server.listen(0, '127.0.0.1', () => {
  const port = server.address().port;
  const file = path.join(DATA_DIR, 'api-port');
  fs.writeFileSync(file + '.tmp', String(port));
  fs.renameSync(file + '.tmp', file);
  log('API listening on 127.0.0.1:' + port, 'node', process.version);
  // Load the library now so a broken install shows up at once in the log.
  try { loadLib(); } catch (e) { log('FAILED to load eufy-security-client:', e); }
});

process.on('unhandledRejection', (e) => log('unhandled rejection:', e));
process.on('uncaughtException', (e) => log('uncaught exception:', e));
