#!/usr/bin/env python3
"""Verify durable warnings and unchanged frozen Word/Office contents; no engine calls.

Requires the completed iteration40 normal, edited, render and after HTTP evidence.
Writes fresh after renders and public results once; never reruns conversions/OCR.
"""
import hashlib, io, json, xml.etree.ElementTree as E, zipfile
from pathlib import Path
import fitz
from PIL import Image, ImageChops
from qa_process_guard import matches
from verify_numeric35 import packaged

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT/'qa-samples/work'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
DC = '{http://purl.org/dc/elements/1.1/}'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text())
def parts(path):
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}
def compact(text): return ''.join(text.split())
def report(name, count):
    folder = WORK/name
    value = read(folder/'report.json')
    assert value['status'] == 'completed' and not value['failures']
    assert len(value['cases']) == count == len(value['workerIdentities'])
    assert not value['newZombies'] and value['supervision']['waitpidNoChildren']
    assert all(not matches(identity) for identity in value['workerIdentities'])
    for case in value['cases']:
        assert case['task']['status'] == 'SUCCESS' and sha(folder/case['artifact']) == case['sha256']
    return folder, value, {case['case']: case for case in value['cases']}
def complete_tsv(folder, task):
    capture = read(folder/'ocr-capture.json')
    assert capture['observerStopped'] and not capture['errors']
    candidates = [folder/row['artifact'] for row in capture['records'] if row['taskId'] == task]
    assert candidates
    path = max(candidates, key=lambda p: p.stat().st_size)
    data = path.read_bytes()
    assert data.startswith(b'level\tpage_num') and data.endswith(b'\n')
    return sha(path)
def value_lines(page, value):
    # Full line equality avoids ambiguous substring matches (10 versus 10%).
    return [line['bbox'] for block in page.get_text('dict')['blocks']
            if 'lines' in block for line in block['lines']
            if ''.join(span['text'] for span in line['spans']) == value]

