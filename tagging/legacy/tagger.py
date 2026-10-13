#!/usr/bin/env python3
"""Immich VLM tagger — DB-only writes via the API. See project_immich_vlm_tagging_geo.md.
VLM emits FREE-FORM tags + caption (accurate); we MAP free-form terms -> the controlled
taxonomy in code (folder name + caption + free tags feed the match). Sidecar queue MUST be paused.
Modes:  --dry-run --ids <file>    |    --full [--limit N]"""
import json, base64, os, sys, time, re, sqlite3, urllib.request

IMMICH = os.environ.get("IMMICH_URL", "http://localhost:8080")
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434")
KEY    = os.environ["IMMICH_KEY"]
MODEL  = os.environ.get("VLM_MODEL", "qwen3-vl:8b-instruct")
WORK   = os.environ.get("WORK_DIR", "/work")
DRY    = "--dry-run" in sys.argv
def _arg(f, d=None): return sys.argv[sys.argv.index(f)+1] if f in sys.argv else d
LIMIT    = int(_arg("--limit", "0")) or None
IDS_FILE = _arg("--ids")
CKPT = os.path.join(WORK, "processed.txt"); LOGF = os.path.join(WORK, "tagger.log"); LOCK = os.path.join(WORK, "tagger.lock")

# These ceilings are deliberately separate because preview pixels, Immich JSON,
# and a model response have very different expected sizes. They bound an
# unhealthy peer without changing tag selection or write behavior.
MAX_METADATA_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_MODEL_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_PREVIEW_RESPONSE_BYTES = 16 * 1024 * 1024
METADATA_TIMEOUT_SECONDS = 30
PREVIEW_TIMEOUT_SECONDS = 60
MODEL_TIMEOUT_SECONDS = 300

class ResponseTooLarge(RuntimeError): pass

class Checkpoint:
    """Disk-backed processed-ID index with streaming legacy text compatibility."""
    def __init__(self, path):
        self.path = path
        self.db = sqlite3.connect(path + ".sqlite3")
        self.db.execute("CREATE TABLE IF NOT EXISTS processed (asset_id TEXT PRIMARY KEY)")
        # Existing deployments use processed.txt. Import it one line at a time
        # on every start, so an older runner can still add entries between runs.
        if os.path.exists(path):
            with open(path, encoding="utf-8") as legacy:
                for line in legacy:
                    asset_id = line.strip()
                    if asset_id:
                        self.db.execute("INSERT OR IGNORE INTO processed(asset_id) VALUES (?)", (asset_id,))
        self.db.commit()
        self.legacy = open(path, "a", encoding="utf-8")

    def contains(self, asset_id):
        return self.db.execute("SELECT 1 FROM processed WHERE asset_id = ?", (asset_id,)).fetchone() is not None

    def count(self):
        return self.db.execute("SELECT COUNT(*) FROM processed").fetchone()[0]

    def mark(self, asset_id):
        self.db.execute("INSERT OR IGNORE INTO processed(asset_id) VALUES (?)", (asset_id,))
        self.db.commit()
        # Preserve the text checkpoint for existing operators and rollback
        # tooling. Repeated writes remain harmless because SQLite deduplicates.
        self.legacy.write(asset_id + "\n")
        self.legacy.flush()

    def close(self):
        self.legacy.close()
        self.db.close()

def log(m):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + m
    print(line, flush=True)
    try: open(LOGF, "a").write(line + "\n")
    except Exception: pass

def http(method, url, body=None, headers=None, timeout=METADATA_TIMEOUT_SECONDS, max_bytes=MAX_METADATA_RESPONSE_BYTES):
    h = dict(headers or {}); data = None
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h, method=method), timeout=timeout) as r:
        deadline = time.monotonic() + timeout
        chunks = []; total = 0
        while True:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"request deadline exceeded after {timeout}s")
            chunk = r.read(min(64 * 1024, max_bytes + 1 - total))
            if not chunk: break
            total += len(chunk)
            if total > max_bytes:
                raise ResponseTooLarge(f"response exceeds {max_bytes} bytes")
            chunks.append(chunk)
        return b"".join(chunks)
def im(method, path, body=None):
    b = http(method, IMMICH + path, body, {"x-api-key": KEY}, timeout=METADATA_TIMEOUT_SECONDS, max_bytes=MAX_METADATA_RESPONSE_BYTES); return json.loads(b) if b else None
