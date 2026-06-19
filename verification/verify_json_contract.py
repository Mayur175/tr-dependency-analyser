"""
Verify the JSON wire contract between the ABAP backend (to_json) and the
Eclipse plugin parser (AnalysisResult.fromJson).

This script:

  1. Runs the same fixtures through simulate_pipeline.run().
  2. Serialises each Result to JSON in EXACTLY the shape ZCL_GCTS_TR_ANALYZER
     produces (manually built string, no json.dumps - mirrors the ABAP code).
  3. Runs a pure-Python re-implementation of AnalysisResult.fromJson() against
     that JSON.
  4. Asserts the round-trip preserves: tr label, summary counts, every
     cluster, every edge, every pull-order step.

Why bother:
  AnalysisResult.fromJson is hand-rolled (no Jackson/Gson/Parsson) - it is
  the most fragile component on the Java side. This test catches contract
  drift between the two ends without needing a live SAP system or a Java
  test runner.

Run:    python3 verify_json_contract.py
Exit 0  on success, exit 1 on failure.
"""

from __future__ import annotations
import json
import re
import sys

from simulate_pipeline import (
    Result,
    cluster_risk,
    fixture_scenario_a,
    fixture_scenario_b,
    fixture_same_object_conflict,
    fixture_chain,
    run,
)


# ----------------------------------------------------------------------------
# 1. ABAP-equivalent JSON serialiser (mirrors zcl_gcts_tr_analyzer->to_json)
# ----------------------------------------------------------------------------
def json_escape(s: str) -> str:
    """Same character set the ABAP json_escape( ) replaces."""
    return (s
            .replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\n", "\\n")
            .replace("\r\n", "\\n"))


def to_json_abap_style(r: Result, label: str) -> str:
    """
    Produce JSON in the exact shape ZCL_GCTS_TR_ANALYZER->to_json emits.
    Hand-built string concatenation, no json.dumps - this is what the ABAP
    code does today.
    """
    unique_tasks = sorted({t for tasks in r.clusters.values() for t in tasks})

    summary = (
        f'"tr":"{json_escape(label)}",'
        f'"taskCount":{len(unique_tasks)},'
        f'"objectCount":{sum(len(tasks) for tasks in r.clusters.values())},'
        f'"edgeCount":{len(r.edges)}'
    )

    cluster_parts = []
    for tasks in r.clusters.values():
        risk = cluster_risk(tasks, r.edges)
        tasks_arr = ",".join(f'"{json_escape(t)}"' for t in tasks)
        edges_arr_parts = []
        for e in r.edges:
            if e.source_task not in tasks:
                continue
            edges_arr_parts.append(
                "{"
                f'"from":"{json_escape(e.source_object)}",'
                f'"fromTask":"{json_escape(e.source_task)}",'
                f'"to":"{json_escape(e.target_object)}",'
                f'"toTask":"{json_escape(e.target_task)}",'
                f'"kind":"{json_escape(e.kind)}",'
                f'"detail":"{json_escape(e.detail)}"'
                "}"
            )
        edges_arr = ",".join(edges_arr_parts)
        cluster_parts.append(
            "{"
            f'"risk":"{risk}",'
            f'"tasks":[{tasks_arr}],'
            f'"edges":[{edges_arr}]'
            "}"
        )

    pull_parts = []
    for step in r.pull_order:
        st = ",".join(f'"{json_escape(t)}"' for t in step.tasks)
        pull_parts.append(
            f'{{"step":{step.step},'
            f'"action":"{json_escape(step.action)}",'
            f'"tasks":[{st}]}}'
        )

    return (
        '{"version":"1.1",'
        + summary
        + ',"clusters":[' + ",".join(cluster_parts) + "]"
        + ',"pullOrder":[' + ",".join(pull_parts) + "]"
        + "}"
    )


