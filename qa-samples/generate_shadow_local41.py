#!/usr/bin/env python3
"""Freeze independent faint/strong ink shadow truth and one bounded local-gain hypothesis."""
import hashlib,json,math,random
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,__version__
from fontTools.ttLib import TTFont
ROOT=Path(__file__).resolve().parents[1]
EN=['Independent shadow record 00637','Date 2091-04-26','Amount -041.85',
    'Rate +12.50% and balance 009.70','Keep upper lines and original pixels',
    'Review batch 00846 before approval','Audit 73519 completed']
ZH=['独立阴影记录00637','日期2091-04-26','金额-041.85','比例+12.50%，余额009.70',
    '保留上方文字和原始像素','审批前复核批次00846','核验73519完成']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/shadow-local41';out.mkdir(exist_ok=False)
    fonts={'en':Path('/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'),
           'zh':Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')}
    fontmeta={}
    for lang,path in fonts.items():
        with TTFont(path,fontNumber=0) as font:
            assert set(map(ord,''.join(EN if lang=='en' else ZH)))<=set(font.getBestCmap())
            fontmeta[lang]=dict(path=str(path),sha256=sha(path),license='SIL-OFL-1.1',
                version=sorted({n.toUnicode() for n in font['name'].names if n.nameID==5}))
    cases=[];sources={};rng=random.Random(410042026)
    for lang,kind in [('en','gradient'),('zh','gradient'),('en','local'),('zh','local'),('en','normal'),('zh','normal'),('none','blank'),('none','noise')]:
        name=lang+'-'+kind;width,height=1600,1300
        paper=Image.new('L',(width,height))
        pixels=paper.load()
        for y in range(height):
            for x in range(width):
                if kind=='normal':value=238
                elif kind=='local':value=round(238-126*math.exp(-((x-620)**2/(2*390**2)+(y-300)**2/(2*250**2))))
                else:value=112+126*y//height
                pixels[x,y]=value
        lines=[] if lang=='none' else (EN if lang=='en' else ZH)
        ink=Image.new('L',(width,height));draw=ImageDraw.Draw(ink);positions=[]
        for i,line in enumerate(lines):
            size=44 if i<4 else 64;strength=24 if i<4 and kind!='normal' else 210
            font=ImageFont.truetype(str(fonts[lang]),size);y=[120,260,400,540,730,920,1110][i]
            box=draw.textbbox((100,y),line,font=font,anchor='ls');assert 0<=box[0]<box[2]<width and 0<=box[1]<box[3]<height
            draw.text((100,y),line,font=font,fill=strength,anchor='ls')
            positions.append(dict(text=line,box=list(box),fontSize=size,contrastStrength=strength))
        if kind=='noise':
            for _ in range(24):ink.putpixel((rng.randrange(width),rng.randrange(height)),80)
        # Same coordinates and alpha-shaped ink, no resampling/cropping/rotation.
        result=Image.new('L',(width,height));result.putdata([max(0,a-b) for a,b in zip(paper.getdata(),ink.getdata())])
        path=out/(name+'.png');result.save(path,dpi=(300,300));sources[path.name]=sha(path)
        cases.append(dict(id=name,file=path.name,language=lang,kind=kind,expectedLines=lines,sourcePositions=positions,
            expectedNumericSurfaces=['00637','2091-04-26','-041.85','+12.50%','009.70','00846','73519'] if lines else [],
            sourceSha256=sha(path),pixels=[width,height],dpi=300))
    plan=dict(parentRevision='0277f5723de6f6c335b58cd6a0bb8cac77d15140',seed=410042026,generatorSha256=sha(Path(__file__)),pillowVersion=__version__,
        hypothesis='Global ink contrast dominated by strong rows may leave faint shaded rows too pale;fixed per-tile contrast capped at4x global gain may improve candidate quality',
        candidate=dict(tile='unchanged production32..128',contrastPercentile=.99,minimumContrast=12,minimumFractionOfGlobal=.25,maximumGain=4,psm=3,dimensionsUnchanged=True,newProductionRetries=0),
        gates=dict(minimumConfidence=.35,fullAndNewRowGain=.05,reliableNumericAndOrderProtectionUnchanged=True,geometryAuthority='original image pixels'),
        bounds=dict(maximumNativeCli=24,perCommandSeconds=25,wholeDiagnosticSeconds=360,maximumCliRssKiB=262144,addressSpaceBytes=1073741824),
        stoppingRule='No sweeps. Reject if no adopted measurable gain,any added numeric error in adoptable path,normal/nontext regression,orresourcebound. Positive diagnostic still requires real HTTP/Word/mask acceptance.',
        comparison=dict(currentJarSha256='1ead3c12b621c612535ba7a9f338915484b79be3b74509cf3c3b8a304b2ce68b',
            earlyAccuracyJar='qa-samples/work/baseline-11f6ce7.jar',earlyAccuracyJarSha256='9173918803d13de7ebde2a8bffea371a6040a2a08517222b25c9339a43e55c5c',
            earlyComparison='reuse exact native original/current enhancement pixels/TSV if byte-compatible;evaluate actual old selector without new recognition;historical HTTP limits remain separate'),
        fonts=fontmeta,cases=cases,sources=sources,freezeBeforeAnyOcr=True)
    (out/'expected.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
    print('Frozen8independent cases;manifestSHA256',sha(out/'expected.json'))
if __name__=='__main__':main()
