#!/usr/bin/env python3
import csv
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "qa-samples"
INPUT = BASE / "input"
OUTPUT = BASE / "output"
REPORT = BASE / "report"
WORK = BASE / "work"
PORT = int(os.environ.get("FORMAT_QA_PORT", "18081"))
BASE_URL = f"http://127.0.0.1:{PORT}"
JAVA = os.environ.get("JAVA_BIN", "java")
SOFFICE = os.environ.get("SOFFICE_BIN") or shutil.which("soffice")
PDFTOPPM = os.environ.get("PDFTOPPM_BIN") or shutil.which("pdftoppm")
JAR = ROOT / "web-api" / "target" / "web-api-0.1.0-SNAPSHOT.jar"

VISUAL_THRESHOLD = float(os.environ.get("FORMAT_QA_VISUAL_THRESHOLD", "0.001"))


def run(command, timeout=120):
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if completed.returncode != 0:
        raise RuntimeError(f"Command failed: {' '.join(map(str, command))}\n{completed.stdout}\n{completed.stderr}")
    return completed.stdout.strip()


def wait_for_health(process):
    deadline = time.time() + 45
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Service exited before health check passed")
        try:
            with urllib.request.urlopen(f"{BASE_URL}/api/health", timeout=2) as response:
                if response.status == 200:
                    return json.loads(response.read().decode("utf-8"))
        except Exception:
            time.sleep(1)
    raise RuntimeError("Timed out waiting for service health")


def start_service():
    data_root = BASE / "runtime-data"
    shutil.rmtree(data_root, ignore_errors=True)
    command = [
        JAVA, "-jar", str(JAR),
        f"--server.port={PORT}",
        f"--format-converter.data-root={data_root}",
        "--spring.main.banner-mode=off",
        "--logging.level.root=WARN",
    ]
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    health = wait_for_health(process)
    return process, health


def stop_service(process):
    if process.poll() is not None:
        return
    process.send_signal(signal.SIGINT)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def upload_convert(path, target):
    body = run([
        "curl", "-sS", "-X", "POST",
        "-F", f"files=@{path}",
        "-F", f"targetFormat={target}",
        f"{BASE_URL}/api/tasks",
    ], timeout=180)
    created = json.loads(body)
    task_id = created["taskId"]
    deadline = time.time() + 180
    while time.time() < deadline:
        with urllib.request.urlopen(f"{BASE_URL}/api/tasks/{task_id}", timeout=5) as response:
            task = json.loads(response.read().decode("utf-8"))
        if task["status"] in ("SUCCESS", "FAILED"):
            break
        time.sleep(0.5)
    else:
        raise RuntimeError(f"Task timed out: {task_id}")
    if task["status"] != "SUCCESS":
        return task, None
    name = task["downloadName"]
    out = OUTPUT / f"{path.stem}-to-{target}-{name}"
    with urllib.request.urlopen(f"{BASE_URL}/api/tasks/{task_id}/download", timeout=30) as response:
        out.write_bytes(response.read())
    return task, out


