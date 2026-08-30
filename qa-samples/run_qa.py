#!/usr/bin/env python3
import csv
import hashlib
import io
import json
import math
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree

try:
    from PIL import Image, ImageChops
    PIL_IMPORT_ERROR = None
except ModuleNotFoundError as error:
    if error.name != "PIL":
        raise
    Image = None
    ImageChops = None
    PIL_IMPORT_ERROR = error

try:
    import fcntl
except ImportError:  # Windows
    fcntl = None

try:
    import msvcrt
except ImportError:  # Unix
    msvcrt = None

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "qa-samples"
INPUT = BASE / "input"
OUTPUT = BASE / "output"
REPORT = BASE / "report"
WORK = BASE / "work"

QA_PREFLIGHT_MISSING_EXIT_CODE = 3
QA_DEPENDENCY_MISSING_EXIT_CODE = 4

REQUIRED_QA_SAMPLES = (
    ("dummy.pdf", "PDF -> PNG/JPEG and PDF-tool baseline"),
    ("demo.docx", "DOCX -> PDF visual fidelity"),
    ("frictionless-sample.xlsx", "XLSX -> PDF visual fidelity"),
    ("microsoft-workshop.pptx", "PPTX -> PDF visual fidelity"),
    ("w3c-home.png", "image -> PDF layout and OCR contracts"),
    ("countries.csv", "CSV <-> XLSX data round trip"),
)

OPTIONAL_QA_SAMPLES = (
    ("quanzhou-drug-retail.wps", "WPS -> DOCX compatibility"),
    ("wps-template-newchart.et", "ET -> XLSX compatibility"),
    ("wps-template-newfile.dps", "DPS -> PPTX compatibility"),
    ("libreoffice-generated-demo.uof", "UOF -> DOCX compatibility"),
    ("ofdrw-invoice.ofd", "OFD -> TXT/DOCX/PDF/PNG/JPEG/XLSX compatibility"),
)

PDF_COMPRESSION_MAX_NORMALIZED_RASTER_ERROR = {
    "lossless": 0.0,
    "balanced": 0.14,
    "strong": 0.19,
}


def acquire_run_lock():
    lock_id = hashlib.sha256(str(BASE.resolve()).encode("utf-8")).hexdigest()[:16]
    lock_path = Path(tempfile.gettempdir()) / f"format-converter-qa-{lock_id}.lock"
    lock_file = lock_path.open("a+b")
    try:
        if fcntl is not None:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        elif msvcrt is not None:
            lock_file.seek(0, os.SEEK_END)
            if lock_file.tell() == 0:
                lock_file.write(b"\0")
                lock_file.flush()
            lock_file.seek(0)
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            raise RuntimeError("this platform has no supported file-locking backend")
    except (BlockingIOError, OSError):
        try:
            lock_file.seek(0)
            holder = lock_file.read().decode("utf-8", errors="replace").strip("\0\r\n ")
        except OSError:
            holder = ""
        lock_file.close()
        raise RuntimeError(
            "another QA run is already active for this checkout; "
            f"wait for it to finish ({holder or 'holder details unavailable'})"
        ) from None
    except RuntimeError:
        lock_file.close()
        raise
    lock_file.seek(0)
    lock_file.truncate()
    holder = f"pid={os.getpid()} started={time.strftime('%Y-%m-%dT%H:%M:%S%z')}"
    lock_file.write(holder.encode("utf-8"))
    lock_file.flush()
    return lock_file


def release_run_lock(lock_file):
    try:
        if fcntl is not None:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
        elif msvcrt is not None:
            lock_file.seek(0)
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)
    finally:
        lock_file.close()


def inspect_qa_samples(input_dir=None):
    sample_dir = Path(input_dir) if input_dir is not None else INPUT
    missing_required = tuple(
        entry for entry in REQUIRED_QA_SAMPLES if not (sample_dir / entry[0]).is_file())
    missing_optional = tuple(
        entry for entry in OPTIONAL_QA_SAMPLES if not (sample_dir / entry[0]).is_file())
    return {
        "inputDir": sample_dir,
        "missingRequired": missing_required,
        "missingOptional": missing_optional,
    }


def preflight_qa_samples(input_dir=None, error_stream=None):
    stream = error_stream if error_stream is not None else sys.stderr
    inventory = inspect_qa_samples(input_dir)
    missing_required = inventory["missingRequired"]
    missing_optional = inventory["missingOptional"]
    if missing_required:
        print(
            f"QA sample preflight failed: {len(missing_required)} required sample(s) missing "
            f"under {inventory['inputDir']}:", file=stream)
        for filename, purpose in missing_required:
            print(f"  - {filename}: {purpose}", file=stream)
        print(
            "完整 QA 语料不随仓库发布。请仅放入来源清楚、具备公开许可或已充分脱敏，"
            "且你有权使用的样本。", file=stream)
        print(
            "Place the files at the exact names above; see qa-samples/README.md. "
            f"Exiting with code {QA_PREFLIGHT_MISSING_EXIT_CODE} before service startup "
            "or QA output changes.", file=stream)
        if missing_optional:
            print("Optional samples are not preflight blockers; their cases run only when present:",
                  file=stream)
            for filename, purpose in missing_optional:
                print(f"  - {filename}: {purpose}", file=stream)
        return False
    if missing_optional:
        print("QA preflight: required samples are complete. Optional cases will be skipped for:",
              file=stream)
        for filename, purpose in missing_optional:
            print(f"  - {filename}: {purpose}", file=stream)
    return True


