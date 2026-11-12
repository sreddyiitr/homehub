#!/usr/bin/env python3
"""Human-readable summary of telemetry/*.jsonl (OTLP JSON, one object per line).

    python3 scripts/telemetry_summary.py [--trace TRACE_ID] [--last N]

Shows each trace as an indented span tree (with HomeHub attributes), the log
records with their trace/span ids, and the metric names that arrived. Read
this FIRST when debugging: the trace shows exactly where a request went.
"""
import argparse
import json
import pathlib
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent / "telemetry"
INTERESTING = ("homehub.", "enduser.", "http.", "url.", "client.", "user_agent.", "rpc.", "aws.", "db.")
KINDS = {0: "UNSPECIFIED", 1: "INTERNAL", 2: "SERVER", 3: "CLIENT", 4: "PRODUCER", 5: "CONSUMER"}


def records(signal):
    path = ROOT / signal / f"{signal}.jsonl"
    if not path.exists():
        return
    for line in path.read_text(errors="replace").splitlines():
        line = line.strip("\x00").strip()
        if line:
            yield json.loads(line)


def attr_value(v):
    for k in ("stringValue", "intValue", "boolValue", "doubleValue"):
        if k in v:
            return v[k]
    if "arrayValue" in v:
        return [attr_value(x) for x in v["arrayValue"].get("values", [])]
    return v


def attrs(items, only_interesting=True):
    out = {}
    for a in items or []:
        if not only_interesting or a["key"].startswith(INTERESTING):
            out[a["key"]] = attr_value(a.get("value", {}))
    return out


def resource_service(res):
    return attrs(res.get("resource", {}).get("attributes"), only_interesting=False).get("service.name", "?")


def show_traces(trace_filter, last):
    spans = []
    for batch in records("traces"):
        for rs in batch.get("resourceSpans", []):
            svc = resource_service(rs)
            for ss in rs.get("scopeSpans", []):
                for s in ss.get("spans", []):
                    s["_svc"] = svc
                    spans.append(s)
    by_trace = defaultdict(list)
    for s in spans:
        by_trace[s["traceId"]].append(s)
    trace_ids = sorted(by_trace, key=lambda t: min(int(s["startTimeUnixNano"]) for s in by_trace[t]))
    if trace_filter:
        trace_ids = [t for t in trace_ids if t.startswith(trace_filter)]
    trace_ids = trace_ids[-last:]
    print(f"== TRACES ({len(trace_ids)} of {len(by_trace)} shown, {len(spans)} spans total)")
    for tid in trace_ids:
        group = by_trace[tid]
        ids = {s["spanId"] for s in group}
        children = defaultdict(list)
        for s in group:
            parent = s.get("parentSpanId") or ""
            children[parent if parent in ids else "ROOT"].append(s)

        def walk(span, depth):
            ms = (int(span["endTimeUnixNano"]) - int(span["startTimeUnixNano"])) / 1e6
            status = span.get("status", {}).get("code", 0)
            flag = "  ERROR" if status == 2 else ""
            print(f"  {'  ' * depth}{span['name']}  [{span['_svc']} {KINDS.get(span.get('kind', 0))}, {ms:.1f} ms]{flag}")
            a = attrs(span.get("attributes"))
            if a:
                print(f"  {'  ' * depth}    {a}")
            for ev in span.get("events", []):
                print(f"  {'  ' * depth}    event: {ev['name']} {attrs(ev.get('attributes'), False)}")
            for c in sorted(children[span["spanId"]], key=lambda x: int(x["startTimeUnixNano"])):
                walk(c, depth + 1)

        services = sorted({s["_svc"] for s in group})
        print(f"\n trace {tid}  (services: {', '.join(services)})")
        for root in sorted(children["ROOT"], key=lambda x: int(x["startTimeUnixNano"])):
            walk(root, 1)


def show_logs(trace_filter, last):
    logs = []
    for batch in records("logs"):
        for rl in batch.get("resourceLogs", []):
            for sl in rl.get("scopeLogs", []):
                logs.extend(sl.get("logRecords", []))
    if trace_filter:
        logs = [l for l in logs if l.get("traceId", "").startswith(trace_filter)]
    print(f"\n== LOGS ({min(last * 5, len(logs))} of {len(logs)} shown)")
    for l in logs[-last * 5:]:
        body = attr_value(l.get("body", {}))
        tid = l.get("traceId") or "-" * 32
        sid = l.get("spanId") or "-" * 16
        print(f"  {l.get('severityText', '?'):5} trace={tid[:12]}… span={sid[:8]}… {str(body)[:90]}")
        a = attrs(l.get("attributes"), only_interesting=False)
        a = {k: v for k, v in a.items() if not k.startswith(("thread.", "code."))}
        if a:
            print(f"        {a}")


def show_metrics():
    names = defaultdict(int)
    for batch in records("metrics"):
        for rm in batch.get("resourceMetrics", []):
            for sm in rm.get("scopeMetrics", []):
                for m in sm.get("metrics", []):
                    names[m["name"]] += 1
    print(f"\n== METRICS ({len(names)} distinct names; count = export batches seen)")
    for n in sorted(names):
        print(f"  {n}  x{names[n]}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", help="only this trace id (prefix ok)")
    ap.add_argument("--last", type=int, default=5, help="show the last N traces (default 5)")
    args = ap.parse_args()
    show_traces(args.trace, args.last)
    show_logs(args.trace, args.last)
    if not args.trace:
        show_metrics()