def im_bytes(path):
    return http("GET", IMMICH + path, headers={"x-api-key": KEY}, timeout=PREVIEW_TIMEOUT_SECONDS, max_bytes=MAX_PREVIEW_RESPONSE_BYTES)

# ---- controlled-vocabulary keyword map: taxonomy tag -> substrings implying it ----
KW = {
 "Scene/Backyard":["backyard","back yard"],"Scene/Garden":["garden","flowerbed","flower bed","planter","vegetable"],
 "Scene/HomeInterior":["living room","bedroom","interior","couch","sofa","indoor room"],"Scene/Kitchen":["kitchen"],
 "Scene/Aquarium":["aquarium","fish tank","aquascape","planted tank","substrate"],"Scene/Forest":["forest","woodland","woods"],
 "Scene/Redwoods":["redwood"],"Scene/Beach":["beach","seashore","sandy shore"],"Scene/Coast":["coast","shoreline","cliff","breakwater","pier","jetty"],
 "Scene/Ocean":["ocean","waves","surf","tide "," sea "],"Scene/Tidepools":["tide pool","tidepool"],"Scene/Lake":["lake","pond"],
 "Scene/River":["river","creek","stream"],"Scene/Waterfall":["waterfall"],"Scene/Mountains":["mountain","peak","summit","alpine"],
 "Scene/Desert":["desert","dunes","arid"],"Scene/Canyon":["canyon","gorge","mesa","butte"],"Scene/Snow":["snow"],
 "Scene/City":["cityscape","downtown","skyline","urban"],"Scene/Street":["street","road","sidewalk","suburban"],"Scene/Park":["park "],
 "Scene/BotanicalGarden":["botanical"],"Scene/Farm":["farm","orchard","barn"],"Scene/Vineyard":["vineyard","winery"],"Scene/Zoo":["zoo"],
 "Scene/Indoor":["indoors"],"Scene/Outdoor":["outdoors"],
 "Light/GoldenHour":["golden hour"],"Light/Sunset":["sunset","dusk"],"Light/Sunrise":["sunrise","dawn"],"Light/BlueHour":["blue hour","twilight"],
 "Light/Night":["night","dark sky"],"Light/Overcast":["overcast","cloudy sky"],"Light/Backlit":["backlit","silhouette"],
 "People/Family":["family"],"People/Portrait":["portrait","headshot"],"People/Group":["group of","people","crowd"],"People/Candid":["candid"],
 "People/Baby":["baby","infant","newborn"],"People/Child":["child","kid","toddler"," boy "," girl "],"People/Selfie":["selfie"],
 "Animals/Dog":["dog","puppy","retriever","canine"],"Animals/Cat":["cat","kitten","feline"],"Animals/GuineaPig":["guinea pig"],
 "Animals/Rabbit":["rabbit","bunny"],"Animals/Pet":["pet "],"Animals/Bird":["bird"],"Animals/Songbird":["sparrow","finch","robin","songbird"],
 "Animals/Hummingbird":["hummingbird"],"Animals/Duck":["duck","goose","waterfowl"],"Animals/BirdOfPrey":["hawk","eagle","owl","falcon"],
 "Animals/Butterfly":["butterfly","moth"],"Animals/Bee":["bee ","bees","honeybee","bumblebee"],
 "Animals/Insect":["insect","bug ","mantis","dragonfly","grasshopper","beetle","ladybug"," ant "],"Animals/Spider":["spider","arachnid"],
 "Animals/Fish":["fish","shrimp","snail"],"Animals/Wildlife":["wildlife","deer","squirrel","lizard","turtle"],
 "Plants/Flower":["flower","blossom","bloom","rose","tulip","daisy","orchid","petal"],"Plants/Succulent":["succulent","aloe"],
 "Plants/Houseplant":["houseplant","potted plant","indoor plant"],"Plants/Tree":["tree","palm","canopy"],"Plants/PineTree":["pine","conifer","spruce"," fir "],
 "Plants/Fern":["fern"],"Plants/Mushroom":["mushroom","fungi","fungus"],"Plants/Cactus":["cactus","cacti"],"Plants/GardenBed":["garden bed","raised bed"],
 "Plants/Foliage":["foliage","leaves","leaf","greenery","shrub","bush","plant","sapling","seedling"],"Plants/AquaticPlant":["aquatic plant","aquarium plant"],
 "Plants/Fruit":["fruit","avocado","apple","citrus","berry","berries","pepper","tomato","chili","cannabis"],
 "Things/Food":["food","dish of","plate of","snack"],"Things/Meal":["meal","dinner","lunch","breakfast"],"Things/Dessert":["dessert","cake","cookie","ice cream"],
 "Things/Drink":["beverage","coffee","cocktail"," beer ","wine glass"],"Things/Guitar":["guitar"],"Things/Instrument":["piano","drum","ukulele","violin","instrument"],
 "Things/Car":[" car ","truck","vehicle","automobile","dodge"],"Things/Boat":["boat","ship","kayak","canoe"],
 "Things/Building":["building","tower","warehouse","structure"],"Things/Architecture":["architecture","facade"],"Things/Sign":["sign ","signage","billboard"],
 "Things/Book":["book","manual","magazine","sheet music"],"Things/Electronics":["laptop","computer","phone","camera","electrical panel","circuit"],
 "Things/Artwork":["artwork","painting","mural","drawing"],"Things/ProductListing":["for sale","product photo","listing","jeans","clothing item"],
 "Activity/Hiking":["hiking","hike","trail"],"Activity/Walking":["walking"],"Activity/Camping":["camping","campsite","tent"],
 "Activity/Travel":["travel","vacation","trip"],"Activity/RoadTrip":["road trip"],"Activity/BeachDay":["beach day"],"Activity/Gardening":["gardening"],
 "Activity/Aquascaping":["aquascape","aquascaping"],"Activity/DIYProject":["diy","construction","tools","workbench"],"Activity/Cooking":["cooking","baking"],
 "Activity/Golf":["golf"],"Activity/Sports":["sport","soccer","basketball","tennis","baseball"],"Activity/Concert":["concert","live music"," stage "],
 "Activity/Festival":["festival"," fair "],"Activity/Party":["party"],"Activity/Gathering":["gathering","hangout"],
 "Event/Birthday":["birthday"],"Event/Wedding":["wedding","bride","groom"],"Event/Graduation":["graduation","commencement"],"Event/Holiday":["holiday"],
 "Event/Christmas":["christmas"],"Event/Thanksgiving":["thanksgiving"],"Event/Halloween":["halloween","pumpkin"],"Event/Easter":["easter"],"Event/NewYear":["new year","fireworks"],
 "Style/Macro":["macro","extreme close"],"Style/Closeup":["close-up","closeup","close up"],"Style/Landscape":["landscape","scenic","vista"],
 "Style/Astrophotography":["milky way","starry","astro","stars in","night sky"],"Style/LongExposure":["long exposure","light trail"],
 "Style/Timelapse":["timelapse","time-lapse","time lapse"],"Style/Panorama":["panorama"],"Style/BlackAndWhite":["black and white","monochrome"," b&w"],
 "Skip/Screenshot":["screenshot"],"Skip/Document":["scanned document","receipt","paperwork"],"Skip/Meme":["meme"],"Skip/Blurry":["out of focus","blurry"],
}
def folder_words(path):
    parts = (path or "").split("/")
    seg = parts[-2] if len(parts) >= 2 else ""
    seg = re.sub(r"^\d{4}-\d{2}-\d{2}\s*", "", seg)  # strip date prefix
    return " " + seg.lower() + " "