def preflight_qa_dependencies(error_stream=None):
    stream = error_stream if error_stream is not None else sys.stderr
    missing = []
    if PIL_IMPORT_ERROR is not None:
        missing.append("Python Pillow (install with: python3 -m pip install Pillow)")
    if shutil.which("curl") is None:
        missing.append("curl")
    if not SOFFICE:
        missing.append("LibreOffice/soffice (or set SOFFICE_BIN)")
    if not PDFTOPPM:
        missing.append("Poppler pdftoppm (or set PDFTOPPM_BIN)")
    if not PDFINFO:
        missing.append("Poppler pdfinfo (or set PDFINFO_BIN)")
    if not missing:
        return True
    print("QA dependency preflight failed:", file=stream)
    for dependency in missing:
        print(f"  - {dependency}", file=stream)
    print(
        f"Exiting with code {QA_DEPENDENCY_MISSING_EXIT_CODE} before service startup "
        "or QA output changes; see qa-samples/README.md.", file=stream)
    return False


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
PDFINFO = os.environ.get("PDFINFO_BIN") or shutil.which("pdfinfo")


def project_version():
    pom_root = ElementTree.parse(ROOT / "pom.xml").getroot()
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    value = pom_root.findtext(f"{namespace}version")
    if value is None or not value.strip():
        raise RuntimeError("Could not read the project version from pom.xml")
    return value.strip()


JAR = ROOT / "web-api" / "target" / f"web-api-{project_version()}.jar"

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


def upload_convert(path, target, fields=None):
    command = [
        "curl", "-fsS", "-X", "POST",
        "-F", f"files=@{path}",
        "-F", f"targetFormat={target}",
    ]
    for name, value in (fields or {}).items():
        command.extend(["-F", f"{name}={value}"])
    command.append(f"{BASE_URL}/api/tasks")
    body = run(command, timeout=180)
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
    # Chained round-trip checks can feed a generated artifact back into the API.
    # Keep the local name bounded so repeated conversions never exceed the host
    # filesystem's per-component limit.
    suffix = Path(name).suffix or f".{target}"
    out = OUTPUT / f"{path.stem[:48]}-to-{target}-{task_id[:8]}{suffix}"
    with urllib.request.urlopen(f"{BASE_URL}/api/tasks/{task_id}/download", timeout=30) as response:
        out.write_bytes(response.read())
    return task, out


