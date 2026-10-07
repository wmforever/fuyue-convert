#!/usr/bin/env python3
"""Freeze one native-header/two-scan PDF and a native-only negative;no OCR."""
import hashlib,json,shutil
from pathlib import Path
import fitz
from PIL import Image,ImageDraw,ImageFont,__version__
from fontTools.ttLib import TTCollection
ROOT=Path(__file__).resolve().parents[1]
FONT=Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/multi-scan46';out.mkdir(exist_ok=False)
    columns=[['左栏 Left ledger','ID 04619','Date 2095-02-11','Amount -064.30','左栏完成 End left'],
             ['右栏 Right ledger','ID 04620','Date 2095-02-12','Amount +0128.60','右栏完成 End right']]
    header='NATIVE HEADER MIXED 00573';images=[];face=TTCollection(FONT).fonts[0];cmap=face.getBestCmap()
    for index,lines in enumerate(columns):
        image=Image.new('RGB',(960,960),'white');draw=ImageDraw.Draw(image);truth=[]
        for row,text in enumerate(lines):
            assert all(ord(c) in cmap for c in text)
            font=ImageFont.truetype(str(FONT),42);x,y=60,110+row*160
            draw.text((x,y),text,font=font,fill='black',anchor='ls')
            truth.append(dict(text=text,boxPixels=list(draw.textbbox((x,y),text,font=font,anchor='ls'))))
        path=out/('left.png' if index==0 else 'right.png');image.save(path,dpi=(200,200));images.append(dict(file=path.name,sha256=sha(path),pixels=[960,960],lines=truth))
    boxes=[[24,68,264,308],[336,68,576,308]]
    doc=fitz.open();page=doc.new_page(width=600,height=340);page.insert_text((24,28),header,fontname='helv',fontsize=18)
    for image,box in zip(images,boxes):page.insert_image(fitz.Rect(box),filename=str(out/image['file']))
    path=out/'mixed-two-scans.pdf';doc.save(path,garbage=4,deflate=True);doc.close()
    native_columns=[['LEFT ledger','ID 05731','Date 2096-03-21','Amount -075.45','End LEFT'],
                    ['RIGHT ledger','ID 05732','Date 2096-03-22','Amount +0150.90','End RIGHT']]
    doc=fitz.open();page=doc.new_page(width=600,height=340);page.insert_text((24,28),header,fontname='helv',fontsize=18)
    for col,lines in enumerate(native_columns):
        for row,text in enumerate(lines):page.insert_text((boxes[col][0]+15,95+row*40),text,fontname='helv',fontsize=13)
    native=out/'native-two-columns.pdf';doc.save(native,garbage=4,deflate=True);doc.close()
    source_pdf=ROOT/'qa-samples/work/iteration45-http/columns-wrap-result.pdf';assert source_pdf.exists();shutil.copyfile(source_pdf,out/'single-scan45.pdf')
    sources={p.name:sha(p) for p in out.iterdir()}
    plan=dict(parentRevision='d32db3cf329f7ed24bcf9cf93d6a78dcf65b4de6',generatorSha256=sha(Path(__file__)),pillowVersion=__version__,pymupdfVersion=fitz.VersionBind,
        font=dict(path=str(FONT),sha256=sha(FONT),version=sorted({n.toUnicode() for n in face['name'].names if n.nameID==5}),license='SIL-OFL-1.1'),
        header=header,columns=columns,nativeColumns=native_columns,images=images,imageBoxesPt=boxes,pageSizePt=[600,340],sources=sources,
        expectedMixedColumnMajor='\n'.join([header,*columns[0],*columns[1]]),expectedNativeColumnMajor='\n'.join([header,*native_columns[0],*native_columns[1]]),
        actions=[dict(id='mixed-word',input='mixed-two-scans.pdf',target='docx'),dict(id='mixed-office',input='@mixed-word',target='pdf'),dict(id='native-word',input='native-two-columns.pdf',target='docx'),dict(id='native-office',input='@native-word',target='pdf')],
        acceptance=dict(actualTwoRequiredImageOcrInputs=True,nativeHeaderPreserved=True,doNotInterpretLocalOrdinalAsPageOrder=True,noWordMaskImageCoordinateNumberChanges=True,singleScan45SourceOrderBenefitPreserved=True,nativePolicyUnchanged=True,pixelExactBeforeAfter=True),
        bounds=dict(beforeHttp=4,afterHttp=6,perContractSeconds=120,wholeHttpSeconds=480,pageSeconds=120,maximumPixels=25000000,concurrency=1),
        scope='Disjointclearcolumns with sourceknownintent;no implicit imageappend-orderintent andno lowconfidenceframe/scale/PSM/threshold/mask candidate')
    (out/'expected.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
    after=ROOT/'qa-samples/generated/multi-scan46-after';after.mkdir(exist_ok=False)
    for name in sources:shutil.copyfile(out/name,after/name)
    plan['actions'] += [dict(id='single-word',input='single-scan45.pdf',target='docx'),dict(id='single-office',input='@single-word',target='pdf')]
    plan['beforeManifestSha256']=sha(out/'expected.json');(after/'expected.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
    print('Frozen nativeheader+2scanRGB images/native-only control/reusedsingle45control;4before+6after contracts;0OCR')
if __name__=='__main__':main()
