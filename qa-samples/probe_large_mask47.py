#!/usr/bin/env python3
"""Finite zero-OCR renderer experiment. Candidate is isolated from production.

Remove only anomalous low-confidence masks, retaining every word/font/box/color
and the whole-page layering decision. Reject unless visibility improves safely.
"""
import csv, hashlib, json, os, subprocess, sys, time, zipfile
from pathlib import Path
import fitz
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'qa-samples/work'
OUT = WORK / 'iteration47-renderer'
JAR = ROOT / 'web-api/target/web-api-0.1.5.jar'
OFFICE = '/opt/codex/runtimes/codex-primary-runtime/dependencies/bin/override/soffice'

JAVA = r'''
package com.fuyue.formatconverter.task;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.parser.ParseLimits;
import java.nio.file.*;import java.util.*;import javax.imageio.ImageIO;
public class LargeMaskReplay47 {
 public static void main(String[] args)throws Exception {
  Path out=Path.of(args[0]);Files.createDirectories(out);
  var dims=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");
  var dc=dims.getDeclaredConstructor(int.class,int.class);dc.setAccessible(true);
  var method=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dims,ParseLimits.class);method.setAccessible(true);
  var engine=new TesseractOcrConverter(DocumentFormat.PNG,new TesseractOcrConverter.Settings(Path.of("/nonexistent-no-ocr"),"chi_sim+eng","frozen-tsv-only"));
  for(String name:List.of("ruled","direct","dark","ordinary")) {
   var words=new ArrayList<TextBlock>();byte[] scan;Rect page;
   if(name.equals("ruled")||name.equals("direct")) {
    var pixels=ImageIO.read(Path.of(args[name.equals("ruled")?2:4]).toFile());
    page=new Rect(0,0,243.84,121.92);
    var parsed=(TesseractOcrConverter.RecognitionResult)method.invoke(engine,Path.of(args[name.equals("ruled")?1:3]),1,page,dc.newInstance(pixels.getWidth(),pixels.getHeight()),ParseLimits.defaults());
    for(var block:parsed.blocks()) words.add(OcrWordGeometryRefiner.refine(block,pixels,page));
    scan=Files.readAllBytes(Path.of(args[name.equals("ruled")?2:4]));pixels.flush();
   }else if(name.equals("dark")) {
    var im=new java.awt.image.BufferedImage(3749,2500,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();
    for(int x=0;x<3749;x++){int gray=45+170*x/3748;g.setColor(new java.awt.Color(gray,gray,gray));g.drawLine(x,0,x,2499);}
    g.setColor(java.awt.Color.RED);g.fillRect(1687,1312,375,375);g.dispose();
    var bytes=new java.io.ByteArrayOutputStream();ImageIO.write(im,"png",bytes);scan=bytes.toByteArray();im.flush();page=new Rect(0,0,317.5,211.6667);
    for(int l=0;l<8;l++){var ww=new ArrayList<TextBlock.OcrWord>();for(int c=0;c<6;c++)ww.add(new TextBlock.OcrWord(new Rect(25+c*25,20+l*22,23,7),"VISIBLE",.99));
     words.add(new TextBlock("ocr-"+l,1,new Rect(25,20+l*22,148,7),"VISIBLE ".repeat(6).strip(),27+l*22,new FontStyle("Arial",14,false,false,null),l+1,0,0,List.of(),Transform2D.IDENTITY,ww));}
   }else {
    var im=new java.awt.image.BufferedImage(1200,600,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,1200,600);g.setColor(java.awt.Color.RED);g.fillRect(700,350,70,50);g.dispose();
    var bytes=new java.io.ByteArrayOutputStream();ImageIO.write(im,"png",bytes);scan=bytes.toByteArray();im.flush();page=new Rect(0,0,200,100);
    for(int i=0;i<5;i++){var b=new Rect(10+i*30,20,22,5);var w=new TextBlock.OcrWord(b,i==4?"UNCERTAIN":"RELIABLE",i==4?.2:.99);
     words.add(new TextBlock("ordinary-"+i,1,b,w.text(),24,new FontStyle("Arial",12,false,false,null),i+1,0,0,List.of(),Transform2D.IDENTITY,List.of(w)));}
   }
   var background=new ImageBlock("scan",1,page,"image/png",scan,"OCR_PAGE_BACKGROUND",0);
   var p=new PageModel(1,page,words,List.of(),List.of(background),List.of(),List.of(),List.of());
   new PoiDocxRenderer().render(new DocumentModel(name,"frozen-tsv-or-explicit-negative",1,List.of(p),List.of()),out.resolve(name+".docx"));
   var audit=new StringBuilder();for(var b:words)for(var w:b.ocrWords())audit.append(b.id()).append('\t').append(w.text()).append('\t').append(w.confidence()).append('\t').append(w.box()).append('\n');
   Files.writeString(out.resolve(name+"-model.tsv"),audit.toString());Files.write(out.resolve(name+"-source.png"),scan);
  }
 }
}
'''

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def run(args, timeout=90):
    p = subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if p.returncode:
        raise RuntimeError(p.stdout[-5000:])
    return p.stdout

