#!/usr/bin/env python3
"""Derive final controls without changing the completed frozen baseline."""
import hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];NS='{http://www.ofdspec.org/2016}'
E.register_namespace('ofd',NS[1:-1])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    src=ROOT/'qa-samples/generated/dedup32';out=ROOT/'qa-samples/generated/dedup32-final';out.mkdir(exist_ok=False)
    truth=json.loads((src/'expected.json').read_text());sources={};actions=list(truth['actions'])
    def put(name,path):
        p=out/name;p.write_bytes(path.read_bytes());sources[name]=sha(p);return name
    for name,value in truth['sources'].items():assert sha(src/name)==value;put(name,src/name)
    with zipfile.ZipFile(src/'duplicate.ofd') as z:parts={n:z.read(n) for n in z.namelist()}
    root=E.fromstring(parts['Doc_0/Pages/Page_0/Content.xml']);layer=root.find('.//'+NS+'Layer')
    for child in list(layer):
        if child.tag==NS+'TextObject':layer.remove(child)
    obj=E.SubElement(layer,NS+'ImageObject',dict(layer.find(NS+'ImageObject').attrib));obj.set('ID','17')
    parts['Doc_0/Pages/Page_0/Content.xml']=E.tostring(root,encoding='utf-8',xml_declaration=True)
    p=out/'overlap-ocr-noop.ofd'
    with zipfile.ZipFile(p,'w',zipfile.ZIP_DEFLATED) as z:
        for n,data in parts.items():z.writestr(n,data)
    sources[p.name]=sha(p);actions.append(dict(id='overlap-ocr-noop-text',input=p.name,target='txt'))
    actions.extend([dict(id='partial-word',input='partial-new-digits.ofd',target='docx'),dict(id='partial-office',input='@partial-word',target='pdf'),
        dict(id='partial-office-text',input='@partial-office',target='txt'),
        dict(id='partial-edited-office',input='@partial-word',target='pdf',edit=dict(old='048.65',new='147.80')),
        dict(id='partial-edited-text',input='@partial-edited-office',target='txt'),
        dict(id='conflict-word',input='negative-sign.ofd',target='docx')])
    with zipfile.ZipFile(src/'partial-new-digits.ofd') as z:parts={n:z.read(n) for n in z.namelist()}
    parts['Doc_0/Pages/Page_0/Content.xml']=parts['Doc_0/Pages/Page_0/Content.xml'].replace(b'ofd:',b'mixed:').replace(b'xmlns:ofd=',b'xmlns:mixed=')
    p=out/'alias-partial.ofd'
    with zipfile.ZipFile(p,'w',zipfile.ZIP_DEFLATED) as z:
        for n,data in parts.items():z.writestr(n,data)
    sources[p.name]=sha(p);actions.append(dict(id='alias-partial-text',input=p.name,target='txt'))
    for name,path in [('masked-edited.pdf','qa-samples/report/iteration31-after/reserve-edited-office-result.pdf'),
                      ('boundary-reserve.pdf','qa-samples/report/iteration30-heading-after/reserve-office-result.pdf')]:
        put(name,ROOT/path);actions.append(dict(id=name.removesuffix('.pdf')+'-text',input=name,target='txt'))
    final=dict(sources=sources,actions=actions,cases=truth['cases'],parentManifestSha256=sha(src/'expected.json'),generatorSha256=sha(Path(__file__)),
               expectedCommon='DEDUP AUDIT 2076\nRecord 00793\nAmount 048.65\nDate 2076-10-14\n',
               scope='Finite dedup numeric integrity and accepted namespace/Word/edit/masking/boundary controls; conflicting source layers are not a single amount truth')
    (out/'expected.json').write_text(json.dumps(final,indent=2)+'\n');print('prepared',len(actions),'final requests')
if __name__=='__main__':main()
