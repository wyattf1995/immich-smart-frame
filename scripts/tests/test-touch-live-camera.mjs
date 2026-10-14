import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync("frameos/touchframe/src/main/assets/frame-live-camera/live.js", "utf8");
const timers = new Map();
let nextTimer = 0;
class FakeSocket {
  static instances = [];
  static OPEN = 1;
  constructor(url) { this.url = url; this.readyState = 0; this.sent = []; FakeSocket.instances.push(this); }
  send(value) { this.sent.push(JSON.parse(value)); }
  close() { this.closed = true; this.onclose?.(); }
  open() { this.readyState = FakeSocket.OPEN; this.onopen?.(); }
  emit(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
}
class FakePeer {
  static instances = [];
  constructor(config) { this.config = config; this.localDescription = { type: "offer", sdp: "offer-sdp" }; FakePeer.instances.push(this); }
  createDataChannel(name) { this.dataChannel = name; }
  addTransceiver(kind, options) { (this.transceivers ??= []).push({ kind, options }); }
  async createOffer() { return this.localDescription; }
  async setLocalDescription() {}
  async setRemoteDescription(value) { this.remoteDescription = value; }
  async addIceCandidate(value) { (this.remoteCandidates ??= []).push(value); }
  close() { this.closed = true; }
}
const video = {
  addEventListener(type, callback) { if (type === "playing") this.playing = callback; },
  removeAttribute() {}, load() {}, srcObject: { stale: true },
};
const nativeCalls = [];
const browser = { runtime: { sendNativeMessage: async (_app, value) => {
  nativeCalls.push(value);
  if (value.type === "start-live") return { accepted: true, entity_id: "camera.entryway", origin: "wss://ha.example/api/websocket", access_token: "secret" };
  return { accepted: true };
} } };
const context = {
  console, URL, JSON, Promise, browser, location: { href: "moz-extension://id/live.html#capability=abcdefghijklmnop&entity=camera.entryway" },
  document: { querySelector: () => video }, WebSocket: FakeSocket, RTCPeerConnection: FakePeer,
  MediaStream: class { constructor() { this.tracks = []; } addTrack(track) { this.tracks.push(track); } getTracks() { return this.tracks; } },
  RTCIceCandidate: class { constructor(value) { Object.assign(this, value); } },
  setTimeout(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
  clearTimeout(id) { timers.delete(id); }, addEventListener() {}, globalThis: {},
};
context.window = context;
context.globalThis = context;
vm.createContext(context);
vm.runInContext(source, context);
await new Promise(resolve => setImmediate(resolve));
const live = context.FrameLiveCamera.active;
const socket = FakeSocket.instances[0];
assert.equal(socket.url, "wss://ha.example/api/websocket");
socket.open();
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "socket"), true, "an opened signaling socket emits only its bounded milestone");
socket.emit({ type: "auth_required" });
assert.equal(socket.sent.at(-1).access_token, "secret");
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "auth"), true, "authentication emits a bounded milestone");
socket.emit({ type: "auth_ok" });
assert.equal(socket.sent.at(-1).type, "camera/webrtc/get_client_config");
const configId = socket.sent.at(-1).id;
socket.emit({ type: "result", id: configId, success: true, result: { configuration: { iceServers: [{ urls: "stun:example" }] }, dataChannel: "nest" } });
await new Promise(resolve => setImmediate(resolve));
const peer = FakePeer.instances[0];
assert.equal(peer.dataChannel, "nest");
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "config"), true, "client configuration emits a bounded milestone");
assert.deepEqual(peer.transceivers.map(({ kind }) => kind), ["audio", "video"]);
const offer = socket.sent.find(value => value.type === "camera/webrtc/offer");
assert.ok(offer);
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "offer"), true, "offer creation emits a bounded milestone");
socket.emit({ type: "event", id: offer.id, event: { type: "candidate", candidate: { candidate: "remote", sdpMid: "0" } } });
await new Promise(resolve => setImmediate(resolve));
socket.emit({ type: "event", id: offer.id, event: { type: "session", session_id: "session-1" } });
await new Promise(resolve => setImmediate(resolve));
assert.equal(peer.remoteCandidates, undefined, "remote ICE waits for the remote answer");
socket.emit({ type: "event", id: offer.id, event: { type: "answer", answer: "answer-sdp" } });
await new Promise(resolve => setImmediate(resolve));
assert.ok(peer.remoteCandidates?.length, "direct HA event payload delivers queued remote ICE");
assert.equal(peer.remoteCandidates[0].candidate, "remote");
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "answer"), true, "answer acceptance emits a bounded milestone");
peer.onicecandidate({ candidate: { candidate: "local", toJSON: () => ({ candidate: "local" }) } });
assert.equal(socket.sent.at(-1).type, "camera/webrtc/candidate");
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "ice"), true, "ICE exchange emits a bounded milestone");
peer.ontrack({ track: { kind: "video" } });
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "playing"), false);
video.playing();
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "playing"), true);
peer.iceConnectionState = "failed";
peer.oniceconnectionstatechange();
assert.equal(peer.closed, true, "a failed ICE connection tears down the owned peer");
assert.equal(video.srcObject, null, "a failed ICE connection releases the rendered stream");
assert.equal(peer.closed, true);
assert.equal(socket.closed, true);
assert.equal(timers.size, 0);
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "failed-ice"), true, "ICE failure reports a bounded category without remote details");

