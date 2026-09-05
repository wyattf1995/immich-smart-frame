#!/usr/bin/env python3
"""Immich VLM tagger — DB-only writes via the API. See project_immich_vlm_tagging_geo.md.
VLM emits FREE-FORM tags + caption (accurate); we MAP free-form terms -> the controlled
taxonomy in code (folder name + caption + free tags feed the match). Sidecar queue MUST be paused.
Modes:  --dry-run --ids <file>    |    --full [--limit N]"""
import json, base64, os, sys, time, re, urllib.request
from policy import ClassificationError, TYPE_SKIP_TAG, filename_skip_tag, plan_asset_update

IMMICH = os.environ.get("IMMICH_URL", "http://localhost:8080")
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434")
KEY    = os.environ["IMMICH_KEY"]
MODEL  = os.environ.get("VLM_MODEL", "qwen3-vl:8b-instruct")
WORK   = os.environ.get("WORK_DIR", "/work")
DRY    = "--dry-run" in sys.argv
APPLY  = "--apply" in sys.argv
def _arg(f, d=None): return sys.argv[sys.argv.index(f)+1] if f in sys.argv else d
LIMIT    = int(_arg("--limit", "0")) or None
IDS_FILE = _arg("--ids")
CKPT = os.path.join(WORK, "processed.txt"); LOGF = os.path.join(WORK, "tagger.log"); LOCK = os.path.join(WORK, "tagger.lock")

def log(m):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + m
    print(line, flush=True)
    try: open(LOGF, "a").write(line + "\n")
    except Exception: pass

def http(method, url, body=None, headers=None, timeout=300):
    h = dict(headers or {}); data = None
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h, method=method), timeout=timeout) as r:
        return r.read()
def im(method, path, body=None):
    b = http(method, IMMICH + path, body, {"x-api-key": KEY}); return json.loads(b) if b else None
def im_bytes(path):
    return http("GET", IMMICH + path, headers={"x-api-key": KEY})

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
POLICY_TAGS = tuple(sorted(set(TYPE_SKIP_TAG.values())))
def folder_words(path):
    parts = (path or "").split("/")
    seg = parts[-2] if len(parts) >= 2 else ""
    seg = re.sub(r"^\d{4}-\d{2}-\d{2}\s*", "", seg)  # strip date prefix
    return " " + seg.lower() + " "
KW_RE = {tag: re.compile(r"\b(?:" + "|".join(re.escape(k.strip()) for k in kws) + r")s?\b")
         for tag, kws in KW.items() if kws}
def map_tags(free_tags, caption, path):
    hay = " " + " ".join(free_tags).lower() + " || " + (caption or "").lower() + folder_words(path) + " "
    return [tag for tag, rx in KW_RE.items() if rx.search(hay)]

SCHEMA = {"type":"object","properties":{
    "image_type":{"type":"string","enum":["photograph","screenshot","document","meme","graphic","other"]},
    "tags":{"type":"array","items":{"type":"string"}},"caption":{"type":"string"},
    "ocr_text":{"type":"array","items":{"type":"string"}}},"required":["image_type","tags","caption"]}
PROMPT = ("First classify image_type as exactly one of photograph, screenshot, document, meme, graphic, or other. "
          "Then output: (1) tags = 5-12 concise lowercase nouns/phrases for the subjects, animals, plants, "
          "objects, scene and setting actually visible; (2) caption = one factual sentence, max 18 words; "
          "(3) ocr_text = any legible sign/label/landmark text. Do NOT guess a geographic location. "
          "Do not invent people names, dates, or places.")

TAGMAP = {}
def ensure_tags():
    for t in im("PUT", "/api/tags", {"tags": list(dict.fromkeys((*KW.keys(), *POLICY_TAGS)))}):
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
        kind = re.search(r'"image_type"\s*:\s*"(photograph|screenshot|document|meme|graphic|other)"', txt)
        if kind and m and c:
            tags = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
            return {"image_type": kind.group(1), "tags": tags, "caption": c.group(1), "ocr_text": []}
        raise

def vlm(asset_id):
    img = im_bytes(f"/api/assets/{asset_id}/thumbnail?size=preview")
    payload = {"model":MODEL,"prompt":PROMPT,"images":[base64.b64encode(img).decode()],
               "stream":False,"keep_alive":"30m","think":False,"format":SCHEMA,"options":{"temperature":0.2,"num_ctx":8192,"num_predict":1200}}
    out = json.loads(http("POST", OLLAMA+"/api/generate", payload, {"Content-Type":"application/json"}, timeout=300))
    return parse_vlm(out["response"])

