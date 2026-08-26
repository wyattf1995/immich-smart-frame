# Weather-scene inputs and outputs

The example intentionally does not ship a house-specific art pack or generated
media. Supply four 16:9 PNG source scenes that share one composition:

- `source/sunny.png`
- `source/cloudy.png`
- `source/rainy.png`
- `source/clear-night.png`

Run the builder from the parent directory:

```sh
./build-weather-loops.sh
```

It requires `ffmpeg` and `ffprobe`, creates `output/` and `preview/`, and emits
four local 1280x720 H.264 MP4 loops. The landscape itself stays fixed; only
lighting, drizzle, and two occasional night shooting stars move.

Also provide `output/neutral.png` as the static background for camera and
calendar views. It can be a small solid-color PNG or another privacy-safe
neutral image. The builder fingerprints it together with the four loops, so
copy the four MP4 files and `neutral.png` to Home Assistant's
`/config/www/wallpanel-weather/` directory. They will then be available below
`/local/wallpanel-weather/`.

The `source/`, `output/`, and `preview/` directories are ignored because they
can contain original art, deployment screenshots, or large generated files.