def main():
    out = WORK/'iteration40-after-render'; out.mkdir(exist_ok=False)
    truth = read(ROOT/'qa-samples/generated/conflict-word40/expected.json')
    frozen = ROOT/'qa-samples/generated/conflict-word40'
    for name, digest in truth['sources'].items(): assert sha(frozen/name) == digest
    before, rb, cb = report('iteration40-normal-http', 8)
    after, ra, ca = report('iteration40-after-http', 8)
    edited, re, ce = report('iteration40-edits-http', 7)
    baseline = read(WORK/'iteration40-render/evidence.json')
    assert baseline['extraDiagnosticRoiOcr'] == 11
    assert baseline['helperSha256'] == sha(ROOT/'qa-samples/render_conflict_word40.py')
    provenance = read(WORK/'iteration40-fixed-provenance.json')
    assert ra['jarSha256'] == provenance['jarSha256'] != rb['jarSha256'] == re['jarSha256']
    edits_manifest = read(ROOT/'qa-samples/generated/conflict-word40-edits/expected.json')
    assert not edits_manifest['unsupportedEdits']
    package0 = packaged(WORK/'iteration40-before.jar'); package1 = packaged(ROOT/'web-api/target/web-api-0.1.5.jar')
    assert package0.keys() == package1.keys()
    changed_classes = [part for part in package0 if package0[part] != package1[part]]
    expected_classes = ['PoiDocxRenderer.class','PoiDocxRenderer$1.class','PoiDocxRenderer$PositionedContent.class','PoiDocxRenderer$PositionedPage.class']
    assert sorted(changed_classes) == sorted('BOOT-INF/lib/docx-renderer-0.1.5.jar/com/fuyue/formatconverter/docx/'+name for name in expected_classes), changed_classes
    prior_build = read(ROOT/'docs/cloud-postfix39-results.json')
    tests = dict(tests=0,failures=0,errors=0,skipped=0); testcases = []
    for path in ROOT.glob('*/target/surefire-reports/TEST-*.xml'):
        root = E.parse(path).getroot()
        for key in tests: tests[key] += int(root.get(key, '0'))
        testcases.extend(dict(className=node.get('classname'),name=node.get('name'),skipped=node.find('skipped') is not None)
                         for node in root.findall('testcase'))
    assert tests == dict(tests=505,failures=0,errors=0,skipped=1), tests
    bundled = []
    for expected in prior_build['bundledTests']:
        actual = next(t for t in testcases if t['className']==expected['className'] and t['name']==expected['name'])
        assert not actual['skipped']; bundled.append(actual)
    runtime_root = WORK/'iteration25-review-app/ocr'; runtime_manifest = read(runtime_root/'OCR-RUNTIME.json')
    for payload in runtime_manifest['files']:
        path = runtime_root/payload['path']; assert sha(path)==payload['sha256'] and path.stat().st_size==payload['size']
    fonts = prior_build['fontsRetainedFromAccepted35']
    for font in [fonts['source'], *fonts['bundled']]:
        path = Path(font['path']); assert sha(path if path.is_absolute() else ROOT/path)==font['sha256']
    rows = []
    for case in truth['cases']:
        name = case['id']; old = parts(before/cb[name+'-word']['artifact']); new = parts(after/ca[name+'-word']['artifact'])
        assert old.keys() == new.keys()
        differences = [part for part in old if old[part] != new[part]]
        assert differences == ['docProps/core.xml'], differences
        core0 = E.fromstring(old['docProps/core.xml']); core1 = E.fromstring(new['docProps/core.xml'])
        assert core0.find(DC+'subject') is None and core0.find(DC+'description') is None
        subject = core1.findtext(DC+'subject'); description = core1.findtext(DC+'description')
        assert subject == ('OCR 数值冲突：需人工核对' if case['expectedConflict'] else 'OCR 扫描文字：需人工核对')
        assert '扫描像素不会随文字编辑变化' in description and '同位置内容可能叠印' in description
        assert ('未选择或规范化' in description) == case['expectedConflict']
        text = ''.join(n.text or '' for n in E.fromstring(new['word/document.xml']).iter(W+'t'))
        assert compact(case['nativeValue']) in compact(text)
        if case['expectedConflict']: assert compact(case['rasterValue']) in compact(text)
        warnings0 = cb[name+'-word']['task']['warnings']; warnings1 = ca[name+'-word']['task']['warnings']
        assert warnings0 == warnings1
        assert any(w['code']=='OCR_RECOGNITION_CONFLICT' for w in warnings1) == case['expectedConflict']
        tsv0 = complete_tsv(before, cb[name+'-word']['task']['taskId']); tsv1 = complete_tsv(after, ca[name+'-word']['task']['taskId'])
        assert tsv0 == tsv1
        scans = []
        for part, data in new.items():
            if part.startswith('word/media/'):
                image = Image.open(io.BytesIO(data)).convert('RGB'); original = Image.open(frozen/(name+'.png')).convert('RGB')
                if image.size == original.size:
                    assert ImageChops.difference(image, original).getbbox() is None
                    scans.append(dict(part=part, sourcePixelsExact=True, size=list(image.size)))
        assert len(scans) == 1
        with fitz.open(before/cb[name+'-office']['artifact']) as oldpdf, fitz.open(after/ca[name+'-office']['artifact']) as pdf:
            assert len(oldpdf) == len(pdf) == 1 and oldpdf[0].rect == pdf[0].rect
            oldpixels = oldpdf[0].get_pixmap(dpi=300, alpha=False); pixels = pdf[0].get_pixmap(dpi=300, alpha=False)
            assert pixels.samples == oldpixels.samples and pixels.width == oldpixels.width and pixels.height == oldpixels.height
            assert pdf[0].get_text() == oldpdf[0].get_text()
            render = out/(name+'.png'); pixels.save(render)
            subject_pdf = pdf.metadata.get('subject'); assert subject_pdf == subject
            geometry = {value:value_lines(pdf[0], value) for value in [case['nativeValue'], case['rasterValue']]}
            old_pdf_metadata = oldpdf.metadata; new_pdf_metadata = pdf.metadata
        editrows = []
        for edit in case['edits']:
            path = ROOT/'qa-samples/generated/conflict-word40-edits'/(edit['id']+'.docx')
            changed = parts(path); assert changed.keys() == old.keys()
            assert [part for part in old if old[part] != changed[part]] == ['word/document.xml']
            root = E.fromstring(old['word/document.xml']); nodes = [n for n in root.iter(W+'t') if n.text == edit['old']]
            assert len(nodes) == 1; nodes[0].text = edit['new']
            assert E.tostring(root) == E.tostring(E.fromstring(changed['word/document.xml']))
            actual = next(row for row in baseline['rows'] if row['id']==name)['pdfs'][edit['id']]
            assert edit['new'] in actual['nativeText'] and sha(edited/ce[edit['id']]['artifact']) == actual['sha256']
            editrows.append(dict(**edit, exactOneNodeEdit=True, otherPartsByteExact=True, officeNativeText=actual['nativeText'],
                                 visibleRoiOcr=actual['actualVisibleRoiOcr'], visibleNumericAcceptance=False,
                                 afterMetadataEditedOfficeRerun=False))
        prior = next(row for row in baseline['rows'] if row['id']==name)
        rows.append(dict(id=name, expectedConflict=case['expectedConflict'], nativeSource=case['nativeValue'], rasterSource=case['rasterValue'],
            changedWordParts=differences, wordSubject=subject, wordDescription=description, apiWarnings=warnings1,
            wordBodyAllOtherPartsByteExact=True, fullOcrTsvSha256=tsv1, originalScans=scans, sourceStylesMasksBoxesUnchanged=True,
            office300DpiPixelsExact=True, officeNativeTextExact=True, normalVisibleRoiOcr=prior['pdfs']['normal']['actualVisibleRoiOcr'],
            visibleNumericAcceptance=False, exactValueLineBoxes=geometry, beforePdfMetadata=old_pdf_metadata,
            afterPdfMetadata=new_pdf_metadata, afterRenderSha256=sha(render), edits=editrows))
    result = dict(parentRevision=truth['parentRevision'], beforeJarSha256=rb['jarSha256'], afterJarSha256=ra['jarSha256'],
        buildInputSha256=provenance['buildInputSha256'], applicationClassCount=provenance['applicationClassCount'],
        frozenManifestSha256=sha(frozen/'expected.json'), rows=rows,
        requestCounts=dict(beforeNormal=8,beforeEditedOffice=7,afterNormal=8,total=23,sourceOcr=8,office=15,diagnosticRoiOcr=11,replayOcr=0),
        durableWordWarnings=dict(before=0,after=4), durableOfficePdfSubjects=dict(before=0,after=4),
        warningOnlyImprovement=True, visibleNumericAcceptance=False, editedVisibleNumericAcceptance=False,
        numericCerChangeClaim=False, ocrTsvExactControls=4, bodyAndFullRenderExactControls=4,
        changedClasses=changed_classes, other228ClassesAndAllApplicationResourcesByteExact=True,
        fullSuite=tests, bundledTests=bundled, skips=[t for t in testcases if t['skipped']],
        resources={name:record['resources'] for name,record in [('beforeNormal',rb),('beforeEdits',re),('afterNormal',ra)]},
        runtime=dict(ocr=ra['health']['ocr'],office=ra['health']['office'],pinnedEngineSha256=baseline['pinnedEngineSha256'],
            pinnedRuntimeManifestSha256=baseline['pinnedRuntimeManifestSha256'], manifest=runtime_manifest,
            payloadFilesReverified=len(runtime_manifest['files'])),
        versions=prior_build['versions'], versionsSource='unchanged tools from accepted39; current HTTP independently reports OCR/Office versions',
        fontsReverified=fonts,
        helperSha256={name:sha(ROOT/'qa-samples'/name) for name in ['prepare_conflict_word40.py','render_conflict_word40.py','verify_conflict_word40.py']},
        unrun=['after-metadata seven edited Office conversions (body/styles/scans unchanged; prior edits retained)',
               'Microsoft Word and native macOS/Windows packages','optional signed OFD fixture'],
        limits=['metadata warning is not an in-page banner and does not repair overprinting; Office PDF retains subject but not full Word description',
                'source images do not change when editable text changes; neither source is canonical financial truth',
                'ROI PSM6 is diagnostic and not directly comparable with source full-page PSM3 CER',
                'original sparse OFD, adjacent long edits, CJK/prior mixed strict API visibility and per-mille remain limited'])
    (ROOT/'docs/cloud-conflict-word40-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('Durable Word/PDF warnings 0→4; body/source/styles/TSV/full-render 4 exact; frozen edits7 verified; visible acceptance FALSE')

if __name__ == '__main__': main()
