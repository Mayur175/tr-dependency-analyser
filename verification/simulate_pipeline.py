"""
Simulator for the TR Analyser ABAP algorithm.

PURPOSE
-------
The ABAP class ZCL_GCTS_TR_ANALYZER has four analytical stages that are pure
functions of three inputs:

    objects[]   : (task, object_type, object_name)
    metadata    : per-object dependencies (CLAS->INTF, TABL->DTEL, etc.)
    risk-rules  : kind -> severity

Whether the metadata is fetched from XCO, from SEOMETAREL, or from a hand-built
fixture, the algorithm itself is identical:

    Stage 1  Inventory          (here: hand-built fixture)
    Stage 2  Per-type extractors (here: dict lookups in fixture)
    Stage 2b Same-object conflict detection
    Stage 3  Cluster detection (Union-Find)
    Stage 4  Pull-order recommendation

This script reimplements those four stages 1:1 in Python, drives them with
synthetic fixtures that mirror the problem statements in
SOLUTION_ARCHITECTURE.md (Scenario A and Scenario B), and asserts the
expected output. It is the closest the Mac / VS Code workstation can get
to "running the tool" without a live SAP system.

Run:    python3 simulate_pipeline.py
Exit 0  on success, exit 1 on any assertion failure.
"""

from __future__ import annotations
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Tuple


# ----------------------------------------------------------------------------
# Risk vocabulary - matches ABAP constants (c_risk_critical, c_risk_high, ...)
# ----------------------------------------------------------------------------
RISK_CRITICAL = "CRITICAL"
RISK_HIGH     = "HIGH"
RISK_MEDIUM   = "MEDIUM"
RISK_NONE     = "NONE"

ACT_COORD     = "COORDINATE"
ACT_TOGETHER  = "TOGETHER"
ACT_TG_RECOM  = "TOGETHER_RECOMMENDED"
ACT_ALONE     = "ALONE"


@dataclass(frozen=True)
class Obj:
    task:    str
    type_:   str   # CLAS / INTF / TABL / DTEL / FUGR
    name:    str


@dataclass(frozen=True)
class Edge:
    source_task:   str
    source_object: str
    target_task:   str
    target_object: str
    kind:          str   # IMPLEMENTS / INHERITS / TYPE_REF / CALLS / CONFLICT
    detail:        str   = ""


# ----------------------------------------------------------------------------
# Fixture metadata - the kind of thing SEOMETAREL / DD03L / DD04L / TFDIR
# would tell us on a real system. Each entry is a per-object dependency map.
# ----------------------------------------------------------------------------
@dataclass
class Fixture:
    """One synthetic SAP system snapshot."""
    name:    str
    objects: List[Obj]
    # name -> list of (target_type, target_name, kind)
    deps:    Dict[str, List[Tuple[str, str, str]]] = field(default_factory=dict)

    def task_of(self, name: str) -> str:
        for o in self.objects:
            if o.name == name:
                return o.task
        return ""


# ----------------------------------------------------------------------------
# Stage 2 - dependency extraction (mirrors deps_for_clas / deps_for_tabl etc.)
# ----------------------------------------------------------------------------
def stage2_dependencies(fx: Fixture) -> List[Edge]:
    edges: List[Edge] = []
    for o in fx.objects:
        for tgt_type, tgt_name, kind in fx.deps.get(o.name, []):
            tgt_task = fx.task_of(tgt_name)
            # Mirrors add_dep: skip if target is outside the input set or
            # in the same task.
            if not tgt_task or tgt_task == o.task:
                continue
            edges.append(Edge(
                source_task   = o.task,
                source_object = f"{o.type_}/{o.name}",
                target_task   = tgt_task,
                target_object = f"{tgt_type}/{tgt_name}",
                kind          = kind,
                detail        = f"{o.name} -> {tgt_name}",
            ))
    return edges


# ----------------------------------------------------------------------------
# Stage 2b - same-object conflict detection
# ----------------------------------------------------------------------------
def stage2b_conflicts(fx: Fixture) -> List[Edge]:
    """If the same object name appears in 2+ tasks -> CRITICAL CONFLICT edge."""
    name_to_tasks: Dict[str, List[str]] = defaultdict(list)
    for o in fx.objects:
        if o.task not in name_to_tasks[o.name]:
            name_to_tasks[o.name].append(o.task)

    out: List[Edge] = []
    for name, tasks in name_to_tasks.items():
        if len(tasks) < 2:
            continue
        first = tasks[0]
        for other in tasks[1:]:
            out.append(Edge(
                source_task   = first,
                source_object = name,
                target_task   = other,
                target_object = name,
                kind          = "CONFLICT",
                detail        = f"{name} owned by both {first} and {other}",
            ))
    return out


