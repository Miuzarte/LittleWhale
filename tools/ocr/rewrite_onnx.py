"""Rewrite the PaddleOCR ONNX exports into a form the QNN HTP builder can compile.

What the app needs is only the first step:

1. every spatial dimension in the official export is dynamic (`DynamicDimension.0`) and QNN does
   not support dynamic shapes at all, so the input is pinned to one shape here

The other two are **off by default** and only exist because the fp32 route looked like it needed
them. It does not: on the fp32 path the HTP graph builder cannot create `HardSigmoid`, `Erf` or
the `Clip` standing in for them ("could not create op"), but with `--float_bitwidth 16` the same
graph builds with no rewriting whatsoever. See docs/step8-record.md. They are kept because the
transformations are the obvious first thing to reach for if a future model or a quantized route
hits the same wall:

2. PPLCNetV3's activation is exported as `HardSigmoid` + `Mul`, and `HardSigmoid` is not a QNN op
   (`OpDef/SupportedOps.html` lists it zero times), so it can be split into `Mul` / `Add` / `Clip`
3. GELU is exported as `0.5 * x * (1 + erf(x / sqrt(2)))` and `Erf` is not a QNN op either, so
   the subgraph can collapse into a single `Gelu` node (opset 20) or the tanh approximation

usage:
    python rewrite_onnx.py --kind det --input det.onnx --output det_pinned.onnx
    python rewrite_onnx.py --kind rec --input rec.onnx --output rec_pinned.onnx
"""

import argparse
import math

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

# The recognizer is exported with the 320-wide bucket PaddleOCR recommends for a
# 48-pixel line height, the detector with a square 640
SHAPES = {
    "det": (1, 3, 640, 640),
    "rec": (1, 3, 48, 320),
}

GELU_OPSET = 20


def scalar(name, value):
    return numpy_helper.from_array(np.array(value, dtype=np.float32), name)


def unique(graph, stem):
    taken = {i.name for i in graph.initializer} | {n.name for n in graph.node}
    for v in graph.value_info:
        taken.add(v.name)
    name = stem
    n = 0
    while name in taken:
        n += 1
        name = f"{stem}_{n}"
    return name


def producers(graph):
    return {o: n for n in graph.node for o in n.output}


def consumers(graph):
    out = {}
    for n in graph.node:
        for i in n.input:
            out.setdefault(i, []).append(n)
    return out


def const_value(inits, name):
    if name not in inits:
        return None
    value = numpy_helper.to_array(inits[name])
    return value if value.size == 1 else None


def pin_input_shape(model, shape, kind):
    graph = model.graph
    if len(graph.input) != 1:
        raise SystemExit(f"expected exactly one graph input, got {[i.name for i in graph.input]}")
    x = graph.input[0]
    del x.type.tensor_type.shape.dim[:]
    for d in shape:
        x.type.tensor_type.shape.dim.add().dim_value = d
    # a fixed input makes every down-stream shape inferable, which is what QNN needs
    model = onnx.shape_inference.infer_shapes(model, strict_mode=False)
    # the recognizer declares its time axis as `floor(floor(floor(W/2 - 1/2)/2)/2 - 1/2) + 1`,
    # which the shape inferencer cannot evaluate, so it is filled in from the pinned width here
    # (320 -> 159 -> 79 -> 39, +1)
    if kind == "rec":
        out = model.graph.output[0]
        dims = out.type.tensor_type.shape.dim
        if not dims[1].HasField("dim_value"):
            del dims[1].dim_value
            dims[1].dim_value = shape[3] // 2 // 2 // 2 + 1
    dynamic = []
    for vi in list(model.graph.value_info) + list(model.graph.output):
        for d in vi.type.tensor_type.shape.dim:
            if not d.HasField("dim_value"):
                dynamic.append(vi.name)
                break
    return model, sorted(set(dynamic))