# ----------------------------------------------------------------------------
# 2. Pure-Python clone of com.gmw.gcts.analyzer.model.AnalysisResult.fromJson
#    Same logic as the Java JsonReader inner class.
# ----------------------------------------------------------------------------
class JavaParserClone:
    """
    Mirrors the Java JsonReader exactly:
      - stringField       : finds "key": "value" by index scanning
      - intField          : finds "key": <digits>
      - arrayContent      : finds "key": [ ... ] balanced brackets
      - splitObjects      : splits a JSON array of {} blocks at depth 0
    """

    def __init__(self, src: str):
        self.src = src or ""

    def string_field(self, key: str):
        pat = f'"{key}"'
        ki = self.src.find(pat)
        if ki < 0:
            return None
        colon = self.src.find(":", ki + len(pat))
        if colon < 0:
            return None
        q1 = self.src.find('"', colon + 1)
        if q1 < 0:
            return None
        q2 = self.src.find('"', q1 + 1)
        while q2 > 0 and self.src[q2 - 1] == "\\":
            q2 = self.src.find('"', q2 + 1)
        if q2 < 0:
            return None
        raw = self.src[q1 + 1:q2]
        return (raw
                .replace('\\"', '"')
                .replace("\\n", "\n")
                .replace("\\\\", "\\"))

    def int_field(self, key: str) -> int:
        pat = f'"{key}"'
        ki = self.src.find(pat)
        if ki < 0:
            return 0
        colon = self.src.find(":", ki + len(pat))
        if colon < 0:
            return 0
        out = []
        for ch in self.src[colon + 1:]:
            if ch.isdigit():
                out.append(ch)
            elif not ch.isspace() and out:
                break
        return int("".join(out)) if out else 0

    def array_content(self, key: str):
        pat = f'"{key}"'
        ki = self.src.find(pat)
        if ki < 0:
            return None
        bracket = self.src.find("[", ki + len(pat))
        if bracket < 0:
            return None
        depth = 0
        i = bracket
        while i < len(self.src):
            if self.src[i] == "[":
                depth += 1
            elif self.src[i] == "]":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        return self.src[bracket + 1:i]

    @staticmethod
    def split_objects(arr: str):
        out, depth, start = [], 0, -1
        for i, c in enumerate(arr):
            if c == "{":
                if depth == 0:
                    start = i
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0 and start >= 0:
                    out.append(arr[start:i + 1])
                    start = -1
        return out

    def string_array(self, key: str):
        arr = self.array_content(key)
        if not arr or not arr.strip():
            return []
        out = []
        for tok in arr.split(","):
            s = tok.strip()
            if s.startswith('"') and s.endswith('"'):
                out.append(s[1:-1])
        return out


def java_parse(src: str) -> dict:
    """Reproduce AnalysisResult.fromJson result as a plain dict."""
    r = JavaParserClone(src)
    parsed = {
        "tr":          r.string_field("tr"),
        "taskCount":   r.int_field("taskCount"),
        "objectCount": r.int_field("objectCount"),
        "edgeCount":   r.int_field("edgeCount"),
        "clusters":    [],
        "pullOrder":   [],
    }

    clusters_arr = r.array_content("clusters")
    if clusters_arr:
        for obj in JavaParserClone.split_objects(clusters_arr):
            cr = JavaParserClone(obj)
            parsed["clusters"].append({
                "risk":  cr.string_field("risk"),
                "tasks": cr.string_array("tasks"),
                "edges": [
                    {
                        "from":     JavaParserClone(e).string_field("from"),
                        "fromTask": JavaParserClone(e).string_field("fromTask"),
                        "to":       JavaParserClone(e).string_field("to"),
                        "toTask":   JavaParserClone(e).string_field("toTask"),
                        "kind":     JavaParserClone(e).string_field("kind"),
                        "detail":   JavaParserClone(e).string_field("detail"),
                    }
                    for e in JavaParserClone.split_objects(
                        cr.array_content("edges") or "")
                ],
            })

    pull_arr = r.array_content("pullOrder")
    if pull_arr:
        for obj in JavaParserClone.split_objects(pull_arr):
            sr = JavaParserClone(obj)
            parsed["pullOrder"].append({
                "step":   sr.int_field("step"),
                "action": sr.string_field("action"),
                "tasks":  sr.string_array("tasks"),
            })

    return parsed


# ----------------------------------------------------------------------------
# 3. Round-trip verification harness
# ----------------------------------------------------------------------------
def assert_eq(actual, expected, label: str):
    if actual != expected:
        raise AssertionError(
            f"\n{label}\n  expected: {expected!r}\n  actual:   {actual!r}")


