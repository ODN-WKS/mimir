"""Checks that Ingrid's pre-built model (.ort) is the same voice as the original (.onnx): the same
settings (sample rate, language and the rest) and, with the random variation turned off, the same
sound for a few lines. Used by tools/ingrid-ort.sh; fails the app build if anything differs."""
import sys

import numpy as np
import onnxruntime as ort

original, built = sys.argv[1], sys.argv[2]


def session(path):
    o = ort.SessionOptions()
    o.intra_op_num_threads = 2
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])


a, b = session(original), session(built)
ma, mb = a.get_modelmeta().custom_metadata_map, b.get_modelmeta().custom_metadata_map
if ma != mb:
    sys.exit(f"the pre-built model's settings differ: {ma} vs {mb}")
names = [i.name for i in a.get_inputs()]
for ids in ([0, 20, 0, 59, 0, 21, 0, 24, 0, 27, 0, 3, 0], list(range(1, 60)), [0, 50, 0, 61, 0, 88, 0, 14, 0]):
    x = np.array([ids], dtype=np.int64)
    feeds = {"input": x, "input_lengths": np.array([len(ids)], dtype=np.int64), "scales": np.array([0, 1, 0], dtype=np.float32)}
    feeds = {k: v for k, v in feeds.items() if k in names}
    ya, yb = a.run(None, feeds)[0], b.run(None, feeds)[0]
    if ya.shape != yb.shape or float(np.abs(ya - yb).max()) > 1e-4:
        sys.exit(f"the pre-built model sounds different ({ya.shape} vs {yb.shape})")
print("Ingrid's pre-built model matches the original")
