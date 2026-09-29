const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const assets = path.join(__dirname, '../../android/app/src/main/assets/browser');
const source = fs.readFileSync(path.join(assets, 'assistance.js'), 'utf8');
const capture = fs.readFileSync(path.join(assets, 'capture.js'), 'utf8');
const exportsBox = { exports: {} };
vm.runInNewContext(source, { module: exportsBox, Uint8Array, Int32Array, ArrayBuffer });
const { encodeRequest, field, methodOf, locateHand } = exportsBox.exports;

const ascii = value => Array.from(value, c => c.charCodeAt(0));
const wrapper = (name, body = []) => [10, name.length, ...ascii(name), 18, body.length, ...body];
const action = step => new Uint8Array([1, ...wrapper('.lq.ActionPrototype', [8, step])]);
const request = (name, id) => new Uint8Array([2, id & 255, id >> 8, ...wrapper(name)]);
const response = (id, body = []) => new Uint8Array([3, id & 255, id >> 8, ...wrapper('', body)]);
const idOf = bytes => bytes[1] | bytes[2] << 8;

async function browser(random = .5) {
  let time = 0, nextTimer = 0;
  const timers = new Map(), captures = [], sent = [], gameMessages = [];
  const document = new EventTarget();
  document.visibilityState = 'visible';
  const win = new EventTarget();
  class Socket extends EventTarget {
    constructor(url) { super(); this.url = url; this.readyState = 1; this.binaryType = 'arraybuffer'; }
    send(data) { sent.push(new Uint8Array(data.buffer || data, data.byteOffset || 0, data.byteLength).slice()); }
    receive(bytes) { this.dispatchEvent(new MessageEvent('message', { data: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) })); }
    close() { this.readyState = 3; this.dispatchEvent(new Event('close')); }
  }
  const math = Object.create(Math);
  math.random = () => random;
  Object.assign(win, {
    window: win, top: win, document, WebSocket: Socket, ArrayBuffer, Uint8Array, Int32Array, Blob, MessageEvent,
    crypto: { randomUUID: () => 'document-1' }, location: { href: 'https://mahjongsoul.game.yo-star.com/' },
    performance: { now: () => time }, Math: math,
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, at: time + delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    AkagiCapture: { postMessage: value => captures.push(JSON.parse(value)) },
  });
  vm.createContext(win);
  vm.runInContext(source + '\n' + capture, win);
  const socket = new win.WebSocket('wss://game.example/socket');
  socket.addEventListener('message', event => gameMessages.push(new Uint8Array(event.data)));
  async function flush() { for (let i = 0; i < 15; i++) await Promise.resolve(); }
  async function advance(ms) {
    const target = time + ms;
    while (true) {
      const pending = [...timers].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
      if (!pending) break;
      time = pending[1].at; timers.delete(pending[0]); pending[1].fn(); await flush();
    }
    time = target; await flush();
  }
  async function turn(step = 1) {
    socket.receive(action(step)); await flush();
    const captured = captures.findLast(item => item.type === 'websocket' && item.direction === 'inbound');
    return {
      id: `document-1/ws-1/${captured.gameRevision}`, generation: 'document-1', connectionId: 'ws-1', revision: captured.gameRevision,
      hand: ['1m', '5pr', 'E'], tileIndices: [1], label: 'Discard', tile: '5pr', timeLimitMs: 20000,
      request: { method: 'inputOperation', type: 1, tile: '0p', moqie: false },
    };
  }
  function configure(plan, options = {}) {
    win.AkagiCapture.onmessage({ data: JSON.stringify({ active: true, autoplay: true, highlight: false, plan, ...options }) });
  }
  await flush();
  return { win, document, socket, captures, sent, gameMessages, timers, flush, advance, turn, configure };
}

test('every move gets a variable delay within the inclusive 1–3 second range', async () => {
  for (const [random, delay] of [[0, 1000], [.37, 1740], [.999999, 3000]]) {
    const b = await browser(random), plan = await b.turn();
    b.configure(plan);
    await b.advance(delay - 1); assert.equal(b.sent.length, 0);
    await b.advance(1); assert.equal(b.sent.length, 1);
    assert.equal(methodOf(b.sent[0]), '.lq.FastTest.inputOperation');
    const body = field(b.sent[0].subarray(3), 2);
    assert.equal(field(body, 1), 1);
    assert.equal(Buffer.from(field(body, 3)).toString(), '0p');
    assert.equal(field(body, 5), 0);
    assert.equal(field(body, 6), Math.floor(delay / 1000));
    b.socket.receive(response(idOf(b.sent[0]))); await b.flush();
    b.configure(plan); await b.advance(5000);
    assert.equal(b.sent.length, 1, 'a repeated recommendation must not send a second move');
  }
});

