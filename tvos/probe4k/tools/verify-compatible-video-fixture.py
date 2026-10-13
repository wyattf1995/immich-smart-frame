"""Require actual Main5.1 bitstream metadata and tightly bounded luma error."""
from pathlib import Path
import hashlib,json,re,subprocess
root=Path(__file__).resolve().parents[1];work=root/'.nas/video-fixture'
encoded=root/'app/src/main/res/raw/probe4k_hevc_main51.mp4';decoded=work/'compatible-decoded.yuv'
def run(args):
 p=subprocess.run([str(work/'ffmpeg'),'-hide_banner','-y','-threads','2','-filter_threads','1','-i',str(encoded)]+args,
  stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=45)
 assert p.returncode==0,p.stdout[-2000:]
 return p.stdout
log=run(['-vf','showinfo','-frames:v','1','-threads','2','-pix_fmt','yuv420p','-f','rawvideo',str(decoded)])
(root/'reports/video-compatible-decode.log').write_text(log)
assert 's:3840x2160' in log and 'fmt:yuv420p' in log
metadata=run(['-frames:v','1','-c:v','copy','-bsf:v','trace_headers','-f','null','-'])
(root/'reports/video-compatible-headers.log').write_text(metadata)
for field,value in [('general_profile_idc',1),('general_level_idc',153),('general_tier_flag',1)]:
 values=re.findall(r'\b'+field+r'\b[^\n]*=\s*(\d+)',metadata)
 assert values and all(int(v)==value for v in values),(field,values)
raw=(work/'reference.yuv').read_bytes();actual=decoded.read_bytes()
assert len(raw)==len(actual)==3840*2160*3//2
luma_bytes=3840*2160
max_error=max(abs(a-b) for a,b in zip(raw[:luma_bytes],actual[:luma_bytes]))
assert max_error<=8,('Compression obscures fine detail',max_error)
assert raw[luma_bytes:]==actual[luma_bytes:],'Neutral chroma changed'
j=json.loads((root/'reports/video-compatible-fixture.json').read_text())
assert j['encodedSha256']==hashlib.sha256(encoded.read_bytes()).hexdigest()
assert j['encodedBytes']*8/(8/15)<60_000_000,'Fixture exceeds observed Sony decoder bitrate limit'
j.update(verifiedDimensions=[3840,2160],profile='Main',level='5.1',tier='High',
 maximumLumaError=max_error,lumaErrorLimit=8,neutralChromaExact=True,verified=True)
(root/'reports/video-compatible-fixture.json').write_text(json.dumps(j,indent=2)+'\n')
print(json.dumps(j))
