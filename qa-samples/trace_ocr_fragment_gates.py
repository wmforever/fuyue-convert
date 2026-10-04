#!/usr/bin/env python3
"""Generate read-only gate tracing copies from exact repository source, never edit production."""
import argparse,hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True);spec=[];hashes={}
 for name in ['OcrReadingOrder','OcrFragmentedColumns']:
  path=ROOT/'task-service/src/main/java/com/fuyue/formatconverter/task'/(name+'.java');text=path.read_text();hashes[name]=hashlib.sha256(path.read_bytes()).hexdigest();edits=[];counter=0
  for match in re.finditer(r'\bif\s*\(',text):
   start=text.index('(',match.start());level=1;i=start+1
   while level:
    if text[i]=='(':level+=1
    elif text[i]==')':level-=1
    i+=1
   if not re.match(r'\s*return (fallback|unchanged);',text[i:]):continue
   counter+=1;stage=f'{name}.G{counter:02d}';condition=text[start+1:i-1];spec.append({'stage':stage,'runtimeStage':stage.replace(name,name+'Trace'),'sourceLine':text[:start].count('\n')+1,'rejectWhen':condition})
   edits.append((start+1,i-1,'OcrGateTrace.rejected("'+stage+'", ('+condition+'))'))
  for start,end,replacement in reversed(edits):text=text[:start]+replacement+text[end:]
  text=re.sub(r'\bOcrReadingOrder\b','OcrReadingOrderTrace',text);text=re.sub(r'\bOcrFragmentedColumns\b','OcrFragmentedColumnsTrace',text)
  (args.out/(name+'Trace.java')).write_text(text)
 (args.out/'OcrGateTrace.java').write_text('''package com.fuyue.formatconverter.task;
import java.util.*;
final class OcrGateTrace {
 static final List<Map<String,Object>> checks=new ArrayList<>();
 static boolean rejected(String stage,boolean rejected){checks.add(Map.of("stage",stage,"rejected",rejected));return rejected;}
 static void reset(){checks.clear();}
}
''')
 (args.out/'spec.json').write_text(json.dumps({'sourceSha256':hashes,'gates':spec},indent=2)+'\n');print(json.dumps({'sources':hashes,'gateCount':len(spec)}))
if __name__=='__main__':main()
