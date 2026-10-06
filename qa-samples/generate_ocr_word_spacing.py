#!/usr/bin/env python3
"""Public synthetic scan for visible word gaps, signed money and editable Office QA."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version

ROOT = Path(__file__).resolve().parents[1]
LINES = ["REVENUE REVIEW 2071", "Record 00973", "Amount -0054.80", "Date 2071-09-23"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "qa-samples/generated/ocr-word-spacing")
    parser.add_argument("--after", type=Path, help="Reuse the baseline HTTP output PDF and compare its Word/Office artifacts")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    font_path = ROOT / "task-service/src/main/resources/fonts/LiberationSans-Regular.ttf"
    if args.after:
        path = args.out / "revenue.pdf"
        shutil.copyfile(args.after / "revenue-pdf-result.pdf", path)
        for suffix in ("word-result.docx", "office-result.pdf"):
            assert (args.after / ("revenue-" + suffix)).is_file(), suffix
        actions = [{"id": "revenue-word", "input": path.name, "target": "docx"},
                   {"id": "revenue-office", "input": "@revenue-word", "target": "pdf"},
                   {"id": "revenue-text", "input": "@revenue-office", "target": "txt"},
                   {"id": "revenue-edited-office", "input": "@revenue-word", "target": "pdf",
                    "edit": {"old": "-0054.80", "new": "-0068.95"}},
                   {"id": "revenue-edited-text", "input": "@revenue-edited-office", "target": "txt"}]
    else:
        image = Image.new("RGB", (1400, 1000), "white")
        draw = ImageDraw.Draw(image)
        font = ImageFont.truetype(str(font_path), 56)
        for line, top in zip(LINES, [90, 280, 470, 700], strict=True):
            draw.text((100, top), line, font=font, fill="black")
        path = args.out / "revenue.png"
        image.save(path, dpi=(300, 300))
        image.close()
        actions = [{"id": "revenue-pdf", "input": path.name, "target": "pdf"},
                   {"id": "revenue-word", "input": "@revenue-pdf", "target": "docx"},
                   {"id": "revenue-office", "input": "@revenue-word", "target": "pdf"}]
    manifest = {
        "provenance": "Repository-generated synthetic text; no private documents",
        "pillowVersion": pillow_version,
        "font": {"path": str(font_path.relative_to(ROOT)),
                 "sha256": hashlib.sha256(font_path.read_bytes()).hexdigest(),
                 "license": "task-service/src/main/resources/fonts/LiberationSans-LICENSE.txt"},
        "truth": {"normal": "\n".join(LINES) + "\n",
                  "edited": "\n".join(LINES).replace("-0054.80", "-0068.95") + "\n"},
        "sources": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()},
        "actions": actions
    }
    if args.after:
        manifest["cases"] = [{"id": "revenue",
                              "beforeWord": str((args.after / "revenue-word-result.docx").resolve()),
                              "beforeOffice": str((args.after / "revenue-office-result.pdf").resolve())}]
    (args.out / "expected.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
