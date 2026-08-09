#!/usr/bin/env python3
import csv
import io
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "qa-samples"
INPUT = BASE / "input"
OUTPUT = BASE / "output"
REPORT = BASE / "report"
WORK = BASE / "work"
def available_port():
    configured = os.environ.get("FORMAT_QA_PORT")
    if configured:
        return int(configured)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


PORT = available_port()
BASE_URL = f"http://127.0.0.1:{PORT}"
JAVA = os.environ.get("JAVA_BIN", "java")
SOFFICE = os.environ.get("SOFFICE_BIN") or shutil.which("soffice")
PDFTOPPM = os.environ.get("PDFTOPPM_BIN") or shutil.which("pdftoppm")
JAR = ROOT / "web-api" / "target" / "web-api-0.1.1.jar"

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


def snapshot_service_jar(destination):
    for attempt in range(5):
        try:
            shutil.copy2(JAR, destination)
            with zipfile.ZipFile(destination) as archive:
                if archive.testzip() is not None or "BOOT-INF/classpath.idx" not in archive.namelist():
                    raise zipfile.BadZipFile("incomplete Spring Boot JAR")
            return
        except (OSError, zipfile.BadZipFile):
            destination.unlink(missing_ok=True)
            if attempt == 4:
                raise RuntimeError("Could not create a stable executable JAR snapshot; another build may be replacing it")
            time.sleep(0.5)


def start_service():
    runtime_root = BASE / "runtime-data"
    shutil.rmtree(runtime_root, ignore_errors=True)
    runtime_root.mkdir(parents=True, exist_ok=True)
    launch_jar = runtime_root / "qa-service.jar"
    snapshot_service_jar(launch_jar)
    data_root = runtime_root / "data"
    command = [
        JAVA, "-jar", str(launch_jar),
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
        "curl", "-fsS", "-X", "POST",
        "-F", f"files=@{path}",
        "-F", f"targetFormat={target}",
        f"{BASE_URL}/api/tasks",
    ], timeout=180)
    created = json.loads(body)
    if "taskId" not in created:
        raise RuntimeError(f"Task creation returned no taskId: {body[:1000]}")
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