# ----------------------------------------------------------------------------
# Stage 3 - cluster detection (Union-Find), same algorithm as the ABAP class
# ----------------------------------------------------------------------------
class UnionFind:
    def __init__(self):
        self.parent: Dict[str, str] = {}

    def add(self, x: str) -> None:
        if x not in self.parent:
            self.parent[x] = x

    def find(self, x: str) -> str:
        self.add(x)
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]   # path compression
            x = self.parent[x]
        return x

    def union(self, a: str, b: str) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[rb] = ra


def stage3_clusters(objects: List[Obj], edges: List[Edge]) -> Dict[str, List[str]]:
    """Returns {root_task: [tasks_in_cluster]}."""
    uf = UnionFind()
    for o in objects:
        uf.add(o.task)
    for e in edges:
        uf.union(e.source_task, e.target_task)

    clusters: Dict[str, List[str]] = defaultdict(list)
    for o in objects:
        root = uf.find(o.task)
        if o.task not in clusters[root]:
            clusters[root].append(o.task)
    return dict(clusters)


def cluster_risk(cluster_tasks: List[str], edges: List[Edge]) -> str:
    """Risk = highest severity edge in the cluster (mirrors stage3_clusters)."""
    risk = RISK_NONE
    for e in edges:
        if e.source_task not in cluster_tasks:
            continue
        if e.kind == "CONFLICT":
            return RISK_CRITICAL
        if e.kind in ("IMPLEMENTS", "INHERITS"):
            risk = RISK_HIGH if risk != RISK_CRITICAL else risk
        elif e.kind in ("TYPE_REF", "USES", "EXTENDS", "CALLS"):
            if risk == RISK_NONE:
                risk = RISK_MEDIUM
    return risk


# ----------------------------------------------------------------------------
# Stage 4 - pull order
# ----------------------------------------------------------------------------
@dataclass
class PullStep:
    step:   int
    action: str
    tasks:  List[str]

    def __str__(self) -> str:
        return f"Step {self.step}: {self.action} -> {', '.join(self.tasks)}"


def stage4_pull_order(clusters: Dict[str, List[str]],
                      edges: List[Edge]) -> List[PullStep]:
    """Same risk-priority sort the ABAP class does, then number the steps."""
    cluster_list = []
    for root, tasks in clusters.items():
        risk = cluster_risk(tasks, edges)
        cluster_list.append((risk, root, tasks))

    # ABAP sorts by risk ascending; in pull-order the highest severity goes
    # first to draw the developer's attention. Reproduce the ABAP behaviour:
    risk_order = {
        RISK_CRITICAL: 0,
        RISK_HIGH:     1,
        RISK_MEDIUM:   2,
        RISK_NONE:     3,
    }
    cluster_list.sort(key=lambda x: risk_order[x[0]])

    steps: List[PullStep] = []
    for i, (risk, _root, tasks) in enumerate(cluster_list, start=1):
        action = {
            RISK_CRITICAL: ACT_COORD,
            RISK_HIGH:     ACT_TOGETHER,
            RISK_MEDIUM:   ACT_TG_RECOM,
            RISK_NONE:     ACT_ALONE,
        }[risk]
        steps.append(PullStep(step=i, action=action, tasks=tasks))
    return steps


# ----------------------------------------------------------------------------
# Top-level driver
# ----------------------------------------------------------------------------
@dataclass
class Result:
    fixture:     str
    edges:       List[Edge]
    clusters:    Dict[str, List[str]]
    pull_order:  List[PullStep]


def run(fx: Fixture) -> Result:
    edges = stage2_dependencies(fx) + stage2b_conflicts(fx)
    clusters = stage3_clusters(fx.objects, edges)
    return Result(
        fixture    = fx.name,
        edges      = edges,
        clusters   = clusters,
        pull_order = stage4_pull_order(clusters, edges),
    )


