#!/usr/bin/env python3
"""Bind final white-foreground fallback to unchanged accepted light-paper payloads."""
from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET
import fitz
from verify_edit_iteration26 import parts,sha
from qa_process_guard import matches,snapshot
ROOT=Path(__file__).resolve().parents[1]
def substantive(path):
    data=parts(path)
    # Core timestamps are volatile; all other core metadata must still match.
    if 'docProps/core.xml' in data:
        root=ET.fromstring(data['docProps/core.xml'])
        for n in root:
            if n.tag in ['{http://purl.org/dc/terms/}created','{http://purl.org/dc/terms/}modified']:n.text='VOLATILE-TIMESTAMP'
        data['docProps/core.xml']=ET.tostring(root)
    return data

def main():
    final=ROOT/'qa-samples/report/iteration26-final';report=json.loads((final/'report.json').read_text());assert report['status']=='completed' and len(report['cases'])==11 and not report['failures'] and not report['newZombies'];assert report['supervision']['waitpidNoChildren'];assert not any(matches(v) for v in report['workerIdentities'])
    frozen=json.loads((ROOT/'qa-samples/generated/edit-iteration26-pdf/expected.json').read_text());rows=[]
    for c in frozen['cases']:
        file=c['id']+'-word-result.docx';candidate=ROOT/'qa-samples/report/iteration26-scan-after'/file;current=final/file
        a,b=substantive(candidate),substantive(current);assert a==b,c['id'];rows.append({'case':c['id'],'candidateSha256':sha(candidate),'finalSha256':sha(current),'allSubstantivePartsExact':True,'partHashes':{k:hashlib.sha256(v).hexdigest() for k,v in b.items()}})
    original=ROOT/'qa-samples/report/iteration26-original-after';originalChecks=[]
    for file in ['original-word-result.docx','original-edited-office-edited.docx']:assert substantive(original/file)==substantive(final/file)
    for file in ['original-office-result.pdf','original-edited-office-result.pdf']:
        with fitz.open(original/file) as a,fitz.open(final/file) as b:
            assert len(a)==len(b)==1;assert a[0].get_pixmap(dpi=300,alpha=False).samples==b[0].get_pixmap(dpi=300,alpha=False).samples;assert a[0].get_text('words')==b[0].get_text('words');originalChecks.append({'artifact':file,'candidateSha256':sha(original/file),'finalSha256':sha(final/file),'visible300DpiPixelsAndNativeWordBoxesExact':True})
    assert (original/'original-edited-text-result.txt').read_bytes()==(final/'original-edited-text-result.txt').read_bytes()
    old=json.loads((ROOT/'qa-samples/work/iteration22-final-check.json').read_text())['allCurrentZombieIdentities'];assert len([v for v in snapshot().values() if v['state']=='Z'])==len(old)==17 and all(matches(v) for v in old)
    result={'candidateRevision':'096f0eda722c626e9680268846979c19c9ee054f','candidateCi':{'run':37207251967,'officeJob':111450914787,'status':'failed dark-paper white-text visibility on LibreOffice 24.2.7; original assertions retained'},'finalJarSha256':report['jarSha256'],'finalManifestSha256':report['manifestSha256'],'httpContracts':11,'workersAbsent':11,'ECHILD':True,'newZombies':[],'historicalZombiesUnchanged':17,'resources':report['resources'],'lightPaperWordPayloads':rows,'originalRegression':originalChecks,'originalApiTextBytesUnchangedStillIncorrect':True,'qualification':'Seven existing light-paper Office/OCR measurements transfer through exact substantive DOCX parts. Original full chain rerun on final JAR; Office pixels and native boxes exact. No final seven-case Office matrix rerun.'}
    (ROOT/'docs/cloud-edit-final26-results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'finalJarSha256':report['jarSha256'],'payloadsExact':len(rows),'originalPixelsExact':len(originalChecks),'httpContracts':11,'cleanup':'ECHILD,11 workers absent,17 old zombies unchanged'}))
if __name__=='__main__':main()