def fetch_json(path):
    with urllib.request.urlopen(f"{BASE_URL}{path}", timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


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


def pdf_page_count(pdf):
    if not PDFINFO:
        raise RuntimeError("pdfinfo not found")
    info = run([PDFINFO, str(pdf)])
    match = re.search(r"^Pages:\s+(\d+)", info, re.MULTILINE)
    if not match:
        raise RuntimeError(f"pdfinfo did not report a page count for {pdf}")
    return int(match.group(1))


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
        left = white_rgb(left)
        right = white_rgb(right)
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
    left = [Image.open(path) for path in expected]
    right = [Image.open(path) for path in actual]
    return compare_image_values(left, right, diff_dir)


def white_rgb(image):
    rgba = image.convert("RGBA")
    background = Image.new("RGBA", rgba.size, "white")
    background.alpha_composite(rgba)
    return background.convert("RGB")


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


def docx_embedded_font_entries(docx):
    with zipfile.ZipFile(docx) as archive:
        names = archive.namelist()
        fonts = [name for name in names if name.startswith("word/fonts/") and name.endswith(".odttf")]
        return fonts, "word/fontTable.xml" in names


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
        left = white_rgb(Image.open(left_path))
        right = white_rgb(Image.open(right_path))
        if left.size != right.size:
            return float("inf")
        histogram = ImageChops.difference(left, right).histogram()
        error += sum((index % 256) * count for index, count in enumerate(histogram))
        values += left.width * left.height * 3
    return error / values if values else 0.0


def padded_image_mean_absolute_error(expected, actual):
    if len(expected) != len(actual):
        return float("inf")
    error = 0
    values = 0
    for left, right in zip(expected, actual):
        width = max(left.width, right.width)
        height = max(left.height, right.height)
        canvas_left = Image.new("RGB", (width, height), "white")
        canvas_right = Image.new("RGB", (width, height), "white")
        canvas_left.paste(left, (0, 0))
        canvas_right.paste(right, (0, 0))
        histogram = ImageChops.difference(canvas_left, canvas_right).histogram()
        error += sum((index % 256) * count for index, count in enumerate(histogram))
        values += width * height * 3
    return error / values if values else 0.0


def visual_case(name, source, target, source_pdf=None, exact_mode="visual", verified_route_id=None):
    result = {"name": name, "source": source.name, "target": target, "type": "visual"}
    task, converted = upload_convert(source, target)
    if verified_route_id:
        result["verifiedRouteIds"] = [verified_route_id]
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
    elif exact_mode == "raster":
        result["exactCheck"] = "white-composited-cross-engine-raster-bound"
        result["strictPass"] = comparison["pageCountMatch"] and comparison["diffRatio"] <= 0.001
    else:
        result["exactCheck"] = "rendered-pixels"
        result["strictPass"] = comparison["pageCountMatch"] and comparison["differentPixels"] == 0
    result["practicalPass"] = result["visualPass"]
    return result


def image_pdf_layout_case(source):
    result = {"name": "png-to-pdf-physical-page", "source": source.name,
              "target": "pdf", "type": "content-layout"}
    task, output = upload_convert(source, "pdf")
    result["taskStatus"] = task.get("status")
    result["output"] = output.name if output else None
    result["exactCheck"] = "embedded-dpi-to-physical-pdf-page-size"
    if not output or not PDFINFO:
        result["strictPass"] = False
        result["error"] = task.get("errorMessage") or "pdfinfo not available"
        return result
    with Image.open(source) as image:
        dpi = image.info.get("dpi", (96.0, 96.0))
        dpi_x = float(dpi[0]) if 36 <= float(dpi[0]) <= 1200 else 96.0
        dpi_y = float(dpi[1]) if 36 <= float(dpi[1]) <= 1200 else 96.0
        expected_width = image.width * 72.0 / dpi_x
        expected_height = image.height * 72.0 / dpi_y
    info = run([PDFINFO, str(output)])
    pages = re.search(r"^Pages:\s+(\d+)", info, re.MULTILINE)
    size = re.search(r"^Page size:\s+([0-9.]+)\s+x\s+([0-9.]+)\s+pts", info, re.MULTILINE)
    if not pages or not size:
        result["strictPass"] = False
        result["error"] = "pdfinfo did not report page count and size"
        return result
    actual_width, actual_height = float(size.group(1)), float(size.group(2))
    result["pageCount"] = int(pages.group(1))
    result["expectedPagePoints"] = [expected_width, expected_height]
    result["actualPagePoints"] = [actual_width, actual_height]
    result["strictPass"] = (int(pages.group(1)) == 1
                            and abs(actual_width - expected_width) <= 0.05
                            and abs(actual_height - expected_height) <= 0.05)
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    result["diffRatio"] = 0.0
    return result


def ofd_docx_text_case(source):
    result = {"name": "ofd-to-docx-text-exact", "source": source.name, "target": "docx", "type": "content",
              "verifiedRouteIds": []}
    text_task, text_output = upload_convert(source, "txt")
    result["verifiedRouteIds"].append("ofd-to-txt")
    docx_task, docx_output = upload_convert(source, "docx")
    result["verifiedRouteIds"].append("ofd-to-docx")
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


def ofd_pdf_fixed_case(source):
    result = {"name": "ofd-to-pdf-fixed-layout", "source": source.name,
              "target": "pdf", "type": "content-layout", "verifiedRouteIds": []}
    source_task, source_text = upload_convert(source, "txt")
    result["verifiedRouteIds"].append("ofd-to-txt")
    pdf_task, pdf_output = upload_convert(source, "pdf")
    result["verifiedRouteIds"].append("ofd-to-pdf")
    result["taskStatus"] = pdf_task.get("status")
    result["output"] = pdf_output.name if pdf_output else None
    result["exactCheck"] = "ofd-vs-fixed-pdf-character-content-and-page-count"
    if not source_text or not pdf_output:
        result["strictPass"] = False
        result["error"] = source_task.get("errorMessage") or pdf_task.get("errorMessage")
        return result
    extracted_task, extracted_text = upload_convert(pdf_output, "txt")
    if not extracted_text:
        result["strictPass"] = False
        result["error"] = extracted_task.get("errorMessage")
        return result
    expected = normalized_character_counts(source_text.read_text(encoding="utf-8", errors="replace"))
    actual = normalized_character_counts(extracted_text.read_text(encoding="utf-8", errors="replace"))
    pages = render_pdf(pdf_output, WORK / result["name"] / "render", "page")
    file_results = pdf_task.get("files") or []
    declared_pages = file_results[0].get("pageCount") if file_results else None
    result["sourceCharacterCount"] = sum(expected.values())
    result["pdfCharacterCount"] = sum(actual.values())
    result["declaredPageCount"] = declared_pages
    result["renderedPageCount"] = len(pages)
    result["pageCountMatch"] = declared_pages == len(pages)
    result["strictPass"] = expected == actual and result["pageCountMatch"]
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    return result


def ofd_image_fixed_case(source, target):
    result = {"name": f"ofd-to-{target}-fixed-layout", "source": source.name,
              "target": target, "type": "content-layout", "verifiedRouteIds": []}
    pdf_task, reference_pdf = upload_convert(source, "pdf")
    result["verifiedRouteIds"].append("ofd-to-pdf")
    image_task, image_output = upload_convert(source, target)
    result["verifiedRouteIds"].append(f"ofd-to-{target}")
    result["taskStatus"] = image_task.get("status")
    result["output"] = image_output.name if image_output else None
    result["exactCheck"] = "page-count-dimensions-nonblank-and-raster-error"
    if not reference_pdf or not image_output:
        result["strictPass"] = False
        result["error"] = pdf_task.get("errorMessage") or image_task.get("errorMessage")
        return result
    case_dir = WORK / result["name"]
    expected_paths = render_pdf(reference_pdf, case_dir / "reference", "reference")
    if image_output.suffix.lower() == ".zip":
        actual_paths = unzip_images(image_output, case_dir / "actual")
    else:
        actual_paths = [image_output]
    expected = [Image.open(path).convert("RGB") for path in expected_paths]
    actual = [Image.open(path).convert("RGB") for path in actual_paths]
    result["pageCountMatch"] = len(expected) == len(actual)
    result["dimensionsMatch"] = result["pageCountMatch"] and all(
        abs(left.width - right.width) <= 1 and abs(left.height - right.height) <= 1
        for left, right in zip(expected, actual))
    result["nonBlankPages"] = all(
        ImageChops.difference(image, Image.new("RGB", image.size, "white")).getbbox() is not None
        for image in actual)
    result["meanAbsoluteError"] = padded_image_mean_absolute_error(expected, actual)
    comparison = compare_image_values(expected, actual, REPORT / "diffs" / result["name"])
    result["diffRatio"] = comparison["diffRatio"]
    result["visualPass"] = (comparison["pageCountMatch"]
                            and comparison["diffRatio"] <= VISUAL_THRESHOLD)
    result["strictPass"] = (result["dimensionsMatch"] and result["nonBlankPages"]
                            and result["meanAbsoluteError"] <= 8.0)
    result["practicalPass"] = result["strictPass"]
    return result


def ofd_xlsx_table_case(source):
    result = {"name": "ofd-to-xlsx-real-table-data", "source": source.name,
              "target": "xlsx", "type": "data"}
    task, workbook = upload_convert(source, "xlsx")
    result["taskStatus"] = task.get("status")
    result["output"] = workbook.name if workbook else None
    result["exactCheck"] = "invoice-cells-merges-and-known-text"
    if not workbook:
        result["strictPass"] = False
        result["error"] = task.get("errorMessage")
        return result
    spreadsheet_namespace = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    with zipfile.ZipFile(workbook) as archive:
        shared_strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared_root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
            shared_strings = ["".join(node.text or "" for node in item.iter(
                f"{spreadsheet_namespace}t")) for item in shared_root.iter(f"{spreadsheet_namespace}si")]
        sheet_names = sorted(name for name in archive.namelist()
                             if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", name))
        sheets = [ElementTree.fromstring(archive.read(name)) for name in sheet_names]
    values = []
    for sheet in sheets:
        for cell in sheet.iter(f"{spreadsheet_namespace}c"):
            node = cell.find(f"{spreadsheet_namespace}v")
            value = "" if node is None or node.text is None else node.text
            if cell.get("t") == "s" and value:
                value = shared_strings[int(value)]
            values.append(value)
    result["worksheetCount"] = len(sheets)
    result["cellCount"] = sum(len(list(sheet.iter(f"{spreadsheet_namespace}c"))) for sheet in sheets)
    result["mergedRegionCount"] = sum(
        len(list(sheet.iter(f"{spreadsheet_namespace}mergeCell"))) for sheet in sheets)
    normalized_text = "".join("".join(value.split()) for value in values)
    expected_fragments = ["购买方", "项目名称", "价税合计（大写）", "销售方"]
    result["knownTextPreserved"] = all(fragment in normalized_text for fragment in expected_fragments)
    result["strictPass"] = (result["worksheetCount"] == 1 and result["cellCount"] == 18
                            and result["mergedRegionCount"] == 8 and result["knownTextPreserved"])
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    result["diffRatio"] = 0.0
    return result


def pdf_docx_editable_case(source):
    result = {"name": "pdf-to-docx-editable", "source": source.name, "target": "docx", "type": "content",
              "verifiedRouteIds": ["pdf-to-docx"]}
    text_task, text_output = upload_convert(source, "txt")
    docx_task, docx_output = upload_convert(source, "docx")
    result["taskStatus"] = docx_task["status"]
    result["output"] = docx_output.name if docx_output else None
    result["exactCheck"] = "pdf-vs-docx-editable-character-content-no-images-portable-cjk-font"
    if not text_output or not docx_output:
        result["strictPass"] = False
        result["error"] = text_task.get("errorMessage") or docx_task.get("errorMessage")
        return result

    expected = normalized_character_counts(text_output.read_text(encoding="utf-8", errors="replace"))
    actual = normalized_character_counts(docx_text_content(docx_output))
    media = docx_media_entries(docx_output)
    embedded_fonts, has_font_table = docx_embedded_font_entries(docx_output)
    requires_cjk_font = any("\u3400" <= character <= "\u9fff" for character in expected)
    case_dir = WORK / result["name"]
    expected_pages = render_pdf(source, case_dir / "source-render", "source")
    target_pdf = office_pdf(docx_output, case_dir / "target-pdf")
    actual_pages = render_pdf(target_pdf, case_dir / "target-render", "target")
    comparison = compare_images(expected_pages, actual_pages, REPORT / "diffs" / result["name"])
    result["sourceCharacterCount"] = sum(expected.values())
    result["docxCharacterCount"] = sum(actual.values())
    result["embeddedMedia"] = media
    result["embeddedFonts"] = embedded_fonts
    result["hasFontTable"] = has_font_table
    result["pageCountMatch"] = comparison["pageCountMatch"]
    result["diffRatio"] = comparison["diffRatio"]
    result["visualPass"] = comparison["pageCountMatch"] and comparison["diffRatio"] <= VISUAL_THRESHOLD
    result["strictPass"] = (expected == actual and not media and comparison["pageCountMatch"]
                            and (not requires_cjk_font or (has_font_table and bool(embedded_fonts))))
    result["practicalPass"] = result["strictPass"]
    return result


def pdf_docx_ocr_required_case(source):
    result = {"name": "pdf-to-docx-ocr-required", "source": source.name,
              "target": "docx", "type": "failure-contract", "verifiedRouteIds": ["pdf-to-docx"]}
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


def pdf_txt_extraction_case(source, expected_text):
    result = {"name": "pdf-to-txt-layout-extraction", "source": source.name,
              "target": "txt", "type": "content", "verifiedRouteIds": ["pdf-to-txt"]}
    task, output = upload_convert(source, "txt")
    result["taskStatus"] = task.get("status")
    result["output"] = output.name if output else None
    result["exactCheck"] = "source-character-content-and-page-boundaries"
    if not output:
        result["strictPass"] = False
        result["error"] = task.get("errorMessage")
        return result
    expected = normalized_character_counts(expected_text.read_text(encoding="utf-8", errors="replace"))
    extracted_value = output.read_text(encoding="utf-8", errors="replace")
    actual = normalized_character_counts(extracted_value)
    files = task.get("files") or []
    page_count = files[0].get("pageCount") if files else None
    result["sourceCharacterCount"] = sum(expected.values())
    result["textCharacterCount"] = sum(actual.values())
    result["pageCount"] = page_count
    result["pageBoundaryCount"] = extracted_value.count("\f")
    result["strictPass"] = (expected == actual and page_count is not None
                            and result["pageBoundaryCount"] == max(0, page_count - 1))
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    result["diffRatio"] = 0.0
    return result


def pdf_txt_ocr_required_case(source):
    result = {"name": "pdf-to-txt-ocr-required", "source": source.name,
              "target": "txt", "type": "failure-contract", "verifiedRouteIds": ["pdf-to-txt"]}
    task, output = upload_convert(source, "txt")
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
    result["diffRatio"] = 0.0
    return result


def pdf_ofd_fixed_case(source):
    result = {"name": "pdf-to-ofd-fixed-layout", "source": source.name,
              "target": "ofd", "type": "content-layout"}
    source_task, source_text = upload_convert(source, "txt")
    ofd_task, ofd_output = upload_convert(source, "ofd")
    result["taskStatus"] = ofd_task.get("status")
    result["output"] = ofd_output.name if ofd_output else None
    result["exactCheck"] = "real-ofd-package-character-and-page-count-preservation"
    if not source_text or not ofd_output:
        result["strictPass"] = False
        result["error"] = source_task.get("errorMessage") or ofd_task.get("errorMessage")
        return result
    with zipfile.ZipFile(ofd_output) as archive:
        result["realOfdPackage"] = "OFD.xml" in archive.namelist()
    text_task, ofd_text = upload_convert(ofd_output, "txt")
    pdf_task, roundtrip_pdf = upload_convert(ofd_output, "pdf")
    if not ofd_text or not roundtrip_pdf:
        result["strictPass"] = False
        result["error"] = text_task.get("errorMessage") or pdf_task.get("errorMessage")
        return result
    expected = normalized_character_counts(source_text.read_text(encoding="utf-8", errors="replace"))
    actual = normalized_character_counts(ofd_text.read_text(encoding="utf-8", errors="replace"))
    case_dir = WORK / result["name"]
    source_pages = render_pdf(source, case_dir / "source-render", "source")
    roundtrip_pages = render_pdf(roundtrip_pdf, case_dir / "roundtrip-render", "roundtrip")
    comparison = compare_images(source_pages, roundtrip_pages, REPORT / "diffs" / result["name"])
    result["sourceCharacterCount"] = sum(expected.values())
    result["ofdCharacterCount"] = sum(actual.values())
    result["pageCountMatch"] = comparison["pageCountMatch"]
    result["diffRatio"] = comparison["diffRatio"]
    result["visualPass"] = comparison["pageCountMatch"] and comparison["diffRatio"] <= VISUAL_THRESHOLD
    result["strictPass"] = result["realOfdPackage"] and expected == actual and result["pageCountMatch"]
    result["practicalPass"] = result["strictPass"]
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


def docx_txt_extraction_case(source):
    result = {"name": "docx-to-txt-content", "source": source.name,
              "target": "txt", "type": "content", "verifiedRouteIds": ["docx-to-txt"]}
    task, output = upload_convert(source, "txt")
    result["taskStatus"] = task.get("status")
    result["output"] = output.name if output else None
    result["exactCheck"] = "docx-main-text-character-preservation"
    if not output:
        result["strictPass"] = False
        result["error"] = task.get("errorMessage")
        return result
    expected = normalized_character_counts(docx_text_content(source))
    actual = normalized_character_counts(output.read_text(encoding="utf-8", errors="replace"))
    result["sourceCharacterCount"] = sum(expected.values())
    result["textCharacterCount"] = sum(actual.values())
    result["strictPass"] = expected == actual
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    return result


def create_image_heavy_png(path, width=1800, height=1400):
    """Create deterministic high-entropy RGB pixels that PNG stores much larger than JPEG."""
    pixels = bytearray(width * height * 3)
    seed = 0x13579BDF
    offset = 0
    for y in range(height):
        for x in range(width):
            seed = (seed * 1103515245 + 12345) & 0xffffffff
            noise = (seed >> 16) & 0xff
            pixels[offset] = noise
            pixels[offset + 1] = (x + noise) & 0xff
            pixels[offset + 2] = (y + noise) & 0xff
            offset += 3
    path.parent.mkdir(parents=True, exist_ok=True)
    image = Image.frombytes("RGB", (width, height), bytes(pixels))
    try:
        image.save(path, format="PNG", dpi=(144, 144), compress_level=0)
    finally:
        image.close()
    return path


def create_compressible_pdf_source():
    fixture_png = create_image_heavy_png(WORK / "pdf-compression-fixture" / "image-heavy.png")
    task, source = upload_convert(fixture_png, "pdf")
    if not source:
        raise RuntimeError("Could not create deterministic image-heavy PDF QA source: "
                           + str(task.get("errorMessage")))
    return source


def rendered_pages_are_nonblank(pages):
    if not pages:
        return False
    for page in pages:
        with Image.open(page) as image:
            rgb = white_rgb(image)
            if ImageChops.difference(rgb, Image.new("RGB", rgb.size, "white")).getbbox() is None:
                return False
    return True


def pdf_compression_case(source, source_render_pages, mode):
    result = {"name": f"pdf-compress-{mode}-image-heavy", "source": source.name,
              "target": "pdf-compress", "type": "content-layout", "compressionMode": mode,
              "verifiedRouteIds": ["pdf-to-pdf-compress"]}
    task, output = upload_convert(source, "pdf-compress", {"compressionMode": mode})
    result["taskStatus"] = task.get("status")
    result["output"] = output.name if output else None
    result["exactCheck"] = "mode-warning-size-pages-nonblank-and-bounded-raster-error"
    if not output:
        result["strictPass"] = False
        result["error"] = task.get("errorMessage")
        return result
    result["sourceBytes"] = source.stat().st_size
    result["outputBytes"] = output.stat().st_size
    result["savedBytes"] = result["sourceBytes"] - result["outputBytes"]
    result["savingsPercent"] = round(result["savedBytes"] * 100.0 / result["sourceBytes"], 4)
    result["sourcePageCount"] = pdf_page_count(source)
    result["outputPageCount"] = pdf_page_count(output)
    output_render_pages = render_pdf(output, WORK / result["name"] / "render", "page")
    result["renderedNonBlank"] = rendered_pages_are_nonblank(output_render_pages)
    raster_mae = image_mean_absolute_error(source_render_pages, output_render_pages)
    result["rasterDimensionsMatch"] = math.isfinite(raster_mae)
    result["rasterMeanAbsoluteError"] = raster_mae if math.isfinite(raster_mae) else None
    result["normalizedRasterError"] = raster_mae / 255.0 if math.isfinite(raster_mae) else 1.0
    result["rasterPixelsExact"] = result["rasterDimensionsMatch"] and raster_mae == 0.0
    result["maxNormalizedRasterError"] = PDF_COMPRESSION_MAX_NORMALIZED_RASTER_ERROR[mode]
    result["rasterWithinLimit"] = (
        result["normalizedRasterError"] <= result["maxNormalizedRasterError"])
    result["warningCodes"] = [warning.get("code") for warning in (task.get("warnings") or [])]
    expected_warning = ("PDF_COMPRESSION_APPLIED" if result["outputBytes"] < result["sourceBytes"]
                        else "PDF_SIZE_NOT_REDUCED")
    result["expectedWarningCode"] = expected_warning
    warning_matches_size = result["warningCodes"] == [expected_warning]
    common_contract = (result["sourcePageCount"] == result["outputPageCount"]
                       and result["renderedNonBlank"] and result["rasterWithinLimit"]
                       and warning_matches_size)
    if mode in ("balanced", "strong"):
        result["strictPass"] = (common_contract
                                and result["outputBytes"] < result["sourceBytes"]
                                and expected_warning == "PDF_COMPRESSION_APPLIED")
    else:
        result["strictPass"] = common_contract and result["outputBytes"] <= result["sourceBytes"]
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    return result


def beta_capability_coverage_case(capabilities, executed_cases):
    available_beta = {
        route["id"] for route in capabilities
        if route.get("status") == "available" and route.get("qualityLevel") == "beta"
    }
    route_evidence = {}
    for case in executed_cases:
        for route_id in case.get("verifiedRouteIds") or []:
            route_evidence.setdefault(route_id, []).append(case["name"])
    verified_routes = set(route_evidence)
    missing = sorted(available_beta - verified_routes)
    result = {
        "name": "beta-capability-coverage",
        "source": "GET /api/tasks/capabilities",
        "target": "available beta routes",
        "type": "capability-coverage",
        "exactCheck": "every-available-beta-route-has-explicit-qa-coverage",
        "availableBetaRouteIds": sorted(available_beta),
        "verifiedBetaRouteIds": sorted(verified_routes & available_beta),
        "verifiedRouteEvidence": {
            route_id: sorted(case_names)
            for route_id, case_names in sorted(route_evidence.items())
            if route_id in available_beta
        },
        "missingBetaRouteIds": missing,
        "verifiedOutsideAvailableBetaRouteIds": sorted(verified_routes - available_beta),
        "strictPass": not missing,
        "practicalPass": not missing,
        "visualPass": None,
    }
    return result


def pdf_watermark_case(source):
    result = {"name": "pdf-watermark-text", "source": source.name,
              "target": "pdf-watermark", "type": "content-layout",
              "verifiedRouteIds": ["pdf-to-pdf-watermark"]}
    task, output = upload_convert(source, "pdf-watermark", {
        "watermarkText": "QA-WATERMARK-2026",
        "watermarkOpacity": "0.30",
        "watermarkAngle": "25",
        "watermarkPosition": "center",
        "watermarkPages": "all",
        "watermarkColor": "#667788",
    })
    result["taskStatus"] = task.get("status")
    result["output"] = output.name if output else None
    result["exactCheck"] = "same-pages-and-visible-watermark-render"
    if not output:
        result["strictPass"] = False
        result["error"] = task.get("errorMessage")
        return result
    result["sourcePageCount"] = pdf_page_count(source)
    result["outputPageCount"] = pdf_page_count(output)
    case_dir = WORK / result["name"]
    source_pages = render_pdf(source, case_dir / "source-render", "source")
    output_pages = render_pdf(output, case_dir / "output-render", "output")
    comparison = compare_images(source_pages, output_pages, case_dir / "diff")
    result.update(comparison)
    result["watermarkPixelsChanged"] = comparison["differentPixels"]
    result["strictPass"] = (result["sourcePageCount"] == result["outputPageCount"]
                            and comparison["pageCountMatch"]
                            and (comparison["differentPixels"] or 0) > 1_000)
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = result["strictPass"]
    return result


def docx_uof_round_trip_case(source):
    result = {"name": "docx-uof-docx-roundtrip", "source": source.name,
              "target": "uof,docx", "type": "content"}
    export_task, uof = upload_convert(source, "uof")
    result["firstTaskStatus"] = export_task.get("status")
    result["output"] = uof.name if uof else None
    result["exactCheck"] = "uof-root-namespace-and-text-roundtrip"
    if not uof:
        result["strictPass"] = False
        result["error"] = export_task.get("errorMessage")
        return result
    root = ElementTree.parse(uof).getroot()
    local_name = root.tag.rsplit("}", 1)[-1]
    namespace = root.tag[1:].split("}", 1)[0] if root.tag.startswith("{") else ""
    reopen_task, reopened = upload_convert(uof, "docx")
    result["secondTaskStatus"] = reopen_task.get("status")
    if not reopened:
        result["strictPass"] = False
        result["error"] = reopen_task.get("errorMessage")
        return result
    expected = normalized_character_counts(docx_text_content(source))
    actual = normalized_character_counts(docx_text_content(reopened))
    result["rootElement"] = local_name
    result["namespace"] = namespace
    result["sourceCharacterCount"] = sum(expected.values())
    result["roundTripCharacterCount"] = sum(actual.values())
    result["strictPass"] = (local_name == "UOF" and namespace.startswith("http://schemas.uof.org/")
                            and expected == actual)
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    return result


def unavailable_ocr_case(name, source, target):
    result = {"name": name, "source": source.name, "target": target,
              "type": "capability-contract", "exactCheck": "unavailable-route-error-contract"}
    task, output = upload_convert(source, target)
    files = task.get("files") or []
    file_result = files[0] if files else {}
    result["taskStatus"] = task.get("status")
    result["errorCode"] = file_result.get("errorCode")
    result["downloadProduced"] = output is not None
    result["strictPass"] = (task.get("status") == "FAILED"
                            and result["errorCode"] == "OCR_ENGINE_UNAVAILABLE"
                            and output is None)
    result["practicalPass"] = result["strictPass"]
    result["visualPass"] = None
    return result


def run_qa_suite():
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
        docx_source_task, generated_docx_source = upload_convert(pdf_editable_text, "docx")
        if not generated_docx_source:
            raise RuntimeError("Could not create editable DOCX QA source: "
                               + str(docx_source_task.get("errorMessage")))
        pdf_compression_source = create_compressible_pdf_source()
        pdf_compression_render = render_pdf(
            pdf_compression_source, WORK / "pdf-compression-fixture" / "source-render", "page")
        if not rendered_pages_are_nonblank(pdf_compression_render):
            raise RuntimeError("Deterministic image-heavy PDF QA source rendered blank")
        cases = [
            visual_case("docx-to-pdf-exact", INPUT / "demo.docx", "pdf",
                        verified_route_id="docx-to-pdf"),
            visual_case("xlsx-to-pdf-exact", INPUT / "frictionless-sample.xlsx", "pdf",
                        verified_route_id="xlsx-to-pdf"),
            visual_case("pptx-to-pdf-exact", INPUT / "microsoft-workshop.pptx", "pdf",
                        verified_route_id="pptx-to-pdf"),
        ]
        if (INPUT / "quanzhou-drug-retail.wps").is_file():
            cases.append(visual_case(
                "wps-to-docx-exact", INPUT / "quanzhou-drug-retail.wps", "docx",
                exact_mode="text"))
        if (INPUT / "wps-template-newchart.et").is_file():
            cases.append(visual_case("et-to-xlsx-exact", INPUT / "wps-template-newchart.et", "xlsx",
                                     exact_mode="table-data"))
        if (INPUT / "wps-template-newfile.dps").is_file():
            cases.append(visual_case("dps-to-pptx-exact", INPUT / "wps-template-newfile.dps", "pptx"))
        if (INPUT / "libreoffice-generated-demo.uof").is_file():
            cases.append(visual_case("uof-to-docx-exact", INPUT / "libreoffice-generated-demo.uof", "docx",
                                     exact_mode="text"))
        cases.extend([
                visual_case("pdf-to-png-exact", pdf_source, "png", source_pdf=pdf_reference,
                            exact_mode="raster"),
                visual_case("pdf-to-jpeg-quality", pdf_source, "jpg", source_pdf=pdf_reference,
                            exact_mode="jpeg"),
                pdf_txt_extraction_case(pdf_editable_source, pdf_editable_text),
                pdf_txt_ocr_required_case(pdf_scanned_source),
                pdf_docx_editable_case(pdf_editable_source),
                pdf_docx_ocr_required_case(pdf_scanned_source),
                docx_txt_extraction_case(generated_docx_source),
                pdf_compression_case(pdf_compression_source, pdf_compression_render, "lossless"),
                pdf_compression_case(pdf_compression_source, pdf_compression_render, "balanced"),
                pdf_compression_case(pdf_compression_source, pdf_compression_render, "strong"),
                pdf_watermark_case(pdf_editable_source),
                pdf_ofd_fixed_case(pdf_editable_source),
                docx_uof_round_trip_case(generated_docx_source),
                image_pdf_layout_case(INPUT / "w3c-home.png"),
                csv_round_trip_case(INPUT / "countries.csv"),
        ])
        if (INPUT / "ofdrw-invoice.ofd").is_file():
            cases.append(ofd_docx_text_case(INPUT / "ofdrw-invoice.ofd"))
            cases.append(ofd_pdf_fixed_case(INPUT / "ofdrw-invoice.ofd"))
            cases.append(ofd_image_fixed_case(INPUT / "ofdrw-invoice.ofd", "png"))
            cases.append(ofd_image_fixed_case(INPUT / "ofdrw-invoice.ofd", "jpg"))
            cases.append(ofd_xlsx_table_case(INPUT / "ofdrw-invoice.ofd"))
        capability_routes = fetch_json("/api/tasks/capabilities")
        capabilities = {route["id"]: route for route in capability_routes}
        cases.append(beta_capability_coverage_case(capability_routes, cases))
        unavailable_ocr = [route_id for route_id in (
            "png-to-txt", "jpg-to-txt", "png-to-docx", "jpg-to-docx"
        ) if capabilities.get(route_id, {}).get("status") == "unavailable"]
        if unavailable_ocr:
            jpg_task, jpg_source = upload_convert(pdf_source, "jpg")
            if not jpg_source:
                raise RuntimeError("Could not create JPEG OCR capability source: "
                                   + str(jpg_task.get("errorMessage")))
            ocr_sources = {"png": INPUT / "w3c-home.png", "jpg": jpg_source}
            for route_id in unavailable_ocr:
                source_format, target_format = route_id.split("-to-", 1)
                cases.append(unavailable_ocr_case(
                    f"{route_id}-unavailable-contract", ocr_sources[source_format], target_format))
        results.extend(cases)
    finally:
        if process:
            stop_service(process)
    report = {
        "standard": "strictPass uses route-specific exactness: rendered pixels for direct fidelity routes, normalized text for editable documents, and table data for spreadsheets. PDF-to-TXT requires source character preservation, correct page-boundary count, and OCR_REQUIRED for image-only input. Editable PDF-to-DOCX additionally requires matching page count, no embedded media, and a font table plus an OOXML obfuscated font part for generated CJK text; an image-only PDF must fail with OCR_REQUIRED. DOCX-to-TXT covers an ordinary document without optional comments or notes. PDF compression uses one deterministic image-heavy PDF for all three modes: every result must preserve pages, render nonblank, never grow, return the warning matching its byte outcome, and stay within a per-mode normalized raster MAE limit (lossless 0, balanced 0.14, strong 0.19); balanced and strong must strictly shrink with PDF_COMPRESSION_APPLIED. PDF watermarking must preserve page count and introduce a visible rendered pixel delta. PDF-to-OFD requires a real OFD package plus character and page-count preservation, while DOCX-to-UOF-to-DOCX requires a real UOF root/namespace and exact normalized text. OFD-to-PDF requires character and declared/rendered page-count preservation. OFD-to-PNG/JPEG require page-count, pixel-dimension, nonblank-content, and bounded raster-error checks. OFD-to-XLSX requires exact invoice cell and merge counts plus known text, while low-confidence grid candidates are excluded; generated fixtures additionally cover pages, cells, merges, NO_TABLE_FOUND, and OCR_REQUIRED. Every currently available Beta capability must appear in verifiedRouteIds emitted by a case that actually ran in this QA invocation. Unavailable OCR routes must fail with OCR_ENGINE_UNAVAILABLE and produce no download. JPEG uses a declared lossy-error bound.",
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
    ]
    beta_coverage = next(
        (item for item in results if item.get("name") == "beta-capability-coverage"), None)
    if beta_coverage:
        lines.extend([
            "## Beta Capability Coverage",
            "",
            "- Available: " + ", ".join(beta_coverage["availableBetaRouteIds"]),
            "- Verified by executed cases: " + ", ".join(beta_coverage["verifiedBetaRouteIds"]),
            "- Missing: " + (", ".join(beta_coverage["missingBetaRouteIds"]) or "none"),
            "",
        ])
    lines.extend([
        "| Case | Type | Exact check | Strict | Visual | Visual diff | Compression savings | Raster error / limit | Warnings | Output |",
        "| --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- |",
    ])
    for item in results:
        visual = item.get("visualPass")
        savings = ""
        if item.get("sourceBytes") is not None and item.get("outputBytes") is not None:
            savings = (f"{item['sourceBytes']} → {item['outputBytes']} bytes "
                       f"({float(item.get('savingsPercent') or 0):.4f}%)")
        raster_error = ""
        if item.get("normalizedRasterError") is not None:
            raster_error = (f"{float(item['normalizedRasterError']):.6f} / "
                            f"{float(item['maxNormalizedRasterError']):.6f}")
        warning_codes = ", ".join(item.get("warningCodes") or [])
        lines.append("| {name} | {type} | {check} | {strict} | {visual} | {ratio:.8f} | {savings} | {raster_error} | {warnings} | {output} |".format(
            name=item["name"],
            type=item["type"],
            check=item.get("exactCheck") or "data-roundtrip",
            strict="PASS" if item.get("strictPass") else "FAIL",
            visual="N/A" if visual is None else ("PASS" if visual else "FAIL"),
            ratio=float(item.get("visualDiffRatio", item.get("diffRatio")) or 0),
            savings=savings,
            raster_error=raster_error,
            warnings=warning_codes,
            output=item.get("output") or "",
        ))
    (REPORT / "qa-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False))
    return 0 if report["summary"]["strictFailed"] == 0 else 1


def main(input_dir=None, suite=None):
    try:
        lock_file = acquire_run_lock()
    except RuntimeError as error:
        print(f"QA run refused: {error}", file=sys.stderr)
        return 2
    try:
        if not preflight_qa_samples(input_dir):
            return QA_PREFLIGHT_MISSING_EXIT_CODE
        if suite is None and not preflight_qa_dependencies():
            return QA_DEPENDENCY_MISSING_EXIT_CODE
        runner = run_qa_suite if suite is None else suite
        return runner()
    finally:
        release_run_lock(lock_file)


def sanitized_health(health):
    value = json.loads(json.dumps(health))
    office = value.get("office")
    if isinstance(office, dict) and office.get("binary"):
        office["binary"] = Path(office["binary"]).name
    return value


if __name__ == "__main__":
    sys.exit(main())
