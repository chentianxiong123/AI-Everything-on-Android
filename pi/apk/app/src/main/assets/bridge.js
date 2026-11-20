#!/usr/bin/env node
/**
 * bridge.js — Pi Runner 的 WebView ↔ pi 引擎桥梁。
 *
 * 职责：
 *   1. HTTP 服务（默认 127.0.0.1:8765）给 WebView 提供聊天 UI（index.html）
 *   2. REST API：
 *        POST /api/prompt  { message }  -> SSE 流式返回 pi 事件
 *        GET  /api/status              -> 引擎状态
 *        POST /api/stop                -> 中止当前 run
 *   3. 持有一个 pi RPC 子进程（pi --mode rpc --no-session），stdin/stdout JSONL 双向转发。
 *
 * 关键：pi RPC 不依赖 TUI（pi-tui native 在 android 上自动跳过加载），
 * 故本桥在纯 node + pi dist 下即可运行。
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const WORK = __dirname;
const CFG_PATH = path.join(WORK, 'pi-config.json');

function loadCfg() {
  try {
    return JSON.parse(fs.readFileSync(CFG_PATH, 'utf8'));
  } catch (e) {
    return { port: 8765, provider: 'anthropic', apiKey: '', model: 'claude-sonnet-4-5', runtimeDir: '' };
  }
}
const CFG = loadCfg();
const PORT = Number(CFG.port || 8765);

const KEY_ENV_MAP = {
  anthropic: 'ANTHROPIC_API_KEY',
  openai: 'OPENAI_API_KEY',
  deepseek: 'DEEPSEEK_API_KEY',
  openrouter: 'OPENROUTER_API_KEY',
  groq: 'GROQ_API_KEY',
  mistral: 'MISTRAL_API_KEY',
  xai: 'XAI_API_KEY',
  gemini: 'GEMINI_API_KEY',
  google: 'GEMINI_API_KEY',
  nvidia: 'NVIDIA_API_KEY',
  fireworks: 'FIREWORKS_API_KEY',
  together: 'TOGETHER_API_KEY',
};

function findPiCli() {
  const rd = CFG.runtimeDir || '';
  const candidates = [];
  if (rd) {
    candidates.push(path.join(rd, 'pi/dist/bundle/cli.js'));
    candidates.push(path.join(rd, 'lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js'));
    candidates.push(path.join(rd, 'node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js'));
  }
  candidates.push('/data/data/com.termux/files/usr/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js');
  for (const c of candidates) {
    try { if (fs.existsSync(c)) return c; } catch (e) {}
  }
  return null;
}

function log(line) {
  const ts = new Date().toISOString();
  process.stdout.write(`[${ts}] ${line}\n`);
}

// ---------- pi 引擎（单例 RPC 子进程） ----------
let piProc = null;
let piBuf = '';
let reqCounter = 0;
const pending = new Map();   // id -> {resolve,reject}
let streamClients = new Set(); // 当前订阅事件的 SSE res

function piEnv() {
  const env = Object.assign({}, process.env);
  env.HOME = WORK;
  env.TMPDIR = WORK;
  const provider = (CFG.provider || '').toLowerCase();
  if (CFG.apiKey) {
    const envName = KEY_ENV_MAP[provider];
    if (envName) env[envName] = CFG.apiKey;
    // 兜底：同时设一个通用名，某些 provider 解析会用
  }
  return env;
}

function piArgs() {
  const args = ['--mode', 'rpc', '--no-session'];
  if (CFG.model) {
    if (CFG.provider) args.push('--provider', CFG.provider);
    args.push('--model', CFG.model);
  }
  if (CFG.apiKey) args.push('--api-key', CFG.apiKey);
  return args;
}

function broadcast(line) {
  for (const res of streamClients) {
    try { res.write(`data: ${line}\n\n`); } catch (e) {}
  }
}

function handlePiEvent(line) {
  let ev;
  try { ev = JSON.parse(line); } catch (e) {
    log('[pi.raw] ' + line.slice(0, 200));
    return;
  }
  const type = ev.type;

  // command ack
  if (type === 'response' && ev.id && pending.has(ev.id)) {
    const p = pending.get(ev.id);
    pending.delete(ev.id);
    try { p.resolve(ev); } catch (e) {}
    // 仍然广播 ack（前端可显示提交状态）
    broadcast(line);
    return;
  }

  // 广播所有事件给前端
  broadcast(line);

  if (type === 'agent_start') log('pi agent_start');
  if (type === 'agent_settled' || type === 'agent_end') {
    log('pi run settled');
    for (const res of streamClients) {
      try { res.write('event: done\ndata: {}\n\n'); res.end(); } catch (e) {}
    }
    streamClients.clear();
  }
}

function startPi() {
  if (piProc && piProc.exitCode === null) return true;
  const cli = findPiCli();
  if (!cli) {
    log('[ERROR] 找不到 pi cli.js');
    return false;
  }
  const args = piArgs();
  log(`spawn pi: node ${cli} ${args.join(' ')}`);
  piProc = spawn(process.execPath, [cli, ...args], {
    cwd: WORK,
    env: piEnv(),
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  piBuf = '';
  piProc.stdout.setEncoding('utf8');
  piProc.stdout.on('data', (chunk) => {
    piBuf += chunk;
    let nl;
    while ((nl = piBuf.indexOf('\n')) !== -1) {
      const line = piBuf.slice(0, nl).trim();
      piBuf = piBuf.slice(nl + 1);
      if (line) handlePiEvent(line);
    }
  });

  piProc.stderr.setEncoding('utf8');
  piProc.stderr.on('data', (d) => log('[pi.stderr] ' + d.trim()));

  piProc.on('exit', (code, sig) => {
    log(`pi 退出 code=${code} sig=${sig}`);
    piProc = null;
    for (const [id, p] of pending) { try { p.reject(new Error('pi 进程退出')); } catch (e) {} }
    pending.clear();
  });

  return true;
}

function ensurePi() {
  if (!piProc || piProc.exitCode !== null) return startPi();
  return true;
}

function sendPrompt(message) {
  return new Promise((resolve, reject) => {
    if (!ensurePi()) return reject(new Error('pi 启动失败'));
    const id = `req-${++reqCounter}`;
    pending.set(id, { resolve, reject });
    const cmd = JSON.stringify({ id, type: 'prompt', message });
    log(`-> pi: ${cmd.slice(0, 140)}`);
    try {
      piProc.stdin.write(cmd + '\n');
    } catch (e) {
      pending.delete(id);
      return reject(e);
    }
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error('prompt ack 超时'));
      }
    }, 15000);
  });
}

// ---------- HTTP ----------
const INDEX_HTML = path.join(WORK, 'index.html');

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const p = url.pathname;

  if (p === '/' || p === '/index.html') {
    fs.readFile(INDEX_HTML, 'utf8', (err, data) => {
      if (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('index.html 缺失: ' + err.message);
        return;
      }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(data);
    });
    return;
  }

  if (p === '/api/status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      piRunning: !!(piProc && piProc.exitCode === null),
      provider: CFG.provider || '',
      model: CFG.model || '',
      hasKey: !!CFG.apiKey,
      port: PORT,
    }));
    return;
  }

  if (p === '/api/prompt' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 2 * 1024 * 1024) req.destroy(); });
    req.on('end', () => {
      let message = '';
      try { message = JSON.parse(body).message || ''; } catch (e) { message = body; }
      if (!message.trim()) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'message 为空' }));
        return;
      }

      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
      });
      res.write(': connected\n\n');
      streamClients.add(res);

      const hb = setInterval(() => { try { res.write(': hb\n\n'); } catch (e) {} }, 15000);
      res.on('close', () => {
        clearInterval(hb);
        streamClients.delete(res);
      });

      sendPrompt(message).catch((err) => {
        log('prompt 失败: ' + err.message);
        try {
          res.write(`event: error\ndata: ${JSON.stringify({ error: err.message })}\n\n`);
        } catch (e) {}
      });
    });
    return;
  }

  if (p === '/api/stop' && req.method === 'POST') {
    try { piProc && piProc.kill('SIGINT'); } catch (e) {}
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
});

server.listen(PORT, '127.0.0.1', () => {
  log(`bridge listening http://127.0.0.1:${PORT}`);
  log(`provider=${CFG.provider} model=${CFG.model} key=${CFG.apiKey ? '***' : '(none)'}`);
  log(`pi cli: ${findPiCli() || 'NOT FOUND'}`);
  startPi(); // 预热
});

process.on('SIGTERM', () => { try { piProc && piProc.kill(); } catch (e) {} process.exit(0); });
process.on('SIGINT', () => { try { piProc && piProc.kill(); } catch (e) {} process.exit(0); });
