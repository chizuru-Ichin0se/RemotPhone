const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const crypto = require('crypto');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Serve web client
app.use(express.static(path.join(__dirname, '..', 'web-client')));

// ─── State ───────────────────────────────────────────────────────
const sessions = new Map();   // sessionCode -> { phone: ws, pc: ws, created: Date }
const CLEANUP_INTERVAL = 60000;
const SESSION_TIMEOUT = 24 * 60 * 60 * 1000; // 24 hours

// ─── Generate 6-digit pairing code ──────────────────────────────
function generateCode() {
  let code;
  do {
    code = crypto.randomInt(100000, 999999).toString();
  } while (sessions.has(code));
  return code;
}

// ─── WebSocket handling ─────────────────────────────────────────
wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.sessionCode = null;
  ws.role = null;

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data) => {
    let msg;
    try {
      // Check if it's a binary message (screen frame)
      if (Buffer.isBuffer(data) || data instanceof ArrayBuffer) {
        // Forward binary data directly to paired PC
        if (ws.role === 'phone' && ws.sessionCode) {
          const session = sessions.get(ws.sessionCode);
          if (session && session.pc && session.pc.readyState === 1) {
            session.pc.send(data);
          }
        }
        return;
      }
      msg = JSON.parse(data.toString());
    } catch (e) {
      ws.send(JSON.stringify({ type: 'error', message: 'Invalid message format' }));
      return;
    }

    switch (msg.type) {
      // ── Phone registers and gets a pairing code ──
      case 'phone_register': {
        const code = generateCode();
        sessions.set(code, {
          phone: ws,
          pc: null,
          created: Date.now(),
          phoneInfo: msg.deviceInfo || {}
        });
        ws.sessionCode = code;
        ws.role = 'phone';
        ws.send(JSON.stringify({
          type: 'registered',
          code: code,
          message: `Pairing code: ${code}`
        }));
        console.log(`📱 Phone registered. Code: ${code}`);
        break;
      }

      // ── PC connects using pairing code ──
      case 'pc_connect': {
        const code = msg.code;
        const session = sessions.get(code);
        if (!session) {
          ws.send(JSON.stringify({ type: 'error', message: 'Invalid code' }));
          return;
        }
        if (session.pc) {
          ws.send(JSON.stringify({ type: 'error', message: 'Session already has a PC connected' }));
          return;
        }
        session.pc = ws;
        ws.sessionCode = code;
        ws.role = 'pc';
        ws.send(JSON.stringify({
          type: 'connected',
          deviceInfo: session.phoneInfo,
          message: 'Connected to phone'
        }));
        // Notify phone
        if (session.phone && session.phone.readyState === 1) {
          session.phone.send(JSON.stringify({
            type: 'pc_connected',
            message: 'PC connected'
          }));
        }
        console.log(`💻 PC connected to session ${code}`);
        break;
      }

      // ── Relay messages between phone and PC ──
      case 'touch':
      case 'key':
      case 'text_input':
      case 'swipe':
      case 'scroll':
      case 'back':
      case 'home':
      case 'recents':
      case 'volume_up':
      case 'volume_down':
      case 'request_screen':
      case 'request_info':
      case 'request_notifications':
      case 'request_sms':
      case 'send_sms':
      case 'request_files':
      case 'download_file':
      case 'upload_file':
      case 'request_apps':
      case 'launch_app':
      case 'request_battery':
      case 'request_clipboard':
      case 'set_clipboard':
      case 'shell_command':
      case 'screen_config': {
        if (!ws.sessionCode) return;
        const session = sessions.get(ws.sessionCode);
        if (!session) return;

        const target = ws.role === 'pc' ? session.phone : session.pc;
        if (target && target.readyState === 1) {
          target.send(JSON.stringify(msg));
        }
        break;
      }

      // ── Phone sends data to PC (notifications, SMS, etc.) ──
      case 'screen_frame':
      case 'notification':
      case 'sms_list':
      case 'sms_sent':
      case 'file_list':
      case 'file_data':
      case 'app_list':
      case 'device_info':
      case 'battery_info':
      case 'clipboard_data':
      case 'shell_result':
      case 'phone_status': {
        if (!ws.sessionCode) return;
        const session = sessions.get(ws.sessionCode);
        if (!session) return;

        const target = ws.role === 'phone' ? session.pc : session.phone;
        if (target && target.readyState === 1) {
          target.send(JSON.stringify(msg));
        }
        break;
      }

      case 'ping': {
        ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
        break;
      }

      default:
        // Forward unknown messages to peer
        if (ws.sessionCode) {
          const session = sessions.get(ws.sessionCode);
          if (session) {
            const target = ws.role === 'pc' ? session.phone : session.pc;
            if (target && target.readyState === 1) {
              target.send(JSON.stringify(msg));
            }
          }
        }
    }
  });

  ws.on('close', () => {
    if (ws.sessionCode) {
      const session = sessions.get(ws.sessionCode);
      if (session) {
        const peer = ws.role === 'pc' ? session.phone : session.pc;
        if (peer && peer.readyState === 1) {
          peer.send(JSON.stringify({
            type: 'peer_disconnected',
            role: ws.role,
            message: `${ws.role} disconnected`
          }));
        }
        if (ws.role === 'phone') {
          sessions.delete(ws.sessionCode);
          console.log(`📱 Phone disconnected. Session ${ws.sessionCode} removed.`);
        } else {
          session.pc = null;
          console.log(`💻 PC disconnected from session ${ws.sessionCode}`);
        }
      }
    }
  });

  ws.on('error', (err) => {
    console.error('WebSocket error:', err.message);
  });
});

// ─── Heartbeat ──────────────────────────────────────────────────
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

// ─── Session cleanup ────────────────────────────────────────────
setInterval(() => {
  const now = Date.now();
  for (const [code, session] of sessions) {
    if (now - session.created > SESSION_TIMEOUT) {
      if (session.phone) session.phone.terminate();
      if (session.pc) session.pc.terminate();
      sessions.delete(code);
      console.log(`🗑️ Expired session ${code} removed`);
    }
  }
}, CLEANUP_INTERVAL);

// ─── REST API for status ────────────────────────────────────────
app.get('/api/status', (req, res) => {
  res.json({
    activeSessions: sessions.size,
    connectedClients: wss.clients.size,
    uptime: process.uptime()
  });
});

// ─── Start ──────────────────────────────────────────────────────
const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`
╔══════════════════════════════════════════════╗
║          🔗 RemotPhone Server                ║
║──────────────────────────────────────────────║
║  Web Client:  http://localhost:${PORT}          ║
║  WebSocket:   ws://localhost:${PORT}            ║
║                                              ║
║  For remote access, use your public IP       ║
║  or deploy to a cloud server.                ║
╚══════════════════════════════════════════════╝
  `);
});
