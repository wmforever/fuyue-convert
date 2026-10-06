#!/usr/bin/env python3
"""Audit downloaded Word/Office pairs; visible spacing is separate from text CER.

The manifest supplies cases with id, beforeWord and beforeOffice paths. Generated
artifacts and reports must remain in ignored QA directories. No OCR is invoked.
"""
import argparse
import copy
import json
import re
import zipfile
from pathlib import Path

import fitz
from lxml import etree as E
from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
V = "{urn:schemas-microsoft-com:vml}"


def word_parts(path):
    with zipfile.ZipFile(path) as z:
        root = E.fromstring(z.read("word/document.xml"))
        images = {n: z.read(n) for n in z.namelist() if n.startswith("word/media/")}
    frames = {n.get("id"): n for n in root.iter(V + "rect") if n.find(".//" + W + "txbxContent") is not None}
    masks = [E.tostring(n, method="c14n") for n in root.iter(V + "rect") if n not in frames.values()]
    return frames, masks, images


def value(frame):
    return "".join(t.text or "" for t in frame.iter(W + "t"))


def dimensions(frame):
    return {k: float(v) for k, v in re.findall(r"(margin-left|margin-top|width|height):([0-9.]+)pt", frame.get("style"))}


def without_width_scale(frame):
    frame = copy.deepcopy(frame)
    frame.set("style", re.sub(r"width:[0-9.]+pt", "width:COMPARED", frame.get("style")))
    for n in list(frame.iter(W + "w")):
        n.getparent().remove(n)
    return E.tostring(frame, method="c14n")


def scale(frame):
    node = frame.find(".//" + W + "w")
    return int(node.get(W + "val")) if node is not None else 100


def title_gaps(page):
    words = []
    # Office's extraction can retain a space even when its glyphs overlap.
    for block in page.get_text("rawdict")["blocks"]:
        for line in block.get("lines", []):
            if line["bbox"][1] > 45:
                continue
            current = []
            for span in line["spans"]:
                for char in span["chars"]:
                    if char["c"].isspace():
                        if current:
                            words.append(current)
                            current = []
                    else:
                        current.append(char)
            if current:
                words.append(current)
    return [{"left": "".join(c["c"] for c in a), "right": "".join(c["c"] for c in b),
             "gapPt": b[0]["bbox"][0] - a[-1]["bbox"][2]} for a, b in zip(words, words[1:])]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    assert not args.out.exists(), "retain every previous audit"
    results = []
    for case in json.loads(args.manifest.read_text())["cases"]:
        name = case["id"]
        before, masks, images = word_parts(ROOT / case["beforeWord"])
        after, new_masks, new_images = word_parts(args.after / (name + "-word-result.docx"))
        assert before.keys() == after.keys(), name
        assert masks == new_masks and images == new_images, (name, "original scans/masks changed")
        changed = []
        for key, old in before.items():
            new = after[key]
            assert value(old) == value(new), (name, key, "text changed")
            if E.tostring(old, method="c14n") != E.tostring(new, method="c14n"):
                assert re.fullmatch(r"[A-Za-z]{3,32}", value(old).strip()), (name, key, "numeric/unknown word changed")
                assert without_width_scale(old) == without_width_scale(new), (name, key, "non-width property changed")
                assert 60 <= scale(new) < scale(old), (name, key, "unbounded scale")
                changed.append({"id": key, "text": value(old), "oldScale": scale(old), "newScale": scale(new)})
        with fitz.open(ROOT / case["beforeOffice"]) as oldpdf, fitz.open(args.after / (name + "-office-result.pdf")) as newpdf:
            assert len(oldpdf) == len(newpdf), name
            assert "".join("".join(p.get_text().split()) for p in oldpdf) == "".join("".join(p.get_text().split()) for p in newpdf), (name, "Office content/order changed")
            pixel_changes = []
            for page_index, (oldpage, newpage) in enumerate(zip(oldpdf, newpdf, strict=True)):
                assert oldpage.rect == newpage.rect, name
                pictures = []
                for page in (oldpage, newpage):
                    pix = page.get_pixmap(dpi=150, alpha=False)
                    pictures.append(Image.frombytes("RGB", (pix.width, pix.height), pix.samples))
                diff = ImageChops.difference(*pictures)
                pixel_changes.append(diff.width * diff.height - diff.convert("L").histogram()[0])
                draw = ImageDraw.Draw(diff)
                for item in changed:
                    # OCR ids explicitly carry original page numbers.
                    if re.search(r"-p" + str(page_index + 1) + r"-", item["id"]) is None:
                        continue
                    for frame in (before[item["id"]], after[item["id"]]):
                        box = dimensions(frame)
                        k = 150 / 72
                        draw.rectangle(((box["margin-left"] - 2) * k, (box["margin-top"] - 2) * k,
                                        (box["margin-left"] + box["width"] + 2) * k,
                                        (box["margin-top"] + box["height"] + 2) * k), fill=(0, 0, 0))
                assert diff.getbbox() is None, (name, page_index + 1, "pixels changed outside affected text frames")
            gaps = {"before": title_gaps(oldpdf[0]), "after": title_gaps(newpdf[0])} if name in ("review", "revenue") else None
            if gaps:
                assert min(g["gapPt"] for g in gaps["before"]) < 0, (name, "negative control must reproduce collision")
                assert min(g["gapPt"] for g in gaps["after"]) >= 2, (name, "actual glyph gap still insufficient")
            results.append({"case": name, "pages": len(newpdf), "words": len(after), "changed": changed,
                            "originalScansMasksNumbersAndPositionPreserved": True,
                            "pixelChangesPerPage": pixel_changes, "titleGaps": gaps})
    args.out.write_text(json.dumps({"status": "passed", "cases": results}, indent=2) + "\n")
    print(json.dumps({"cases": len(results), "changedWords": sum(len(c["changed"]) for c in results)}))


if __name__ == "__main__":
    main()