def pretty(r: Result) -> str:
    lines = [
        f"=== Fixture: {r.fixture} ===",
        f"Tasks: {sum(len(t) for t in r.clusters.values())}",
        f"Edges: {len(r.edges)}",
        "",
        "Clusters:",
    ]
    for root, tasks in r.clusters.items():
        risk = cluster_risk(tasks, r.edges)
        lines.append(f"  [{risk:8s}] {' + '.join(tasks)}")
    lines += ["", "Pull order:"]
    for s in r.pull_order:
        lines.append(f"  {s}")
    lines += ["", "Edges (detail):"]
    for e in r.edges:
        lines.append(f"  {e.kind:11s} {e.source_object} -> {e.target_object} "
                     f"[{e.source_task} -> {e.target_task}]")
    return "\n".join(lines)


# ----------------------------------------------------------------------------
# Test fixtures - built from the two scenarios in SOLUTION_ARCHITECTURE.md
# ----------------------------------------------------------------------------
def fixture_scenario_a() -> Fixture:
    """
    Scenario A from SOLUTION_ARCHITECTURE.md - one TR, multi-task gCTS:

      Task GMWK900692 contains ZCL_FOO  (extends nothing, implements ZIF_FOO)
      Task GMWK900693 contains ZIF_FOO
      Task GMWK900694 contains ZTBL_BAR (column type ZDE_FOO)
      Task GMWK900695 contains ZDE_FOO
      Task GMWK900696 contains ZCL_INDEPENDENT  (no dependencies)

    Expected:
      - HIGH cluster (692,693)  - IMPLEMENTS edge
      - MEDIUM cluster (694,695) - TYPE_REF edge
      - NONE cluster (696)
    """
    objs = [
        Obj("GMWK900692", "CLAS", "ZCL_FOO"),
        Obj("GMWK900693", "INTF", "ZIF_FOO"),
        Obj("GMWK900694", "TABL", "ZTBL_BAR"),
        Obj("GMWK900695", "DTEL", "ZDE_FOO"),
        Obj("GMWK900696", "CLAS", "ZCL_INDEPENDENT"),
    ]
    deps = {
        "ZCL_FOO":  [("INTF", "ZIF_FOO",  "IMPLEMENTS")],
        "ZTBL_BAR": [("DTEL", "ZDE_FOO",  "TYPE_REF")],
    }
    return Fixture("Scenario A: gCTS task-based release", objs, deps)


def fixture_scenario_b() -> Fixture:
    """
    Scenario B - cross-TR (classic CTS):

      TR DEVK900042 has task DEVK900043 with ZTBL_BAR (column type ZDE_FOO)
      TR DEVK900044 has task DEVK900045 with ZDE_FOO

    Expected:
      - MEDIUM cluster (043,045) - one TYPE_REF edge across TRs
    """
    objs = [
        Obj("DEVK900043", "TABL", "ZTBL_BAR"),
        Obj("DEVK900045", "DTEL", "ZDE_FOO"),
    ]
    deps = {
        "ZTBL_BAR": [("DTEL", "ZDE_FOO", "TYPE_REF")],
    }
    return Fixture("Scenario B: cross-TR classic CTS", objs, deps)


def fixture_same_object_conflict() -> Fixture:
    """
    Same-object conflict (CRITICAL):

      Task GMWK900700 and GMWK900701 both contain ZCL_BAZ.
      Pulling either alone overwrites the other's lock - data loss risk.
    """
    objs = [
        Obj("GMWK900700", "CLAS", "ZCL_BAZ"),
        Obj("GMWK900701", "CLAS", "ZCL_BAZ"),
    ]
    return Fixture("Same-object CONFLICT", objs)


def fixture_chain() -> Fixture:
    """
    Chain dependency:
      Task A: ZCL_A inherits ZCL_B
      Task B: ZCL_B implements ZIF_C
      Task C: ZIF_C
    All three must pull together (HIGH cluster) because of transitive activation
    requirements.
    """
    objs = [
        Obj("DEVK900100", "CLAS", "ZCL_A"),
        Obj("DEVK900101", "CLAS", "ZCL_B"),
        Obj("DEVK900102", "INTF", "ZIF_C"),
    ]
    deps = {
        "ZCL_A": [("CLAS", "ZCL_B", "INHERITS")],
        "ZCL_B": [("INTF", "ZIF_C", "IMPLEMENTS")],
    }
    return Fixture("Chain inheritance", objs, deps)


