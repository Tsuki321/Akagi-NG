(() => {
  'use strict';
  if (window.__akagiCaptureInstalled || !window.AkagiCapture) return;
  const bridge = window.AkagiCapture;
  const generation = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  Object.defineProperty(window, '__akagiCaptureInstalled', { value: generation });
  const NativeWebSocket = window.WebSocket;
  const nativeSend = NativeWebSocket.prototype.send;
  const connections = new WeakMap();
  const MAX_FRAME = 4 * 1024 * 1024;
  const MAX_PENDING_BYTES = 16 * 1024 * 1024;
  let sequence = 0;
  let connectionCount = 0;
  let pendingBytes = 0;
  let pendingCount = 0;
  let stopped = false;
  let queue = Promise.resolve();
  let assistant = null;

  function post(event) {
    bridge.postMessage(JSON.stringify(event));
  }

  function fail(reason, event) {
    stopped = true;
    assistant?.configure({ active: false, autoplay: false, highlight: false, plan: null });
    post({ ...event, type: 'capture_error', message: reason });
  }

  function emit(type, connection, direction, payload, extra = {}) {
    if (stopped) return;
    const event = {
      type, generation, sequence: ++sequence,
      connectionId: connection ? connection.id : '',
      url: connection ? connection.url : location.href,
      direction: direction || '', ...extra,
    };
    let snapshot = payload;
    let byteLength = 0;
    // A caller may reuse or mutate an ArrayBuffer immediately after send(). Copy it
    // at observation time; Blob is immutable and can be converted on our queue.
    try {
      if (typeof payload === 'string') byteLength = payload.length * 2;
      else if (payload instanceof Blob) byteLength = payload.size;
      else if (payload instanceof ArrayBuffer) byteLength = payload.byteLength;
      else if (ArrayBuffer.isView(payload)) byteLength = payload.byteLength;
      else if (type === 'websocket') throw new Error('Unsupported WebSocket payload');
      if (byteLength > MAX_FRAME || pendingBytes + byteLength > MAX_PENDING_BYTES || pendingCount >= 512) {
        stopped = true;
        queue = queue.then(() => fail('Capture buffer exceeded. Reload the game to synchronize.', event));
        return;
      }
      if (payload instanceof ArrayBuffer) snapshot = new Uint8Array(payload.slice(0));
      else if (ArrayBuffer.isView(payload)) {
        snapshot = new Uint8Array(payload.buffer, payload.byteOffset, payload.byteLength).slice();
      }
      if (type === 'websocket' && snapshot instanceof Uint8Array) {
        event.gameRevision = assistant?.observe(connection, direction, snapshot, event.sequence) || event.sequence;
      }
      if (snapshot instanceof Blob) assistant?.converting(connection, 1);
    } catch (error) {
      stopped = true;
      queue = queue.then(() => fail(String(error.message || error), event));
      return;
    }
    pendingBytes += byteLength;
    pendingCount++;
    // One document-wide chain includes all sockets, sends, receives and closes.
    // No later text or ArrayBuffer can overtake an earlier asynchronous Blob.
    queue = queue.then(async () => {
      try {
        if (type === 'websocket') {
          if (typeof snapshot === 'string') {
            event.data = snapshot;
            event.binary = false;
            event.payloadType = 'text';
          } else {
            const bytes = snapshot instanceof Blob
              ? new Uint8Array(await snapshot.arrayBuffer()) : snapshot;
            if (snapshot instanceof Blob) {
              event.gameRevision = assistant?.observe(connection, direction, bytes, event.sequence) || event.sequence;
            }
            const parts = [];
            for (let offset = 0; offset < bytes.length; offset += 0x4000) {
              parts.push(String.fromCharCode.apply(null, bytes.subarray(offset, offset + 0x4000)));
            }
            event.data = btoa(parts.join(''));
            event.binary = true;
            event.payloadType = payload instanceof Blob ? 'blob' : 'arraybuffer';
          }
        }
        post(event);
      } catch (error) {
        fail(`Capture conversion failed: ${String(error.message || error)}`, event);
      } finally {
        if (snapshot instanceof Blob) assistant?.converting(connection, -1);
        pendingBytes -= byteLength;
        pendingCount--;
      }
    }).catch(() => { stopped = true; });
  }

  assistant = window.__akagiAssistance?.createAssistant({
    window, generation, emit,
    send: (socket, data) => {
      if (stopped) throw new Error('Capture is paused');
      return Reflect.apply(nativeSend, socket, [data]);
    },
  });
  bridge.onmessage = event => {
    if (stopped) return;
    try { assistant?.configure(JSON.parse(event.data)); } catch { /* Ignore malformed native commands. */ }
  };

  Object.defineProperty(NativeWebSocket.prototype, 'send', {
    ...Object.getOwnPropertyDescriptor(NativeWebSocket.prototype, 'send'),
    value: function send(data) {
      const connection = connections.get(this);
      const prepared = connection && assistant ? assistant.prepareClientSend(connection, data) : { data };
      const result = Reflect.apply(nativeSend, this, [prepared.data]);
      prepared.commit?.();
      if (connection) emit('websocket', connection, 'outbound', prepared.data);
      return result;
    },
  });

  const CapturedWebSocket = new Proxy(NativeWebSocket, {
    construct(target, args, newTarget) {
      const socket = Reflect.construct(target, args, newTarget);
      const connection = { id: `ws-${++connectionCount}`, url: socket.url };
      connections.set(socket, connection);
      assistant?.open(socket, connection);
      emit('websocket_created', connection);
      socket.addEventListener('message', event => {
        if (!assistant?.incoming(connection, event)) emit('websocket', connection, 'inbound', event.data);
      });
      socket.addEventListener('close', event => {
        assistant?.close(connection);
        emit('websocket_closed', connection, '', undefined, {
          code: event.code, reason: event.reason, wasClean: event.wasClean,
        });
      });
      return socket;
    },
  });
  window.WebSocket = CapturedWebSocket;
  Object.defineProperty(NativeWebSocket.prototype, 'constructor', {
    ...Object.getOwnPropertyDescriptor(NativeWebSocket.prototype, 'constructor'),
    value: CapturedWebSocket,
  });

  emit('capture_ready', null, '', undefined, {
    documentUrl: location.href, mainFrame: window === window.top,
    coverage: 'document-websockets',
  });
})();