def rewrite_hardsigmoid(model):
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}
    keep = [i for i in graph.initializer]
    out = []
    hits = 0
    for node in graph.node:
        if node.op_type != "HardSigmoid":
            out.append(node)
            continue
        attrs = {a.name: a.f for a in node.attribute}
        alpha = attrs.get("alpha", 0.2)
        beta = attrs.get("beta", 0.5)
        x = node.input[0]
        tag = node.name or f"hs{hits}"
        a_name = unique(graph, f"lw_{tag}_alpha")
        b_name = unique(graph, f"lw_{tag}_beta_item")
        lo_name = unique(graph, f"lw_{tag}_clip_min")
        hi_name = unique(graph, f"lw_{tag}_clip_max")
        keep += [scalar(a_name, alpha), scalar(b_name, beta), scalar(lo_name, 0.0), scalar(hi_name, 1.0)]
        scaled = f"{tag}/mul"
        shifted = f"{tag}/add"
        out.append(helper.make_node("Mul", [x, a_name], [scaled], name=f"{tag}/Mul"))
        out.append(helper.make_node("Add", [scaled, b_name], [shifted], name=f"{tag}/Add"))
        out.append(
            helper.make_node("Clip", [shifted, lo_name, hi_name], list(node.output), name=f"{tag}/Clip")
        )
        hits += 1
    del graph.node[:]
    graph.node.extend(out)
    del graph.initializer[:]
    graph.initializer.extend(keep)
    return hits


