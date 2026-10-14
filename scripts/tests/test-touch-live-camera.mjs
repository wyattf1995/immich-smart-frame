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
  removeAttribute() {}, load() {},
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
  clearTimeout(id) { timers.delete(id); }, window: { addEventListener() {} }, globalThis: {},
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(source, context);
await new Promise(resolve => setImmediate(resolve));
const live = context.FrameLiveCamera.active;
const socket = FakeSocket.instances[0];
assert.equal(socket.url, "wss://ha.example/api/websocket");
socket.open();
socket.emit({ type: "auth_required" });
assert.equal(socket.sent.at(-1).access_token, "secret");
socket.emit({ type: "auth_ok" });
assert.equal(socket.sent.at(-1).type, "camera/webrtc/get_client_config");
const configId = socket.sent.at(-1).id;
socket.emit({ type: "result", id: configId, success: true, result: { configuration: { iceServers: [{ urls: "stun:example" }] }, dataChannel: "nest" } });
await new Promise(resolve => setImmediate(resolve));
const peer = FakePeer.instances[0];
assert.equal(peer.dataChannel, "nest");
assert.deepEqual(peer.transceivers.map(({ kind }) => kind), ["audio", "video"]);
const offer = socket.sent.find(value => value.type === "camera/webrtc/offer");
assert.ok(offer);
socket.emit({ type: "event", id: offer.id, event: { type: "candidate", candidate: { candidate: "remote", sdpMid: "0" } } });
await new Promise(resolve => setImmediate(resolve));
socket.emit({ type: "event", id: offer.id, event: { type: "session", session_id: "session-1" } });
await new Promise(resolve => setImmediate(resolve));
assert.equal(peer.remoteCandidates[0].candidate, "remote");
peer.onicecandidate({ candidate: { candidate: "local", toJSON: () => ({ candidate: "local" }) } });
assert.equal(socket.sent.at(-1).type, "camera/webrtc/candidate");
peer.ontrack({ track: { kind: "video" } });
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "playing"), false);
video.playing();
assert.equal(nativeCalls.some(value => value.type === "live-status" && value.state === "playing"), true);
live.close();
assert.equal(peer.closed, true);
assert.equal(socket.closed, true);
assert.equal(timers.size, 0);
console.log("touch live camera protocol: PASS");