test('each successive decision draws its own delay', async () => {
  const b = await browser(0);
  b.configure(await b.turn(1)); await b.advance(1000);
  b.socket.receive(response(idOf(b.sent[0]))); await b.flush();
  b.win.Math.random = () => .999999;
  b.configure(await b.turn(2)); await b.advance(2999);
  assert.equal(b.sent.length, 1);
  await b.advance(1); assert.equal(b.sent.length, 2);
});

test('highlight mode alone never sends game inputs', async () => {
  const b = await browser(), plan = await b.turn();
  b.configure(plan, { autoplay: false, highlight: true });
  await b.advance(19000);
  assert.equal(b.sent.length, 0);
});

test('turn changes cancel a pending move before the native worker sees the new frame', async () => {
  const b = await browser(0), old = await b.turn(1);
  b.configure(old); await b.advance(600);
  b.socket.receive(action(2));
  b.configure(old); await b.advance(4000);
  assert.equal(b.sent.length, 0);
});

test('manual discards cancel autoplay immediately, including stale native commands', async () => {
  const b = await browser(0), old = await b.turn();
  b.configure(old); await b.advance(900);
  b.socket.send(encodeRequest({ method: 'inputOperation', type: 1, tile: '1m' }, 7, 1));
  b.configure(old); await b.advance(5000);
  assert.equal(b.sent.length, 1);
  assert.equal(idOf(b.sent[0]), 7);
});

test('manual touch stops automation while a stale enable message is in flight', async () => {
  const b = await browser(0), plan = await b.turn();
  b.configure(plan);
  // EventTarget requires a real Event; its isTrusted property can be shadowed in this harness.
  const event = new Event('pointerdown'); Object.defineProperty(event, 'isTrusted', { value: true });
  b.document.dispatchEvent(event);
  b.configure(plan); await b.advance(2000);
  assert.equal(b.sent.length, 0);
  assert.ok(b.captures.some(item => item.type === 'assistance_status' && item.stopAutoplay));
  b.configure(plan, { autoplay: false, highlight: true });
  b.configure(plan); await b.advance(1000);
  assert.equal(b.sent.length, 1, 'the user can explicitly enable autoplay again');
});

test('backgrounding, an open panel, disabling autoplay and closing the socket all cancel timers', async () => {
  for (const change of [
    (b, p) => b.configure(p, { active: false }),
    (b, p) => b.configure(p, { autoplay: false }),
    b => { b.document.visibilityState = 'hidden'; b.document.dispatchEvent(new Event('visibilitychange')); },
    b => b.socket.close(),
    b => b.win.dispatchEvent(new Event('pagehide')),
  ]) {
    const b = await browser(0), plan = await b.turn(); b.configure(plan);
    change(b, plan); await b.advance(4000);
    assert.equal(b.sent.length, 0);
  }
});

test('wrong documents, wrong sockets, and expired decisions cannot send a move', async () => {
  for (const change of [p => ({ ...p, generation: 'old' }), p => ({ ...p, connectionId: 'ws-old' }), p => ({ ...p, timeLimitMs: 500 })]) {
    const b = await browser(0); b.configure(change(await b.turn())); await b.advance(5000);
    assert.equal(b.sent.length, 0);
  }
});

test('inference time counts against the deadline without shortening a move below one second', async () => {
  const b = await browser(.999999), plan = await b.turn();
  await b.advance(800);
  b.configure({ ...plan, timeLimitMs: 3000 });
  await b.advance(2049); assert.equal(b.sent.length, 0);
  await b.advance(1); assert.equal(b.sent.length, 1);
});

test('toggling highlights does not restart an autoplay countdown', async () => {
  const b = await browser(0), plan = await b.turn();
  b.configure(plan); await b.advance(700);
  b.configure(plan, { highlight: true }); await b.advance(300);
  assert.equal(b.sent.length, 1);
});

test('a different recommendation for the same turn replaces the pending move', async () => {
  const b = await browser(0), plan = await b.turn();
  b.configure(plan); await b.advance(700);
  b.configure({ ...plan, request: { ...plan.request, tile: '1m' } });
  await b.advance(400); assert.equal(b.sent.length, 0);
  await b.advance(600); assert.equal(b.sent.length, 1);
  assert.equal(Buffer.from(field(field(b.sent[0].subarray(3), 2), 3)).toString(), '1m');
});

test('duplicate server notifications keep the same decision and never double-play', async () => {
  const b = await browser(0), plan = await b.turn();
  b.configure(plan); await b.advance(500);
  b.socket.receive(action(1)); await b.flush();
  await b.advance(500); assert.equal(b.sent.length, 1);
});

