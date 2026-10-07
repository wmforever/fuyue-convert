#!/usr/bin/env python3
"""Opt-in local PDF OCR sidecar. Models must be prepared beforehand; no downloads here."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path

os.environ.setdefault("OMP_NUM_THREADS", "2")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")

MODELS = {
    "PP-OCRv6_det_small.onnx": "090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f",
    "PP-OCRv6_rec_small.onnx": "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884",
    "ch_ppocr_mobile_v2.0_cls_mobile.onnx": "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--models", required=True, type=Path)
    args = parser.parse_args()
    if importlib.metadata.version("rapidocr") != "3.9.2":
        raise RuntimeError("This experimental sidecar requires rapidocr==3.9.2")
    for name, expected in MODELS.items():
        path = args.models / name
        if not path.is_file():
            raise RuntimeError(f"Missing offline model: {name}")
        with path.open("rb") as model:
            actual = hashlib.file_digest(model, "sha256").hexdigest()
        if actual != expected:
            raise RuntimeError(f"Missing or mismatched offline model: {name}")
    from rapidocr import RapidOCR

    engine = RapidOCR(params={
        "Global.model_root_dir": str(args.models),
        "Global.max_side_len": 4000,
        "Det.limit_side_len": 1920,
        "Det.model_path": str(args.models / "PP-OCRv6_det_small.onnx"),
        "Rec.model_path": str(args.models / "PP-OCRv6_rec_small.onnx"),
        "Cls.model_path": str(args.models / "ch_ppocr_mobile_v2.0_cls_mobile.onnx"),
        "EngineConfig.onnxruntime.intra_op_num_threads": 2,
        "EngineConfig.onnxruntime.inter_op_num_threads": 1,
    })
    result = engine(str(args.input))
    data = result.to_json() or []
    if len(data) > 10000:
        raise RuntimeError("OCR result exceeds entry limit")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    temporary.replace(args.output)


if __name__ == "__main__":
    main()
