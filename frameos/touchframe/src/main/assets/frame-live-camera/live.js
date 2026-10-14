"use strict";

const STARTUP_TIMEOUT_MS = 12000;
const MAX_LIVE_MS = 5 * 60 * 1000;

class LiveCameraSession {
  constructor({ browserApi, WebSocketImpl, RTCPeerConnectionImpl, MediaStreamImpl, RTCIceCandidateImpl, video, locationHref, timers }) {
    this.browser = browserApi; this.WebSocket = WebSocketImpl; this.RTCPeerConnection = RTCPeerConnectionImpl;
    this.MediaStream = MediaStreamImpl; this.RTCIceCandidate = RTCIceCandidateImpl; this.video = video;
    this.locationHref = locationHref; this.timers = timers; this.socket = null; this.peer = null; this.remoteStream = null;
    this.entity = ""; this.capability = ""; this.sessionId = null; this.offerSubscriptionId = null; this.nextId = 0;
    this.pending = new Map(); this.pendingCandidates = []; this.pendingRemoteCandidates = []; this.remoteDescriptionSet = false;
    this.closed = false; this.playing = false; this.startupTimer = null; this.maximumTimer = null; this.milestones = new Set(); this.failureReported = false;
  }
  nextRequestId() { this.nextId += 1; return this.nextId; }
  send(value) { if (!this.closed && this.socket?.readyState === this.WebSocket.OPEN) this.socket.send(JSON.stringify(value)); }
  async native(value) { return this.browser.runtime.sendNativeMessage("frame_live_camera", value); }
  status(state) { return this.native({ type: "live-status", capability: this.capability, entity_id: this.entity, state }).catch(() => {}); }
  milestone(state) { if (!this.milestones.has(state)) { this.milestones.add(state); return this.status(state); } }
  failure(state) { if (!this.failureReported) { this.failureReported = true; this.status(state); } }
  async start() {
    const fragment = new URL(this.locationHref).hash.match(/^#capability=([A-Za-z0-9_-]{16,})&entity=(camera\.[a-z0-9_]+)$/);
    if (!fragment) return this.close(true, "failed-native-grant");
    this.capability = fragment[1]; this.entity = fragment[2];
    const response = await this.native({ type: "start-live", capability: this.capability, entity_id: this.entity });
    if (this.closed) return;
    if (!response?.accepted || response.entity_id !== this.entity || typeof response.access_token !== "string" || !/^wss?:\/\/[^/]+\/api\/websocket$/.test(response.origin)) return this.close(true, "failed-native-grant");
    let token = response.access_token;
    this.milestone("native-grant");
    try { this.socket = new this.WebSocket(response.origin); } catch (_) { return this.close(true, "failed-socket"); }
    this.startupTimer = this.timers.setTimeout(() => this.close(true, "failed-timeout"), STARTUP_TIMEOUT_MS);
    this.maximumTimer = this.timers.setTimeout(() => this.close(true), MAX_LIVE_MS);
    this.socket.onopen = () => this.milestone("socket");
    this.socket.onmessage = event => this.onMessage(event, () => token, value => { token = value; }).catch(() => this.close(true, "failed-offer"));
    this.socket.onerror = () => this.close(true, "failed-socket");
    this.socket.onclose = () => { if (!this.closed) this.close(true, "failed-socket"); };
  }
  async onMessage(event, getToken, clearToken) {
    const message = JSON.parse(event.data);
    if (message.type === "auth_required") { this.send({ type: "auth", access_token: getToken() }); clearToken(""); this.milestone("auth"); return; }
    if (message.type === "auth_invalid") return this.close(true, "failed-auth");
    if (message.type === "auth_ok") {
      this.milestone("config");
      try { return await this.configurePeer(await this.request({ type: "camera/webrtc/get_client_config", entity_id: this.entity })); }
      catch (_) { return this.close(true, "failed-config"); }
    }
    if (message.type === "result") return this.resolveRequest(message);
    if (message.type === "event" && message.id === this.offerSubscriptionId) return this.handleOfferEvent(message.event);
  }
  request(command) { const id = this.nextRequestId(); return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.send({ ...command, id }); }); }
  resolveRequest(message) { const pending = this.pending.get(message.id); if (!pending) return; this.pending.delete(message.id); if (message.success === true) pending.resolve(message.result); else pending.reject(new Error("Home Assistant request failed")); }
  async configurePeer(config) {
    if (this.closed || !config?.configuration) return this.close(true, "failed-config");
    this.peer = new this.RTCPeerConnection(config.configuration);
    if (config.dataChannel) this.peer.createDataChannel(config.dataChannel);
    this.remoteStream = new this.MediaStream();
    this.peer.ontrack = event => { if (!this.closed && event.track) { this.remoteStream.addTrack(event.track); this.video.srcObject = this.remoteStream; } };
    this.video.addEventListener("playing", () => { if (!this.closed && !this.playing) { this.playing = true; this.timers.clearTimeout(this.startupTimer); this.status("playing"); } }, { once: true });
    this.peer.onicecandidate = event => { if (!event.candidate?.candidate) return; if (this.sessionId) this.sendCandidate(event.candidate); else this.pendingCandidates.push(event.candidate); };
    this.peer.oniceconnectionstatechange = () => { if (this.peer?.iceConnectionState === "failed") this.close(true, "failed-ice"); };
    this.peer.addTransceiver("audio", { direction: "recvonly" }); this.peer.addTransceiver("video", { direction: "recvonly" });
    try { await this.beginNegotiation(); } catch (_) { this.close(true, "failed-offer"); }
  }
  async beginNegotiation() {
    const peer = this.peer;
    if (!peer || this.closed) return;
    const offer = await peer.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
    if (this.closed || this.peer !== peer) return;
    await peer.setLocalDescription(offer);
    if (this.closed || this.peer !== peer) return;
    this.offerSubscriptionId = this.nextRequestId(); this.send({ type: "camera/webrtc/offer", id: this.offerSubscriptionId, entity_id: this.entity, offer: peer.localDescription.sdp }); this.milestone("offer");
  }
  async handleOfferEvent(event) {
    if (!this.peer || !event || this.closed) return;
    if (event.type === "session") { this.sessionId = event.session_id; for (const candidate of this.pendingCandidates.splice(0)) this.sendCandidate(candidate); return; }
    if (event.type === "answer") {
      try { await this.peer.setRemoteDescription({ type: "answer", sdp: event.answer }); } catch (_) { return this.close(true, "failed-answer"); }
      if (this.closed || !this.peer) return;
      this.remoteDescriptionSet = true; this.milestone("answer");
      try { for (const candidate of this.pendingRemoteCandidates.splice(0)) await this.addRemoteCandidate(candidate); } catch (_) { return this.close(true, "failed-ice"); }
      return;
    }
    if (event.type === "candidate") {
      if (!event.candidate) return this.close(true, "failed-ice");
      if (!this.remoteDescriptionSet) { this.pendingRemoteCandidates.push(event.candidate); return; }
      try { return await this.addRemoteCandidate(event.candidate); } catch (_) { return this.close(true, "failed-ice"); }
    }
    if (event.type === "error") return this.close(true, "failed-offer");
  }
  addRemoteCandidate(candidate) {
    if (!this.peer || this.closed) return;
    const value = candidate.sdpMid || candidate.sdpMLineIndex != null ? candidate : { ...candidate, sdpMid: "0" };
    return this.peer.addIceCandidate(new this.RTCIceCandidate(value));
  }
  sendCandidate(candidate) { if (!this.sessionId || this.closed) return; this.send({ type: "camera/webrtc/candidate", id: this.nextRequestId(), entity_id: this.entity, session_id: this.sessionId, candidate: candidate.toJSON() }); this.milestone("ice"); }
  clearTimers() { if (this.startupTimer) this.timers.clearTimeout(this.startupTimer); if (this.maximumTimer) this.timers.clearTimeout(this.maximumTimer); this.startupTimer = null; this.maximumTimer = null; }
  close(failed = false, failure = "failed-timeout") {
    if (this.closed) return;
    this.closed = true; this.clearTimers();
    if (this.offerSubscriptionId !== null && this.socket?.readyState === this.WebSocket.OPEN) this.socket.send(JSON.stringify({ type: "unsubscribe_events", id: this.nextRequestId(), subscription: this.offerSubscriptionId }));
    for (const pending of this.pending.values()) pending.reject(new Error("live camera closed")); this.pending.clear(); this.pendingCandidates = []; this.pendingRemoteCandidates = [];
    this.remoteStream?.getTracks?.().forEach(track => track.stop?.()); if (this.video) { this.video.srcObject = null; this.video.removeAttribute("src"); this.video.load(); }
    this.peer?.close(); this.peer = null; this.socket?.close(); this.socket = null; if (failed) this.failure(failure);
  }
}

globalThis.FrameLiveCamera = { LiveCameraSession };
if (typeof browser !== "undefined" && typeof location !== "undefined") {
  const active = new LiveCameraSession({ browserApi: browser, WebSocketImpl: WebSocket, RTCPeerConnectionImpl: RTCPeerConnection, MediaStreamImpl: MediaStream, RTCIceCandidateImpl: RTCIceCandidate, video: document.querySelector("video"), locationHref: location.href, timers: { setTimeout: (...args) => window.setTimeout(...args), clearTimeout: id => window.clearTimeout(id) } });
  globalThis.FrameLiveCamera.active = active; window.addEventListener("pagehide", () => active.close(false), { once: true }); active.start().catch(() => active.close(true, "failed-native-grant"));
}
