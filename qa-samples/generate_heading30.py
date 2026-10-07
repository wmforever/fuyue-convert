#!/usr/bin/env python3
"""Two independent headings; candidate reuses frozen PDFs and three prior regressions."""
import argparse,hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/generated/heading30'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();mode=p.add_mutually_exclusive_group();mode.add_argument('--after',action='store_true');mode.add_argument('--existing-before',action='store_true');mode.add_argument('--scale-proof',action='store_true');args=p.parse_args()
    if args.existing_before or args.scale_proof:
        out=ROOT/'qa-samples/generated'/('heading30-existing-before' if args.existing_before else 'heading30-scale-proof');out.mkdir(exist_ok=False)
        sources={};actions=[]
        if args.existing_before:
            for name in ['white','negative','zeros']:
                source=ROOT/'qa-samples/report/iteration28-after'/(name+'-office-result.pdf');dest=out/(name+'-office.pdf')
                dest.write_bytes(source.read_bytes());sources[dest.name]=sha(dest);actions.append(dict(id=name+'-text',input=dest.name,target='txt'))
        else:
            source=ROOT/'qa-samples/report/iteration28-after/white-word-result.docx'
            with zipfile.ZipFile(source) as z:parts={n:z.read(n) for n in z.namelist()}
            root=E.fromstring(parts['word/document.xml']);W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
            targets=[n for n in root.iter('{urn:schemas-microsoft-com:vml}rect') if any('RESERVE' in (t.text or '') for t in n.iter(W+'t'))];assert len(targets)==1
            scale=targets[0].find('.//'+W+'w');assert scale.get(W+'val')=='119';scale.set(W+'val','100')
            parts['word/document.xml']=E.tostring(root,encoding='utf-8',xml_declaration=True);dest=out/'scale-proof.docx'
            with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
                for n,b in parts.items():z.writestr(n,b)
            sources[dest.name]=sha(dest);actions.append(dict(id='scale-proof',input=dest.name,target='pdf'))
        (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,generatorSha256=sha(Path(__file__))),indent=2)+'\n')
        print(json.dumps(dict(actions=len(actions))));return
    out=OUT/('after' if args.after else 'before');out.mkdir(parents=True,exist_ok=False)
    sources={};actions=[];cases=[]
    def put(name,source):
        dest=out/name;dest.write_bytes(source.read_bytes());sources[name]=sha(dest);return name
    if not args.after:
        font=ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',56)
        for name,title,amount in [('project','PROJECT SUMMARY 2067','-054.80'),('reserve','RESERVE REVIEW 2068','00062.35')]:
            lines=[title,'Record 00916','Amount '+amount,'Date 2067-11-23','Control 45.70']
            image=Image.new('RGB',(1500,1150),'white');draw=ImageDraw.Draw(image)
            for text,y in zip(lines,[90,280,470,700,920],strict=True):draw.text((100,y),text,font=font,fill='black')
            path=out/(name+'.png');image.save(path,dpi=(300,300));sources[path.name]=sha(path)
            cases.append(dict(id=name,expected='\n'.join(lines)+'\n'))
            for stage,input,target in [('pdf',path.name,'pdf'),('word','@'+name+'-pdf','docx'),('office','@'+name+'-word','pdf'),('text','@'+name+'-office','txt')]:
                actions.append(dict(id=name+'-'+stage,input=input,target=target))
    else:
        before=json.loads((OUT/'before/expected.json').read_text());cases=before['cases']
        old=json.loads((ROOT/'qa-samples/generated/wrap-iteration28/before/expected.json').read_text())
        cases=old['cases'][:3]+cases
        for case in cases:
            name=case['id'];folder=ROOT/'qa-samples/report'/('iteration28-after' if name in ['white','negative','zeros'] else 'iteration30-heading-before')
            source=ROOT/'qa-samples/report/iteration28-before'/(name+'-pdf-result.pdf') if name in ['white','negative','zeros'] else folder/(name+'-pdf-result.pdf')
            case['baselineFolder']=str(folder.relative_to(ROOT));file=put(name+'-scan.pdf',source)
            actions.extend([dict(id=name+'-word',input=file,target='docx'),dict(id=name+'-office',input='@'+name+'-word',target='pdf'),dict(id=name+'-text',input='@'+name+'-office',target='txt')])
        actions.extend([dict(id='white-edited-office',input='@white-word',target='pdf',edit=dict(old='4.10',new='541.80')),
                        dict(id='white-edited-text',input='@white-edited-office',target='txt')])
        dark=put('dark-scan.pdf',ROOT/'qa-samples/report/iteration27-before/dark-pdf-result.pdf');actions.append(dict(id='dark-word',input=dark,target='docx'))
        sparse=ROOT/'qa-samples/generated/sparse-trace30'
        for name in ['original','alias-missing-amount','missing-amount','noise']:
            file=put(name+'.ofd',sparse/(name+'.ofd'));actions.append(dict(id=name+'-ofd-text',input=file,target='txt'))
    (out/'expected.json').write_text(json.dumps(dict(sources=sources,actions=actions,cases=cases,generatorSha256=sha(Path(__file__))),ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(actions=len(actions),cases=len(cases))))
if __name__=='__main__':main()