let resolveNative;
const delayed = new context.FrameLiveCamera.LiveCameraSession({
  browserApi: { runtime: { sendNativeMessage: () => new Promise(resolve => { resolveNative = resolve; }) } },
  WebSocketImpl: FakeSocket, RTCPeerConnectionImpl: FakePeer,
  MediaStreamImpl: context.MediaStream, RTCIceCandidateImpl: context.RTCIceCandidate,
  video, locationHref: context.location.href, timers: context,
});
const beforeDelayedSocket = FakeSocket.instances.length;
const delayedStart = delayed.start();
delayed.close();
resolveNative({ accepted: true, entity_id: "camera.entryway", origin: "wss://ha.example/api/websocket", access_token: "secret" });
await delayedStart;
assert.equal(FakeSocket.instances.length, beforeDelayedSocket, "a revoked native response cannot open a socket");

// Window timer methods may require their Window receiver. The extension bootstrap
// must not detach them into a plain object before LiveCameraSession invokes them.
const receiverStatuses = [];
let receiverTimers = 0;
let receiverSocket;
const timerWindow = {
  setTimeout() {
    assert.equal(this, timerWindow, "the bootstrap keeps the Window timer receiver");
    receiverTimers += 1;
    return receiverTimers;
  },
  clearTimeout() {
    assert.equal(this, timerWindow, "the bootstrap keeps the Window clear-timer receiver");
  },
  addEventListener() {},
};
class ReceiverSocket {
  static OPEN = 1;
  constructor() { this.readyState = 0; receiverSocket = this; }
  close() { this.closed = true; }
}
const receiverContext = {
  URL, JSON, Promise,
  browser: { runtime: { sendNativeMessage: async (_app, value) => {
    if (value.type === "start-live") {
      return { accepted: true, entity_id: "camera.entryway", origin: "wss://ha.example/api/websocket", access_token: "secret" };
    }
    receiverStatuses.push(value.state);
    return { accepted: true };
  } } },
  location: { href: "moz-extension://id/live.html#capability=abcdefghijklmnop&entity=camera.entryway" },
  document: { querySelector: () => ({ addEventListener() {}, removeAttribute() {}, load() {} }) },
  WebSocket: ReceiverSocket, RTCPeerConnection: FakePeer,
  MediaStream: context.MediaStream, RTCIceCandidate: context.RTCIceCandidate,
  // These are intentionally the same receiver-sensitive Window operations.
  setTimeout: timerWindow.setTimeout, clearTimeout: timerWindow.clearTimeout,
  window: timerWindow, globalThis: {},
};
receiverContext.globalThis = receiverContext;
vm.createContext(receiverContext);
vm.runInContext(source, receiverContext);
await new Promise(resolve => setImmediate(resolve));
assert.equal(receiverSocket?.closed, undefined, "a receiver-sensitive timer does not abort the live bootstrap");
assert.equal(receiverTimers, 2, "both live deadlines are scheduled through the Window receiver");
assert.deepEqual(receiverStatuses, ["native-grant"], "a timer receiver failure is never mislabeled as a native-grant failure");
console.log("touch live camera protocol: PASS");