def process(asset_id, original_file_name, path, existing_tag_names, existing_desc, *, apply):
    # A strong original filename is the only fast path.  Never pass a UUID or path
    # into filename_skip_tag: false positives would hide real photographs.
    direct = filename_skip_tag(original_file_name)
    p = None if direct else vlm(asset_id)
    plan = plan_asset_update(
        original_file_name=original_file_name,
        vlm=p,
        existing_tag_names=existing_tag_names,
        existing_description=existing_desc,
        mapped_content_tags=(None if direct else map_tags(p.get("tags", []), (p.get("caption") or "").strip(), path)),
    )
    if apply and plan.add_tag_names:
        im("PUT", "/api/tags/assets", {"tagIds":[TAGMAP[t] for t in plan.add_tag_names], "assetIds":[asset_id]})
    if apply and plan.description_to_write:
        im("PUT", "/api/assets", {"ids":[asset_id], "description": plan.description_to_write})
    return plan

def ml_busy():
    try:
        j = im("GET", "/api/jobs")
        for q in ("smartSearch","faceDetection","facialRecognition","ocr","thumbnailGeneration"):
            c = j.get(q,{}).get("jobCounts",{})
            if c.get("active",0) > 0 or c.get("waiting",0) > 0: return q
    except Exception: pass
    return None

def record_success_after_process(asset_id, open_checkpoint, *, original_file_name=None, path="", existing_tag_names=(), existing_desc=""):
    # A classification/update failure never reaches the checkpoint.  The next
    # guarded run can retry it rather than treating an incomplete response done.
    try:
        plan = process(asset_id, original_file_name, path, existing_tag_names, existing_desc, apply=APPLY)
    except Exception:
        return False
    if APPLY and open_checkpoint is not None:
        open_checkpoint.write(asset_id + "\n"); open_checkpoint.flush()
    return True

def asset_fields(asset):
    return (asset.get("originalFileName"), asset.get("originalPath", ""),
            [tag.get("value") for tag in asset.get("tags", [])],
            (asset.get("exifInfo") or {}).get("description") or "")

def main():
    if DRY and APPLY:
        log("invalid mode combination"); return 2
    if not DRY and not APPLY:
        log("explicit --apply is required for writes"); return 2
    if APPLY:
        ensure_tags()
    if IDS_FILE:
        with open(IDS_FILE) as ids_file:
            ids = [line.strip() for line in ids_file if line.strip()]
        log(f"ID mode over {len(ids)} assets"); failed = 0
        for i, aid in enumerate(ids, 1):
            try:
                asset = im("GET", f"/api/assets/{aid}")
                success = record_success_after_process(aid, None, original_file_name=asset_fields(asset)[0], path=asset_fields(asset)[1], existing_tag_names=asset_fields(asset)[2], existing_desc=asset_fields(asset)[3])
                if not success: failed += 1
                log(f"[{i}] {'completed' if success else 'classification failed'}")
            except Exception:
                failed += 1; log(f"[{i}] classification failed")
        log("ID mode complete"); return 1 if failed else 0
    if DRY:
        log("--dry-run requires --ids"); return 2
    if os.path.exists(LOCK):
        try: os.kill(int(open(LOCK).read()), 0); log("already running; exit"); return 0
        except Exception: pass
    open(LOCK,"w").write(str(os.getpid()))
    done = set(line.strip() for line in open(CKPT)) if os.path.exists(CKPT) else set()
    log(f"FULL start — {len(done)} already done"); ck = open(CKPT,"a"); ok=err=n=0; page=1; stop=False
    while not stop:
        try:
            res = im("POST","/api/search/metadata",{"page":page,"size":1000,"withExif":True,"type":"IMAGE"})
        except Exception:
            log(f"page {page} fetch failed; retry in 30s"); time.sleep(30); continue
        items = (res.get("assets") or {}).get("items",[])
        if not items: break
        for asset in items:
            aid = asset["id"]
            if aid in done: continue
            if LIMIT and n >= LIMIT: stop=True; break
            b = ml_busy()
            while b: log(f"Immich ML busy ({b}); wait 90s"); time.sleep(90); b = ml_busy()
            original_file_name, path, existing_tags, existing_desc = asset_fields(asset)
            if record_success_after_process(aid, ck, original_file_name=original_file_name, path=path, existing_tag_names=existing_tags, existing_desc=existing_desc):
                ok += 1; done.add(aid)
                if ok % 100 == 0: log(f"progress: {ok} tagged, {err} err (page {page})")
            else:
                err += 1; log("ERR classification or update failed")
            n += 1
            if n % 500 == 0:
                try: im("PUT","/api/jobs/sidecar",{"command":"empty"})
                except Exception: pass
        page += 1
    log(f"FULL done — {ok} tagged, {err} err")
    try: os.remove(LOCK)
    except OSError: pass
    return 1 if err else 0

if __name__ == "__main__":
    raise SystemExit(main())
