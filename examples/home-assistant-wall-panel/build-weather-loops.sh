#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $# -gt 1 ]]; then
  printf 'usage: %s [weather-asset-root]\n' "$0" >&2
  exit 2
fi

asset_root="${1:-$script_dir/weather}"
source_dir="$asset_root/source"
output_dir="$asset_root/output"
preview_dir="$asset_root/preview"
fps="${FPS:-24}"
frames="${FRAMES:-240}"
night_frames="${NIGHT_FRAMES:-720}"

for required_command in ffmpeg ffprobe; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    printf 'missing required command: %s\n' "$required_command" >&2
    exit 1
  fi
done

for weather in sunny cloudy rainy clear-night; do
  if [[ ! -f "$source_dir/$weather.png" ]]; then
    printf 'missing source image: %s\n' "$source_dir/$weather.png" >&2
    exit 1
  fi
done

mkdir -p "$output_dir" "$preview_dir"

encode_loop() {
  local source_file="$1"
  local output_file="$2"
  local video_filter="$3"
  local frame_count="${4:-$frames}"

  ffmpeg -hide_banner -loglevel error -y \
    -loop 1 -framerate "$fps" -i "$source_file" \
    -frames:v "$frame_count" \
    -vf "$video_filter" \
    -an -c:v libx264 -preset medium -crf 27 \
    -profile:v main -level:v 3.1 -pix_fmt yuv420p \
    -movflags +faststart "$output_file"
}

# Keep the entire source image fixed. Applying motion to the whole frame makes
# foreground terrain wander and exposes the loop seam. Animate light or weather
# overlays instead.
base_scale="scale=1312:738:force_original_aspect_ratio=increase,crop=1280:720:(iw-ow)/2:(ih-oh)/2"

encode_loop \
  "$source_dir/sunny.png" \
  "$output_dir/sunny.mp4" \
  "$base_scale,eq=brightness='0.004+0.004*sin(2*PI*n/$frames)':saturation='1.025+0.005*sin(2*PI*n/$frames)':eval=frame,format=yuv420p"

encode_loop \
  "$source_dir/cloudy.png" \
  "$output_dir/cloudy.mp4" \
  "$base_scale,eq=brightness='0.005+0.003*sin(2*PI*n/$frames)':saturation=0.96:eval=frame,format=yuv420p"

ffmpeg -hide_banner -loglevel error -y \
  -loop 1 -framerate "$fps" -i "$source_dir/rainy.png" \
  -f lavfi -i "color=c=black:s=1280x90:r=$fps:d=10" \
  -frames:v "$frames" \
  -filter_complex \
    "[0:v]$base_scale,eq=brightness=-0.012:saturation=0.86[base];[1:v]format=gray,noise=alls=100:all_seed=173:allf=u,lut='if(gte(val,246),255,0)',scale=1280:720:flags=neighbor,gblur=sigma=0.45,scroll=vertical=0.0166666667:horizontal=0[drizzle];[base][drizzle]blend=all_mode=screen:all_opacity=0.13,format=yuv420p[out]" \
  -map "[out]" -an -c:v libx264 -preset medium -crf 27 \
  -profile:v main -level:v 3.1 -pix_fmt yuv420p \
  -movflags +faststart "$output_dir/rainy.mp4"

# The night loop is longer so the two shooting stars feel occasional. Both
# enter and leave beyond the frame edges while the base image stays fixed.
ffmpeg -hide_banner -loglevel error -y \
  -loop 1 -framerate "$fps" -i "$source_dir/clear-night.png" \
  -f lavfi -i "color=c=black@0.0:s=112x44:r=$fps:d=30" \
  -frames:v "$night_frames" \
  -filter_complex \
    "[0:v]$base_scale,eq=brightness='-0.012+0.003*sin(2*PI*n/$night_frames)':gamma='1.0+0.006*sin(4*PI*n/$night_frames)':saturation=1.02:eval=frame[base];[1:v]format=rgba,geq=r='255':g='246':b='220':a='max(if(lt(abs(Y-(40-0.34*X)),1.4),210*pow(max(0,min(1,(112-X)/112)),1.5),0),if(lt(hypot(X-6,Y-38),2.5),235,0))',gblur=sigma=0.55,split=2[star1][star2raw];[star2raw]scale=80:32,colorchannelmixer=aa=0.58[star2];[base][star1]overlay=x='if(between(t,7,9),1280-800*(t-7),NAN)':y='if(between(t,7,9),70+150*(t-7),NAN)':eval=frame:format=auto[night1];[night1][star2]overlay=x='if(between(t,21,22.8),1280-820*(t-21),NAN)':y='if(between(t,21,22.8),155+105*(t-21),NAN)':eval=frame:format=auto,format=yuv420p[out]" \
  -map "[out]" -an -c:v libx264 -preset medium -crf 27 \
  -profile:v main -level:v 3.1 -pix_fmt yuv420p \
  -movflags +faststart "$output_dir/clear-night.mp4"

for weather in sunny cloudy rainy clear-night; do
  for preview_time in 2.5 7.5; do
    ffmpeg -hide_banner -loglevel error -y \
      -ss "$preview_time" -i "$output_dir/$weather.mp4" -frames:v 1 \
      "$preview_dir/$weather-${preview_time}s.png"
  done
done

for weather in sunny cloudy rainy clear-night; do
  ffprobe -v error \
    -show_entries format=filename,duration,size:stream=codec_name,profile,width,height,pix_fmt,r_frame_rate \
    -of json "$output_dir/$weather.mp4"
done