# ----------------------------------------------------------------------------
# Assertions - encode the expected behaviour from the documents
# ----------------------------------------------------------------------------
def assert_eq(actual, expected, label: str):
    if actual != expected:
        raise AssertionError(
            f"\n{label}\n  expected: {expected!r}\n  actual:   {actual!r}")


def verify_scenario_a(r: Result):
    # 5 tasks expected
    all_tasks = sorted({t for tasks in r.clusters.values() for t in tasks})
    assert_eq(all_tasks,
              ["GMWK900692", "GMWK900693", "GMWK900694",
               "GMWK900695", "GMWK900696"],
              "Scenario A: task list")

    # Two cross-task edges
    assert_eq(len(r.edges), 2, "Scenario A: edge count")
    kinds = sorted(e.kind for e in r.edges)
    assert_eq(kinds, ["IMPLEMENTS", "TYPE_REF"], "Scenario A: edge kinds")

    # Clusters: {692,693}, {694,695}, {696}
    cluster_sets = sorted(sorted(c) for c in r.clusters.values())
    assert_eq(cluster_sets,
              [["GMWK900692", "GMWK900693"],
               ["GMWK900694", "GMWK900695"],
               ["GMWK900696"]],
              "Scenario A: cluster membership")

    # First step must be the HIGH cluster (IMPLEMENTS edge)
    assert_eq(r.pull_order[0].action, ACT_TOGETHER,
              "Scenario A: step 1 action (HIGH -> TOGETHER)")
    assert_eq(sorted(r.pull_order[0].tasks),
              ["GMWK900692", "GMWK900693"],
              "Scenario A: step 1 tasks")

    # Last step must be the lonely 696 (NONE -> ALONE)
    assert_eq(r.pull_order[-1].action, ACT_ALONE,
              "Scenario A: last step action (NONE -> ALONE)")
    assert_eq(r.pull_order[-1].tasks, ["GMWK900696"],
              "Scenario A: last step tasks")


def verify_scenario_b(r: Result):
    assert_eq(len(r.edges), 1, "Scenario B: one cross-TR edge")
    assert_eq(r.edges[0].kind, "TYPE_REF", "Scenario B: edge kind")
    assert_eq(len(r.clusters), 1, "Scenario B: one cluster spans both TRs")
    assert_eq(r.pull_order[0].action, ACT_TG_RECOM,
              "Scenario B: MEDIUM -> TOGETHER_RECOMMENDED")
    assert_eq(sorted(r.pull_order[0].tasks),
              ["DEVK900043", "DEVK900045"],
              "Scenario B: cross-TR tasks bundled")


def verify_same_object_conflict(r: Result):
    # The CONFLICT edge must be CRITICAL
    crit = [e for e in r.edges if e.kind == "CONFLICT"]
    assert_eq(len(crit), 1, "Conflict: one CONFLICT edge")
    assert_eq(r.pull_order[0].action, ACT_COORD,
              "Conflict: CRITICAL -> COORDINATE")


def verify_chain(r: Result):
    # All three tasks must collapse into one cluster, action TOGETHER
    assert_eq(len(r.clusters), 1, "Chain: single cluster")
    assert_eq(r.pull_order[0].action, ACT_TOGETHER, "Chain: HIGH -> TOGETHER")
    assert_eq(sorted(r.pull_order[0].tasks),
              ["DEVK900100", "DEVK900101", "DEVK900102"],
              "Chain: all three tasks together")


# ----------------------------------------------------------------------------
# main
# ----------------------------------------------------------------------------
def main():
    cases = [
        (fixture_scenario_a(),           verify_scenario_a),
        (fixture_scenario_b(),           verify_scenario_b),
        (fixture_same_object_conflict(), verify_same_object_conflict),
        (fixture_chain(),                verify_chain),
    ]

    failed = 0
    for fx, verifier in cases:
        r = run(fx)
        print(pretty(r))
        try:
            verifier(r)
            print(f"  -> PASS\n")
        except AssertionError as ex:
            failed += 1
            print(f"  -> FAIL: {ex}\n")

    if failed:
        print(f"\n{failed}/{len(cases)} fixture(s) FAILED")
        raise SystemExit(1)
    print(f"\nAll {len(cases)} fixtures passed.")


if __name__ == "__main__":
    main()