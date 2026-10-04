#!/usr/bin/env python3
"""Independent born-digital tagged groups plus untagged ambiguity controls."""
import hashlib,json,pathlib,shutil
import fitz
ROOT=pathlib.Path(__file__).resolve().parents[1];OUT=ROOT/'qa-samples/generated/text-iteration23'
FONTS={'en':'/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf','bold':'/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf','zh':'/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'}
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def main():
 assert not OUT.exists();OUT.mkdir(parents=True);cases=[]
 def draw(p,text,x,y,key='en',group=None):
  p.insert_font(fontname=key,fontfile=FONTS[key]);p.insert_text((x,y),text,fontname=key,fontsize=12)
  return {'page':p.number+1,'text':text,'x':x,'baseline':y,'font':key,'size':12,'group':group,'contentXref':p.get_contents()[-1]}
 def save(doc,name,items,order,kind,tagged=False,word=False,extra=None):
  # Explicit source ToUnicode prevents compatibility-glyph reverse cmap choices.
  for key in FONTS:
   font=fitz.Font(fontfile=FONTS[key]);mapping={}
   for item in items:
    if item['font']==key:
     for c in item['text']:
      gid=font.has_glyph(ord(c));assert gid and (gid not in mapping or mapping[gid]==c);mapping[gid]=c
   entries=[f'<{gid:04X}> <{c.encode("utf-16-be").hex().upper()}>' for gid,c in sorted(mapping.items())]
   cmap='/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /QATruth def /CMapType 2 def 1 begincodespacerange <0000> <FFFF> endcodespacerange\n'
   for start in range(0,len(entries),100):
    chunk=entries[start:start+100];cmap+=str(len(chunk))+' beginbfchar\n'+'\n'.join(chunk)+'\nendbfchar\n'
   cmap+='endcmap CMapName currentdict /CMap defineresource pop end end'
   for ref in {f[0] for p in doc for f in p.get_fonts() if f[4]==key}:
    ref=int(doc.xref_get_key(ref,'ToUnicode')[1].split()[0]);doc.update_stream(ref,cmap.encode())
  if tagged:
   root=doc.get_new_xref();container=doc.get_new_xref();groups=sorted({i['group'] for i in items});refs={g:doc.get_new_xref() for g in groups}
   for mcid,item in enumerate(items):
    ref=item['contentXref'];doc.update_stream(ref,f'/P <</MCID {mcid}>> BDC\n'.encode()+doc.xref_stream(ref)+b'\nEMC\n')
   page=doc[0].xref
   for group,ref in refs.items():
    kids=' '.join(str(i) for i,item in enumerate(items) if item['group']==group)
    doc.update_object(ref,f'<< /Type /StructElem /S /P /P {container} 0 R /Pg {page} 0 R /K [{kids}] >>')
   doc.update_object(container,f'<< /Type /StructElem /S /Document /P {root} 0 R /K ['+' '.join(f'{r} 0 R' for r in refs.values())+'] >>')
   parent=doc.get_new_xref();doc.update_object(parent,'<< /Nums [0 ['+' '.join(f"{refs[item['group']]} 0 R" for item in items)+']] >>')
   doc.update_object(root,f'<< /Type /StructTreeRoot /K [{container} 0 R] /ParentTree {parent} 0 R /ParentTreeNextKey 1 >>')
   doc.xref_set_key(page,'StructParents','0');doc.xref_set_key(doc.pdf_catalog(),'StructTreeRoot',f'{root} 0 R');doc.xref_set_key(doc.pdf_catalog(),'MarkInfo','<< /Marked true >>')
  path=OUT/(name+'.pdf');doc.set_metadata({'title':name,'author':'FormatConverter synthetic QA'});doc.save(path,garbage=4,deflate=True,no_new_id=True)
  case={'file':path.name,'sha256':sha(path),'pages':len(doc),'kind':kind,'tagged':tagged,'wordControl':word,'items':[{k:v for k,v in i.items() if k!='contentXref'} for i in items],'expectedText':'\n'.join(order)}
  if extra:case.update(extra)
  cases.append(case);doc.close()
 for name,mixed,unequal in [('tagged-wide-regions',False,False),('tagged-mixed-regions',True,False),('tagged-unequal-regions',False,True)]:
  d=fitz.open();p=d.new_page(width=720,height=760);items=[];order=[]
  for group in [1,2]:
   top=45+(group-1)*345;heading=f'PART {group}: records and independent review 2034'
   items.append(draw(p,heading,42,top,'bold',group));order.append(heading);left=[];right=[]
   nl,nr=((5,3) if group==1 else (3,5)) if unequal else (3,3)
   for i in range(max(nl,nr)):
    if i<nl:left.append(draw(p,f'Left {group}-{i+1} ID00571 -742.63',42+20*(group-1),top+60+i*40,'en',group));items.append(left[-1])
    if i<nr:right.append(draw(p,f'右栏{group}-{i+1} 编号00924 .47' if mixed else f'Right {group}-{i+1} ID00924 .47',425,top+60+i*40,'zh' if mixed else 'en',group));items.append(right[-1])
   order += [i['text'] for i in left+right]
  save(d,name,items,order,'regions',True,mixed)
 d=fitz.open();p=d.new_page(width=720,height=760);items=[];left=[];right=[]
 for index,y in enumerate([70,105,140,410,445,480]):
  left.append(draw(p,f'Continued left {index+1}: ID00571.',42,y));right.append(draw(p,f'Continued right {index+1}: .47.',425,y));items.extend([left[-1],right[-1]])
 save(d,'continuous-wide-gap-control',items,[i['text'] for i in left+right],'continuous-columns')
 d=fitz.open();p=d.new_page(width=650,height=470);items=[]
 for i,t in enumerate(['Single record ID00571: amount -742.63.', 'Date 2034-08-19; balance .47; keep 00924.', 'Final line: EUR 1,308.56 / revision 02.']):items.append(draw(p,t,42,70+i*100))
 d.new_page(width=650,height=470);save(d,'single-and-blank-control',items,[i['text'] for i in items],'single',extra={'blankPages':[2]})
 rows=[['ID','Date','Amount','Status'],['AB-00571','2034-08-19','-742.63','Keep'],['CD-00924','2034-09-23','.47','Review'],['EF-00168','2034-10-26','1,308.56','Exact']]
 for ruled in [True,False]:
  d=fitz.open();p=d.new_page(width=700,height=420);items=[];xs=[42,195,370,535,658];ys=[65+i*64 for i in range(5)]
  if ruled:
   for x in xs:p.draw_line((x,ys[0]),(x,ys[-1]),width=.6)
   for y in ys:p.draw_line((xs[0],y),(xs[-1],y),width=.6)
  for i,row in enumerate(rows):
   for j,t in enumerate(row):items.append(draw(p,t,xs[j]+6,ys[i]+30,'bold' if i==0 else 'en'))
  save(d,'ruled-numeric-control' if ruled else 'unruled-ledger-control',items,[i['text'] for i in items],'table' if ruled else 'ambiguous-ledger',word=ruled,extra={'cells':rows,'orderAssessment':'explicit-table' if ruled else 'ambiguous-control-no-inference'})
 old=ROOT/'qa-samples/report/iteration22-after/cjk-heading-control-office.pdf';path=OUT/'accepted22-office-regression.pdf';shutil.copy2(old,path)
 oldtruth=json.loads((ROOT/'qa-samples/generated/cjk-iteration22/expected.json').read_text());c=next(c for c in oldtruth['cases'] if c['file']=='cjk-heading-control.pdf')
 cases.append({'file':path.name,'sha256':sha(path),'pages':1,'kind':'retained-regression','tagged':True,'wordControl':False,'items':c['items'],'expectedText':c['expectedText'],'retainedSource':'accepted22 immutable Office artifact; no completed matrix rerun'})
 m={'generatorSha256':sha(pathlib.Path(__file__)),'PyMuPDF':fitz.VersionBind,'fonts':{k:{'path':v,'sha256':sha(v)} for k,v in FONTS.items()},'cases':cases};(OUT/'expected.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n');print(sha(OUT/'expected.json'),len(cases))
if __name__=='__main__':main()