def rewrite_gelu(model, mode):
    """0.5 * x * (1 + erf(x / sqrt(2))) -> something QNN can build

    `tanh` keeps the model at its native opset and uses the standard approximation
    (the same one `Gelu(approximate="tanh")` and PyTorch use), `op` emits the real
    `Gelu` node, which forces the whole model up to opset 20 and therefore drags
    every operator that changed shape between 14 and 20 along with it
    """
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}
    prod = producers(graph)
    cons = consumers(graph)
    drop = set()
    replacements = []
    hits = 0
    for node in graph.node:
        if node.op_type != "Mul" or len(node.input) != 2:
            continue
        for x, other in (node.input, node.input[::-1]):
            add = prod.get(other)
            if add is None or add.op_type != "Add" or len(add.input) != 2:
                continue
            erf_in = None
            addend = None
            for i in add.input:
                if i == other:
                    continue
                p = prod.get(i)
                if p is not None and p.op_type == "Erf":
                    erf_in = i
                else:
                    v = const_value(inits, i)
                    if v is not None:
                        addend = v
            if erf_in is None or addend is None:
                continue
            erf = prod[erf_in]
            div = prod.get(erf.input[0])
            if div is None or div.op_type != "Div" or div.input[0] != x:
                continue
            divisor = const_value(inits, div.input[1])
            if divisor is None:
                continue
            if abs(float(divisor) - math.sqrt(2)) > 1e-4 or abs(float(addend) - 1.0) > 1e-6:
                continue
            # the halving is a separate Mul right after, and both rewrites carry it
            halves = [c for c in cons.get(node.output[0], []) if c.op_type == "Mul" and len(c.input) == 2]
            half = None
            for c in halves:
                other_in = [i for i in c.input if i != node.output[0]]
                v = const_value(inits, other_in[0]) if other_in else None
                if v is not None and abs(float(v) - 0.5) < 1e-6:
                    half = c
                    break
            if half is None:
                continue
            for n in (div, erf, add, node, half):
                drop.add(n.name)
            replacements.append((x, half.output[0], node.name))
            hits += 1
            break

    if not hits:
        return 0

    extra_inits = []
    if mode == "tanh":
        # gelu(x) ~= 0.5 * x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 * x^3)))
        c_cubic = unique(graph, "lw_gelu_cubic")
        c_scale = unique(graph, "lw_gelu_scale")
        c_half = unique(graph, "lw_gelu_half")
        c_one = unique(graph, "lw_gelu_one")
        extra_inits = [
            scalar(c_cubic, 0.044715),
            scalar(c_scale, math.sqrt(2.0 / math.pi)),
            scalar(c_half, 0.5),
            scalar(c_one, 1.0),
        ]

    def build(src, dst, tag):
        if mode == "op":
            return [helper.make_node("Gelu", [src], [dst], name=f"{tag}/Gelu")]
        sq = f"{tag}/x2"
        cu = f"{tag}/x3"
        cubic = f"{tag}/cubic"
        inner = f"{tag}/inner"
        scaled = f"{tag}/scaled"
        t = f"{tag}/tanh"
        plus = f"{tag}/plus1"
        prod_ = f"{tag}/prod"
        return [
            helper.make_node("Mul", [src, src], [sq], name=f"{tag}/Mul_x2"),
            helper.make_node("Mul", [sq, src], [cu], name=f"{tag}/Mul_x3"),
            helper.make_node("Mul", [cu, c_cubic], [cubic], name=f"{tag}/Mul_cubic"),
            helper.make_node("Add", [src, cubic], [inner], name=f"{tag}/Add_inner"),
            helper.make_node("Mul", [inner, c_scale], [scaled], name=f"{tag}/Mul_scale"),
            helper.make_node("Tanh", [scaled], [t], name=f"{tag}/Tanh"),
            helper.make_node("Add", [t, c_one], [plus], name=f"{tag}/Add_one"),
            helper.make_node("Mul", [src, plus], [prod_], name=f"{tag}/Mul_prod"),
            helper.make_node("Mul", [prod_, c_half], [dst], name=f"{tag}/Mul_half"),
        ]

    survivors = [n for n in graph.node if n.name not in drop]

    # keep it topological: each replacement goes just before the first node reading its output
    before = {}
    leftover = []
    for src, dst, tag in replacements:
        nodes = build(src, dst, tag)
        first = next((n for n in survivors if dst in n.input), None)
        if first is None:
            leftover.extend(nodes)
        else:
            before.setdefault(first.name, []).extend(nodes)

    final = []
    for node in survivors:
        final.extend(before.get(node.name, []))
        final.append(node)
    final.extend(leftover)

    del graph.node[:]
    graph.node.extend(final)
    del graph.initializer[:]
    graph.initializer.extend(list(inits.values()) + extra_inits)
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", choices=sorted(SHAPES), required=True)
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument(
        "--gelu",
        choices=("tanh", "op", "none"),
        default="none",
        help="how to replace the erf form of GELU; not needed in fp16, kept for experiments",
    )
    ap.add_argument(
        "--hardsigmoid",
        choices=("rewrite", "keep"),
        default="keep",
        help="'rewrite' splits HardSigmoid into Mul/Add/Clip; not needed in fp16, kept for experiments",
    )
    args = ap.parse_args()

    model = onnx.load(args.input, load_external_data=True)
    print(f"loaded {args.input}: ir={model.ir_version} opset={[o.version for o in model.opset_import]}")

    model, dynamic = pin_input_shape(model, SHAPES[args.kind], args.kind)
    print(f"pinned input to {SHAPES[args.kind]}, still-dynamic value infos: {dynamic or 'none'}")

    before = len(model.graph.node)
    hs = rewrite_hardsigmoid(model) if args.hardsigmoid == "rewrite" else 0
    print(f"HardSigmoid -> Mul/Add/Clip: {hs} sites ({args.hardsigmoid})")

    gelu = rewrite_gelu(model, args.gelu) if args.gelu != "none" else 0
    print(f"erf-GELU -> {args.gelu}: {gelu} sites")
    if gelu and args.gelu == "op":
        for o in model.opset_import:
            if o.domain in ("", "ai.onnx"):
                o.version = max(o.version, GELU_OPSET)
        print(f"opset raised to {[o.version for o in model.opset_import]}")

    print(f"nodes {before} -> {len(model.graph.node)}")
    onnx.checker.check_model(model)
    onnx.save(model, args.output)
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
