#!/usr/bin/env python3
"""Offline line-frame experiment; no production renderer or accepted heuristic.

Positioned mode keeps word runs and uses absolute tab stops inside one line
frame. Flow/mono intentionally relax word geometry for diagnostic comparison;
never adopt them on extraction CER alone. Source media and masks are preserved.
"""
import argparse
import copy
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from PIL import ImageFont

NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
      "v": "urn:schemas-microsoft-com:vml"}
for prefix, uri in NS.items():
    ET.register_namespace(prefix, uri)


def w(name):
    return "{" + NS["w"] + "}" + name


def style(shape):
    return dict(pair.split(":", 1) for pair in shape.get("style").split(";") if ":" in pair)


def pt(properties, name):
    return float(properties[name].replace("pt", ""))


def run_text(shape):
    return "".join(t.text or "" for t in shape.findall(".//w:t", NS))


def transform(source, destination, mode="positioned"):
    with zipfile.ZipFile(source) as archive:
        root = ET.fromstring(archive.read("word/document.xml"))
        groups = {}
        parents = {child: parent for parent in root.iter() for child in parent}
        for shape in root.findall(".//v:rect", NS):
            if shape.find(".//w:txbxContent", NS) is not None:
                match = re.match(r"(.*)-word-\d+-\d+$", shape.get("id", ""))
                if match:
                    groups.setdefault(match.group(1), []).append(shape)
        boxes = {}
        for key, shapes in groups.items():
            properties = [style(s) for s in shapes]
            boxes[key] = (min(pt(s, "margin-left") for s in properties),
                          pt(properties[0], "margin-top"),
                          max(pt(s, "margin-left") + pt(s, "width") for s in properties))
        ambiguous_columns = any(abs(a[1] - b[1]) < 8 and (a[2] + 12 < b[0] or b[2] + 12 < a[0])
                                for i, a in enumerate(boxes.values())
                                for b in list(boxes.values())[i + 1:])
        intervals = sorted((box[0], box[2]) for box in boxes.values())
        right_edge = intervals[0][1] if intervals else 0
        for left_edge, right in intervals[1:]:
            ambiguous_columns |= left_edge - right_edge > 12
            right_edge = max(right_edge, right)
        table = root.find(".//w:tbl", NS) is not None
        changed = 0
        skipped = {}
        for key, shapes in groups.items():
            properties = [style(s) for s in shapes]
            lefts = [pt(s, "margin-left") for s in properties]
            reason = None
            if len(shapes) < 2:
                reason = "single word"
            elif table or ambiguous_columns:
                reason = "table or simultaneous disjoint columns"
            elif any(abs(float(s.get("rotation", "0"))) > .01 for s in properties):
                reason = "rotation"
            elif any(lefts[i] <= lefts[i - 1] for i in range(1, len(lefts))):
                reason = "non-monotone word positions"
            elif max(pt(s, "margin-top") for s in properties) - min(pt(s, "margin-top") for s in properties) > .05:
                reason = "mixed baselines"
            elif any(lefts[i] - lefts[i - 1] - pt(properties[i - 1], "width") > 12
                     for i in range(1, len(lefts))):
                reason = "large unknown word gap"
            if reason:
                skipped[key] = reason
                continue
            original_text = "".join(run_text(s) for s in shapes)
            runs = [copy.deepcopy(s.find(".//w:r", NS)) for s in shapes]
            first = shapes[0]
            left = lefts[0]
            right = max(pt(s, "margin-left") + pt(s, "width") for s in properties)
            properties[0]["width"] = str(right - left) + "pt"
            first.set("style", ";".join(k + ":" + value for k, value in properties[0].items()))
            paragraph = first.find(".//w:p", NS)
            ppr = paragraph.find("w:pPr", NS)
            for child in list(paragraph):
                if child.tag == w("r"):
                    paragraph.remove(child)
            if mode == "positioned":
                tabs = ET.SubElement(ppr, w("tabs"))
                for i, original in enumerate(runs):
                    run = copy.deepcopy(original)
                    if i:
                        tab = ET.SubElement(tabs, w("tab"))
                        tab.set(w("val"), "left")
                        tab.set(w("pos"), str(round((lefts[i] - left) * 20)))
                        tab_run = ET.SubElement(paragraph, w("r"))
                        tab_run.append(copy.deepcopy(run.find("w:rPr", NS)))
                        ET.SubElement(tab_run, w("tab"))
                    text = run.find("w:t", NS)
                    trailing = text.text[len(text.text.rstrip()):]
                    text.text = text.text.rstrip()
                    paragraph.append(run)
                    if trailing:
                        space = ET.SubElement(paragraph, w("r"))
                        rpr = copy.deepcopy(run.find("w:rPr", NS))
                        scale = rpr.find("w:w", NS)
                        if scale is None:
                            scale = ET.SubElement(rpr, w("w"))
                        scale.set(w("val"), "1")
                        space.append(rpr)
                        value = ET.SubElement(space, w("t"))
                        value.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
                        value.text = trailing
            else:
                run = copy.deepcopy(runs[0])
                run.find("w:t", NS).text = original_text
                rpr = run.find("w:rPr", NS)
                scale = rpr.find("w:w", NS)
                if scale is None:
                    scale = ET.SubElement(rpr, w("w"))
                size = float(rpr.find("w:sz", NS).get(w("val"))) / 2
                family = "Mono" if mode == "mono" else "Sans"
                font = ImageFont.truetype("/usr/share/fonts/truetype/liberation/Liberation" + family + "-Regular.ttf", 100)
                natural = font.getlength(original_text) * size / 100
                desired = right - left - max(1.5 * 72 / 25.4, size * .8)
                scale.set(w("val"), str(round(100 * desired / natural)))
                if mode == "mono":
                    fonts = rpr.find("w:rFonts", NS)
                    for kind in ["ascii", "hAnsi"]:
                        fonts.set(w(kind), "Courier New")
                paragraph.append(run)
            for shape in shapes[1:]:
                parents[shape].remove(shape)
            changed += 1
        assert "".join(t.text or "" for t in root.findall(".//w:t", NS)) == "".join(
            t.text or "" for t in ET.fromstring(archive.read("word/document.xml")).findall(".//w:t", NS))
        destination.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
            for item in archive.infolist():
                data = ET.tostring(root, encoding="utf-8", xml_declaration=True) if item.filename == "word/document.xml" else archive.read(item)
                output.writestr(item, data)
    return {"groups": len(groups), "changed": changed, "skipped": skipped,
            "mode": mode, "file": str(destination)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--mode", choices=["positioned", "flow", "mono"], default="positioned")
    args = parser.parse_args()
    print(json.dumps(transform(args.source, args.destination, args.mode)))


if __name__ == "__main__":
    main()
