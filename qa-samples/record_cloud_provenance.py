#!/usr/bin/env python3
"""Bind a cloud JAR to unchanged build inputs, fresh target classes, and a Git revision.

Run --before before mvn clean test/package, then --after. After committing, use
--verify-revision SHA on the same record. No credentials/environment are captured.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(data): return hashlib.sha256(data).hexdigest()
def git(*args): return subprocess.check_output(['git', *args], cwd=ROOT).decode().strip()


def inputs():
    paths = [ROOT/'pom.xml']
    for module in ROOT.iterdir():
        if not module.is_dir() or not (module/'pom.xml').is_file(): continue
        paths.append(module/'pom.xml')
        if (module/'src/main').is_dir(): paths.extend(p for p in (module/'src/main').rglob('*') if p.is_file())
    return {str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in sorted(paths)}


def fingerprint(values): return sha(json.dumps(values, sort_keys=True, separators=(',',':')).encode())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record',type=Path,required=True)
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--before',action='store_true');mode.add_argument('--after',action='store_true')
    mode.add_argument('--verify-revision')
    parser.add_argument('--jar',type=Path,default=ROOT/'web-api/target/web-api-0.1.5.jar')
    args=parser.parse_args(); current=inputs()
    if args.before:
        record={'buildInputs':current,'buildInputSha256':fingerprint(current),'parentRevision':git('rev-parse','HEAD'),
                'workingTreeDirtyAtBuildStart':bool(git('status','--porcelain'))}
        args.record.parent.mkdir(parents=True,exist_ok=True)
    else:
        record=json.loads(args.record.read_text())
        assert current==record['buildInputs'],'Build inputs changed since --before; rebuild required'
        if args.after:
            targets={}
            for module in ROOT.iterdir():
                classes=module/'target/classes'
                if not classes.is_dir() or not (module/'pom.xml').is_file():continue
                for path in classes.rglob('*.class'):
                    targets[module.name+'/'+str(path.relative_to(classes))]=sha(path.read_bytes())
            packaged={}
            with zipfile.ZipFile(args.jar) as jar:
                for name in jar.namelist():
                    if name.startswith('BOOT-INF/classes/') and name.endswith('.class'):
                        packaged['web-api/'+name.removeprefix('BOOT-INF/classes/')]=sha(jar.read(name))
                    elif name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                        module=next((m.name for m in ROOT.iterdir() if (m/'pom.xml').is_file()
                                     and Path(name).name.startswith(m.name+'-')),None)
                        if module:
                            with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested:
                                for entry in nested.namelist():
                                    if entry.endswith('.class'):packaged[module+'/'+entry]=sha(nested.read(entry))
            assert packaged==targets,'Packaged application classes differ from current build targets'
            record.update(jarSha256=sha(args.jar.read_bytes()),applicationClassCount=len(packaged),
                          applicationClassSha256=fingerprint(packaged),packagedClassesMatchTargets=True)
        else:
            revision=git('rev-parse',args.verify_revision)
            for path, expected in current.items():
                actual=subprocess.check_output(['git','show',revision+':'+path],cwd=ROOT)
                assert sha(actual)==expected,'Committed source mismatch: '+path
            assert sha(args.jar.read_bytes())==record['jarSha256'],'JAR changed after acceptance'
            record['verifiedCodeRevision']=revision
    args.record.write_text(json.dumps(record,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps({k:v for k,v in record.items() if k!='buildInputs'},indent=2))


if __name__=='__main__':main()
