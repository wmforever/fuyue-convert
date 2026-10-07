#!/usr/bin/env python3
"""Five bounded OOXML experiments; generates inputs, does not run conversions."""
from pathlib import Path
import json,zipfile,io,hashlib,xml.etree.ElementTree as E,re
root=Path(__file__).resolve().parents[1];out=root/'qa-samples/generated/wrap28-probe';out.mkdir();source=root/'qa-samples/report/iteration26-scan-after/longer-gray-edited-office-edited.docx';V='{urn:schemas-microsoft-com:vml}';W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';actions=[];sources={}
with zipfile.ZipFile(source) as z:parts={n:z.read(n) for n in z.namelist()}
for mode in ['baseline','wordwrap-on','textbox-nowrap','fit-true','extra-width']:
 p=parts.copy();r=E.fromstring(p['word/document.xml']);targets=[n for n in r.iter(V+'rect') if '187.50' in ''.join(n.itertext())];assert len(targets)==1;n=targets[0];box=n.find(V+'textbox')
 if mode=='wordwrap-on':E.SubElement(box.find('.//'+W+'pPr'),W+'wordWrap',{W+'val':'1'})
 if mode=='textbox-nowrap':box.set('style',box.get('style')+';mso-wrap-style:none')
 if mode=='fit-true':box.set('style',box.get('style').replace('mso-fit-shape-to-text:false','mso-fit-shape-to-text:true'))
 if mode=='extra-width':n.set('style',re.sub(r'width:([0-9.]+)pt',lambda m:'width:'+str(float(m[1])+27)+'pt',n.get('style')))
 if mode!='baseline':p['word/document.xml']=E.tostring(r,encoding='utf-8',xml_declaration=True)
 dest=out/(mode+'.docx')
 with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
  for k,v in p.items():z.writestr(k,v)
 sources[dest.name]=hashlib.sha256(dest.read_bytes()).hexdigest();actions.append(dict(id=mode,input=dest.name,target='pdf'))
(out/'expected.json').write_text(json.dumps({'sources':sources,'actions':actions,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest()},indent=2)+'\n')
