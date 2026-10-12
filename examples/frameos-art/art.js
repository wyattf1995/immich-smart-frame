(() => {
  "use strict";
  const CYCLE_MILLIS = 120_000;
  const EDGE_GUARD_PX = 36;
  const ROTATION_STATE_VERSION = 1;
  const ROTATION_STORAGE_PREFIX = "frameos-art-rotation-v1:";
  const IMAGE_PATH = /^images\/[a-f0-9]{64}\.jpg$/;
  const text = (value) => typeof value === "string" ? value.trim() : "";
  const validSourceUrl = (value) => {
    try { const url = new URL(text(value)); return url.protocol === "https:" && !url.username && !url.password && !url.search && !url.hash; }
    catch (_) { return false; }
  };
  const validateCollection = (input) => {
    if (!input || typeof input !== "object" || input.version !== 1 || !Array.isArray(input.artworks)) return null;
    const title = text(input.title);
    if (!title || input.artworks.length === 0) return null;
    const seen = new Set();
    const artworks = [];
    for (const entry of input.artworks) {
      const id = text(entry?.id), image = text(entry?.image), artworkTitle = text(entry?.title), artist = text(entry?.artist), year = text(entry?.year), sourceUrl = text(entry?.sourceUrl);
      if (!id || seen.has(id) || !IMAGE_PATH.test(image) || !artworkTitle || !artist || (sourceUrl && !validSourceUrl(sourceUrl))) return null;
      seen.add(id); artworks.push(Object.freeze({ id, image, title:artworkTitle, artist, year, sourceUrl }));
    }
    return Object.freeze({ version:1, title, artworks:Object.freeze(artworks) });
  };
  const shuffledIds = (artworks, random = Math.random) => {
    const ids = artworks.map(({ id }) => id);
    for (let index = ids.length - 1; index > 0; index -= 1) {
      const swap = Math.floor(random() * (index + 1)); [ids[index], ids[swap]] = [ids[swap], ids[index]];
    }
    return ids;
  };
  // This only names a browser-storage bucket. Persisted state remains validated
  // against the current local collection before it can affect rotation.
  const stableStorageHash = (value) => {
    let hash = 0x811c9dc5;
    for (const character of value) { hash ^= character.codePointAt(0); hash = Math.imul(hash, 0x01000193); }
    return (hash >>> 0).toString(16);
  };
  const rotationStorageKey = (collection) => `${ROTATION_STORAGE_PREFIX}${stableStorageHash(collection.title)}`;
  const localStorageOrNull = () => { try { return window.localStorage || null; } catch (_) { return null; } };
  class ArtGallery {
    constructor(root, options = {}) {
      if (!root || !root.ownerDocument) throw new TypeError("Art gallery needs a document root");
      this.root = root; this.document = root.ownerDocument; this.fetch = options.fetch || window.fetch.bind(window);
      this.Image = options.Image || window.Image; this.random = options.random || Math.random;
      this.setInterval = options.setInterval || window.setInterval.bind(window); this.clearInterval = options.clearInterval || window.clearInterval.bind(window);
      this.collectionUrl = options.collectionUrl || "collection.json"; this.timer = null; this.controlsTimer = null;
      this.storage = options.storage === undefined ? localStorageOrNull() : options.storage;
      this.collection = null; this.bag = []; this.history = []; this.historyIndex = -1; this.currentId = null;
      this.rotationShownIds = new Set(); this.rotationLastDisplayedId = null;
      this.currentImage = null; this.nextImage = null; this.failed = new Set(); this.paused = false; this.actionToken = 0;
      this.hidden = this.document.hidden === true; this.destroyed = false;
      this.onVisibility = () => { this.hidden = this.document.hidden === true; this.syncAutoplay(); };
      this.document.addEventListener("visibilitychange", this.onVisibility);
    }
    async start() { await this.reload(); return this; }
    async reload() {
      const token = ++this.actionToken;
      this.stopAutoplay(); this.failed.clear(); this.bag = []; this.history = []; this.historyIndex = -1; this.currentId = null; this.currentImage = null; this.nextImage = null;
      try {
        const response = await this.fetch(this.collectionUrl, { cache:"no-store", credentials:"same-origin" });
        const collection = response?.ok ? validateCollection(await response.json()) : null;
        if (this.destroyed || token !== this.actionToken) return;
        if (!collection) return this.unavailable("Art is unavailable. Add a valid local collection to continue.");
        this.collection = collection; this.restoreRotation(collection); await this.advance(1, true, token);
      } catch (_) { if (!this.destroyed && token === this.actionToken) this.unavailable("Art is temporarily unavailable. Check the local collection and try again."); }
    }
    current() { return this.collection?.artworks.find(({ id }) => id === this.currentId) || null; }
    artworkFor(id) { return this.collection?.artworks.find((artwork) => artwork.id === id) || null; }
    readRotation(collection) {
      if (!this.storage) return null;
      try {
        const parsed = JSON.parse(this.storage.getItem(rotationStorageKey(collection)) || "null");
        if (!parsed || typeof parsed !== "object" || parsed.version !== ROTATION_STATE_VERSION || !Array.isArray(parsed.shownIds)) return null;
        const allowed = new Set(collection.artworks.map(({ id }) => id)); const shown = [];
        for (const id of parsed.shownIds) { if (typeof id !== "string") return null; if (allowed.has(id) && !shown.includes(id)) shown.push(id); }
        const lastDisplayedId = typeof parsed.lastDisplayedId === "string" && allowed.has(parsed.lastDisplayedId) ? parsed.lastDisplayedId : null;
        return { shownIds:shown, lastDisplayedId };
      } catch (_) { return null; }
    }
    persistRotation() {
      if (!this.storage || !this.collection) return;
      const shownIds = this.collection.artworks.map(({ id }) => id).filter((id) => this.rotationShownIds.has(id));
      try { this.storage.setItem(rotationStorageKey(this.collection), JSON.stringify({ version:ROTATION_STATE_VERSION, shownIds, lastDisplayedId:this.rotationLastDisplayedId })); } catch (_) {}
    }
    newRotationBag() {
      if (!this.collection) return [];
      let allowed = this.collection.artworks.filter(({ id }) => !this.failed.has(id));
      let unseen = allowed.filter(({ id }) => !this.rotationShownIds.has(id));
      if (!unseen.length) { this.rotationShownIds.clear(); unseen = allowed; }
      const ids = shuffledIds(unseen, this.random);
      const last = this.rotationLastDisplayedId;
      if (ids.length > 1 && ids[0] === last) { const swap = ids.findIndex((id) => id !== last); [ids[0], ids[swap]] = [ids[swap], ids[0]]; }
      return ids;
    }
    restoreRotation(collection) {
      const persisted = this.readRotation(collection);
      this.rotationShownIds = new Set(persisted?.shownIds || []);
      this.rotationLastDisplayedId = persisted?.lastDisplayedId || null;
      this.bag = this.newRotationBag();
    }
    recordDisplayed(id) {
      this.rotationShownIds.add(id); this.rotationLastDisplayedId = id; this.persistRotation();
    }
    nextCandidate(direction) {
      if (!this.collection) return null;
      const allowed = this.collection.artworks.filter(({ id }) => !this.failed.has(id));
      if (!allowed.length) return null;
      if (direction < 0 && this.historyIndex > 0) {
        const index = this.historyIndex - 1; const artwork = this.artworkFor(this.history[index]);
        if (artwork && !this.failed.has(artwork.id)) return { artwork, historyIndex:index };
      }
      if (direction > 0 && this.historyIndex >= 0 && this.historyIndex < this.history.length - 1) {
        const index = this.historyIndex + 1; const artwork = this.artworkFor(this.history[index]);
        if (artwork && !this.failed.has(artwork.id)) return { artwork, historyIndex:index };
      }
      if (!this.bag.length) this.bag = this.newRotationBag();
      const id = this.bag.shift(); return { artwork:allowed.find(({ id: candidate }) => candidate === id) || allowed[0], historyIndex:null };
    }
    loadImage(artwork) {
      return new Promise((resolve, reject) => { const image = new this.Image(); image.onload = () => resolve(image); image.onerror = () => reject(new Error("image failed")); image.src = artwork.image; });
    }
    commitHistory(artwork, historyIndex) {
      if (historyIndex !== null) { this.historyIndex = historyIndex; return; }
      this.history = this.history.slice(0, this.historyIndex + 1); this.history.push(artwork.id); this.historyIndex = this.history.length - 1;
    }
    discardFailedHistory(id) {
      this.history = this.history.filter((entry) => entry !== id);
      this.historyIndex = this.currentId ? this.history.indexOf(this.currentId) : -1;
    }
    async advance(direction = 1, initial = false, token = ++this.actionToken) {
      if (this.destroyed || !this.collection) return;
      if (direction < 0 && this.currentId && this.historyIndex <= 0) return;
      const candidate = this.nextCandidate(direction);
      if (!candidate) return this.unavailable("Art is unavailable because none of the local images could be displayed.");
      try {
        const image = await this.loadImage(candidate.artwork);
        if (this.destroyed || token !== this.actionToken) return;
        this.currentId = candidate.artwork.id; this.currentImage = image; this.commitHistory(candidate.artwork, candidate.historyIndex);
        this.display(candidate.artwork, image); this.recordDisplayed(candidate.artwork.id); this.syncAutoplay();
      }
      catch (_) {
        if (this.destroyed || token !== this.actionToken) return;
        this.failed.add(candidate.artwork.id); this.discardFailedHistory(candidate.artwork.id);
        if (!this.current() && this.failed.size >= this.collection.artworks.length) return this.unavailable("Art is unavailable because none of the local images could be displayed.");
        if (this.failed.size < this.collection.artworks.length) await this.advance(direction, false, token);
      }
    }
    display(artwork, loadedImage) {
      const shell = this.document.createElement("section"); shell.className = "art-shell"; shell.tabIndex = 0;
      const backdrop = this.document.createElement("img"); backdrop.className = "artwork-backdrop"; backdrop.alt = ""; backdrop.setAttribute("aria-hidden", "true"); backdrop.src = loadedImage.src;
      const image = this.document.createElement("img"); image.className = "artwork"; image.alt = `${artwork.title}, ${artwork.artist}`; image.src = loadedImage.src;
      const caption = this.document.createElement("div"); caption.className = "art-caption";
      const name = this.document.createElement("strong"); name.textContent = artwork.title;
      const details = this.document.createElement("span"); details.textContent = artwork.year ? `${artwork.artist} · ${artwork.year}` : artwork.artist;
      caption.append(name, details);
      const controls = this.document.createElement("div"); controls.className = "art-controls";
      [["Previous", () => this.advance(-1), ""], [this.paused ? "Resume" : "Pause", () => this.togglePause(), "art-pause"], ["Next", () => this.advance(1), ""]].forEach(([label, handler, className]) => {
        const button = this.document.createElement("button"); button.type = "button"; button.className = className; button.textContent = label; button.addEventListener("click", handler); controls.append(button);
      });
      shell.append(backdrop, image, caption, controls);
      shell.addEventListener("pointerdown", (event) => {
        const bounds = shell.getBoundingClientRect();
        if (event.clientX - bounds.left <= EDGE_GUARD_PX || bounds.right - event.clientX <= EDGE_GUARD_PX) return;
        this.revealControls(shell);
      });
      shell.addEventListener("keydown", (event) => {
        if (event.key === "ArrowLeft") { event.preventDefault(); this.advance(-1); }
        if (event.key === "ArrowRight") { event.preventDefault(); this.advance(1); }
        if (event.key === " ") { event.preventDefault(); this.togglePause(); }
      });
      this.root.replaceChildren(shell); this.preloadNext();
    }
    preloadNext() {
      const candidate = this.historyIndex >= 0 && this.historyIndex < this.history.length - 1
        ? this.artworkFor(this.history[this.historyIndex + 1])
        : this.collection?.artworks.find(({ id }) => id === this.bag[0]);
      if (!candidate || candidate.id === this.currentId) return;
      const next = new this.Image(); next.onerror = () => { if (this.nextImage === next) this.nextImage = null; };
      next.src = candidate.image; this.nextImage = next;
    }
    revealControls(shell) {
      shell.classList.add("is-controls-visible");
      if (this.controlsTimer !== null) window.clearTimeout(this.controlsTimer);
      this.controlsTimer = window.setTimeout(() => shell.classList.remove("is-controls-visible"), 4_000);
    }
    togglePause() { this.paused = !this.paused; const button = this.root.querySelector?.(".art-pause"); if (button) button.textContent = this.paused ? "Resume" : "Pause"; this.syncAutoplay(); }
    syncAutoplay() { this.stopAutoplay(); if (!this.paused && !this.hidden && this.current()) this.timer = this.setInterval(() => this.advance(1), CYCLE_MILLIS); }
    stopAutoplay() { if (this.timer !== null) this.clearInterval(this.timer); this.timer = null; }
    unavailable(message) {
      this.stopAutoplay(); this.collection = null; this.currentImage = null; this.nextImage = null;
      const section = this.document.createElement("section"); section.className = "art-unavailable";
      const heading = this.document.createElement("h1"); heading.textContent = "Art unavailable";
      const detail = this.document.createElement("p"); detail.textContent = message;
      const retry = this.document.createElement("button"); retry.className = "art-retry"; retry.type = "button"; retry.textContent = "Retry"; retry.addEventListener("click", () => this.reload());
      section.append(heading, detail, retry); this.root.replaceChildren(section);
    }
    destroy() { this.destroyed = true; ++this.actionToken; this.stopAutoplay(); this.currentImage = null; this.nextImage = null; if (this.controlsTimer !== null) window.clearTimeout(this.controlsTimer); this.document.removeEventListener("visibilitychange", this.onVisibility); this.root.replaceChildren(); }
  }
  const api = Object.freeze({ ArtGallery, validateCollection, shuffledIds, rotationStorageKey, CYCLE_MILLIS, EDGE_GUARD_PX });
  window.FrameOSArtGallery = api;
  document.addEventListener("DOMContentLoaded", () => { const root = document.getElementById("art-gallery"); if (root) new ArtGallery(root).start(); }, { once:true });
})();