def verify_round_trip(label: str, fx) -> None:
    print(f"--- {label} ---")
    result = run(fx)
    abap_json = to_json_abap_style(result, label)

    # Sanity: must parse as standard JSON (proves ABAP output is well-formed)
    standard = json.loads(abap_json)

    # Java parser sees the same fields as standard JSON
    java = java_parse(abap_json)
    assert_eq(java["tr"],          standard["tr"],          "tr")
    assert_eq(java["taskCount"],   standard["taskCount"],   "taskCount")
    assert_eq(java["objectCount"], standard["objectCount"], "objectCount")
    assert_eq(java["edgeCount"],   standard["edgeCount"],   "edgeCount")

    assert_eq(len(java["clusters"]), len(standard["clusters"]),
              "cluster count")
    for i, (jc, sc) in enumerate(zip(java["clusters"], standard["clusters"])):
        assert_eq(jc["risk"],  sc["risk"],  f"cluster[{i}].risk")
        assert_eq(jc["tasks"], sc["tasks"], f"cluster[{i}].tasks")
        assert_eq(len(jc["edges"]), len(sc["edges"]),
                  f"cluster[{i}].edges count")
        for j, (je, se) in enumerate(zip(jc["edges"], sc["edges"])):
            for k in ("from", "fromTask", "to", "toTask", "kind", "detail"):
                assert_eq(je[k], se[k], f"cluster[{i}].edges[{j}].{k}")

    assert_eq(len(java["pullOrder"]), len(standard["pullOrder"]),
              "pullOrder count")
    for i, (jp, sp) in enumerate(zip(java["pullOrder"], standard["pullOrder"])):
        assert_eq(jp["step"],   sp["step"],   f"pullOrder[{i}].step")
        assert_eq(jp["action"], sp["action"], f"pullOrder[{i}].action")
        assert_eq(jp["tasks"],  sp["tasks"],  f"pullOrder[{i}].tasks")

    print(f"  JSON length: {len(abap_json)} chars  -> PASS\n")


# ----------------------------------------------------------------------------
# 4. Edge-case stress for the hand-rolled parser (Gap E6 in
#    GAPS_IN_CURRENT_DESIGN.md). These are KNOWN weaknesses; the test
#    documents them so a future replacement parser must keep behaving the
#    same way.
# ----------------------------------------------------------------------------
def known_parser_limits():
    print("--- Known parser limits (Gap E6 - documented, not yet fixed) ---")

    # 1. Embedded \" inside a string  -- works
    s = '{"tr":"a\\"b","taskCount":1,"objectCount":0,"edgeCount":0,' \
        '"clusters":[],"pullOrder":[]}'
    assert_eq(java_parse(s)["tr"], 'a"b', "escaped quote in string")

    # 2. Newline inside a string  -- works
    s = '{"tr":"line1\\nline2","taskCount":1,"objectCount":0,"edgeCount":0,' \
        '"clusters":[],"pullOrder":[]}'
    assert_eq(java_parse(s)["tr"], "line1\nline2", "escaped newline in string")

    # 3. Unicode escape \u00XX  -- DOES NOT WORK (documented Gap E6)
    s = '{"tr":"x\\u0041y","taskCount":1,"objectCount":0,"edgeCount":0,' \
        '"clusters":[],"pullOrder":[]}'
    parsed_tr = java_parse(s)["tr"]
    if parsed_tr == "xAy":
        print("  unicode escape: unexpectedly handled (parser changed?)")
    else:
        print(f"  unicode escape: NOT handled  (got '{parsed_tr}')  - "
              f"matches Gap E6 documented limit")

    print("  -> documented behaviour confirmed\n")


def main():
    cases = [
        ("Scenario A",          fixture_scenario_a()),
        ("Scenario B",          fixture_scenario_b()),
        ("Same-object",         fixture_same_object_conflict()),
        ("Chain inheritance",   fixture_chain()),
    ]

    failed = 0
    for label, fx in cases:
        try:
            verify_round_trip(label, fx)
        except AssertionError as ex:
            failed += 1
            print(f"  -> FAIL: {ex}\n")

    known_parser_limits()

    if failed:
        print(f"{failed}/{len(cases)} round-trip(s) FAILED")
        sys.exit(1)
    print(f"All {len(cases)} round-trips passed.")


if __name__ == "__main__":
    main()