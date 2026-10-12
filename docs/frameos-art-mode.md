# FrameOS Art mode

Art is an opt-in, local gallery for a separate collection of impressionist
landscapes. Photos stays the default family-photo mode.

Serve these files from Home Assistant at `/local/frameos-art/`:

- `index.html`, `art.js`, and `art.css` from `examples/frameos-art/`;
- `collection.json`, based on `collection.example.json` but kept outside Git;
- local JPEGs in `images/`, named by their SHA-256 digest.

`collection.json` has this shape:

```json
{
  "version": 1,
  "title": "Impressionist landscapes",
  "artworks": [{
    "id": "stable-local-id",
    "image": "images/<64-lowercase-hex-sha256>.jpg",
    "title": "Artwork title",
    "artist": "Artist name",
    "year": "1880",
    "sourceUrl": "https://public-museum.example/artwork"
  }]
}
```

The gallery rejects empty or malformed manifests, external/escaping image paths,
credential-bearing URLs, and source URLs with queries or fragments. It displays
an Art-specific unavailable view and never falls back to family photos. Images
are fit to the screen without cropping. Tapping away from either screen edge
reveals Previous, Pause, and Next; arrow keys navigate and Space pauses. Native
edge swipes are deliberately left alone.

Provision native Art mode only after that local URL exists:

```sh
adb shell am broadcast -a com.wyattfleming.frameos.CONTROL \
  -n com.wyattfleming.frameos/.control.FrameControlReceiver \
  --es frameos.art_url 'https://<local-ha-host>/local/frameos-art/'
```

This endpoint is online-only. The gallery keeps only its current image plus a
next-image preload; it pauses while hidden. It does not create an offline Art
reserve or make any claim that Art will work offline.
