#!/usr/bin/env python3
"""One clear ruled bilingual scan and one distinct unruled column control;no old replay."""
import hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,__version__
from fontTools.ttLib import TTCollection
ROOT=Path(__file__).resolve().parents[1];FONT=Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    out=ROOT/'qa-samples/generated/scan-tables45';out.mkdir(exist_ok=False);cases=[];sources={};width,height,dpi=1920,960,200
    face=TTCollection(FONT).fonts[0];cmap=face.getBestCmap();actions=[]
    for kind in ['ruled','columns']:
        image=Image.new('RGB',(width,height),'white');draw=ImageDraw.Draw(image);items=[]
        def put(value,x,baseline,size,role,row=None,column=None,cell=None):
            assert all(ord(c) in cmap for c in value),value
            font=ImageFont.truetype(str(FONT),size);draw.text((x,baseline),value,font=font,fill='black',anchor='ls')
            box=list(draw.textbbox((x,baseline),value,font=font,anchor='ls'))
            assert 0<=box[0]<box[2]<=width and 0<=box[1]<box[3]<=height
            item=dict(text=value,x=x,baseline=baseline,fontSize=size,boxPixels=box,role=role)
            if row is not None:item.update(row=row,column=column,cellBoxPixels=cell)
            items.append(item)
        if kind=='ruled':
            put('扫描台账 Scan ledger 00783',80,95,42,'title');xs=[80,650,1240,1840];ys=[180,330,480,630,780]
            for x in xs:draw.line((x,ys[0],x,ys[-1]),fill='black',width=4)
            for y in ys:draw.line((xs[0],y,xs[-1],y),fill='black',width=4)
            matrix=[['编号 ID','日期 Date','金额 Amount'],['01936','2093-12-04','-042.70'],['03872','2093-12-05','+0085.40'],['07744','2093-12-06','0170.80']]
            for row,values in enumerate(matrix):
                for col,value in enumerate(values):put(value,xs[col]+28,ys[row]+90,48,'cell',row,col,[xs[col],ys[row],xs[col+1],ys[row+1]])
            case=dict(id=kind,kind='clear-ruled-bilingual-table',rows=4,columns=3,matrix=matrix,xGridPixels=xs,yGridPixels=ys,
                expectedRowMajor='\n'.join([items[0]['text'],*['\t'.join(row) for row in matrix]]),edit=dict(old='0170.80',new='0180.80',row=3,column=2),
                structuralTruth='12distinct logicalsourcecells;current textframes alone do not certify editabletable recovery')
        else:
            put('独立多栏 Independent columns 00649',80,95,42,'title')
            columns=[['Left ledger 左栏','ID 00619','Date 2094-01-13','Amount -053.25','Left end 完成'],['Middle ledger 中栏','ID 01238','Date 2094-01-14','Amount +0106.50','Middle end 完成'],['Right ledger 右栏','ID 02476','Date 2094-01-15','Amount 0213.00','Right end 完成']]
            for col,values in enumerate(columns):
                for row,value in enumerate(values):put(value,[80,690,1300][col],220+row*130,36,'column',row,col,[col*640,160,(col+1)*640,860])
            case=dict(id=kind,kind='distinct-unruled-column-negative',columns=columns,
                expectedColumnMajor='\n'.join([items[0]['text'],*[s for col in columns for s in col]]),
                expectedVisualRowMajor='\n'.join([items[0]['text'],*['\t'.join(col[row] for col in columns) for row in range(5)]]),
                structuralTruth='Not a table;reject automaticcell inference;column-major intended but unruledpixels alone are not a universal reading-intent certificate')
        path=out/(kind+'.png');image.save(path,dpi=(dpi,dpi));sources[path.name]=sha(path);case.update(file=path.name,sourceSha256=sha(path),items=items,pixels=[width,height],dpi=dpi,physicalPageMm=[0,0,width*25.4/dpi,height*25.4/dpi]);cases.append(case)
    actions=[dict(id='ruled-direct-word',input='ruled.png',target='docx'),dict(id='ruled-wrap',input='ruled.png',target='pdf'),dict(id='ruled-scan-word',input='@ruled-wrap',target='docx'),dict(id='ruled-office',input='@ruled-scan-word',target='pdf'),dict(id='ruled-edited-office',input='@ruled-scan-word',target='pdf',edit=cases[0]['edit']),dict(id='ruled-edited-api',input='@ruled-edited-office',target='txt'),dict(id='columns-wrap',input='columns.png',target='pdf'),dict(id='columns-scan-word',input='@columns-wrap',target='docx'),dict(id='columns-office',input='@columns-scan-word',target='pdf')]
    plan=dict(parentRevision='8eae8590ff26b5f8d96f6362e6035857568ea310',generatorSha256=sha(Path(__file__)),pillowVersion=__version__,font=dict(path=str(FONT),sha256=sha(FONT),version=sorted({n.toUnicode() for n in face['name'].names if n.nameID==5}),license='SIL-OFL-1.1'),cases=cases,sources=sources,actions=actions,
        acceptance=dict(separateTextCompletenessAndLogicalCellStructure=True,exactSignedNumbersDatesLeadingZeros=True,clearRowMajorTruth=True,negativeNoFakeTable=True,originalScanPixelIdentityForRasterPdfWord=True,allWordCoordinatesInsideOriginalPhysicalPage=True,actualOfficeVisibleGridAndText=True,onePredeclaredShortNumericFrameEdit=True,editApiMayRetainStrictRefusal=True,conversionSuccessNotCompleteness=True),
        bounds=dict(maximumHttpContracts=9,perContractSeconds=120,wholeHttpSeconds=480,productionPageSeconds=120,productionMaximumPixels=25000000,productionOcrConcurrency=1,maximumTransientTsvs=128,maximumTsvBytes=4000000),
        stoppingRule='No PSManlgegainthreshold sweep;no ambiguoussamepixeltruth pair or old matrix replay. No raster-grid structure inferred from text completeness. Minimalproductionfix only for actualsafe content/order defect;otherwise recordboundary and groundednextquestion.')
    (out/'expected.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n');print('Frozen2distinct bilingualRGB scans;clear12cells+unruled3columncontrol;9maximumactualHTTP;0OCR')
if __name__=='__main__':main()