def office_pdf(source, directory):
    if not SOFFICE:
        raise RuntimeError("soffice not found")
    directory.mkdir(parents=True, exist_ok=True)
    profile = directory / "profile"
    profile.mkdir(parents=True, exist_ok=True)
    run([
        SOFFICE, "--headless", "--nologo", "--nodefault", "--nofirststartwizard", "--nolockcheck",
        f"-env:UserInstallation={profile.as_uri()}",
        "--convert-to", "pdf",
        "--outdir", str(directory),
        str(source),
    ], timeout=180)
    candidates = sorted(directory.glob("*.pdf"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not candidates:
        raise RuntimeError(f"No PDF produced for {source}")
    return candidates[0]


def render_pdf(pdf, directory, prefix):
    if not PDFTOPPM:
        raise RuntimeError("pdftoppm not found")
    directory.mkdir(parents=True, exist_ok=True)
    run([PDFTOPPM, "-r", "160", "-png", str(pdf), str(directory / prefix)], timeout=180)
    return sorted(directory.glob(f"{prefix}-*.png"))


def unzip_pngs(zip_path, directory):
    directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(directory)
    return sorted(directory.glob("*.png"))


def compare_images(expected, actual, diff_dir):
    diff_dir.mkdir(parents=True, exist_ok=True)
    if len(expected) != len(actual):
        return {"pageCountMatch": False, "differentPixels": None, "totalPixels": None, "diffRatio": 1.0}
    different = 0
    total = 0
    for index, (left_path, right_path) in enumerate(zip(expected, actual), start=1):
        left = Image.open(left_path).convert("RGB")
        right = Image.open(right_path).convert("RGB")
        width = max(left.width, right.width)
        height = max(left.height, right.height)
        canvas_left = Image.new("RGB", (width, height), "white")
        canvas_right = Image.new("RGB", (width, height), "white")
        canvas_left.paste(left, (0, 0))
        canvas_right.paste(right, (0, 0))
        delta = ImageChops.difference(canvas_left, canvas_right)
        pixels = width * height
        pixel_data = delta.get_flattened_data() if hasattr(delta, "get_flattened_data") else delta.getdata()
        diff_pixels = sum(1 for value in pixel_data if value != (0, 0, 0))
        if diff_pixels:
            delta.save(diff_dir / f"page-{index:04d}-diff.png")
        different += diff_pixels
        total += pixels
    ratio = different / total if total else 0
    return {"pageCountMatch": True, "differentPixels": different, "totalPixels": total, "diffRatio": ratio}


def visual_case(name, source, target, source_pdf=None):
    result = {"name": name, "source": source.name, "target": target, "type": "visual"}
    task, converted = upload_convert(source, target)
    result["taskStatus"] = task["status"]
    result["output"] = converted.name if converted else None
    if not converted:
        result["strictPass"] = False
        result["error"] = task.get("errorMessage") or task["files"][0].get("errorMessage")
        return result
    case_dir = WORK / name
    source_render_dir = case_dir / "source-render"
    target_render_dir = case_dir / "target-render"
    if source_pdf is None:
        source_pdf = office_pdf(source, case_dir / "source-pdf")
    expected = render_pdf(source_pdf, source_render_dir, "source")
    if converted.suffix.lower() == ".pdf":
        target_pdf = converted
        actual = render_pdf(target_pdf, target_render_dir, "target")
    elif converted.suffix.lower() == ".png":
        actual = [converted]
    elif converted.suffix.lower() == ".zip":
        actual = unzip_pngs(converted, target_render_dir)
    else:
        target_pdf = office_pdf(converted, case_dir / "target-pdf")
        actual = render_pdf(target_pdf, target_render_dir, "target")
    comparison = compare_images(expected, actual, REPORT / "diffs" / name)
    result.update(comparison)
    result["strictPass"] = comparison["pageCountMatch"] and comparison["differentPixels"] == 0
    result["practicalPass"] = comparison["pageCountMatch"] and comparison["diffRatio"] <= VISUAL_THRESHOLD
    return result


def csv_round_trip_case(source):
    result = {"name": "csv-xlsx-csv-roundtrip", "source": source.name, "target": "xlsx,csv", "type": "data"}
    task1, xlsx = upload_convert(source, "xlsx")
    result["firstTaskStatus"] = task1["status"]
    if not xlsx:
        result["strictPass"] = False
        result["error"] = task1.get("errorMessage")
        return result
    task2, csv_file = upload_convert(xlsx, "csv")
    result["secondTaskStatus"] = task2["status"]
    if not csv_file:
        result["strictPass"] = False
        result["error"] = task2.get("errorMessage")
        return result
    original_rows = list(csv.reader(source.open(newline="", encoding="utf-8-sig")))
    roundtrip_rows = list(csv.reader(csv_file.open(newline="", encoding="utf-8-sig")))
    result["rowCount"] = len(original_rows)
    result["roundTripRowCount"] = len(roundtrip_rows)
    result["strictPass"] = original_rows == roundtrip_rows
    return result


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    REPORT.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(WORK, ignore_errors=True)
    shutil.rmtree(REPORT / "diffs", ignore_errors=True)
    process = None
    results = []
    health = {}
    try:
        process, health = start_service()
        pdf_source = INPUT / "dummy.pdf"
        pdf_reference_dir = WORK / "dummy-reference"
        pdf_reference = pdf_source
        cases = [
            visual_case("docx-to-pdf-exact", INPUT / "demo.docx", "pdf"),
            visual_case("xlsx-to-pdf-exact", INPUT / "frictionless-sample.xlsx", "pdf"),
            visual_case("pptx-to-pdf-exact", INPUT / "microsoft-workshop.pptx", "pdf"),
            visual_case("wps-to-docx-exact", INPUT / "quanzhou-drug-retail.wps", "docx"),
        ]
        if (INPUT / "wps-template-newchart.et").is_file():
            cases.append(visual_case("et-to-xlsx-exact", INPUT / "wps-template-newchart.et", "xlsx"))
        if (INPUT / "wps-template-newfile.dps").is_file():
            cases.append(visual_case("dps-to-pptx-exact", INPUT / "wps-template-newfile.dps", "pptx"))
        if (INPUT / "libreoffice-generated-demo.uof").is_file():
            cases.append(visual_case("uof-to-docx-exact", INPUT / "libreoffice-generated-demo.uof", "docx"))
        cases.extend([
                visual_case("pdf-to-png-exact", pdf_source, "png", source_pdf=pdf_reference),
                visual_case("pdf-to-docx-exact", pdf_source, "docx", source_pdf=pdf_reference),
                visual_case("png-to-pdf-exact", INPUT / "w3c-home.png", "pdf", source_pdf=office_pdf(INPUT / "w3c-home.png", pdf_reference_dir / "png-source-pdf")),
                csv_round_trip_case(INPUT / "countries.csv"),
        ])
        results.extend(cases)
    finally:
        if process:
            stop_service(process)
    report = {
        "standard": "strictPass means zero differing pixels for visual tests or exact table data equality for data tests.",
        "visualThresholdForReferenceOnly": VISUAL_THRESHOLD,
        "health": sanitized_health(health),
        "results": results,
        "summary": {
            "total": len(results),
            "strictPassed": sum(1 for item in results if item.get("strictPass")),
            "strictFailed": sum(1 for item in results if not item.get("strictPass")),
        },
    }
    (REPORT / "qa-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# Format Converter QA Report",
        "",
        f"- Total: {report['summary']['total']}",
        f"- Strict passed: {report['summary']['strictPassed']}",
        f"- Strict failed: {report['summary']['strictFailed']}",
        f"- Office available: {health.get('office', {}).get('available')}",
        "",
        "| Case | Type | Strict | Practical | Diff ratio | Output |",
        "| --- | --- | --- | --- | ---: | --- |",
    ]
    for item in results:
        lines.append("| {name} | {type} | {strict} | {practical} | {ratio:.8f} | {output} |".format(
            name=item["name"],
            type=item["type"],
            strict="PASS" if item.get("strictPass") else "FAIL",
            practical="PASS" if item.get("practicalPass", item.get("strictPass")) else "FAIL",
            ratio=float(item.get("diffRatio") or 0),
            output=item.get("output") or "",
        ))
    (REPORT / "qa-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False))
    return 0 if report["summary"]["strictFailed"] == 0 else 1


def sanitized_health(health):
    value = json.loads(json.dumps(health))
    office = value.get("office")
    if isinstance(office, dict) and office.get("binary"):
        office["binary"] = Path(office["binary"]).name
    return value


if __name__ == "__main__":
    sys.exit(main())
