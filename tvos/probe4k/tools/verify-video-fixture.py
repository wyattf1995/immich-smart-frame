"""Independently verify encoded dimensions and lossless reconstruction before packaging."""
from pathlib import Path
import hashlib, json, subprocess

root = Path(__file__).resolve().parents[1]
work = root / '.nas/video-fixture'
encoded = root / 'app/src/main/res/raw/probe4k_hevc.mp4'
decoded = work / 'decoded.yuv'
result = subprocess.run([str(work / 'ffmpeg'), '-hide_banner', '-y', '-threads', '2',
                         '-filter_threads', '1', '-i', str(encoded), '-vf', 'showinfo',
                         '-frames:v', '1', '-threads', '2', '-pix_fmt', 'yuv420p', '-f', 'rawvideo', str(decoded)],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=45)
(root / 'reports/video-decode.log').write_text(result.stdout)
assert result.returncode == 0
assert 's:3840x2160' in result.stdout and 'fmt:yuv420p' in result.stdout
assert decoded.stat().st_size == 3840 * 2160 * 3 // 2
raw = work / 'reference.yuv'
assert decoded.read_bytes() == raw.read_bytes(), 'Encoded fixture changed the detail pattern'
report = json.loads((root / 'reports/video-fixture.json').read_text())
assert report['width'] == 3840 and report['height'] == 2160 and report['frames'] == 8
assert report['encodedSha256'] == hashlib.sha256(encoded.read_bytes()).hexdigest()
report.update(decodedExactlyMatchesReference=True, verifiedDimensions=[3840, 2160])
(root / 'reports/video-fixture.json').write_text(json.dumps(report, indent=2) + '\n')
print('Decoded 4K fixture exactly matches the original test pattern.')
