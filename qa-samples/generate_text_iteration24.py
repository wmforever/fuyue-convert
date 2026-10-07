#!/usr/bin/env python3
"""Small adversarial author-tag corpus; visible ASCII truth is independent of tags."""
from pathlib import Path
import hashlib,json
import fitz
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'qa-samples/generated/text-iteration24'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 assert not OUT.exists();OUT.mkdir(parents=True);cases=[]
 mutations=['valid','malformed','incomplete','duplicate','out-of-order','missing-mcid','artifact','artifact-nested','cycle','deep','mixed-pages','duplicate-content-id']
 for mutation in mutations:
  d=fitz.open();p=d.new_page(width=740,height=760);page=p.xref;items=[];groups=[]
  for g in [1,2]:
   top=50+(g-1)*350
   text=[f'SECTION {g} 2035-09-17',f'LEFT {g}-1 ID000571 -742.63',f'RIGHT {g}-1 ID000924 +0.47',f'LEFT {g}-2 EUR 1,308.56',f'RIGHT {g}-2 DATE 2035-10-26']
   groups.append(text)
   for i,t in enumerate(text):
    x=410 if i and i%2==0 else 42;y=top+(0 if i==0 else 60 if i<=2 else 110)
    p.insert_text((x,y),t,fontname='helv',fontsize=12);ref=p.get_contents()[-1];mid=len(items)
    prefix=f'/P <</MCID {mid}>> BDC\n';suffix='\nEMC\n'
    if mid==9:
     if mutation=='missing-mcid':prefix='/P BMC\n'
     if mutation=='artifact':prefix='/Artifact BMC\n'
     if mutation=='artifact-nested':prefix=f'/Artifact BMC\n/P <</MCID {mid}>> BDC\n';suffix='\nEMC\nEMC\n'
     if mutation=='duplicate-content-id':prefix='/P <</MCID 8>> BDC\n'
    d.update_stream(ref,prefix.encode()+d.xref_stream(ref)+suffix.encode());items.append({'page':1,'text':t,'x':x,'baseline':y,'mcid':mid})
  root=d.get_new_xref();owner=d.get_new_xref();ps=[d.get_new_xref(),d.get_new_xref()]
  for g,ref in enumerate(ps):
   kids=list(range(g*5,g*5+5))
   if g==1 and mutation=='incomplete':kids.pop()
   if g==1 and mutation=='duplicate':kids[-1]=0
   content='['+' '.join(map(str,kids))+']'
   if g==1 and mutation=='malformed':content='(unsupported text tag)'
   if g==1 and mutation=='cycle':content='['+' '.join(map(str,kids))+f' {ref} 0 R]'
   if g==1 and mutation=='deep':
    child=content
    for depth in range(70):
     node=d.get_new_xref();d.update_object(node,f'<< /Type /StructElem /S /Span /Pg {page} 0 R /K {child} >>');child=f'{node} 0 R'
    content=child
   d.update_object(ref,f'<< /Type /StructElem /S /P /P {owner} 0 R /Pg {page} 0 R /K {content} >>')
  refs=ps[::-1] if mutation=='out-of-order' else ps
  d.update_object(owner,f'<< /Type /StructElem /S /Document /P {root} 0 R /K ['+' '.join(f'{r} 0 R' for r in refs)+'] >>')
  d.update_object(root,f'<< /Type /StructTreeRoot /K {owner} 0 R >>');d.xref_set_key(d.pdf_catalog(),'StructTreeRoot',f'{root} 0 R')
  section=[t for g in groups for t in [g[0],g[1],g[3],g[2],g[4]]]
  legacy=[groups[0][0],groups[0][1],groups[0][3],groups[1][0],groups[1][1],groups[1][3],groups[0][2],groups[0][4],groups[1][2],groups[1][4]]
  expected='\n'.join(section if mutation in ['valid','mixed-pages'] else legacy)+'\n'
  if mutation=='mixed-pages':
   p=d.new_page(width=740,height=760)
   for i,t in enumerate(['UNTAGGED ID000168 2035-11-09','Balance -0.05; audit +12.30; .47']):
    p.insert_text((42,70+i*60),t,fontname='helv',fontsize=12);items.append({'page':2,'text':t,'x':42,'baseline':70+i*60})
   expected+='\n\f\nUNTAGGED ID000168 2035-11-09\nBalance -0.05; audit +12.30; .47\n'
  path=OUT/(mutation+'.pdf');d.save(path,garbage=3,deflate=True,no_new_id=True);d.close()
  with fitz.open(path) as saved:
   actual=''.join(p.get_text() for p in saved);assert sorted(''.join(actual.split()))==sorted(''.join(''.join(i['text'] for i in items).split()))
   assert path.stat().st_size<50000
  cases.append({'file':path.name,'sha256':sha(path),'pages':2 if mutation=='mixed-pages' else 1,'wordControl':mutation=='valid','mutation':mutation,'expectedText':expected,'items':items,'expectFallback':mutation not in ['valid','mixed-pages'],'maximumStructureDepth':72 if mutation=='deep' else 4})
 manifest={'generatorSha256':sha(Path(__file__)),'parentRevision':'9f50cf63e6d971e1e31fe3c3bc502526ec39cfef','PyMuPDF':fitz.VersionBind,'font':'PDF standard14 Helvetica; no external font install','cases':cases}
 (OUT/'expected.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps({'cases':len(cases),'manifestSha256':sha(OUT/'expected.json'),'bytes':sum((OUT/c['file']).stat().st_size for c in cases)}))
if __name__=='__main__':main()
