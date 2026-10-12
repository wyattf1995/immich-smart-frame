import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync("examples/frameos-art/art.js", "utf8");
const stylesheet = fs.readFileSync("examples/frameos-art/art.css", "utf8");
const document = { hidden:false, addEventListener() {}, removeEventListener() {}, getElementById() { return null; } };
const window = { document, fetch: async () => ({ ok:false }), Image: class {}, setInterval, clearInterval, setTimeout, clearTimeout };
vm.runInNewContext(source, { window, document, URL, console, setInterval, clearInterval, setTimeout, clearTimeout });
const { ArtGallery, validateCollection, shuffledIds, rotationStorageKey, CYCLE_MILLIS, MAX_ARTWORKS, MAX_HISTORY_ENTRIES, MAX_ROTATION_STORAGE_BYTES } = window.FrameOSArtGallery;
const hash = (character) => character.repeat(64);
const artwork = (id) => ({ id, image:`images/${hash(id)}.jpg`, title:`<${id}> title`, artist:"Artist", year:"1890", sourceUrl:`https://museum.example.invalid/${id}` });
const collection = () => ({ version:1, title:"Impressionist landscapes", artworks:[artwork("a"), artwork("b")] });
const namedCollection = (title, ids) => ({ version:1, title, artworks:ids.map(artwork) });
const indexedArtwork = (index) => {
  const id = index.toString(16).padStart(64, "0");
  return { id, image:`images/${id}.jpg`, title:`Artwork ${index}`, artist:"Artist", year:"1890", sourceUrl:"" };
};
const idleInterval = () => 1;
const idleClear = () => {};
const memoryStorage = () => {
  const values = new Map();
  return { getItem(key) { return values.get(key) ?? null; }, setItem(key, value) { values.set(key, value); }, values };
};
const testGallery = (galleryCollection, storage, options = {}) => {
  const instance = new ArtGallery(root, { Image: class {}, setInterval:idleInterval, clearInterval:idleClear, storage, random:() => 0.99, ...options });
  instance.collection = validateCollection(galleryCollection); instance.restoreRotation(instance.collection); instance.preloadNext = () => {};
  instance.loadImage = async (entry) => ({ src:entry.image }); instance.display = (entry) => { instance.currentId = entry.id; };
  return instance;
};

assert.equal(validateCollection(collection()).artworks.length, 2, "valid local collection is accepted");
assert.equal(validateCollection({ ...collection(), artworks:[] }), null, "empty collections are unavailable, never family fallback");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), year:"", sourceUrl:"" }] }).artworks.length, 1, "local Takeout artwork accepts omitted year and attribution URL");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), sourceUrl:"http://museum.example.invalid/a" }] }), null, "provided attribution URLs must remain safe HTTPS URLs");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), image:"https://evil.example/a.jpg" }] }), null, "external image URLs are refused");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), image:"images/../family.jpg" }] }), null, "escaping image paths are refused");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), sourceUrl:"https://museum.example.invalid/a?token=x" }] }), null, "credential-like source queries are refused");
assert.deepEqual([...shuffledIds(collection().artworks, () => 0.99)].sort(), ["a", "b"], "shuffle bag preserves every artwork exactly once");
assert.equal(validateCollection({ version:1, title:"Too many", artworks:Array.from({ length:MAX_ARTWORKS + 1 }, (_, index) => indexedArtwork(index)) }), null, "an oversized local collection is refused before it can grow rotation state");

const root = { ownerDocument:document, replaceChildren() {} };
const scheduled = []; const cleared = [];
const gallery = new ArtGallery(root, {
  Image: class {},
  setInterval(callback, delay) { scheduled.push({ callback, delay }); return scheduled.length; },
  clearInterval(id) { cleared.push(id); },
});
gallery.collection = validateCollection(collection());
gallery.bag = ["a", "b"];
gallery.preloadNext = () => {};
gallery.loadImage = async (entry) => ({ src:entry.image });
gallery.display = (entry) => { gallery.currentId = entry.id; };
await gallery.advance(1, true);
assert.equal(gallery.currentId, "a", "next renders the first shuffled local artwork");
await gallery.advance(-1);
assert.equal(gallery.currentId, "a", "previous at the beginning preserves the current local artwork");
await gallery.advance(1);
assert.equal(gallery.currentId, "b", "next advances without touching family Photos");
await gallery.advance(-1);
assert.equal(gallery.currentId, "a", "previous navigates the local collection");
await gallery.advance(1);
assert.equal(gallery.currentId, "b", "next returns through actual viewing history after Previous");
const noRepeat = new ArtGallery(root, { Image: class {}, setInterval, clearInterval, random:() => 0 });
noRepeat.collection = validateCollection(collection()); noRepeat.currentId = "a"; noRepeat.bag = [];
assert.equal(noRepeat.nextCandidate(1).artwork.id, "b", "a completed shuffle bag does not immediately repeat the current artwork");

