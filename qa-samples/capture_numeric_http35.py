#!/usr/bin/env python3
"""Run the existing supervised HTTP runner and retain its transient synthetic OCR TSVs."""
import hashlib,json,pathlib,runpy,sys,threading
ROOT=pathlib.Path(__file__).resolve().parents[1]
def sha(data):return hashlib.sha256(data).hexdigest()
def main():
    out=pathlib.Path(sys.argv[sys.argv.index('--out')+1]).resolve();assert not out.exists()
    stop=threading.Event();seen=set();records=[];errors=[]
    def observe():
        while not stop.wait(.02):
            folder=out/'private-data/tasks'
            if not folder.exists():continue
            try:
                for p in folder.rglob('*.tsv'):
                    try:
                        relative=p.relative_to(folder);task=relative.parts[0];data=p.read_bytes()
                        if not data.startswith(b'level\tpage_num') or len(data)>4_000_000:continue
                        digest=sha(data);key=(task,str(relative),digest)
                        if key in seen:continue
                        if len(records)>=128:raise RuntimeError('capture budget exceeded')
                        dest=out/'ocr-capture'/task;dest.mkdir(parents=True,exist_ok=True);file=dest/(digest+'.tsv')
                        file.write_bytes(data);seen.add(key);records.append(dict(taskId=task,source=str(relative),artifact=str(file.relative_to(out)),sha256=digest,bytes=len(data)))
                    except FileNotFoundError:pass
            except Exception as e:errors.append(type(e).__name__+': '+str(e));return
    observer=threading.Thread(target=observe);observer.start()
    try:runpy.run_path(str(ROOT/'qa-samples/run_edit_iteration26.py'),run_name='__main__')
    finally:
        stop.set();observer.join(timeout=3);assert not observer.is_alive()
        (out/'ocr-capture.json').write_text(json.dumps(dict(records=records,errors=errors,observerStopped=True,wrapperSha256=sha(pathlib.Path(__file__).read_bytes()),engineSettingsChanged=False,extraOcrInvocations=0),indent=2)+'\n')
    assert not errors,errors
if __name__=='__main__':main()
