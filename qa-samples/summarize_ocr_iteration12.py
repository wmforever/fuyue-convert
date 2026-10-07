#!/usr/bin/env python3
"""Audit new tilted risk controls, strict-validation fix and prior artifact acceptance."""
import hashlib,json
from pathlib import Path
from verify_cloud_artifact_regression import compare_artifacts
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())
def identity(folder):return {k:v for k,v in load(folder/'artifact-provenance.json').items() if k!='buildInputs'}
def main():
 reports=ROOT/'qa-samples/report';matrices={}
 for label,names in [('dense',('cloud-iteration12','iteration12','iteration12-native')),('sparse',('cloud-iteration12-sparse','iteration12-sparse','iteration12-sparse-native'))]:
  source,prefix,native_folder=names;samples=ROOT/'qa-samples/generated'/source;manifest=load(samples/'expected.json');before,current,after=[reports/(prefix+'-'+suffix) for suffix in ['before','current','final']];runs=[load(p/'report.json') for p in [before,current,after]];native=load(ROOT/'qa-samples/work'/native_folder/'results.json');natives={c['file']:c for c in native['cases']}
  assert all(r['manifest']==manifest for r in runs);assert all(len(r['cases'])==len(manifest['cases']) for r in runs);cases=[]
  for a,b,c in zip(*(r['cases'] for r in runs)):
   assert a['file']==b['file']==c['file'];name=a['file'];truth=next(t for t in manifest['cases'] if t['file']==name);assert hashlib.sha256((samples/name).read_bytes()).hexdigest()==truth['sha256']
   assert a['success'] and b['success'] and c['success']
   for field in ['text','metrics','expectedFailureVerified']:assert a.get(field)==b.get(field)==c.get(field),(name,field)
   n=natives[name];assert n['probe']['sourceObjectsExact']
   cases.append({'truth':truth,'before':a,'current':b,'after':c,'native':{k:v for k,v in n.items() if k not in ['httpBefore','httpCurrent']}})
  matrices[label]={'manifestSha256':hashlib.sha256((samples/'expected.json').read_bytes()).hexdigest(),'manifest':manifest,'identities':dict(zip(['before','current','after'],map(identity,[before,current,after]))),'resources':{s:load(p/'resources.json') for s,p in zip(['before','current','after'],[before,current,after])},'cases':cases}
 before,after=reports/'iteration11-final-word',reports/'iteration12-final-word';a,b=load(before/'report.json'),load(after/'report.json');assert a['manifest']==b['manifest'];artifacts=[]
 for x,y in zip(a['cases'],b['cases']):
  assert x['file']==y['file'] and y['success']
  for field in ['text','metrics','editableText','officeText','officeMetrics','pages','expectedFailureVerified']:assert x.get(field)==y.get(field),(x['file'],field)
  if 'scan' in y:
   for field in ['editableText','mediaSha256','originalScanPixelsPreserved','metrics','officeMetrics','unrecognizedInkProbes']:assert x['scan'][field]==y['scan'][field]
   for suffix in ['', '.scan']:artifacts.append(compare_artifacts(before,after,x['file'],suffix))
 regressions={}
 for label in ['rotated','prior20','prior9']:
  old,new=reports/('iteration11-final-'+label),reports/('iteration12-final-'+label);a,b=load(old/'report.json'),load(new/'report.json');assert a['manifest']==b['manifest']
  for x,y in zip(a['cases'],b['cases']):
   assert x['file']==y['file'] and y['success']
   for field in ['text','metrics','expectedFailureVerified']:assert x.get(field)==y.get(field)
  assert len(a['cases'])==len(b['cases']);regressions[label]={'count':len(b['cases']),'textMetricsNumberBoundariesExact':True,'beforeIdentity':identity(old),'afterIdentity':identity(new),'beforeResources':load(old/'resources.json'),'afterResources':load(new/'resources.json'),'measurements':b['cases']}
 all_native=[c['native'] for m in matrices.values() for c in m['cases']];qualified=[c['file'] for c in all_native if c['probe']['strictValidationSucceeded']];assert len(all_native)==44
 result={'scope':'Finite actual true-tilt risk validation plus confirmed model-level enumeration guard fix; no general deskew safety claim','riskMatrices':matrices,'runtime':load(ROOT/'desktop/.runtime/ocr/OCR-RUNTIME.json'),'nativeExecutionSourceRevision':'94b2b738bab122b615a9757cd97d02c86007212c','nativeRuns':44,'genuinelyTiltedTextInputs':33,'uprightControls':9,'blankNoiseControls':2,'strictQualifiedFiles':qualified,'conditionalRiskClosed':False,'wordArtifactComparisons':artifacts,'wordBeforeIdentity':identity(before),'wordAfterIdentity':identity(after),'wordBeforeResources':load(before/'resources.json'),'wordAfterResources':load(after/'resources.json'),'regressions':regressions,'limits':['All44 new inputs fail original strict fragmentation; exact parity does not establish safety for genuinely tilted qualifying pages.','The confirmed failure is fake-engine/real-Java evidence, not an observed native regression against1aee8c4.','Explicit validated flag protects legitimate already-ordered fragments and stays false for every fallback.','No numeric/confidence/geometry/deadline safeguard, table-intent model or OCR policy is changed.','Native installers, Microsoft Word, arbitrary Word rotation/editing/reflow and private handwriting remain unrun.']}
 out=ROOT/'docs/cloud-ocr-iteration12-results.json';out.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n');print(json.dumps({'httpContracts':44+16+sum(r['count'] for r in regressions.values()),'nativeRuns':44,'strictQualified':len(qualified),'conditionalRiskClosed':False,'wordArtifacts':len(artifacts)}))
if __name__=='__main__':main()