const rotationCollection = namedCollection("Rotation test", ["a", "b", "c"]);
const storage = memoryStorage();
const firstRotation = testGallery(rotationCollection, storage); firstRotation.bag = ["a", "b", "c"];
await firstRotation.advance(1, true);
assert.equal(firstRotation.currentId, "a", "the first successful artwork is recorded only after display");
const recreatedRotation = testGallery(rotationCollection, storage);
assert.ok(!recreatedRotation.bag.includes("a"), "gallery recreation retains the successful display and excludes it for this cycle");
await recreatedRotation.advance(1, true); const secondRotationId = recreatedRotation.currentId;
assert.notEqual(secondRotationId, "a", "recreated gallery does not immediately repeat the prior successful artwork");
await recreatedRotation.advance(1); const thirdRotationId = recreatedRotation.currentId;
assert.notEqual(thirdRotationId, secondRotationId, "rotation visits a distinct remaining artwork before cycling");
const nextCycle = testGallery(rotationCollection, storage);
await nextCycle.advance(1, true);
assert.notEqual(nextCycle.currentId, thirdRotationId, "the next cycle avoids repeating its previous boundary artwork");

const corruptStorage = memoryStorage(); corruptStorage.setItem(rotationStorageKey(validateCollection(rotationCollection)), "not-json");
const recoveredFromCorruptState = testGallery(rotationCollection, corruptStorage);
assert.equal(recoveredFromCorruptState.bag.length, 3, "corrupt rotation state is ignored without losing the local collection");
const oversizedStateStorage = memoryStorage();
const rotationKey = rotationStorageKey(validateCollection(rotationCollection));
oversizedStateStorage.setItem(rotationKey, JSON.stringify({ version:1, shownIds:["a"], lastDisplayedId:"a", padding:"x".repeat(MAX_ROTATION_STORAGE_BYTES) }));
const ignoredOversizedState = testGallery(rotationCollection, oversizedStateStorage);
assert.ok(ignoredOversizedState.bag.includes("a"), "oversized valid storage is ignored before JSON parsing");
const oversizedShownStorage = memoryStorage();
oversizedShownStorage.setItem(rotationKey, JSON.stringify({ version:1, shownIds:Array(MAX_ARTWORKS + 1).fill("a"), lastDisplayedId:"a" }));
const ignoredOversizedShown = testGallery(rotationCollection, oversizedShownStorage);
assert.ok(ignoredOversizedShown.bag.includes("a"), "overlong shown-ID state is ignored without iterating it");
const changedCollection = namedCollection("Rotation test", ["a", "b", "c", "d"]);
const changedGallery = testGallery(changedCollection, storage);
assert.ok(changedGallery.bag.includes("d"), "new collection artwork joins the unfinished rotation");
const removedCollection = namedCollection("Rotation test", ["a", "c"]);
const removedGallery = testGallery(removedCollection, storage);
assert.ok(removedGallery.bag.every((id) => id === "a" || id === "c"), "removed collection artwork is discarded from persisted rotation state");
await removedGallery.advance(1, true);
assert.notEqual(removedGallery.currentId, "a", "collection removal still avoids repeating the persisted rotation boundary");
const failingStorage = memoryStorage();
const failedRotation = testGallery(rotationCollection, failingStorage); failedRotation.bag = ["a", "b"];
failedRotation.loadImage = async (entry) => entry.id === "a" ? Promise.reject(new Error("failed first image")) : ({ src:entry.image });
await failedRotation.advance(1, true);
const failedState = JSON.parse(failingStorage.getItem(rotationStorageKey(validateCollection(rotationCollection))));
assert.deepEqual(failedState.shownIds, ["b"], "failed image attempts are never persisted as displays");
const deniedStorage = { getItem() { throw new Error("denied"); }, setItem() { throw new Error("denied"); } };
const noStorageGallery = testGallery(rotationCollection, deniedStorage); await noStorageGallery.advance(1, true);
assert.ok(noStorageGallery.currentId, "storage denial falls back to in-memory local rotation");

gallery.syncAutoplay();
assert.equal(CYCLE_MILLIS, 120_000, "autoplay uses the two-minute cadence");
assert.equal(scheduled.at(-1).delay, CYCLE_MILLIS, "autoplay schedules the configured cadence");
gallery.togglePause();
assert.equal(gallery.paused, true, "space/control pause state stops autoplay");
assert.ok(cleared.length > 0, "pause clears the scheduled cycle");
document.hidden = true; gallery.onVisibility();
assert.equal(gallery.timer, null, "hidden document does not keep Art autoplaying");

const failures = new ArtGallery(root, { Image: class {}, setInterval:idleInterval, clearInterval:idleClear });
failures.collection = validateCollection(collection()); failures.bag = ["a", "b"]; failures.preloadNext = () => {};
failures.loadImage = async (entry) => entry.id === "a" ? Promise.reject(new Error("temporary image failure")) : ({ src:entry.image });
failures.display = (entry) => { failures.currentId = entry.id; };
await failures.advance(1, true);
assert.equal(failures.currentId, "b", "a failed local image advances to another Art image");
assert.equal(failures.failed.has("a"), true, "a failed image is not retried in a tight loop");

