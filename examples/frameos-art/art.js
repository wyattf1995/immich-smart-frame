(() => {
  "use strict";
  const CYCLE_MILLIS = 60_000;
  const EDGE_GUARD_PX = 36;
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
      const id = text(entry?.id), image = text(entry?.image), artworkTitle = text(entry?.title), artist = text(entry?.artist), year = text(entry?.year);
      if (!id || seen.has(id) || !IMAGE_PATH.test(image) || !artworkTitle || !artist || !year || !validSourceUrl(entry?.sourceUrl)) return null;
      seen.add(id); artworks.push(Object.freeze({ id, image, title:artworkTitle, artist, year, sourceUrl:text(entry.sourceUrl) }));
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
  class ArtGallery {
    constructor(root, options = {}) {
      if (!root || !root.ownerDocument) throw new TypeError("Art gallery needs a document root");
      this.root = root; this.document = root.ownerDocument; this.fetch = options.fetch || window.fetch.bind(window);
      this.Image = options.Image || window.Image; this.random = options.random || Math.random;
      this.setInterval = options.setInterval || window.setInterval.bind(window); this.clearInterval = options.clearInterval || window.clearInterval.bind(window);
      this.collectionUrl = options.collectionUrl || "collection.json"; this.timer = null; this.controlsTimer = null;
      this.collection = null; this.bag = []; this.currentId = null; this.failed = new Set(); this.paused = false; this.hidden = this.document.hidden === true; this.destroyed = false;
      this.onVisibility = () => { this.hidden = this.document.hidden === true; this.syncAutoplay(); };
      this.document.addEventListener("visibilitychange", this.onVisibility);
    }
    async start() { await this.reload(); return this; }
    async reload() {
      this.stopAutoplay(); this.failed.clear(); this.bag = []; this.currentId = null;
      try {
        const response = await this.fetch(this.collectionUrl, { cache:"no-store", credentials:"same-origin" });
        const collection = response?.ok ? validateCollection(await response.json()) : null;
        if (!collection) return this.unavailable("Art is unavailable. Add a valid local collection to continue.");
        this.collection = collection; this.bag = shuffledIds(collection.artworks, this.random); await this.advance(1, true);
      } catch (_) { this.unavailable("Art is temporarily unavailable. Check the local collection and try again."); }
    }
    current() { return this.collection?.artworks.find(({ id }) => id === this.currentId) || null; }
    nextCandidate(direction) {
      if (!this.collection) return null;
      const allowed = this.collection.artworks.filter(({ id }) => !this.failed.has(id));
      if (!allowed.length) return null;
      if (!this.bag.length) this.bag = shuffledIds(allowed, this.random);
      if (direction < 0 && this.currentId) {
        const index = allowed.findIndex(({ id }) => id === this.currentId);
        return allowed[(index - 1 + allowed.length) % allowed.length];
      }
      const id = this.bag.shift(); return allowed.find(({ id: candidate }) => candidate === id) || allowed[0];
    }
    loadImage(artwork) {
      return new Promise((resolve, reject) => { const image = new this.Image(); image.onload = () => resolve(image); image.onerror = () => reject(new Error("image failed")); image.src = artwork.image; });
    }
    async advance(direction = 1, initial = false) {
      if (this.destroyed || !this.collection) return;
      const candidate = this.nextCandidate(direction);
      if (!candidate) return this.unavailable("Art is unavailable because none of the local images could be displayed.");
      try { const image = await this.loadImage(candidate); if (this.destroyed) return; this.display(candidate, image); this.syncAutoplay(); }
      catch (_) {
        this.failed.add(candidate.id);
        if (!this.current() && this.failed.size >= this.collection.artworks.length) return this.unavailable("Art is unavailable because none of the local images could be displayed.");
        if (!initial || this.failed.size < this.collection.artworks.length) await this.advance(direction, false);
      }
    }
    display(artwork, loadedImage) {
      this.currentId = artwork.id;
      const shell = this.document.createElement("section"); shell.className = "art-shell"; shell.tabIndex = 0;
      const image = this.document.createElement("img"); image.className = "artwork"; image.alt = `${artwork.title}, ${artwork.artist}`; image.src = loadedImage.src;
      const caption = this.document.createElement("div"); caption.className = "art-caption";
      const name = this.document.createElement("strong"); name.textContent = artwork.title;
      const details = this.document.createElement("span"); details.textContent = `${artwork.artist} · ${artwork.year}`;
      caption.append(name, details);
      const controls = this.document.createElement("div"); controls.className = "art-controls";
      [["Previous", () => this.advance(-1)], ["Pause", () => this.togglePause()], ["Next", () => this.advance(1)]].forEach(([label, handler]) => {
        const button = this.document.createElement("button"); button.type = "button"; button.textContent = label; button.addEventListener("click", handler); controls.append(button);
      });
      shell.append(image, caption, controls);
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
      const candidate = this.collection?.artworks.find(({ id }) => id === this.bag[0]); if (!candidate) return;
      const next = new this.Image(); next.src = candidate.image;
    }
    revealControls(shell) {
      shell.classList.add("is-controls-visible");
      if (this.controlsTimer !== null) window.clearTimeout(this.controlsTimer);
      this.controlsTimer = window.setTimeout(() => shell.classList.remove("is-controls-visible"), 4_000);
    }
    togglePause() { this.paused = !this.paused; this.syncAutoplay(); }
    syncAutoplay() { this.stopAutoplay(); if (!this.paused && !this.hidden && this.current()) this.timer = this.setInterval(() => this.advance(1), CYCLE_MILLIS); }
    stopAutoplay() { if (this.timer !== null) this.clearInterval(this.timer); this.timer = null; }
    unavailable(message) {
      this.stopAutoplay(); this.collection = null;
      const section = this.document.createElement("section"); section.className = "art-unavailable";
      const heading = this.document.createElement("h1"); heading.textContent = "Art unavailable";
      const detail = this.document.createElement("p"); detail.textContent = message;
      const retry = this.document.createElement("button"); retry.className = "art-retry"; retry.type = "button"; retry.textContent = "Retry"; retry.addEventListener("click", () => this.reload());
      section.append(heading, detail, retry); this.root.replaceChildren(section);
    }
    destroy() { this.destroyed = true; this.stopAutoplay(); if (this.controlsTimer !== null) window.clearTimeout(this.controlsTimer); this.document.removeEventListener("visibilitychange", this.onVisibility); this.root.replaceChildren(); }
  }
  const api = Object.freeze({ ArtGallery, validateCollection, shuffledIds, CYCLE_MILLIS, EDGE_GUARD_PX });
  window.FrameOSArtGallery = api;
  document.addEventListener("DOMContentLoaded", () => { const root = document.getElementById("art-gallery"); if (root) new ArtGallery(root).start(); }, { once:true });
})();