KW_RE = {tag: re.compile(r"\b(?:" + "|".join(re.escape(k.strip()) for k in kws) + r")s?\b")
         for tag, kws in KW.items() if kws}
def map_tags(free_tags, caption, path):
    hay = " " + " ".join(free_tags).lower() + " || " + (caption or "").lower() + folder_words(path) + " "
    return [tag for tag, rx in KW_RE.items() if rx.search(hay)][:12]

SCHEMA = {"type":"object","properties":{
    "tags":{"type":"array","items":{"type":"string"}},"caption":{"type":"string"},
    "ocr_text":{"type":"array","items":{"type":"string"}}},"required":["tags","caption"]}
PROMPT = ("Describe this photo. Output: (1) tags = 5-12 concise lowercase nouns/phrases for the "
          "subjects, animals, plants, objects, scene and setting actually visible; (2) caption = one "
          "factual sentence, max 18 words; (3) ocr_text = any legible sign/label/landmark text. "
          "Do NOT guess a geographic location. Do not invent people names, dates, or places.")

TAGMAP = {}
def ensure_tags():
    for t in im("PUT", "/api/tags", {"tags": list(KW.keys())}):
        TAGMAP[t["value"]] = t["id"]
    log(f"vocabulary present: {len(TAGMAP)} tags")