def main():
    if OUT.exists():
        assert '--resume-bootstrap' in sys.argv and not list(OUT.glob('*/*.docx')), 'Never replay completed renderer/Office actions.'
    OUT.mkdir(exist_ok=True)
    assert run(['git','rev-parse','HEAD']).strip() == 'cd302610d7e421b2ac7c1bb99b9ce9b494423c5f'
    assert sha(JAR) == '2e1961405e080daf66bb60bec6b6c8784739ef7091b0171301834336aaae6061'
    report = json.loads((WORK/'iteration45-http/report.json').read_text())
    capture = json.loads((WORK/'iteration45-http/ocr-capture.json').read_text())
    paths = []
    for case in ['ruled-scan-word','ruled-direct-word']:
        item = next(c for c in report['cases'] if c['case']==case)
        record = max((r for r in capture['records'] if r['taskId']==item['task']['taskId']), key=lambda r:r['bytes'])
        path = WORK/'iteration45-http'/record['artifact']; assert sha(path)==record['sha256']; paths.append(path)
    with zipfile.ZipFile(WORK/'iteration45-http/ruled-scan-word-result.docx') as z:
        media = [n for n in z.namelist() if n.startswith('word/media/')]
        assert len(media)==1
        scan = OUT/'ruled-source.png';scan.write_bytes(z.read(media[0]))
    direct = ROOT/'qa-samples/generated/scan-tables45/ruled.png'
    libs = OUT/'lib';libs.mkdir(exist_ok=True)
    with zipfile.ZipFile(JAR) as z:
        for n in z.namelist():
            if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'):
                (libs/Path(n).name).write_bytes(z.read(n))
    cp = str(libs/'*')
    harness = OUT/'LargeMaskReplay47.java';harness.write_text(JAVA)
    baseline = OUT/'baseline-classes';baseline.mkdir(exist_ok=True)
    run(['javac','-cp',cp,'-d',str(baseline),str(harness)])
    source = ROOT/'docx-renderer/src/main/java/com/fuyue/formatconverter/docx/FixedLayoutDocxRenderer.java'
    candidate = source.read_text()
    needle = '        List<OcrMask> masks = new ArrayList<>();'
    assert candidate.count(needle)==1
    candidate = candidate.replace(needle,needle+'''
        // ISOLATED REJECTABLE EXPERIMENT47: no production adoption.
        double[] trustedHeights = texts.stream().flatMap(b -> b.ocrWords().stream())
                .filter(w -> w.confidence() >= .85d).mapToDouble(w -> w.box().height()).sorted().toArray();
        double typicalHeight = trustedHeights.length >= 4 && trustedHeights.length <= 512
                ? trustedHeights[trustedHeights.length / 2] : Double.POSITIVE_INFINITY;
''')
    needle = '                        masks.add(new OcrMask(block.id(), fill, mixedForeground));'
    assert candidate.count(needle)==1
    candidate = candidate.replace(needle,'''                        if (!(word.confidence() < .35d && word.box().height() > 3d * typicalHeight))
                            masks.add(new OcrMask(block.id(), fill, mixedForeground));''')
    candidate_source = OUT/'FixedLayoutDocxRenderer.java';candidate_source.write_text(candidate)
    candidate_classes = OUT/'candidate-classes';candidate_classes.mkdir()
    run(['javac','-cp',cp,'-d',str(candidate_classes),str(candidate_source)])
    for label,classes in [('baseline',baseline),('candidate',candidate_classes)]:
        classpath = os.pathsep.join([str(classes),str(baseline),cp])
        run(['java','-Djava.awt.headless=true','-cp',classpath,'com.fuyue.formatconverter.task.LargeMaskReplay47',str(OUT/label),str(paths[0]),str(scan),str(paths[1]),str(direct)],120)
    manifest = dict(parentRevision='cd302610d7e421b2ac7c1bb99b9ce9b494423c5f',jarSha256=sha(JAR),productionSourceSha256=sha(source),candidateSourceSha256=sha(candidate_source),helperSha256=sha(Path(__file__)),frozenTsvs=[dict(path=str(p.relative_to(ROOT)),sha256=sha(p)) for p in paths],ocrInvocations=0,httpInvocations=0,officeVersion=run([OFFICE,'--version']).strip(),cases=[])
    for name in ['ruled','direct','dark','ordinary']:
        outputs=[]
        for label in ['baseline','candidate']:
            folder=OUT/label;word=folder/(name+'.docx');pdf=folder/(name+'.pdf')
            profile=OUT/(label+'-'+name+'-office-profile'); started=time.monotonic()
            run([OFFICE,'-env:UserInstallation='+profile.as_uri(),'--headless','--convert-to','pdf','--outdir',str(folder),str(word)],90)
            assert pdf.exists()
            doc=fitz.open(pdf);assert len(doc)==1
            png=folder/(name+'.png');doc[0].get_pixmap(dpi=200,alpha=False).save(png)
            outputs.append(dict(stage=label,wordSha256=sha(word),pdfSha256=sha(pdf),renderSha256=sha(png),nativeText=doc[0].get_text(),pages=1,officeWallSeconds=time.monotonic()-started));doc.close()
        a,b=OUT/'baseline',OUT/'candidate'
        assert (a/(name+'-model.tsv')).read_bytes()==(b/(name+'-model.tsv')).read_bytes()
        assert (a/(name+'-source.png')).read_bytes()==(b/(name+'-source.png')).read_bytes()
        with Image.open(a/(name+'.png')) as p,Image.open(b/(name+'.png')) as q:
            x,y=np.asarray(p.convert('RGB')),np.asarray(q.convert('RGB'))
        changed=int(np.any(x!=y,axis=2).sum())
        manifest['cases'].append(dict(case=name,allRawWordsConfidenceOriginalBoxesExact=True,sourceScanByteExact=True,changedRenderPixels=changed,pixelExact=changed==0,outputs=outputs))
    (OUT/'execution.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(cases=len(manifest['cases']),ocr=0,http=0,pixels=[(c['case'],c['changedRenderPixels']) for c in manifest['cases']])))

if __name__=='__main__':
    main()
