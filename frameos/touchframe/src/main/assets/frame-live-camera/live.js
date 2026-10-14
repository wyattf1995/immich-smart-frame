"use strict";

const STARTUP_TIMEOUT_MS = 12000;
const MAX_LIVE_MS = 5 * 60 * 1000;
const fragment = new URL(location.href).hash.match(/^#capability=([A-Za-z0-9_-]{16,})&entity=(camera\.[a-z0-9_]+)$/);
const capability = fragment?.[1] || "";
const requestedEntity = fragment?.[2] || "";
const video = document.querySelector("video");
let socket = null;
let peer = null;
let sessionId = null;
let requestId = 0;
let closed = false;

function nextId() { requestId += 1; return requestId; }
function send(value) { if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value)); }
function status(state, entity) {
  if (capability && entity) browser.runtime.sendNativeMessage("frame_live_camera", {
    type: "live-status", capability, entity_id: entity, state,
  }).catch(() => {});
}
function close(entity, failed) {
  if (closed) return;
  closed = true;
  if (peer) peer.close();
  if (socket) socket.close();
  if (failed) status("failed", entity);
}
function waitForIceComplete(pc) {
  return new Promise(resolve => {
    if (pc.iceGatheringState === "complete") return resolve();
    const timer = setTimeout(done, 3000);
    function done() { clearTimeout(timer); pc.removeEventListener("icegatheringstatechange", check); resolve(); }
    function check() { if (pc.iceGatheringState === "complete") done(); }
    pc.addEventListener("icegatheringstatechange", check);
  });
}
async function start() {
  if (!capability) return;
  const response = await browser.runtime.sendNativeMessage("frame_live_camera", { type: "start-live", capability, entity_id: requestedEntity });
  if (!response || !response.accepted || typeof response.entity_id !== "string" || typeof response.access_token !== "string") return;
  const entity = response.entity_id;
  let token = response.access_token;
  const endpoint = response.origin;
  if (!/^wss?:\/\/[^/]+\/api\/websocket$/.test(endpoint)) return close(entity, true);
  socket = new WebSocket(endpoint);
  const startup = setTimeout(() => close(entity, true), STARTUP_TIMEOUT_MS);
  const maximum = setTimeout(() => close(entity, false), MAX_LIVE_MS);
  socket.onopen = () => {};
  socket.onmessage = async event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return close(entity, true); }
    if (message.type === "auth_required") {
      send({ type: "auth", access_token: token }); token = ""; return;
    }
    if (message.type === "auth_invalid") return close(entity, true);
    if (message.type !== "auth_ok") {
      if (message.type === "event" && message.event?.data?.type === "session") sessionId = message.event.data.session_id;
      if (message.type === "event" && message.event?.data?.type === "answer" && peer) {
        await peer.setRemoteDescription({ type: "answer", sdp: message.event.data.answer });
      }
      if (message.type === "event" && message.event?.data?.type === "candidate" && peer) {
        await peer.addIceCandidate(message.event.data.candidate);
      }
      if (message.type === "event" && message.event?.data?.type === "error") return close(entity, true);
      return;
    }
    peer = new RTCPeerConnection();
    peer.ontrack = event => { video.srcObject = event.streams[0]; clearTimeout(startup); status("playing", entity); };
    peer.onicecandidate = event => {
      if (event.candidate && sessionId) send({ type: "camera/webrtc/candidate", id: nextId(), entity_id: entity, session_id: sessionId, candidate: event.candidate.toJSON() });
    };
    const offer = await peer.createOffer({ offerToReceiveAudio: false, offerToReceiveVideo: true });
    await peer.setLocalDescription(offer);
    await waitForIceComplete(peer);
    send({ type: "camera/webrtc/offer", id: nextId(), entity_id: entity, offer: peer.localDescription.sdp });
  };
  socket.onerror = () => close(entity, true);
  socket.onclose = () => { clearTimeout(startup); clearTimeout(maximum); };
}
window.addEventListener("pagehide", () => close("", false), { once: true });
start().catch(() => close("", true));
