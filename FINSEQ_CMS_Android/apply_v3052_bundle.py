#!/usr/bin/env python3
from pathlib import Path
import base64, io, tarfile

root = Path(__file__).resolve().parent
parts = sorted((root / "patch_v3052").glob("part*.b64"))
data = "".join(p.read_text().strip() for p in parts)
with tarfile.open(fileobj=io.BytesIO(base64.b64decode(data)), mode="r:gz") as tf:
    tf.extractall(root)
print("Applied FINSEQ CMS v3.0.5.2 patch")