const racing = new ArtGallery(root, { Image: class {}, setInterval:idleInterval, clearInterval:idleClear });
racing.collection = validateCollection(namedCollection("Race coverage", ["a", "b", "c"])); racing.bag = ["a", "b", "c"]; racing.preloadNext = () => {};
const pending = new Map(); const renderedRaceIds = [];
racing.loadImage = (entry) => new Promise((resolve) => pending.set(entry.id, () => resolve({ src:entry.image })));
racing.display = (entry) => { racing.currentId = entry.id; renderedRaceIds.push(entry.id); };
const earlier = racing.advance(1, true); const later = racing.advance(1, false);
pending.get("b")(); await later; pending.get("a")(); await earlier;
assert.equal(racing.currentId, "b", "a slower stale load cannot overwrite the latest navigation action");
racing.loadImage = async (entry) => ({ src:entry.image });
await racing.advance(1); await racing.advance(1);
assert.deepEqual(renderedRaceIds, ["b", "c", "a"], "fast Next still returns every unseen viable artwork before repeating the cycle");

const boundedHistory = testGallery(namedCollection("History bound", ["a", "b", "c"]), memoryStorage());
for (let index = 0; index < MAX_HISTORY_ENTRIES + 7; index += 1) await boundedHistory.advance(1, true);
assert.equal(boundedHistory.history.length, MAX_HISTORY_ENTRIES, "rotation history retains only the bounded manual-navigation window");
const latestHistoryId = boundedHistory.currentId;
await boundedHistory.advance(-1); assert.notEqual(boundedHistory.currentId, latestHistoryId, "Previous still works at the retained history boundary");
await boundedHistory.advance(1); assert.equal(boundedHistory.currentId, latestHistoryId, "Next returns through retained history without changing the cycle");

const retiring = new ArtGallery(root, { Image: class {}, setInterval:idleInterval, clearInterval:idleClear });
retiring.collection = validateCollection(collection()); retiring.bag = ["a"]; retiring.preloadNext = () => {};
let renderedAfterDestroy = false; let resolveRetiring;
retiring.loadImage = () => new Promise((resolve) => { resolveRetiring = resolve; });
retiring.display = () => { renderedAfterDestroy = true; };
const retiredLoad = retiring.advance(1, true); retiring.destroy(); resolveRetiring({ src:"images/a.jpg" }); await retiredLoad;
assert.equal(renderedAfterDestroy, false, "a destroyed gallery ignores an in-flight image completion");

assert.doesNotMatch(source, /innerHTML/, "captions are assigned with textContent, never HTML");
assert.match(source, /fetch\(this\.collectionUrl/, "only the same-origin local collection is fetched");
assert.doesNotMatch(source, /fetch\([^)]*sourceUrl/, "museum attribution is metadata, not an external image request");
assert.match(source, /EDGE_GUARD_PX/, "edge gestures are deliberately left to native navigation");
assert.match(source, /\[\["Previous"/, "tap-revealed controls include previous navigation");
assert.match(source, /"Resume"\s*:\s*"Pause"/, "tap-revealed controls include pause and resume");
assert.match(source, /this\.paused \? "Resume" : "Pause"/, "pause control reports Resume when cycling is paused");
assert.match(source, /\["Next"/, "tap-revealed controls include next navigation");
assert.match(stylesheet, /\.artwork\s*\{[^}]*object-fit:contain/, "foreground artwork remains fully visible without cropping");
assert.match(stylesheet, /\.artwork-backdrop\s*\{[^}]*object-fit:cover[^}]*filter:blur\(24px\)/, "a blurred cover backdrop fills contain gaps");
assert.match(stylesheet, /\.artwork\s*\{[^}]*background:transparent/, "foreground artwork has no cream border or flash");
assert.doesNotMatch(stylesheet, /#e9e5dc/, "Art fullscreen surface does not flash cream");
assert.match(source, /backdrop\.className = "artwork-backdrop"/, "display creates a separate artwork backdrop");
assert.match(source, /backdrop\.src = loadedImage\.src/, "backdrop is synchronized to the loaded local artwork");
assert.match(source, /ROTATION_STORAGE_PREFIX/, "Art rotation persists only under a bounded local storage namespace");
assert.match(source, /this\.recordDisplayed\(candidate\.artwork\.id\)/, "only a completed display records rotation progress");
assert.match(source, /rotationShownIds/, "rotation state stores stable artwork identifiers rather than image bytes");
assert.doesNotMatch(source, /JSON\.stringify\([^)]*(?:image|sourceUrl)/, "rotation storage never writes image data or source URLs");
assert.match(source, /backdrop\.setAttribute\("aria-hidden", "true"\)/, "decorative backdrop is hidden from assistive technology");
assert.match(source, /shell\.append\(backdrop, image, caption, controls\)/, "backdrop remains behind the complete foreground artwork and controls");
assert.match(stylesheet, /\.art-controls\s*\{[^}]*pointer-events:none/, "hidden controls cannot receive the first tap");
assert.match(stylesheet, /\.art-shell\.is-controls-visible \.art-controls\s*\{[^}]*pointer-events:auto/, "visible controls become interactive after being revealed");
console.log("FrameOS Art gallery tests passed");
