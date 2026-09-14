"""Encode a HEVC Main 5.1 High-tier fixture accepted by ordinary 4K decoders."""
from pathlib import Path
import hashlib,json,subprocess
root=Path(__file__).resolve().parents[1]
work=root/'.nas/video-fixture'
output=root/'app/src/main/res/raw/probe4k_hevc_main51.mp4'
command=[str(work/'ffmpeg'),'-hide_banner','-y','-threads','2','-filter_threads','1',
 '-stream_loop','7','-f','rawvideo','-pixel_format','yuv420p','-video_size','3840x2160',
 '-framerate','15','-i',str(work/'reference.yuv'),'-frames:v','8','-an','-c:v','libx265',
 '-preset','medium','-crf','0','-threads','2','-x265-params',
 'pools=1:frame-threads=1:wpp=0:level-idc=5.1:high-tier=1:vbv-maxrate=120000:vbv-bufsize=120000',
 '-tag:v','hvc1','-movflags','+faststart',str(output)]
p=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
(root/'reports/video-compatible-encode.log').write_text(p.stdout)
assert p.returncode==0 and 0<output.stat().st_size<8*1024*1024
j={'encodedSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'encodedBytes':output.stat().st_size,
 'width':3840,'height':2160,'frames':8,'fps':15,'format':'yuv420p','purpose':'bounded near-lossless hardware-compatible detail probe'}
(root/'reports/video-compatible-fixture.json').write_text(json.dumps(j,indent=2)+'\n')
print(json.dumps(j))
