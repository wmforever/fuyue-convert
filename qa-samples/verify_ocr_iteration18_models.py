#!/usr/bin/env python3
"""Frozen OFD outcome/word/confidence/coordinate warning regression assertions."""
import json,pathlib,collections,math
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text())

def verify(folder,layout,mode):
    truth=load(ROOT/'qa-samples/generated/cloud-iteration18/expected.json');r=load(folder/'model.json')
    assert len(r['pages'])==(1 if layout=='same' else 2)
    checks=[]
    for page in r['pages']:
        p=page['pageNumber'];names=['full','partial','reject','low'] if layout=='same' else ['full','partial'] if p==1 else ['reject','low']
        assert len(page['images'])==len(names)
        all_words=[]
        for index,name in enumerate(names,1):
            box=page['images'][index-1]['box'];assert page['images'][index-1]['role']=='OCR_SCAN_BACKGROUND'
            # The application's image suffix preserves the recognized candidate index.
            blocks=[b for b in page['blocks'] if b['id'].endswith('-i'+str(index))]
            words=[w for b in blocks for w in b['ocrWords']]
            rows=truth['selectedRows'][name] if mode=='treatment' else truth['originalRows'][name]
            assert [w['text'] for w in words]==[row[11] for row in rows],(layout,mode,p,name,[w['text'] for w in words])
            for w,row in zip(words,rows):
                assert abs(w['confidence']-row[10]/100)<1e-9,(name,w,row)
                expected={'x':box['x']+row[6]*box['width']/1200,'y':box['y']+row[7]*box['height']/1200,
                    'width':row[8]*box['width']/1200,'height':row[9]*box['height']/1200}
                assert all(abs(w['box'][key]-value)<1e-6 for key,value in expected.items()),(name,w,expected)
            all_words+=words;prefix=f'OFD 第 {p} 页图片 {index}'
            warnings=[w for w in page['warnings'] if w['message'].startswith(prefix)]
            codes=collections.Counter(w['code'] for w in warnings)
            expected_codes=collections.Counter()
            mean=sum(row[10]/100 for row in rows)/len(rows)
            if mean<.75:expected_codes['OCR_LOW_CONFIDENCE']=1
            if mode=='treatment' and name in ['full','partial']:expected_codes['OCR_IMAGE_ENHANCED']=1
            if mode=='treatment' and name=='partial':
                expected_codes.update({'OCR_RECOGNITION_CONFLICT':1,'OCR_POSSIBLE_TEXT_OMISSION':1})
                assert all('严格分离的新行' in w['message'] for w in warnings if w['code'] in ['OCR_IMAGE_ENHANCED','OCR_POSSIBLE_TEXT_OMISSION'])
                assert '.95' in [w['text'] for w in words] and '0.95' not in [w['text'] for w in words]
            else:assert all('严格分离的新行' not in w['message'] for w in warnings)
            assert codes==expected_codes,(layout,mode,p,name,codes,expected_codes)
            for w in warnings:
                assert w['pageNumber']==p
                if w['code']=='OCR_LOW_CONFIDENCE':assert abs(w['confidence']-mean)<1e-9
            if name=='reject':assert 'ID00424' in [w['text'] for w in words] and 'ID80424' not in [w['text'] for w in words]
            checks.append({'page':p,'image':index,'name':name,'words':len(words),'mean':mean,'codes':dict(codes),'pixelMappedBoxesExact':True})
        applied=[w for w in page['warnings'] if w['code']=='OCR_APPLIED'];assert len(applied)==1
        mean=sum(w['confidence'] for w in all_words)/len(all_words)
        assert abs(applied[0]['confidence']-mean)<1e-9
        assert '图片' not in applied[0]['message']
    return {'layout':layout,'mode':mode,'checks':checks,'pages':len(r['pages']),'selectedWordsCoordinatesConfidencesAndScopedWarningsExact':True}

if __name__=='__main__':
    results=[]
    for layout in ['same','multi']:
        for mode in ['control','treatment']:
            results.append(verify(ROOT/'qa-samples/work/iteration18-models'/layout/mode,layout,mode))
    (ROOT/'qa-samples/work/iteration18-model-checks.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(results))
