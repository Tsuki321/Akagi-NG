/* Runs before the game. All moves are gated by an observed, still-current Liqi operation. */
(() => {
  'use strict';

  const INPUTS = new Set(['.lq.FastTest.inputOperation', '.lq.FastTest.inputChiPengGang']);
  const BOUNDARIES = new Set([
    '.lq.ActionPrototype', '.lq.NotifyGameEndResult', '.lq.NotifyGameTerminate',
    '.lq.FastTest.authGame', '.lq.FastTest.syncGame', '.lq.FastTest.enterGame', ...INPUTS,
  ]);

  function bytesOf(value) {
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    return null;
  }

  function varint(value) {
    const out = [];
    do { out.push((value & 127) | (value > 127 ? 128 : 0)); value = Math.floor(value / 128); } while (value);
    return out;
  }

  function field(bytes, number) {
    let cursor = 0;
    function read() {
      let value = 0;
      for (let shift = 0; shift < 35 && cursor < bytes.length; shift += 7) {
        const byte = bytes[cursor++];
        value += (byte & 127) * 2 ** shift;
        if (!(byte & 128)) return value;
      }
      throw new Error('Invalid protobuf integer');
    }
    while (cursor < bytes.length) {
      const tag = read(), wire = tag & 7;
      let value;
      if (wire === 0) value = read();
      else if (wire === 2) {
        const length = read();
        if (cursor + length > bytes.length) throw new Error('Invalid protobuf length');
        value = bytes.subarray(cursor, cursor + length);
        cursor += length;
      } else if (wire === 1) cursor += 8;
      else if (wire === 5) cursor += 4;
      else throw new Error('Invalid protobuf field');
      if (tag >>> 3 === number) return value;
    }
    return undefined;
  }

  function methodOf(bytes) {
    try {
      if (!bytes || (bytes[0] !== 1 && bytes[0] !== 2)) return '';
      const name = field(bytes.subarray(bytes[0] === 1 ? 1 : 3), 1);
      return name instanceof Uint8Array && name.length < 160 ? String.fromCharCode(...name) : '';
    } catch { return ''; }
  }

  function withId(bytes, id) {
    const copy = bytes.slice();
    copy[1] = id & 255;
    copy[2] = id >>> 8;
    return copy;
  }

  function encodeRequest(request, id, seconds) {
    const method = `.lq.FastTest.${request.method}`;
    if (!INPUTS.has(method)) throw new Error('Unsupported game input');
    const body = [];
    const number = (tag, value) => { if (value !== undefined) body.push(tag * 8, ...varint(value)); };
    const string = (tag, value) => {
      if (typeof value !== 'string' || !/^[0-9][mpsz]$/.test(value)) throw new Error('Invalid tile');
      body.push(tag * 8 + 2, value.length, ...Array.from(value, char => char.charCodeAt(0)));
    };
    if (request.cancel_operation !== true && (!Number.isInteger(request.type) || request.type < 1 || request.type > 11)) {
      throw new Error('Invalid game operation');
    }
    number(1, request.type);
    if (request.index !== undefined && (!Number.isInteger(request.index) || request.index < 0 || request.index > 63)) {
      throw new Error('Invalid operation combination');
    }
    number(2, request.index);
    if (request.method === 'inputOperation') {
      if (request.tile !== undefined) string(3, request.tile);
      if (request.cancel_operation) number(4, 1);
      if (request.moqie !== undefined) number(5, request.moqie ? 1 : 0);
    } else if (request.cancel_operation) number(3, 1);
    number(6, Math.max(0, Math.min(120, Math.floor(seconds))));
    const name = Array.from(method, char => char.charCodeAt(0));
    return new Uint8Array([2, id & 255, id >>> 8, 10, ...varint(name.length), ...name, 18, ...varint(body.length), ...body]);
  }

  /** Locate upright, light tile faces, accepting only one complete row of the expected size. */
  function locateHand(image, expected) {
    const { width, height, data } = image;
    if (expected < 1 || expected > 14 || width < 100 || height < 80) return [];
    const y0 = Math.floor(height * 0.60);
    const size = width * (height - y0);
    const mask = new Uint8Array(size);
    const stack = new Int32Array(size);
    for (const threshold of [210, 185, 160]) {
      for (let i = 0; i < size; i++) {
        const p = (i + y0 * width) * 4, r = data[p], g = data[p + 1], b = data[p + 2];
        mask[i] = Math.min(r, g, b) >= threshold && Math.max(r, g, b) - Math.min(r, g, b) < 65 ? 1 : 0;
      }
      const boxes = [];
      for (let start = 0; start < size; start++) {
        if (!mask[start]) continue;
        let used = 1, count = 0, left = width, right = 0, top = height, bottom = 0;
        stack[0] = start; mask[start] = 0;
        while (used) {
          const i = stack[--used], x = i % width, y = Math.floor(i / width) + y0;
          count++; left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
          if (x > 0 && mask[i - 1]) { mask[i - 1] = 0; stack[used++] = i - 1; }
          if (x + 1 < width && mask[i + 1]) { mask[i + 1] = 0; stack[used++] = i + 1; }
          if (i >= width && mask[i - width]) { mask[i - width] = 0; stack[used++] = i - width; }
          if (i + width < size && mask[i + width]) { mask[i + width] = 0; stack[used++] = i + width; }
        }
        const w = right - left + 1, h = bottom - top + 1;
        if (w >= width * .018 && w <= width * .11 && h >= height * .075 && h <= height * .32 &&
            w / h >= .35 && w / h <= 1.05 && count / (w * h) >= .48 && bottom > height * .78) {
          boxes.push({ x: left, y: top, width: w, height: h });
        }
      }
      const rows = [];
      for (const anchor of boxes) {
        const row = boxes.filter(box => Math.abs(box.height - anchor.height) < anchor.height * .25 &&
          Math.abs(box.y + box.height - anchor.y - anchor.height) < anchor.height * .22)
          .sort((a, b) => a.x - b.x);
        if (row.length !== expected || rows.some(other => other[0] === row[0])) continue;
        const widths = row.map(box => box.width).sort((a, b) => a - b);
        const typical = widths[Math.floor(widths.length / 2)];
        if (row.some((box, i) => Math.abs(box.width - typical) > typical * .30 ||
          (i && (box.x < row[i - 1].x + row[i - 1].width ||
            box.x - row[i - 1].x - row[i - 1].width > typical * (i === row.length - 1 ? .85 : .35))))) continue;
        rows.push(row);
      }
      if (rows.length === 1) return rows[0].map(box => ({
        x: box.x / width, y: box.y / height, width: box.width / width, height: box.height / height,
      }));
      if (rows.length > 1) return [];
    }
    return [];
  }

  function createAssistant(hooks) {
    const win = hooks.window, doc = win.document;
    const now = () => win.performance.now();
    const sockets = new Map();
    const forwarded = new WeakSet();
    let config = { autoplay: false, highlight: false, active: true, plan: null };
    let timer = null, expiry = null, wake = null, handled = '', manual = '', scheduled = '';
    let overlay = null, buffer = null, lastScan = -Infinity, lastStatus = '';
    let lastRects = [], lastPlan = '', stableFrames = 0;

    function report(message, stopAutoplay = false) {
      if (stopAutoplay) { config.autoplay = false; cancel(); }
      const key = `${stopAutoplay}/${message}`;
      if (key === lastStatus) return;
      lastStatus = key;
      hooks.emit('assistance_status', null, '', undefined, { message, stopAutoplay });
    }
    function clearVisuals() {
      overlay?.remove(); overlay = null;
      lastRects = []; stableFrames = 0;
      if (wake !== null) win.clearTimeout(wake);
      wake = null;
    }
    function cancel() {
      if (timer !== null) win.clearTimeout(timer);
      if (expiry !== null) win.clearTimeout(expiry);
      timer = expiry = null; scheduled = '';
      clearVisuals();
    }
    function current(plan = config.plan) {
      const connection = plan && sockets.get(plan.connectionId);
      return plan && config.active && doc.visibilityState !== 'hidden' &&
        plan.generation === hooks.generation && connection && !connection.opaque &&
        connection.socket.readyState === 1 && connection.revision === plan.revision &&
        !connection.converting && now() - connection.actionAt < Math.min(plan.timeLimitMs, 120000) &&
        handled !== plan.id ? connection : null;
    }
    function allocate(connection) {
      if (connection.pending.size >= 4096) throw new Error('Too many pending game requests');
      for (let i = 0; i < 65535; i++) {
        const id = connection.nextId;
        connection.nextId = id > 1 ? id - 1 : 65535;
        if (!connection.pending.has(id)) return id;
      }
      throw new Error('Game request identifiers exhausted');
    }
    function sendMove(plan) {
      timer = null; scheduled = '';
      const connection = current(plan);
      if (!config.autoplay || !connection || manual === plan.id) return;
      if (connection.socket.binaryType !== 'arraybuffer') {
        report('Autoplay paused: this game connection does not support automatic moves.', true);
        return;
      }
      handled = plan.id;
      clearVisuals();
      try {
        const id = allocate(connection);
        const bytes = encodeRequest(plan.request, id, (now() - connection.actionAt) / 1000);
        const pending = { auto: true, method: methodOf(bytes), timeout: null };
        connection.pending.set(id, pending);
        hooks.send(connection.socket, bytes);
        hooks.emit('websocket', connection, 'outbound', bytes);
        pending.timeout = win.setTimeout(() => {
          // Keep this id reserved so a late response can never reach the game's own RPC handler.
          report('Autoplay paused: the game did not confirm the move.', true);
        }, 10000);
        report('Move sent');
      } catch {
        report('Autoplay paused: the game could not accept this move.', true);
      }
    }
    function reschedule() {
      const plan = config.plan, connection = current(plan);
      if (!connection) { cancel(); return; }
      if (expiry === null) expiry = win.setTimeout(cancel, Math.max(0, Math.min(plan.timeLimitMs, 120000) - now() + connection.actionAt));
      if (config.highlight) requestScan();
      if (!config.autoplay || scheduled === plan.id || handled === plan.id || manual === plan.id) return;
      const available = Math.min(3000, Math.floor(plan.timeLimitMs - (now() - connection.actionAt) - 150));
      if (available < 1000) { report('Play this turn manually: less than one second remains.'); return; }
      const delay = 1000 + Math.floor(win.Math.random() * (available - 1000 + 1));
      scheduled = plan.id;
      timer = win.setTimeout(() => sendMove(plan), delay);
      report(`Autoplay in ${(delay / 1000).toFixed(1)} s`);
    }
    function configure(next) {
      if (!next || typeof next !== 'object') return;
      const plan = next.plan;
      if (plan && (plan.generation !== hooks.generation || !Array.isArray(plan.hand) || plan.hand.length > 14 ||
          !Array.isArray(plan.tileIndices) || plan.tileIndices.some(i => !Number.isInteger(i) || i < 0 || i >= plan.hand.length) ||
          !Number.isFinite(plan.timeLimitMs) || plan.timeLimitMs <= 0 || typeof plan.id !== 'string')) return;
      const changed = config.plan?.id !== plan?.id || JSON.stringify(config.plan?.request) !== JSON.stringify(plan?.request);
      if (changed || !next.active || !next.autoplay) {
        if (timer !== null) win.clearTimeout(timer);
        timer = null; scheduled = '';
      }
      if (changed) {
        cancel(); lastPlan = ''; lastStatus = '';
      }
      config = { autoplay: next.autoplay === true, highlight: next.highlight === true, active: next.active === true, plan };
      if (!config.autoplay) manual = '';
      if (!config.highlight) clearVisuals();
      if (!config.active || !plan) { cancel(); return; }
      reschedule();
    }
    function trustedInput(event) {
      if (!event.isTrusted || !config.plan || !config.active) return;
      if (config.autoplay) {
        manual = config.plan.id;
        config.autoplay = false;
        cancel();
        report('Autoplay stopped. You are playing manually.', true);
      }
    }
    doc.addEventListener('pointerdown', trustedInput, true);
    doc.addEventListener('keydown', trustedInput, true);
    doc.addEventListener('visibilitychange', () => {
      if (doc.visibilityState === 'hidden') cancel();
      else reschedule();
    });
    win.addEventListener('pagehide', cancel);
    win.addEventListener('resize', () => { clearVisuals(); lastScan = -Infinity; });

    function surface() {
      return doc.getElementById('unity-canvas') || doc.getElementById('layaCanvas') ||
        Array.from(doc.querySelectorAll('canvas')).filter(canvas => canvas.width > 400 && canvas.height > 200)
          .sort((a, b) => b.width * b.height - a.width * a.height)[0];
    }
    function positionOverlay(canvas) {
      const rect = canvas.getBoundingClientRect();
      if (!rect.width || !rect.height) return null;
      const transform = win.getComputedStyle(canvas).transform;
      const matrix = new win.DOMMatrixReadOnly(transform === 'none' ? undefined : transform);
      const quarter = Math.abs(matrix.b) > Math.abs(matrix.a);
      const angle = quarter ? (matrix.b > 0 ? 90 : -90) : (matrix.a < 0 ? 180 : 0);
      const width = quarter ? rect.height : rect.width, height = quarter ? rect.width : rect.height;
      const x = angle === 90 || angle === 180 ? rect.right : rect.left;
      const y = angle === -90 || angle === 180 ? rect.bottom : rect.top;
      Object.assign(overlay.style, { width: `${width}px`, height: `${height}px`, transform: `translate(${x}px,${y}px) rotate(${angle}deg)` });
      return { width, height };
    }
    function render(canvas, boxes, plan) {
      if (!doc.body) return;
      if (!overlay) {
        overlay = doc.createElement('div');
        overlay.id = 'akagi-table-guidance';
        overlay.setAttribute('aria-live', 'polite');
        Object.assign(overlay.style, { position: 'fixed', left: '0', top: '0', transformOrigin: '0 0',
          pointerEvents: 'none', zIndex: '2147483600', overflow: 'visible' });
        doc.body.appendChild(overlay);
      }
      const dimensions = positionOverlay(canvas);
      if (!dimensions) { clearVisuals(); return; }
      overlay.replaceChildren();
      for (const index of plan.tileIndices) {
        const box = boxes[index];
        if (!box) continue;
        const marker = doc.createElement('div');
        marker.dataset.tileIndex = String(index);
        marker.setAttribute('aria-label', `Recommended ${plan.hand[index]}`);
        Object.assign(marker.style, { position: 'absolute', left: `${box.x * 100}%`, top: `${box.y * 100}%`,
          width: `${box.width * 100}%`, height: `${box.height * 100}%`, boxSizing: 'border-box',
          border: '3px solid #64ffd2', borderRadius: '5px', background: 'rgba(69,255,199,.13)',
          boxShadow: '0 0 0 1px #063d32,0 0 12px #38e9b9', pointerEvents: 'none' });
        overlay.appendChild(marker);
      }
      const cue = doc.createElement('div');
      cue.dataset.action = plan.label;
      cue.textContent = `${plan.label}${plan.tile ? ` ${plan.tile}` : ''}`;
      const focus = boxes[plan.tileIndices[0]];
      const center = focus ? Math.max(.12, Math.min(.88, focus.x + focus.width / 2)) : .5;
      const top = boxes.length ? Math.min(...boxes.map(box => box.y)) : .80;
      Object.assign(cue.style, { position: 'absolute', left: `${center * 100}%`, top: `${top * 100}%`,
        transform: 'translate(-50%,calc(-100% - 10px))', padding: '7px 12px', borderRadius: '10px',
        background: '#10382fef', color: '#b2ffe9', border: '1px solid #64ffd2', font: '600 15px sans-serif',
        whiteSpace: 'nowrap', boxShadow: '0 2px 8px #0009', pointerEvents: 'none' });
      overlay.appendChild(cue);
    }
    function scan(timestamp) {
      if (!config.highlight || !current() || timestamp - lastScan < 250) return;
      lastScan = timestamp;
      const plan = config.plan, canvas = surface();
      if (!canvas) { clearVisuals(); return; }
      try {
        if (!buffer) buffer = doc.createElement('canvas');
        const width = Math.min(960, canvas.width), height = Math.round(canvas.height * width / canvas.width);
        if (buffer.width !== width || buffer.height !== height) { buffer.width = width; buffer.height = height; }
        const context = buffer.getContext('2d', { willReadFrequently: true });
        context.clearRect(0, 0, width, height);
        context.drawImage(canvas, 0, 0, width, height);
        const boxes = locateHand(context.getImageData(0, 0, width, height), plan.hand.length);
        const stable = lastPlan === plan.id && boxes.length && boxes.length === lastRects.length &&
          boxes.every((box, i) => Math.abs(box.x - lastRects[i].x) < .008 && Math.abs(box.y - lastRects[i].y) < .012);
        stableFrames = stable ? stableFrames + 1 : 0;
        lastRects = boxes; lastPlan = plan.id;
        // Two matching frames avoid marking tiles while the draw/sort animation is moving them.
        render(canvas, stableFrames >= 1 ? boxes : [], plan);
      } catch {
        render(canvas, [], plan);
      }
    }
    const nativeRaf = win.requestAnimationFrame;
    if (nativeRaf) {
      win.requestAnimationFrame = function (callback) {
        return nativeRaf.call(this, function (timestamp) {
          try { return callback.call(this, timestamp); }
          finally { try { scan(timestamp); } catch { clearVisuals(); } }
        });
      };
    }
    function requestScan() {
      if (!nativeRaf || wake !== null) return;
      win.requestAnimationFrame(() => {});
      wake = win.setTimeout(() => { wake = null; if (config.highlight && current()) requestScan(); }, 300);
    }

    return {
      configure,
      open(socket, connection) {
        Object.assign(connection, { socket, revision: 0, actionAt: now(), lastAction: null,
          pending: new Map(), nextId: 65535, converting: 0, opaque: false });
        sockets.set(connection.id, connection);
      },
      close(connection) {
        for (const pending of connection.pending.values()) if (pending.timeout) win.clearTimeout(pending.timeout);
        sockets.delete(connection.id);
        if (config.plan?.connectionId === connection.id) cancel();
      },
      converting(connection, increment) { if (connection) connection.converting += increment; },
      observe(connection, direction, bytes, sequence) {
        if (!connection || !bytes) return connection?.revision || 0;
        const id = bytes.length >= 3 ? bytes[1] | bytes[2] << 8 : -1;
        const method = bytes[0] === 3 ? connection.pending.get(id)?.method : methodOf(bytes);
        if (BOUNDARIES.has(method)) {
          const duplicate = method === '.lq.ActionPrototype' && connection.lastAction &&
            connection.lastAction.length === bytes.length && bytes.every((value, i) => value === connection.lastAction[i]);
          if (!duplicate && sequence > connection.revision) {
            connection.revision = sequence; connection.actionAt = now();
            connection.lastAction = method === '.lq.ActionPrototype' ? bytes.slice() : null;
            if (config.plan?.connectionId === connection.id) cancel();
          }
          if (!duplicate) return sequence;
        }
        return connection.revision;
      },
      prepareClientSend(connection, data) {
        const bytes = bytesOf(data);
        if (!bytes || bytes.length < 3 || bytes[0] !== 2) {
          if (typeof data !== 'string' && !bytes) connection.opaque = true;
          return { data };
        }
        const method = methodOf(bytes);
        if (!method.startsWith('.lq.')) return { data };
        const id = bytes[1] | bytes[2] << 8;
        const wire = connection.pending.has(id) ? allocate(connection) : id;
        return { data: wire === id ? data : withId(bytes, wire), commit() {
          connection.pending.set(wire, { clientId: id, method });
        } };
      },
      incoming(connection, event) {
        if (forwarded.has(event)) return true;
        const bytes = bytesOf(event.data);
        if (!bytes || bytes[0] !== 3 || bytes.length < 3) return false;
        const id = bytes[1] | bytes[2] << 8, pending = connection.pending.get(id);
        if (!pending) return false;
        hooks.emit('websocket', connection, 'inbound', event.data);
        connection.pending.delete(id);
        if (pending.timeout) win.clearTimeout(pending.timeout);
        if (pending.auto) {
          event.stopImmediatePropagation();
          try {
            const body = field(bytes.subarray(3), 2), error = body && field(body, 1);
            if (error && field(error, 1)) report('Autoplay paused: the game rejected the move.', true);
          } catch { report('Autoplay paused: the game response could not be checked.', true); }
        } else if (pending.clientId !== id) {
          event.stopImmediatePropagation();
          const translated = new win.MessageEvent('message', { data: withId(bytes, pending.clientId).buffer,
            origin: event.origin, lastEventId: event.lastEventId });
          forwarded.add(translated);
          connection.socket.dispatchEvent(translated);
        }
        return true;
      },
    };
  }

  const api = { createAssistant, encodeRequest, locateHand, field, methodOf };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else if (!window.__akagiAssistance) Object.defineProperty(window, '__akagiAssistance', { value: api });
})();
