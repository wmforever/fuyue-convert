#!/usr/bin/env python3
"""Bind the ruled replay to the actual PDF page, correcting rounded mm only.

Run once, for the changed-coordinate artifact pair only; reuse all negative
controls. No OCR, HTTP, or replay of the earlier four-case matrix.
"""
import json,os
from pathlib import Path
import fitz
from probe_large_mask47 import ROOT,WORK,OUT,JAVA,JAR,OFFICE,run,sha

def main():
    target=OUT/'exact';assert not target.exists();target.mkdir()
    prior=json.loads((OUT/'execution.json').read_text());tsv=ROOT/prior['frozenTsvs'][0]['path']
    source=WORK/'iteration45-http/ruled-wrap-result.pdf'
    doc=fitz.open(source);width=doc[0].rect.width*25.4/72;height=doc[0].rect.height*25.4/72;doc.close()
    code=JAVA.replace('for(String name:List.of("ruled","direct","dark","ordinary"))','for(String name:List.of("ruled"))').replace('page=new Rect(0,0,243.84,121.92);','page=new Rect(0,0,Double.parseDouble(args[5]),Double.parseDouble(args[6]));')
    harness=target/'LargeMaskReplay47.java';harness.write_text(code);classes=target/'classes';classes.mkdir();cp=str(OUT/'lib/*')
    run(['javac','-cp',cp,'-d',str(classes),str(harness)])
    outputs=[]
    for label in ['baseline','candidate']:
        folder=target/label
        prefix=[] if label=='baseline' else [str(OUT/'candidate-classes')]
        run(['java','-Djava.awt.headless=true','-cp',os.pathsep.join(prefix+[str(classes),cp]),'com.fuyue.formatconverter.task.LargeMaskReplay47',str(folder),str(tsv),str(OUT/'ruled-source.png'),str(tsv),str(OUT/'ruled-source.png'),str(width),str(height)],120)
        word=folder/'ruled.docx';pdf=folder/'ruled.pdf'
        run([OFFICE,'-env:UserInstallation='+(target/(label+'-profile')).as_uri(),'--headless','--convert-to','pdf','--outdir',str(folder),str(word)],90)
        d=fitz.open(pdf);assert len(d)==1;png=folder/'ruled.png';d[0].get_pixmap(dpi=200,alpha=False).save(png)
        outputs.append(dict(stage=label,wordSha256=sha(word),pdfSha256=sha(pdf),renderSha256=sha(png),nativeText=d[0].get_text(),pages=1));d.close()
    result=dict(sourcePdfSha256=sha(source),sourcePageMm=[width,height],helperSha256=sha(Path(__file__)),harnessSha256=sha(harness),jarSha256=sha(JAR),ocrInvocations=0,httpInvocations=0,officeInvocations=2,outputs=outputs,reason='243.84 rounded mm caused 0.001pt emitted coordinate differences; bind actual source PDF')
    (target/'execution.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()