def parse_vlm(txt):
    # Ollama can truncate format-constrained output mid-string when a sign
    # photo makes ocr_text huge. tags+caption always precede ocr_text in the
    # schema, so salvage them from the prefix; OCR is unused for tag mapping.
    try:
        return json.loads(txt)
    except Exception:
        m = re.search(r'"tags"\s*:\s*\[(.*?)\]', txt, re.S)
        c = re.search(r'"caption"\s*:\s*"((?:[^"\\]|\\.)*)"', txt)
        if m and c:
            tags = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
            return {"tags": tags, "caption": c.group(1), "ocr_text": []}
        raise

def vlm(asset_id):
    img = im_bytes(f"/api/assets/{asset_id}/thumbnail?size=preview")
    payload = {"model":MODEL,"prompt":PROMPT,"images":[base64.b64encode(img).decode()],
               "stream":False,"keep_alive":"30m","think":False,"format":SCHEMA,"options":{"temperature":0.2,"num_ctx":8192,"num_predict":1200}}
    out = json.loads(http("POST", OLLAMA+"/api/generate", payload, {"Content-Type":"application/json"}, timeout=MODEL_TIMEOUT_SECONDS, max_bytes=MAX_MODEL_RESPONSE_BYTES))
    return parse_vlm(out["response"])

def process(asset_id, path, existing_desc):
    p = vlm(asset_id)
    free = p.get("tags", []); caption = (p.get("caption") or "").strip()
    tags = [t for t in map_tags(free, caption, path) if t in TAGMAP]
    if tags:
        im("PUT", "/api/tags/assets", {"tagIds":[TAGMAP[t] for t in tags], "assetIds":[asset_id]})
    if caption and (not existing_desc or existing_desc.startswith("[AI]")):
        im("PUT", "/api/assets", {"ids":[asset_id], "description": f"[AI] {caption} [/AI]"})
    return free, tags, caption

def ml_busy():
    try:
        j = im("GET", "/api/jobs")
        for q in ("smartSearch","faceDetection","facialRecognition","ocr","thumbnailGeneration"):
            c = j.get(q,{}).get("jobCounts",{})
            if c.get("active",0) > 0 or c.get("waiting",0) > 0: return q
    except Exception: pass
    return None

def main():
    ensure_tags()
    if DRY:
        ids = [l.strip() for l in open(IDS_FILE) if l.strip()]
        log(f"DRY-RUN over {len(ids)} assets")
        for i, aid in enumerate(ids, 1):
            try:
                a = im("GET", f"/api/assets/{aid}")
                free, tags, cap = process(aid, a.get("originalPath",""), (a.get("exifInfo") or {}).get("description") or "")
                log(f"[{i}] {tags}  <=free={free}  cap={cap!r}")
            except Exception as e:
                log(f"[{i}] {aid[:8]} ERROR {e}")
        log("DRY-RUN complete"); return
    # FULL
    if os.path.exists(LOCK):
        try: os.kill(int(open(LOCK).read()), 0); log("already running; exit"); return
        except Exception: pass
    open(LOCK,"w").write(str(os.getpid()))
    checkpoint = Checkpoint(CKPT)
    log(f"FULL start — {checkpoint.count()} already done"); ok=err=n=0; page=1; stop=False
    while not stop:
        try:
            res = im("POST","/api/search/metadata",{"page":page,"size":1000,"withExif":True,"type":"IMAGE"})
        except Exception as e:
            log(f"page {page} fetch failed ({e}); retry in 30s"); time.sleep(30); continue
        items = (res.get("assets") or {}).get("items",[])
        if not items: break
        for a in items:
            aid = a["id"]
            if checkpoint.contains(aid): continue
            if LIMIT and n >= LIMIT: stop=True; break
            b = ml_busy()
            while b: log(f"Immich ML busy ({b}); wait 90s"); time.sleep(90); b = ml_busy()
            try:
                process(aid, a.get("originalPath",""), (a.get("exifInfo") or {}).get("description") or ""); ok+=1
                if ok % 100 == 0: log(f"progress: {ok} tagged, {err} err (page {page})")
            except Exception as e:
                err+=1; log(f"ERR {aid[:8]}: {e}")
            checkpoint.mark(aid); n+=1
            if n % 500 == 0:
                try: im("PUT","/api/jobs/sidecar",{"command":"empty"})   # discard held SidecarWrite jobs
                except Exception: pass
        page += 1
    log(f"FULL done — {ok} tagged, {err} err")
    checkpoint.close()
    try: os.remove(LOCK)
    except OSError: pass

if __name__ == "__main__":
    main()
