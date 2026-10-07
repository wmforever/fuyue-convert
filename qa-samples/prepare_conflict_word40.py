#!/usr/bin/env python3
"""Freeze four existing conflict Word controls, then prepare predeclared exact-node edits."""
import argparse,hashlib,io,json,xml.etree.ElementTree as E,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--edits-from',type=Path);a=p.parse_args()
    frozen=ROOT/'qa-samples/generated/conflict-word40';out=ROOT/('qa-samples/generated/conflict-word40-edits' if a.edits_from else 'qa-samples/generated/conflict-word40');out.mkdir(exist_ok=False)
    if not a.edits_from:
        specs=[('accounting','numeric35-before','accounting','Amount 048.65','Amount (048.65)','Amount 061.42','(048.65)','(079.53)',True),
               ('percent','numeric35-before','percent','Rate 10','Rate 10%','Rate 12','10%','14%',True),
               ('post-minus','postfix39-before','post-minus','Amount 048.65-','Amount 048.65','Amount 061.42-','048.65','079.53',True),
               ('ordinary','numeric35-before','ordinary-punctuation','Invoice No 2094','Invoice No. 2094','Invoice No 2095',None,None,False)]
        cases=[];sources={};actions=[]
        for name,folder,oldId,native,raster,nativeEdit,ocrOld,ocrEdit,conflict in specs:
            parent=ROOT/'qa-samples/generated'/folder;truth=json.loads((parent/'expected.json').read_text());case=next(c for c in truth['cases'] if c['id']==oldId)
            edits=[dict(id=name+'-native-edit',layer='native',old=native,new=nativeEdit)]
            if ocrOld:edits.append(dict(id=name+'-ocr-edit',layer='ocr',old=ocrOld,new=ocrEdit))
            for ext in ['ofd','png']:
                source=parent/(oldId+'.'+ext);assert sha(source)==truth['sources'][source.name];dest=out/(name+'.'+ext);dest.write_bytes(source.read_bytes());sources[dest.name]=sha(dest)
            cases.append(dict(id=name,parentCorpus=folder,parentCase=oldId,parentManifestSha256=sha(parent/'expected.json'),nativeValue=native,rasterValue=raster,expectedConflict=conflict,sourceNativeLines=case['nativeLines'],sourceRasterLines=case['rasterLines'],nativeExpectedInWord=native,ocrExpectedInWord=raster if conflict else None,edits=edits,editSelection='one exact w:t node,not substring;allpartsexceptdocumentXML unchanged;synthetic edits only;no source selected as truth'))
            actions.extend([dict(id=name+'-word',input=name+'.ofd',target='docx'),dict(id=name+'-office',input='@'+name+'-word',target='pdf')])
        manifest=dict(parentRevision='9890918dd4cb87a2f52d57f47b8edd3248f82a00',generatorSha256=sha(Path(__file__)),cases=cases,sources=sources,actions=actions,plannedTotalHttp=15,plannedSourceOcr=4,plannedOffice=11,plannedExactNodeEdits=7,freezeBeforeAnyNewConversion=True,acceptance=dict(nativeAndObservedEditableLayersExact=True,sourceRasterPixelsExact=True,originalWordBoxesAndMaskStylesChecked=True,bothValuesVisiblyReadableRequiredForAcceptance=True,actualOffice300DpiRender=True,editOneLayerWithoutSilentlyOverwritingOther=True,noCanonicalFinancialValue=True,warningRequiredForConflicts=True,stopOnAmbiguousNode=True,noRotationOrFlowPrototype=True))
    else:
        manifest0=json.loads((frozen/'expected.json').read_text());report=json.loads((a.edits_from/'report.json').read_text());requests={c['case']:c for c in report['cases']};manifest=dict(parentManifestSha256=sha(frozen/'expected.json'),sources={},actions=[],edits=[],unsupportedEdits=[],frozenPlanTotalHttp=15)
        for case in manifest0['cases']:
            req=requests[case['id']+'-word'];source=a.edits_from/req['artifact'];assert sha(source)==req['sha256']
            with zipfile.ZipFile(source) as z:parts={n:z.read(n) for n in z.namelist()}
            for edit in case['edits']:
                root=E.fromstring(parts['word/document.xml']);nodes=[n for n in root.iter(W+'t') if n.text==edit['old']]
                if len(nodes)!=1:manifest['unsupportedEdits'].append(dict(**edit,idCase=case['id'],reason='exact editable source node count is not one',count=len(nodes)));continue
                nodes[0].text=edit['new'];changed=dict(parts);changed['word/document.xml']=E.tostring(root,encoding='utf-8',xml_declaration=True);dest=out/(edit['id']+'.docx')
                with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
                    for name,data in changed.items():z.writestr(name,data)
                manifest['sources'][dest.name]=sha(dest);manifest['actions'].append(dict(id=edit['id'],input=dest.name,target='pdf'));manifest['edits'].append(dict(**edit,idCase=case['id'],originalWordSha256=sha(source),artifact=dest.name,sha256=sha(dest),exactSourceNodeCount=1,changedParts=['word/document.xml']))
    (out/'expected.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print('Frozen cases/actions',len(manifest.get('cases',[])),len(manifest['actions']),'unsupported',len(manifest.get('unsupportedEdits',[])))
if __name__=='__main__':main()
