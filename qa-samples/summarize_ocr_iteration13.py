#!/usr/bin/env python3
"""Publish finite gate-stage/native quality evidence without closing unresolved risk."""
import hashlib,json
from pathlib import Path
from verify_cloud_ocr import metrics
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def identity(p):return {k:v for k,v in load(p/'artifact-provenance.json').items() if k!='buildInputs'}
def status(trace,spec):
 return {g['runtimeStage']:{'checks':sum(x['stage']==g['runtimeStage'] for x in trace),'rejected':any(x['stage']==g['runtimeStage'] and x['rejected'] for x in trace),'reached':any(x['stage']==g['runtimeStage'] for x in trace)} for g in spec['gates']}
def main():
 work=ROOT/'qa-samples/work';spec=load(work/'iteration13-trace/spec.json');prior=load(work/'iteration13-rejection-analysis/results.json')
 for c in prior:
  c['entryGateStatus']=status(c['trace']['entryTrace'],spec);c['directStrictGateStatus']=status(c['trace']['directStrictTrace'],spec)
 before,current=ROOT/'qa-samples/report/iteration13-before',ROOT/'qa-samples/report/iteration13-current';a,b=load(before/'report.json'),load(current/'report.json');assert a['manifest']==b['manifest']==load(ROOT/'docs/cloud-ocr-iteration13-target-corpus.json')
 native=load(work/'iteration13-target-native/results.json');assert len(native)==len(a['cases'])==len(b['cases'])==5;cases=[]
 for n,x,y in zip(native,a['cases'],b['cases']):
  t=n['truth'];assert t['file']==x['file']==y['file'] and x['success'] and y['success'];probe=n['probe'];text='\n'.join(probe['production']['lines']);truth='\n'.join(t['expectedLines'])
  assert probe['traceMatchesProduction'];assert hashlib.sha256((ROOT/'qa-samples/generated/cloud-iteration13-target'/t['file']).read_bytes()).hexdigest()==t['sha256']
  if t['sourceSkewDegrees']==0:
   assert probe['production']['fragmentedColumnsValidated'] and y['metrics']==metrics(truth,text) and y['metrics']['cer']<x['metrics']['cer']
   assert not any(w['code']=='OCR_DESKEW_APPLIED' for w in y['warnings'])
  else:
   assert not probe['production']['fragmentedColumnsValidated'];assert x['text']==y['text'] and x['metrics']==y['metrics']
  cases.append({'native':n,'before':x,'current':y,'nativeArrangedMetrics':metrics(truth,text),'entryGateStatus':status(probe['entryTrace'],spec),'directStrictGateStatus':status(probe['directStrictTrace'],spec)})
 shadow=[]
 for name in ['en-serif-shadow.png','zh-sans-shadow.png']:
  d=load(work/'iteration13-shadow'/(name+'.json'));assert d['partialReturnedOriginalObject'] and d['sourceObjectsExact'] and not d['fullAccepted'];shadow.append({'file':name,'diagnostic':d,'scope':'Existing original/candidate TSVs reused; no new OCR or changed confidence gates'})
 result={'scope':'Finite targeted gate-stage investigation, unchanged production code/JAR; model feasibility is not native quality evidence','productionSourceRevision':'7a941441e6624aaba5eb8f0d16a0377ddf8a7abf','jarSha256':'1c3658c86536b0d06a76f583fdc39a6b68bfce77fb05b8f48e3c685e4ab7c112','gateSpecification':spec,'prior44ExistingNativeTrace':prior,'initialRejectedUpright':load(work/'iteration13-native/upright-probe.json'),'initialManifestSha256':'e0489fbea93da37e692647189be42440fa89b820ef976e6a54c04b9d40cc29ed','initialFourAngleAlternativesUnrun':True,'targetManifestSha256':'d1fc1abf730aeca01b42a45693cb3c00e54e021ce63b18d935508f1b8663770e','targetCases':cases,'beforeIdentity':identity(before),'currentIdentity':identity(current),'beforeResources':load(before/'resources.json'),'currentResources':load(current/'resources.json'),'modelChecks':load(work/'iteration13-model.json'),'shadowDiagnosis':shadow,'conditionalRiskClosed':False,'angleInvestigationStopped':True,'nativeNewUniqueRuns':6,'nativeNewTrueTiltedRuns':4,'nativeTrueTiltedQualifyingRuns':0,'old44OcrRepeated':False,'limits':['Geometry permits strict success at2degrees in hand-constructed actual Java blocks; native segmentation may reject even0.35degrees.','One distinct upright page qualifies; four truly tilted variants all reject. No general tilt-suppression safety claim.','Four angles of the initial rejected upright are explicitly unrun.','Two first per-angle stderr logs were overwritten by diagnostic filename collision; surviving empty logs and all TSV/probe/exit/resource records remain; no OCR rerun.','No production recognition/geometry/selection/timeout/model changes; previous full/Word acceptance was not repeated.','PSM6 enhanced-candidate quality is a next bounded hypothesis, not adopted or measured in this batch.']}
 (ROOT/'docs/cloud-ocr-iteration13-results.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n');print(json.dumps({'priorNativeRecordsTracedWithoutOcr':44,'newNativeRuns':6,'targetHttpContractsEachJar':5,'uprightBeforeCER':cases[0]['before']['metrics']['cer'],'uprightCurrentCER':cases[0]['current']['metrics']['cer'],'trueTiltedQualifying':0,'modelChecks':10,'shadowRowsFailingGain':8,'conditionalRiskClosed':False}))
if __name__=='__main__':main()