def office_export(source, directory, target):
    if not SOFFICE:
        raise RuntimeError("soffice not found")
    directory.mkdir(parents=True, exist_ok=True)
    profile = directory / "profile"
    profile.mkdir(parents=True, exist_ok=True)
    run([
        SOFFICE, "--headless", "--nologo", "--nodefault", "--nofirststartwizard", "--nolockcheck",
        f"-env:UserInstallation={profile.as_uri()}",
        "--convert-to", target,
        "--outdir", str(directory),
        str(source),
    ], timeout=180)
    suffix = target.split(":", 1)[0]
    candidates = sorted(directory.glob(f"*.{suffix}"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not candidates:
        raise RuntimeError(f"No {suffix} produced for {source}")
    return candidates[0]


def render_pdf(pdf, directory, prefix):
    if not PDFTOPPM:
        raise RuntimeError("pdftoppm not found")
    directory.mkdir(parents=True, exist_ok=True)
    run([PDFTOPPM, "-r", "160", "-png", str(pdf), str(directory / prefix)], timeout=180)
    return sorted(directory.glob(f"{prefix}-*.png"))


def unzip_images(zip_path, directory):
    directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(directory)
    return sorted(path for path in directory.iterdir() if path.suffix.lower() in (".png", ".jpg", ".jpeg"))


def compare_image_values(expected, actual, diff_dir):
    diff_dir.mkdir(parents=True, exist_ok=True)
    if len(expected) != len(actual):
        return {"pageCountMatch": False, "differentPixels": None, "totalPixels": None, "diffRatio": 1.0}
    different = 0
    total = 0
    for index, (left, right) in enumerate(zip(expected, actual), start=1):
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


def compare_images(expected, actual, diff_dir):
    left = [Image.open(path).convert("RGB") for path in expected]
    right = [Image.open(path).convert("RGB") for path in actual]
    return compare_image_values(left, right, diff_dir)


def compare_docx_page_layers(expected, docx, diff_dir):
    with zipfile.ZipFile(docx) as archive:
        names = [name for name in archive.namelist() if re.fullmatch(r"word/media/image\d+\.png", name)]
        names.sort(key=lambda name: int(re.search(r"(\d+)\.png$", name).group(1)))
        actual = [Image.open(io.BytesIO(archive.read(name))).convert("RGB") for name in names]
    left = [Image.open(path).convert("RGB") for path in expected]
    return compare_image_values(left, actual, diff_dir)


def docx_text_content(docx):
    namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    with zipfile.ZipFile(docx) as archive:
        root = ElementTree.fromstring(archive.read("word/document.xml"))
    return "".join(node.text or "" for node in root.iter(f"{namespace}t"))


def docx_media_entries(docx):
    with zipfile.ZipFile(docx) as archive:
        return [name for name in archive.namelist() if name.startswith("word/media/")]


def normalized_character_counts(value):
    return Counter(character for character in value if not character.isspace())


def compare_office_exports(source, converted, target, directory):
    left = office_export(source, directory / "source", target)
    right = office_export(converted, directory / "target", target)
    if target.startswith("csv"):
        left_value = list(csv.reader(left.open(newline="", encoding="utf-8-sig")))
        right_value = list(csv.reader(right.open(newline="", encoding="utf-8-sig")))
    else:
        left_value = "".join(left.read_text(encoding="utf-8", errors="replace").split())
        right_value = "".join(right.read_text(encoding="utf-8", errors="replace").split())
    return left_value == right_value


def image_mean_absolute_error(expected, actual):
    if len(expected) != len(actual):
        return float("inf")
    error = 0
    values = 0
    for left_path, right_path in zip(expected, actual):
        left = Image.open(left_path).convert("RGB")
        right = Image.open(right_path).convert("RGB")
        if left.size != right.size:
            return float("inf")
        histogram = ImageChops.difference(left, right).histogram()
        error += sum((index % 256) * count for index, count in enumerate(histogram))
        values += left.width * left.height * 3
    return error / values if values else 0.0


def visual_case(name, source, target, source_pdf=None, exact_mode="visual"):
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
    elif converted.suffix.lower() in (".png", ".jpg", ".jpeg"):
        actual = [converted]
    elif converted.suffix.lower() == ".zip":
        actual = unzip_images(converted, target_render_dir)
    else:
        target_pdf = office_pdf(converted, case_dir / "target-pdf")
        actual = render_pdf(target_pdf, target_render_dir, "target")
    comparison = compare_images(expected, actual, REPORT / "diffs" / name)
    result.update(comparison)
    result["visualDiffRatio"] = comparison["diffRatio"]
    result["visualPass"] = comparison["pageCountMatch"] and comparison["diffRatio"] <= VISUAL_THRESHOLD
    if exact_mode == "page-layer":
        layer = compare_docx_page_layers(expected, converted, REPORT / "diffs" / f"{name}-embedded")
        result["exactCheck"] = "embedded-page-pixels"
        result["embeddedDifferentPixels"] = layer["differentPixels"]
        result["strictPass"] = layer["pageCountMatch"] and layer["differentPixels"] == 0
    elif exact_mode == "text":
        result["exactCheck"] = "normalized-text"
        result["strictPass"] = compare_office_exports(
            source, converted, "txt", case_dir / "exact-text")
    elif exact_mode == "table-data":
        result["exactCheck"] = "first-sheet-csv-data"
        result["strictPass"] = comparison["pageCountMatch"] and compare_office_exports(
            source, converted, "csv", case_dir / "exact-table")
    elif exact_mode == "jpeg":
        result["exactCheck"] = "jpeg-mean-absolute-error"
        result["meanAbsoluteError"] = image_mean_absolute_error(expected, actual)
        result["strictPass"] = comparison["pageCountMatch"] and result["meanAbsoluteError"] <= 2.0
    else:
        result["exactCheck"] = "rendered-pixels"
        result["strictPass"] = comparison["pageCountMatch"] and comparison["differentPixels"] == 0
    result["practicalPass"] = result["visualPass"]
    return result


def ofd_docx_text_case(source):
    result = {"name": "ofd-to-docx-text-exact", "source": source.name, "target": "docx", "type": "content"}
    text_task, text_output = upload_convert(source, "txt")
    docx_task, docx_output = upload_convert(source, "docx")
    result["taskStatus"] = docx_task["status"]
    result["output"] = docx_output.name if docx_output else None
    result["exactCheck"] = "ofd-vs-docx-character-content"
    if not text_output or not docx_output:
        result["strictPass"] = False
        result["error"] = text_task.get("errorMessage") or docx_task.get("errorMessage")
        return result
    expected = normalized_character_counts(text_output.read_text(encoding="utf-8", errors="replace"))
    actual = normalized_character_counts(docx_text_content(docx_output))
    result["sourceCharacterCount"] = sum(expected.values())
    result["docxCharacterCount"] = sum(actual.values())
    result["strictPass"] = expected == actual
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    result["diffRatio"] = 0.0
    return result


def pdf_docx_editable_case(source):
    result = {"name": "pdf-to-docx-editable", "source": source.name, "target": "docx", "type": "content"}
    text_task, text_output = upload_convert(source, "txt")
    docx_task, docx_output = upload_convert(source, "docx")
    result["taskStatus"] = docx_task["status"]
    result["output"] = docx_output.name if docx_output else None
    result["exactCheck"] = "pdf-vs-docx-editable-character-content-no-images"
    if not text_output or not docx_output:
        result["strictPass"] = False
        result["error"] = text_task.get("errorMessage") or docx_task.get("errorMessage")
        return result

    expected = normalized_character_counts(text_output.read_text(encoding="utf-8", errors="replace"))
    actual = normalized_character_counts(docx_text_content(docx_output))
    media = docx_media_entries(docx_output)
    case_dir = WORK / result["name"]
    expected_pages = render_pdf(source, case_dir / "source-render", "source")
    target_pdf = office_pdf(docx_output, case_dir / "target-pdf")
    actual_pages = render_pdf(target_pdf, case_dir / "target-render", "target")
    comparison = compare_images(expected_pages, actual_pages, REPORT / "diffs" / result["name"])
    result["sourceCharacterCount"] = sum(expected.values())
    result["docxCharacterCount"] = sum(actual.values())
    result["embeddedMedia"] = media
    result["pageCountMatch"] = comparison["pageCountMatch"]
    result["diffRatio"] = comparison["diffRatio"]
    result["visualPass"] = comparison["pageCountMatch"] and comparison["diffRatio"] <= VISUAL_THRESHOLD
    result["strictPass"] = expected == actual and not media and comparison["pageCountMatch"]
    result["practicalPass"] = result["strictPass"]
    return result


def pdf_docx_ocr_required_case(source):
    result = {"name": "pdf-to-docx-ocr-required", "source": source.name,
              "target": "docx", "type": "failure-contract"}
    task, output = upload_convert(source, "docx")
    files = task.get("files") or []
    file_result = files[0] if files else {}
    result["taskStatus"] = task.get("status")
    result["errorCode"] = file_result.get("errorCode")
    result["downloadProduced"] = output is not None
    result["exactCheck"] = "image-only-pdf-must-fail-with-ocr-required"
    result["strictPass"] = (task.get("status") == "FAILED"
                            and result["errorCode"] == "OCR_REQUIRED"
                            and output is None)
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
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
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
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
        pdf_editable_text = WORK / "pdf-editable-source.txt"
        pdf_editable_text.parent.mkdir(parents=True, exist_ok=True)
        pdf_editable_text.write_text("\n".join(
            f"PDF 可编辑 Word 验收行 {index:02d} / Editable text line {index:02d}"
            for index in range(1, 66)), encoding="utf-8")
        editable_source_task, pdf_editable_source = upload_convert(pdf_editable_text, "pdf")
        if not pdf_editable_source:
            raise RuntimeError("Could not create text-only PDF QA source: "
                               + str(editable_source_task.get("errorMessage")))
        scanned_source_task, pdf_scanned_source = upload_convert(INPUT / "w3c-home.png", "pdf")
        if not pdf_scanned_source:
            raise RuntimeError("Could not create image-only PDF QA source: "
                               + str(scanned_source_task.get("errorMessage")))
        cases = [
            visual_case("docx-to-pdf-exact", INPUT / "demo.docx", "pdf"),
            visual_case("xlsx-to-pdf-exact", INPUT / "frictionless-sample.xlsx", "pdf"),
            visual_case("pptx-to-pdf-exact", INPUT / "microsoft-workshop.pptx", "pdf"),
            visual_case("wps-to-docx-exact", INPUT / "quanzhou-drug-retail.wps", "docx", exact_mode="text"),
        ]
        if (INPUT / "wps-template-newchart.et").is_file():
            cases.append(visual_case("et-to-xlsx-exact", INPUT / "wps-template-newchart.et", "xlsx",
                                     exact_mode="table-data"))
        if (INPUT / "wps-template-newfile.dps").is_file():
            cases.append(visual_case("dps-to-pptx-exact", INPUT / "wps-template-newfile.dps", "pptx"))
        if (INPUT / "libreoffice-generated-demo.uof").is_file():
            cases.append(visual_case("uof-to-docx-exact", INPUT / "libreoffice-generated-demo.uof", "docx",
                                     exact_mode="text"))
        cases.extend([
                visual_case("pdf-to-png-exact", pdf_source, "png", source_pdf=pdf_reference),
                visual_case("pdf-to-jpeg-quality", pdf_source, "jpg", source_pdf=pdf_reference,
                            exact_mode="jpeg"),
                pdf_docx_editable_case(pdf_editable_source),
                pdf_docx_ocr_required_case(pdf_scanned_source),
                visual_case("png-to-pdf-exact", INPUT / "w3c-home.png", "pdf", source_pdf=office_pdf(INPUT / "w3c-home.png", pdf_reference_dir / "png-source-pdf")),
                csv_round_trip_case(INPUT / "countries.csv"),
        ])
        if (INPUT / "ofdrw-invoice.ofd").is_file():
            cases.append(ofd_docx_text_case(INPUT / "ofdrw-invoice.ofd"))
        results.extend(cases)
    finally:
        if process:
            stop_service(process)
    report = {
        "standard": "strictPass uses route-specific exactness: rendered pixels for direct fidelity routes, normalized text for editable documents, and table data for spreadsheets. Editable PDF-to-DOCX additionally requires matching page count and no embedded media for a generated text-only source; an image-only PDF must fail with OCR_REQUIRED. JPEG uses a declared lossy-error bound.",
        "visualThresholdForReferenceOnly": VISUAL_THRESHOLD,
        "health": sanitized_health(health),
        "results": results,
        "summary": {
            "total": len(results),
            "strictPassed": sum(1 for item in results if item.get("strictPass")),
            "strictFailed": sum(1 for item in results if not item.get("strictPass")),
            "visualPassed": sum(1 for item in results if item.get("visualPass") is True),
        },
    }
    (REPORT / "qa-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# Format Converter QA Report",
        "",
        f"- Total: {report['summary']['total']}",
        f"- Strict passed: {report['summary']['strictPassed']}",
        f"- Strict failed: {report['summary']['strictFailed']}",
        f"- Visual threshold passed: {report['summary']['visualPassed']}",
        f"- Office available: {health.get('office', {}).get('available')}",
        "",
        "| Case | Type | Exact check | Strict | Visual | Visual diff | Output |",
        "| --- | --- | --- | --- | --- | ---: | --- |",
    ]
    for item in results:
        visual = item.get("visualPass")
        lines.append("| {name} | {type} | {check} | {strict} | {visual} | {ratio:.8f} | {output} |".format(
            name=item["name"],
            type=item["type"],
            check=item.get("exactCheck") or "data-roundtrip",
            strict="PASS" if item.get("strictPass") else "FAIL",
            visual="N/A" if visual is None else ("PASS" if visual else "FAIL"),
            ratio=float(item.get("visualDiffRatio", item.get("diffRatio")) or 0),
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
