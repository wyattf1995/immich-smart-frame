import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync("examples/frameos-art/art.js", "utf8");
const stylesheet = fs.readFileSync("examples/frameos-art/art.css", "utf8");
const document = { hidden:false, addEventListener() {}, removeEventListener() {}, getElementById() { return null; } };
const window = { document, fetch: async () => ({ ok:false }), Image: class {}, setInterval, clearInterval, setTimeout, clearTimeout };
vm.runInNewContext(source, { window, document, URL, console, setInterval, clearInterval, setTimeout, clearTimeout });
const { ArtGallery, validateCollection, shuffledIds, CYCLE_MILLIS } = window.FrameOSArtGallery;
const hash = (character) => character.repeat(64);
const artwork = (id) => ({ id, image:`images/${hash(id)}.jpg`, title:`<${id}> title`, artist:"Artist", year:"1890", sourceUrl:`https://museum.example.invalid/${id}` });
const collection = () => ({ version:1, title:"Impressionist landscapes", artworks:[artwork("a"), artwork("b")] });

assert.equal(validateCollection(collection()).artworks.length, 2, "valid local collection is accepted");
assert.equal(validateCollection({ ...collection(), artworks:[] }), null, "empty collections are unavailable, never family fallback");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), year:"", sourceUrl:"" }] }).artworks.length, 1, "local Takeout artwork accepts omitted year and attribution URL");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), sourceUrl:"http://museum.example.invalid/a" }] }), null, "provided attribution URLs must remain safe HTTPS URLs");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), image:"https://evil.example/a.jpg" }] }), null, "external image URLs are refused");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), image:"images/../family.jpg" }] }), null, "escaping image paths are refused");
assert.equal(validateCollection({ ...collection(), artworks:[{ ...artwork("a"), sourceUrl:"https://museum.example.invalid/a?token=x" }] }), null, "credential-like source queries are refused");
assert.deepEqual([...shuffledIds(collection().artworks, () => 0.99)].sort(), ["a", "b"], "shuffle bag preserves every artwork exactly once");

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

gallery.syncAutoplay();
assert.equal(scheduled.at(-1).delay, CYCLE_MILLIS, "autoplay uses the sixty second cadence");
gallery.togglePause();
assert.equal(gallery.paused, true, "space/control pause state stops autoplay");
assert.ok(cleared.length > 0, "pause clears the scheduled cycle");
document.hidden = true; gallery.onVisibility();
assert.equal(gallery.timer, null, "hidden document does not keep Art autoplaying");

const failures = new ArtGallery(root, { Image: class {}, setInterval, clearInterval });
failures.collection = validateCollection(collection()); failures.bag = ["a", "b"]; failures.preloadNext = () => {};
failures.loadImage = async (entry) => entry.id === "a" ? Promise.reject(new Error("temporary image failure")) : ({ src:entry.image });
failures.display = (entry) => { failures.currentId = entry.id; };
await failures.advance(1, true);
assert.equal(failures.currentId, "b", "a failed local image advances to another Art image");
assert.equal(failures.failed.has("a"), true, "a failed image is not retried in a tight loop");

const racing = new ArtGallery(root, { Image: class {}, setInterval, clearInterval });
racing.collection = validateCollection(collection()); racing.bag = ["a", "b"]; racing.preloadNext = () => {};
const pending = new Map();
racing.loadImage = (entry) => new Promise((resolve) => pending.set(entry.id, () => resolve({ src:entry.image })));
racing.display = (entry) => { racing.currentId = entry.id; };
const earlier = racing.advance(1, true); const later = racing.advance(1, false);
pending.get("b")(); await later; pending.get("a")(); await earlier;
assert.equal(racing.currentId, "b", "a slower stale load cannot overwrite the latest navigation action");

const retiring = new ArtGallery(root, { Image: class {}, setInterval, clearInterval });
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
assert.match(stylesheet, /object-fit:contain/, "artwork is fit without cropping");
console.log("FrameOS Art gallery tests passed");