test('autoplay RPC ids cannot consume or overwrite the game client callbacks', async () => {
  const b = await browser(0), plan = await b.turn();
  b.configure(plan); await b.advance(1000);
  const autoId = idOf(b.sent[0]);
  const before = b.gameMessages.length;
  b.socket.send(request('.lq.FastTest.heartbeat', autoId));
  const gameWireId = idOf(b.sent[1]);
  assert.notEqual(gameWireId, autoId);
  b.socket.receive(response(gameWireId));
  b.socket.receive(response(autoId)); await b.flush();
  assert.equal(b.gameMessages.length, before + 1);
  assert.equal(idOf(b.gameMessages.at(-1)), autoId, 'the client receives its original id');
  const replies = b.captures.filter(item => item.type === 'websocket' && item.direction === 'inbound')
    .map(item => Buffer.from(item.data, 'base64')).filter(bytes => bytes[0] === 3);
  assert.deepEqual(replies.map(idOf), [gameWireId, autoId], 'the native decoder sees matching wire ids');
  assert.deepEqual(b.captures.map(item => item.sequence), b.captures.map((_, i) => i + 1));
});

test('a missing or rejected response pauses automation without retrying', async () => {
  for (const reject of [false, true]) {
    const b = await browser(0); b.configure(await b.turn()); await b.advance(1000);
    if (reject) b.socket.receive(response(idOf(b.sent[0]), [10, 2, 8, 1]));
    await b.advance(10000);
    assert.equal(b.sent.length, 1);
    assert.ok(b.captures.some(item => item.type === 'assistance_status' && item.stopAutoplay));
  }
});

test('non-Liqi frames and caller-owned buffers are preserved by the capture hook', async () => {
  const b = await browser();
  const raw = new Uint8Array([2, 3, 4, 5]);
  b.socket.send(raw); b.socket.send(raw);
  raw.fill(255); await b.flush();
  assert.deepEqual(b.sent.map(bytes => [...bytes]), [[2, 3, 4, 5], [2, 3, 4, 5]]);
  const outbound = b.captures.filter(item => item.direction === 'outbound');
  assert.deepEqual(outbound.map(item => [...Buffer.from(item.data, 'base64')]), [[2, 3, 4, 5], [2, 3, 4, 5]]);
});

test('RPC encoding distinguishes self actions, call combinations, and passes', () => {
  for (const [move, fields] of [
    [{ method: 'inputOperation', type: 7, tile: '0m', moqie: true }, { 1: 7, 5: 1 }],
    [{ method: 'inputOperation', type: 11, moqie: false }, { 1: 11, 5: 0 }],
    [{ method: 'inputChiPengGang', type: 2, index: 3 }, { 1: 2, 2: 3 }],
    [{ method: 'inputChiPengGang', cancel_operation: true }, { 3: 1 }],
    [{ method: 'inputOperation', cancel_operation: true }, { 4: 1 }],
  ]) {
    const bytes = encodeRequest(move, 35000, 2), body = field(bytes.subarray(3), 2);
    assert.equal(idOf(bytes), 35000);
    for (const [key, value] of Object.entries(fields)) assert.equal(field(body, Number(key)), value);
  }
  assert.throws(() => encodeRequest({ method: 'buyItem', type: 1 }, 1, 1));
  assert.throws(() => encodeRequest({ method: 'inputOperation', type: 1, tile: 'invalid' }, 1, 1));
});

function tableImage(count, width = 960, height = 540, offset = .10, brightness = 245) {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < data.length; i += 4) { data[i] = 24; data[i + 1] = 64; data[i + 2] = 49; data[i + 3] = 255; }
  const rects = [];
  function fill(x, y, w, h, color) {
    for (let row = y; row < Math.min(height, y + h); row++) for (let col = x; col < Math.min(width, x + w); col++) {
      const p = (row * width + col) * 4;
      data[p] = color[0]; data[p + 1] = color[1]; data[p + 2] = color[2];
    }
  }
  for (let i = 0; i < count; i++) {
    const x = Math.round(width * (offset + i * .049 + (i === count - 1 ? .008 : 0)));
    const y = Math.round(height * .82), w = Math.round(width * .046), h = Math.round(height * .165);
    fill(x, y, w, h, [brightness, brightness, brightness]);
    fill(x + Math.round(w * .3), y + Math.round(h * .22), Math.round(w * .25), Math.round(h * .58), i === 4 ? [190, 40, 40] : [25, 30, 25]);
    rects.push({ x: x / width, y: y / height, width: w / width, height: h / height });
  }
  return { image: { width, height, data }, rects };
}

test('tile detection follows the actual hand across sizes, positions, and open meld counts', () => {
  for (const count of [2, 5, 8, 11, 13, 14]) for (const width of [640, 960]) for (const brightness of [170, 195, 245]) {
    const { image, rects } = tableImage(count, width, width * 9 / 16, .12, brightness);
    const detected = locateHand(image, count);
    assert.equal(detected.length, count, `${count} tiles at ${width}px, brightness ${brightness}`);
    detected.forEach((box, index) => assert.ok(Math.abs(box.x - rects[index].x) < .002));
  }
});

test('tile detection refuses an incomplete hand or the wrong tile count', () => {
  const { image } = tableImage(13);
  assert.equal(locateHand(image, 14).length, 0);
  assert.equal(locateHand(tableImage(0).image, 13).length, 0);
});
