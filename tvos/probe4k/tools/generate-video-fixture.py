"""Generate an original, bounded luma-detail HEVC fixture on the Linux NAS."""
from pathlib import Path
import hashlib, json, subprocess, urllib.request, zipfile

root = Path(__file__).resolve().parents[1]
work = root / '.nas/video-fixture'
work.mkdir(parents=True, exist_ok=True)
url = 'https://files.pythonhosted.org/packages/a0/2d/43c8522a2038e9d0e7dbdf3a61195ecc31ca576fb1527a528c877e87d973/imageio_ffmpeg-0.6.0-py3-none-manylinux2014_x86_64.whl'
expected = 'c7e46fcec401dd990405049d2e2f475e2b397779df2519b544b8aab515195282'
wheel = work / 'imageio_ffmpeg.whl'
with urllib.request.urlopen(url, timeout=45) as response:
    data = response.read(32 * 1024 * 1024 + 1)
assert len(data) <= 32 * 1024 * 1024 and hashlib.sha256(data).hexdigest() == expected
wheel.write_bytes(data)
with zipfile.ZipFile(wheel) as archive:
    entries = [n for n in archive.namelist() if n.startswith('imageio_ffmpeg/binaries/ffmpeg-linux')]
    assert len(entries) == 1
    ffmpeg = work / 'ffmpeg'
    ffmpeg.write_bytes(archive.read(entries[0]))
ffmpeg.chmod(0o755)
width, height = 3840, 2160
raw = work / 'reference.yuv'
with raw.open('wb') as target:
    for y in range(height):
        band = min(3, y * 4 // height)
        if band < 3:
            stripe = (1, 2, 4)[band]
            row = bytes(178 if (x // stripe & 1) == 0 else 52 for x in range(width // 2))
            row += bytes([178 if (y // stripe & 1) == 0 else 52]) * (width // 2)
        else:
            row = bytes([108]) * (width // 4)
            for column, cell in ((1, 1), (2, 2), (3, 4)):
                row += bytes(178 if ((x // cell + y // cell) & 1) == 0 else 52
                             for x in range(column * width // 4, (column + 1) * width // 4))
        target.write(row)
    target.write(bytes([128]) * (width * height // 2))
output = root / 'app/src/main/res/raw/probe4k_hevc.mp4'
output.parent.mkdir(parents=True, exist_ok=True)
command = [str(ffmpeg), '-hide_banner', '-y', '-threads', '2', '-filter_threads', '1',
           '-stream_loop', '7', '-f', 'rawvideo', '-pixel_format', 'yuv420p',
           '-video_size', f'{width}x{height}', '-framerate', '15', '-i', str(raw),
           '-frames:v', '8', '-an', '-c:v', 'libx265', '-preset', 'ultrafast',
           '-threads', '2', '-x265-params', 'pools=1:frame-threads=1:wpp=0:lossless=1',
           '-tag:v', 'hvc1', '-movflags', '+faststart', str(output)]
result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=90)
(root / 'reports/video-encode.log').write_text(result.stdout)
assert result.returncode == 0 and 0 < output.stat().st_size < 8 * 1024 * 1024
identity = {'width': width, 'height': height, 'frames': 8, 'fps': 15, 'format': 'yuv420p',
            'wheelSha256': expected, 'ffmpegSha256': hashlib.sha256(ffmpeg.read_bytes()).hexdigest(),
            'rawSha256': hashlib.sha256(raw.read_bytes()).hexdigest(),
            'encodedSha256': hashlib.sha256(output.read_bytes()).hexdigest(),
            'encodedBytes': output.stat().st_size, 'fixture': 'original synthetic grayscale detail pattern'}
(root / 'reports/video-fixture.json').write_text(json.dumps(identity, indent=2) + '\n')
print(json.dumps(identity))
