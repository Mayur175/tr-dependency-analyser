"""
Mock SAP transport data + end-to-end pipeline simulation.

PURPOSE
-------
The user asked for two concrete simulations using sample SAP data:

  Scenario 1 (intra-TR):
      One TR (GMWK900800) with multiple tasks, where the SAME object
      (ZCL_ORDER_API) is locked in two different tasks of the SAME TR.
      This is the CRITICAL "same-object conflict" case.

  Scenario 2 (cross-TR):
      Multiple TRs, each containing dependent objects:
        - TR DEVK900100 holds table ZTBL_CUSTOMER (uses data element ZDE_CUSTID)
        - TR DEVK900101 holds data element ZDE_CUSTID (uses domain ZDOM_CUSTID)
        - TR DEVK900102 holds class ZCL_CUSTOMER_API (implements ZIF_CUSTOMER)
        - TR DEVK900103 holds interface ZIF_CUSTOMER
      Releasing TR 100 before 101 fails activation in QA. Releasing TR 102
      before 103 fails activation in QA.

This script:
  1. Builds the mock data in the SHAPE of the actual SAP tables
     (E070, E071, SEOMETAREL, DD03L, DD04L, TFDIR) so an ABAP developer can
     read it and recognise it.
  2. Re-uses the production-equivalent 4-stage pipeline already validated in
     simulate_pipeline.py.
  3. Emits a human report PLUS the exact JSON that ZGCTS_ANALYZE_HANDLER
     would return over HTTP.

Run:    python3 mock_sap_data.py
Exit:   0 on success
"""

from __future__ import annotations
import json
import os
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Tuple

# Re-use the validated pipeline implementation from simulate_pipeline.py
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simulate_pipeline import (  # noqa: E402
    Obj, Edge, Fixture, run, pretty, cluster_risk,
    RISK_CRITICAL, RISK_HIGH, RISK_MEDIUM, RISK_NONE,
    ACT_COORD, ACT_TOGETHER, ACT_TG_RECOM, ACT_ALONE,
)


# ----------------------------------------------------------------------------
# Mock SAP tables - the rows that SELECT * FROM e070/e071/... would return
# ----------------------------------------------------------------------------

# ----------------------------- E070 (TR header) -----------------------------
# Real columns: TRKORR, TRFUNCTION, TRSTATUS, TARSYSTEM, KORRDEV, AS4USER,
#               AS4DATE, AS4TIME, STRKORR
E070_ROWS: List[Dict[str, str]] = [
    # ---- Scenario 1: ONE parent TR with three tasks --------------------
    {"TRKORR": "GMWK900800", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_ALICE",   "AS4DATE": "20260601", "STRKORR": ""},
    {"TRKORR": "GMWK900801", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_ALICE",   "AS4DATE": "20260601", "STRKORR": "GMWK900800"},
    {"TRKORR": "GMWK900802", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_BOB",     "AS4DATE": "20260601", "STRKORR": "GMWK900800"},
    {"TRKORR": "GMWK900803", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_CAROL",   "AS4DATE": "20260602", "STRKORR": "GMWK900800"},

    # ---- Scenario 2: FOUR independent TRs (each with one task) ---------
    {"TRKORR": "DEVK900100", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_DAN",     "AS4DATE": "20260605", "STRKORR": ""},
    {"TRKORR": "DEVK900110", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_DAN",     "AS4DATE": "20260605", "STRKORR": "DEVK900100"},

    {"TRKORR": "DEVK900101", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_EVE",     "AS4DATE": "20260606", "STRKORR": ""},
    {"TRKORR": "DEVK900111", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_EVE",     "AS4DATE": "20260606", "STRKORR": "DEVK900101"},

    {"TRKORR": "DEVK900102", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_FRANK",   "AS4DATE": "20260607", "STRKORR": ""},
    {"TRKORR": "DEVK900112", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_FRANK",   "AS4DATE": "20260607", "STRKORR": "DEVK900102"},

    {"TRKORR": "DEVK900103", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_GINA",    "AS4DATE": "20260608", "STRKORR": ""},
    {"TRKORR": "DEVK900113", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_GINA",    "AS4DATE": "20260608", "STRKORR": "DEVK900103"},

    # ---- Scenario 3: chain of 5 TRs (clearest demo of TR sequencing) ----
    # Domain ZDOM_ARTID -> Data element ZDE_ARTID -> Table ZTBL_ARTICLE
    #   Interface ZIF_ARTICLE -> Class ZCL_ARTICLE_API (uses ZTBL_ARTICLE)
    {"TRKORR": "DEVK900200", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_HEIDI",   "AS4DATE": "20260610", "STRKORR": ""},
    {"TRKORR": "DEVK900210", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_HEIDI",   "AS4DATE": "20260610", "STRKORR": "DEVK900200"},

    {"TRKORR": "DEVK900201", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_IVAN",    "AS4DATE": "20260610", "STRKORR": ""},
    {"TRKORR": "DEVK900211", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_IVAN",    "AS4DATE": "20260610", "STRKORR": "DEVK900201"},

    {"TRKORR": "DEVK900202", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_JUDY",    "AS4DATE": "20260611", "STRKORR": ""},
    {"TRKORR": "DEVK900212", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_JUDY",    "AS4DATE": "20260611", "STRKORR": "DEVK900202"},

    {"TRKORR": "DEVK900203", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_KAREN",   "AS4DATE": "20260611", "STRKORR": ""},
    {"TRKORR": "DEVK900213", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_KAREN",   "AS4DATE": "20260611", "STRKORR": "DEVK900203"},

    {"TRKORR": "DEVK900204", "TRFUNCTION": "K", "TRSTATUS": "D",
     "AS4USER": "I_LEO",     "AS4DATE": "20260612", "STRKORR": ""},
    {"TRKORR": "DEVK900214", "TRFUNCTION": "S", "TRSTATUS": "D",
     "AS4USER": "I_LEO",     "AS4DATE": "20260612", "STRKORR": "DEVK900204"},
]


# ---------------------------- E071 (TR objects) -----------------------------
# Real columns: TRKORR, AS4POS, PGMID, OBJECT, OBJ_NAME, OBJFUNC, LOCKFLAG
E071_ROWS: List[Dict[str, str]] = [
    # =========================================================
    # Scenario 1 - intra-TR same-object conflict
    # =========================================================
    # Task GMWK900801 (Alice) - working on order class
    {"TRKORR": "GMWK900801", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "CLAS", "OBJ_NAME": "ZCL_ORDER_API",        "LOCKFLAG": "X"},
    {"TRKORR": "GMWK900801", "AS4POS": "002", "PGMID": "R3TR",
     "OBJECT": "INTF", "OBJ_NAME": "ZIF_ORDER",            "LOCKFLAG": "X"},

    # Task GMWK900802 (Bob) - ALSO touching ZCL_ORDER_API !!! conflict
    {"TRKORR": "GMWK900802", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "CLAS", "OBJ_NAME": "ZCL_ORDER_API",        "LOCKFLAG": "X"},
    {"TRKORR": "GMWK900802", "AS4POS": "002", "PGMID": "R3TR",
     "OBJECT": "TABL", "OBJ_NAME": "ZTBL_ORDER_HEADER",    "LOCKFLAG": "X"},

    # Task GMWK900803 (Carol) - independent customer enhancement
    {"TRKORR": "GMWK900803", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "CLAS", "OBJ_NAME": "ZCL_ORDER_REPORT",     "LOCKFLAG": "X"},

    # =========================================================
    # Scenario 2 - cross-TR dependent objects
    # =========================================================
    # TR DEVK900100 task DEVK900110 - customer table
    {"TRKORR": "DEVK900110", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "TABL", "OBJ_NAME": "ZTBL_CUSTOMER",        "LOCKFLAG": "X"},

    # TR DEVK900101 task DEVK900111 - data element used by ZTBL_CUSTOMER
    {"TRKORR": "DEVK900111", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "DTEL", "OBJ_NAME": "ZDE_CUSTID",           "LOCKFLAG": "X"},

    # TR DEVK900102 task DEVK900112 - customer API class implements ZIF
    {"TRKORR": "DEVK900112", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "CLAS", "OBJ_NAME": "ZCL_CUSTOMER_API",     "LOCKFLAG": "X"},
    {"TRKORR": "DEVK900112", "AS4POS": "002", "PGMID": "R3TR",
     "OBJECT": "FUGR", "OBJ_NAME": "ZFG_CUSTOMER",         "LOCKFLAG": "X"},

    # TR DEVK900103 task DEVK900113 - interface
    {"TRKORR": "DEVK900113", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "INTF", "OBJ_NAME": "ZIF_CUSTOMER",         "LOCKFLAG": "X"},

    # =========================================================
    # Scenario 3 - five-TR chain to demonstrate strict topo-sort
    # =========================================================
    # Heidi: domain (most upstream — must move FIRST)
    {"TRKORR": "DEVK900210", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "DOMA", "OBJ_NAME": "ZDOM_ARTID",           "LOCKFLAG": "X"},
    # Ivan: data element using ZDOM_ARTID
    {"TRKORR": "DEVK900211", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "DTEL", "OBJ_NAME": "ZDE_ARTID",            "LOCKFLAG": "X"},
    # Judy: table whose key column is typed by ZDE_ARTID
    {"TRKORR": "DEVK900212", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "TABL", "OBJ_NAME": "ZTBL_ARTICLE",         "LOCKFLAG": "X"},
    # Karen: interface (no upstream deps in this set)
    {"TRKORR": "DEVK900213", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "INTF", "OBJ_NAME": "ZIF_ARTICLE",          "LOCKFLAG": "X"},
    # Leo: class implementing ZIF_ARTICLE (depends on the interface only)
    {"TRKORR": "DEVK900214", "AS4POS": "001", "PGMID": "R3TR",
     "OBJECT": "CLAS", "OBJ_NAME": "ZCL_ARTICLE_API",      "LOCKFLAG": "X"},
]


# -------------------------- SEOMETAREL (class rels) -------------------------
# Real columns: CLSNAME, REFCLSNAME, RELTYPE, VERSION, STATE
# RELTYPE: '1' = INHERITS (extends), '2' = IMPLEMENTS (interface)
SEOMETAREL_ROWS: List[Dict[str, str]] = [
    # ZCL_ORDER_API IMPLEMENTS ZIF_ORDER (both in same TR, different tasks)
    {"CLSNAME": "ZCL_ORDER_API",    "REFCLSNAME": "ZIF_ORDER",
     "RELTYPE": "2", "STATE": "1"},
    # ZCL_ORDER_REPORT inherits from ZCL_ORDER_API (same TR, different tasks)
    {"CLSNAME": "ZCL_ORDER_REPORT", "REFCLSNAME": "ZCL_ORDER_API",
     "RELTYPE": "1", "STATE": "1"},

    # ZCL_CUSTOMER_API IMPLEMENTS ZIF_CUSTOMER (cross-TR)
    {"CLSNAME": "ZCL_CUSTOMER_API", "REFCLSNAME": "ZIF_CUSTOMER",
     "RELTYPE": "2", "STATE": "1"},

    # Scenario 3: ZCL_ARTICLE_API IMPLEMENTS ZIF_ARTICLE (cross-TR)
    {"CLSNAME": "ZCL_ARTICLE_API",  "REFCLSNAME": "ZIF_ARTICLE",
     "RELTYPE": "2", "STATE": "1"},
]


# -------------------------- DD03L (table fields) ----------------------------
# Real columns: TABNAME, FIELDNAME, POSITION, ROLLNAME, DOMNAME, DATATYPE
DD03L_ROWS: List[Dict[str, str]] = [
    # ZTBL_ORDER_HEADER columns
    {"TABNAME": "ZTBL_ORDER_HEADER", "FIELDNAME": "ORDER_ID",
     "POSITION": "0001", "ROLLNAME": "ZDE_ORDERID",  "DOMNAME": ""},
    {"TABNAME": "ZTBL_ORDER_HEADER", "FIELDNAME": "CREATED_BY",
     "POSITION": "0002", "ROLLNAME": "USERNAME",     "DOMNAME": ""},

    # ZTBL_CUSTOMER columns - column type points at ZDE_CUSTID (Scenario 2!)
    {"TABNAME": "ZTBL_CUSTOMER",     "FIELDNAME": "CUST_ID",
     "POSITION": "0001", "ROLLNAME": "ZDE_CUSTID",   "DOMNAME": ""},
    {"TABNAME": "ZTBL_CUSTOMER",     "FIELDNAME": "NAME",
     "POSITION": "0002", "ROLLNAME": "NAME1",        "DOMNAME": ""},

    # Scenario 3: ZTBL_ARTICLE.ARTICLE_ID -> ZDE_ARTID
    {"TABNAME": "ZTBL_ARTICLE",      "FIELDNAME": "ARTICLE_ID",
     "POSITION": "0001", "ROLLNAME": "ZDE_ARTID",    "DOMNAME": ""},
    {"TABNAME": "ZTBL_ARTICLE",      "FIELDNAME": "DESCRIPTION",
     "POSITION": "0002", "ROLLNAME": "MAKTX",        "DOMNAME": ""},
]


# -------------------------- DD04L (data elements) ---------------------------
# Real columns: ROLLNAME, DOMNAME, DATATYPE, LENG
DD04L_ROWS: List[Dict[str, str]] = [
    # ZDE_CUSTID points at domain ZDOM_CUSTID (not in any TR -> external,
    # so this dependency does NOT generate an edge in our scope)
    {"ROLLNAME": "ZDE_CUSTID",  "DOMNAME": "ZDOM_CUSTID",
     "DATATYPE": "CHAR", "LENG": "10"},
    {"ROLLNAME": "ZDE_ORDERID", "DOMNAME": "ZDOM_ORDERID",
     "DATATYPE": "CHAR", "LENG": "12"},

    # Scenario 3: ZDE_ARTID -> ZDOM_ARTID  (BOTH in scope -> generates an edge)
    {"ROLLNAME": "ZDE_ARTID",   "DOMNAME": "ZDOM_ARTID",
     "DATATYPE": "CHAR", "LENG": "18"},
]


# ---------------------------- TFDIR (function modules) ----------------------
# Real columns: FUNCNAME, PNAME (program / function group)
TFDIR_ROWS: List[Dict[str, str]] = [
    {"FUNCNAME": "Z_GET_CUSTOMER", "PNAME": "SAPLZFG_CUSTOMER"},
    {"FUNCNAME": "Z_PUT_CUSTOMER", "PNAME": "SAPLZFG_CUSTOMER"},
]


# ----------------------------------------------------------------------------
# Helpers - turn the SAP-shaped rows into the pipeline's Fixture object
# ----------------------------------------------------------------------------
def expand_input(input_ids: List[str]) -> List[str]:
    """Mirror ABAP behaviour: if id is a TR (E070-STRKORR='') expand to its
    child tasks. If id is already a task, return as-is."""
    expanded: List[str] = []
    for tr in input_ids:
        children = [r["TRKORR"] for r in E070_ROWS if r["STRKORR"] == tr]
        if children:                 # it's a parent TR -> use its tasks
            expanded.extend(children)
        else:                        # already a task or unknown -> use as-is
            expanded.append(tr)
    return expanded


def build_fixture(name: str, input_tasks: List[str]) -> Fixture:
    """Stage 1 - inventory: read E071, build Obj rows.
       Stage 2 metadata - join with SEOMETAREL/DD03L to produce per-object deps."""
    objs: List[Obj] = []
    name_to_obj: Dict[str, Obj] = {}

    for row in E071_ROWS:
        if row["TRKORR"] not in input_tasks:
            continue
        if row["PGMID"] != "R3TR":
            continue
        o = Obj(task=row["TRKORR"], type_=row["OBJECT"], name=row["OBJ_NAME"])
        objs.append(o)
        name_to_obj[o.name] = o

    deps: Dict[str, List[Tuple[str, str, str]]] = defaultdict(list)

    # Class relationships (SEOMETAREL)
    for r in SEOMETAREL_ROWS:
        if r["CLSNAME"] not in name_to_obj:
            continue
        kind = "INHERITS" if r["RELTYPE"] == "1" else "IMPLEMENTS"
        # Target type: the referenced class is CLAS for INHERITS, INTF for IMPLEMENTS
        tgt_type = "CLAS" if r["RELTYPE"] == "1" else "INTF"
        deps[r["CLSNAME"]].append((tgt_type, r["REFCLSNAME"], kind))

    # Table -> data element (DD03L.ROLLNAME)
    for r in DD03L_ROWS:
        if r["TABNAME"] not in name_to_obj:
            continue
        if r["ROLLNAME"]:
            deps[r["TABNAME"]].append(("DTEL", r["ROLLNAME"], "TYPE_REF"))

    # Data element -> domain (DD04L.DOMNAME) - only if domain is in scope
    for r in DD04L_ROWS:
        if r["ROLLNAME"] not in name_to_obj:
            continue
        if r["DOMNAME"]:
            deps[r["ROLLNAME"]].append(("DOMA", r["DOMNAME"], "TYPE_REF"))

    return Fixture(name=name, objects=objs, deps=dict(deps))


# ----------------------------------------------------------------------------
# TR-level release sequence
# ----------------------------------------------------------------------------
# Tasks are how SAP organises ownership inside a TR; the unit that actually
# moves through STMS / gCTS to QA and PROD is the parent TR. The pipeline
# above produces task-level edges; this section condenses them to TR-level
# and topologically sorts the result so we can answer:
#
#   "If multiple TRs have inter-object dependencies, in what order should
#    the Basis team move them through the landscape (DEV -> QA -> PROD)?"
#
# Rules
# -----
# 1. Edge direction. For every non-CONFLICT edge s_task -> t_task in the
#    task graph, the OBJECT in t_task must already exist in QA before the
#    OBJECT in s_task can activate. Therefore t_task's parent TR must be
#    imported BEFORE s_task's parent TR.
#
#    (Activation example: ZCL_CUSTOMER_API IMPLEMENTS ZIF_CUSTOMER. The
#     interface must exist before the class compiles. So the TR holding
#     ZIF_CUSTOMER moves first.)
#
# 2. CONFLICT edges (same object in two tasks/TRs) cannot be ordered - the
#    fix is human coordination, not sequencing. Both TRs are merged into a
#    single "release group" that must move together (same import buffer).
#
# 3. Any directed cycle in the TR graph means those TRs are mutually
#    dependent and ALSO must move together. We collapse strongly connected
#    components via Union-Find, then topo-sort the resulting DAG.
# ----------------------------------------------------------------------------

def _parent_tr(task_id: str) -> str:
    """Return the parent TR for a task. If the id is itself a parent TR
    (STRKORR is empty in E070) we just return it unchanged."""
    for r in E070_ROWS:
        if r["TRKORR"] == task_id:
            return r["STRKORR"] if r["STRKORR"] else task_id
    return task_id            # unknown id - treat as its own TR


def tr_release_sequence(result) -> Tuple[List[Dict], List[Dict]]:
    """Return (groups, sequence_steps).

    groups:    list of {group_id, trs[], reason} - one entry per release group
               (a release group is one or more TRs that must move together).
    sequence:  ordered list of {step, group_id, trs[], action, blocks_on[]}
               representing the recommended DEV->QA->PROD release order.
    """
    # ---- 1. Map every task in scope to its parent TR -----------------------
    task_to_tr: Dict[str, str] = {}
    all_trs: set = set()
    for e in result.edges:
        for t in (e.source_task, e.target_task):
            tr = _parent_tr(t)
            task_to_tr[t] = tr
            all_trs.add(tr)
    for tasks in result.clusters.values():
        for t in tasks:
            tr = _parent_tr(t)
            task_to_tr[t] = tr
            all_trs.add(tr)

    # ---- 2. Build TR-level edges -------------------------------------------
    # We need both:
    #   a) a directed graph (target_TR -> source_TR) for topo-sort, and
    #   b) an undirected "must move together" graph for CONFLICT + cycles.
    directed: Dict[str, set] = defaultdict(set)        # tr -> {trs that depend on it}
    must_merge_pairs: List[Tuple[str, str]] = []
    edge_reasons: Dict[Tuple[str, str], List[str]] = defaultdict(list)

    for e in result.edges:
        s_tr = task_to_tr.get(e.source_task, e.source_task)
        t_tr = task_to_tr.get(e.target_task, e.target_task)
        if s_tr == t_tr:
            continue                                    # same TR - no ordering

        if e.kind == "CONFLICT":
            must_merge_pairs.append((s_tr, t_tr))
            edge_reasons[(min(s_tr, t_tr), max(s_tr, t_tr))].append(
                f"CONFLICT on {e.source_object} (same object in both TRs)")
        else:
            # Object in t_tr must exist in QA before s_tr can activate.
            directed[t_tr].add(s_tr)
            edge_reasons[(t_tr, s_tr)].append(
                f"{e.kind}: {e.source_object} -> {e.target_object}")

    # ---- 3. Detect strongly connected components (Tarjan) ------------------
    # Cycles in the directed graph + CONFLICT pairs both collapse into one
    # release group via Union-Find. SCC handles the cycles; we then merge
    # CONFLICT pairs on top.
    parent: Dict[str, str] = {}

    def find(x: str) -> str:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: str, b: str) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    for tr in all_trs:
        find(tr)

    # Tarjan's SCC
    index_counter = [0]
    stack: List[str] = []
    on_stack: Dict[str, bool] = {}
    indices: Dict[str, int] = {}
    lowlinks: Dict[str, int] = {}

    def strongconnect(v: str) -> None:
        indices[v] = index_counter[0]
        lowlinks[v] = index_counter[0]
        index_counter[0] += 1
        stack.append(v)
        on_stack[v] = True
        for w in directed.get(v, ()):
            if w not in indices:
                strongconnect(w)
                lowlinks[v] = min(lowlinks[v], lowlinks[w])
            elif on_stack.get(w):
                lowlinks[v] = min(lowlinks[v], indices[w])
        if lowlinks[v] == indices[v]:
            scc: List[str] = []
            while True:
                w = stack.pop()
                on_stack[w] = False
                scc.append(w)
                if w == v:
                    break
            # Merge the SCC into one group
            for w in scc[1:]:
                union(scc[0], w)

    for tr in sorted(all_trs):
        if tr not in indices:
            strongconnect(tr)

    # Apply CONFLICT pairs
    for a, b in must_merge_pairs:
        union(a, b)

    # ---- 4. Build groups ---------------------------------------------------
    groups_map: Dict[str, List[str]] = defaultdict(list)
    for tr in all_trs:
        groups_map[find(tr)].append(tr)

    # Stable group IDs (G1, G2, ...) ordered by smallest TR in each group
    sorted_group_keys = sorted(groups_map.keys(),
                                key=lambda k: sorted(groups_map[k])[0])
    group_id_of_tr: Dict[str, str] = {}
    groups_out: List[Dict] = []
    for i, key in enumerate(sorted_group_keys, start=1):
        gid = f"G{i}"
        trs = sorted(groups_map[key])
        for tr in trs:
            group_id_of_tr[tr] = gid

        # Reason text
        reasons: List[str] = []
        if len(trs) > 1:
            for a in trs:
                for b in trs:
                    if a >= b:
                        continue
                    for k in (a, b), (b, a):
                        for txt in edge_reasons.get(k, []):
                            reasons.append(f"{k[0]} <-> {k[1]}: {txt}")
        groups_out.append({
            "group_id": gid,
            "trs":      trs,
            "must_move_together": len(trs) > 1,
            "reason":   reasons or ["independent"],
        })

    # ---- 5. Build group-level DAG and topo-sort ----------------------------
    group_edges: Dict[str, set] = defaultdict(set)   # gid -> set of dependent gids
    in_degree: Dict[str, int] = defaultdict(int)
    for gid in group_id_of_tr.values():
        in_degree.setdefault(gid, 0)

    blocks_on: Dict[str, List[str]] = defaultdict(list)

    for src_tr, dependents in directed.items():
        for dst_tr in dependents:
            g_src = group_id_of_tr[src_tr]
            g_dst = group_id_of_tr[dst_tr]
            if g_src == g_dst:
                continue
            if g_dst not in group_edges[g_src]:
                group_edges[g_src].add(g_dst)
                in_degree[g_dst] += 1
                blocks_on[g_dst].append(g_src)

    # Kahn's algorithm
    ready = [g for g, d in in_degree.items() if d == 0]
    ready.sort()
    sequence: List[Dict] = []
    step_no = 0
    seen: set = set()
    while ready:
        step_no += 1
        gid = ready.pop(0)
        if gid in seen:
            continue
        seen.add(gid)
        group = next(g for g in groups_out if g["group_id"] == gid)
        action = ("RELEASE_TOGETHER (same import buffer)"
                  if group["must_move_together"] else "RELEASE_ALONE")
        sequence.append({
            "step":      step_no,
            "group_id":  gid,
            "trs":       group["trs"],
            "action":    action,
            "blocks_on": sorted(set(blocks_on.get(gid, []))),
            "reason":    group["reason"],
        })
        for nxt in sorted(group_edges.get(gid, ())):
            in_degree[nxt] -= 1
            if in_degree[nxt] == 0:
                ready.append(nxt)
        ready.sort()

    # If any group is missing (should not happen unless cycle survived), append
    for g in groups_out:
        if g["group_id"] not in seen:
            step_no += 1
            sequence.append({
                "step":      step_no,
                "group_id":  g["group_id"],
                "trs":       g["trs"],
                "action":    "RELEASE_ALONE",
                "blocks_on": [],
                "reason":    g["reason"],
            })

    return groups_out, sequence


def pretty_tr_sequence(groups: List[Dict], sequence: List[Dict]) -> str:
    out: List[str] = []
    out.append("Release groups (one or more TRs that must move together):")
    for g in groups:
        flag = "MUST MOVE TOGETHER" if g["must_move_together"] else "independent"
        out.append(f"  {g['group_id']:3s} [{flag}] {', '.join(g['trs'])}")
        for r in g["reason"]:
            out.append(f"        - {r}")
    out.append("")
    out.append("Recommended DEV -> QA -> PROD release sequence:")
    for s in sequence:
        blocks = (f"  (waits on: {', '.join(s['blocks_on'])})"
                  if s["blocks_on"] else "")
        out.append(f"  Step {s['step']}: {s['group_id']} "
                   f"-> {', '.join(s['trs'])}  [{s['action']}]{blocks}")
        for r in s["reason"]:
            if r != "independent":
                out.append(f"        why: {r}")
    return "\n".join(out)


# ----------------------------------------------------------------------------
# JSON serialiser - mirror of ABAP ZCL_GCTS_TR_ANALYZER->TO_JSON()
# ----------------------------------------------------------------------------
def to_json(input_ids: List[str], expanded: List[str], result) -> str:
    groups, sequence = tr_release_sequence(result)
    payload = {
        "version": "1.1",
        "input":   input_ids,
        "tasks":   expanded,
        "edges": [
            {
                "source_task":   e.source_task,
                "source_object": e.source_object,
                "target_task":   e.target_task,
                "target_object": e.target_object,
                "kind":          e.kind,
                "detail":        e.detail,
            }
            for e in result.edges
        ],
        "clusters": [
            {
                "tasks": tasks,
                "risk":  cluster_risk(tasks, result.edges),
            }
            for tasks in result.clusters.values()
        ],
        "pull_order": [
            {"step": s.step, "action": s.action, "tasks": s.tasks}
            for s in result.pull_order
        ],
        "tr_release_groups":   groups,
        "tr_release_sequence": sequence,
    }
    return json.dumps(payload, indent=2)


# ----------------------------------------------------------------------------
# Recommendation engine - turn raw output into per-task action sentences
# ----------------------------------------------------------------------------
def explain(input_ids: List[str], expanded: List[str], result) -> List[str]:
    """Senior-Basis-style natural-language recommendations."""
    lines: List[str] = []
    user_of: Dict[str, str] = {r["TRKORR"]: r.get("AS4USER", "")
                                for r in E070_ROWS}

    for s in result.pull_order:
        risk = {ACT_COORD:    RISK_CRITICAL,
                ACT_TOGETHER: RISK_HIGH,
                ACT_TG_RECOM: RISK_MEDIUM,
                ACT_ALONE:    RISK_NONE}[s.action]

        owners = sorted({user_of.get(t, "?") for t in s.tasks})
        owners_txt = ", ".join(owners)

        if s.action == ACT_COORD:
            lines.append(
                f"Step {s.step} [CRITICAL]: tasks {s.tasks} touch the SAME object. "
                f"Owners {owners_txt} MUST coordinate before either is released. "
                f"Releasing one alone overwrites the other's changes in QA."
            )
        elif s.action == ACT_TOGETHER:
            lines.append(
                f"Step {s.step} [HIGH]: tasks {s.tasks} share an activation "
                f"dependency (IMPLEMENTS / INHERITS / CALLS). "
                f"Owners {owners_txt} must release them in the SAME import "
                f"buffer or activation will fail in QA."
            )
        elif s.action == ACT_TG_RECOM:
            lines.append(
                f"Step {s.step} [MEDIUM]: tasks {s.tasks} share a TYPE_REF "
                f"(table column type or data element domain). "
                f"Owners {owners_txt} should release them together; "
                f"if not, release the referenced object first."
            )
        else:
            lines.append(
                f"Step {s.step} [OK]: task {s.tasks[0]} is independent. "
                f"Owner {owners_txt} can release it alone."
            )

    return lines


# ----------------------------------------------------------------------------
# Pretty SAP-table dump
# ----------------------------------------------------------------------------
def dump_sap_tables(input_ids: List[str], expanded: List[str]) -> str:
    out = []
    out.append("INPUT (what the user typed in the Eclipse dialog):")
    out.append("    " + ", ".join(input_ids))
    out.append("")
    out.append("After E070 expansion (TR -> child tasks):")
    out.append("    " + ", ".join(expanded))
    out.append("")
    out.append("E070 rows in scope:")
    out.append(f"    {'TRKORR':12} {'FUNC':4} {'STAT':4} {'PARENT':12} OWNER")
    for r in E070_ROWS:
        if r["TRKORR"] in input_ids or r["TRKORR"] in expanded \
                or r["STRKORR"] in input_ids:
            out.append(f"    {r['TRKORR']:12} {r['TRFUNCTION']:4} "
                       f"{r['TRSTATUS']:4} {r['STRKORR']:12} {r['AS4USER']}")
    out.append("")
    out.append("E071 rows in scope:")
    out.append(f"    {'TRKORR':12} {'POS':4} {'PGMID':5} {'OBJ':4} OBJ_NAME")
    for r in E071_ROWS:
        if r["TRKORR"] in expanded:
            out.append(f"    {r['TRKORR']:12} {r['AS4POS']:4} "
                       f"{r['PGMID']:5} {r['OBJECT']:4} {r['OBJ_NAME']}")
    return "\n".join(out)


# ----------------------------------------------------------------------------
# Main: run the two scenarios
# ----------------------------------------------------------------------------
def run_scenario(label: str, description: str, input_ids: List[str],
                 expected_top_action: str) -> bool:
    bar = "=" * 78
    print(bar)
    print(f"  {label}")
    print(bar)
    print(description)
    print()

    expanded = expand_input(input_ids)
    print(dump_sap_tables(input_ids, expanded))
    print()

    fx = build_fixture(label, expanded)
    result = run(fx)

    print("--- Pipeline output (Stages 1-4) ---")
    print(pretty(result))
    print()

    print("--- Recommendations (action-oriented) ---")
    for line in explain(input_ids, expanded, result):
        print(line)
    print()

    print("--- TR release sequence (DEV -> QA -> PROD) ---")
    groups, sequence = tr_release_sequence(result)
    print(pretty_tr_sequence(groups, sequence))
    print()

    print("--- ICF wire JSON (what /sap/bc/zgcts/analyze returns) ---")
    print(to_json(input_ids, expanded, result))
    print()

    actual = result.pull_order[0].action if result.pull_order else "?"
    ok = actual == expected_top_action
    verdict = "PASS" if ok else "FAIL"
    print(f">>> Expected top action: {expected_top_action}, "
          f"actual: {actual}  [{verdict}]")
    print()
    return ok


def main() -> int:
    print()
    print("##############################################################")
    print("#  TR Analyser - SAP mock-data simulation                    #")
    print("#  Two scenarios, end-to-end through the production pipeline #")
    print("##############################################################")
    print()

    results = []

    # ---- Scenario 1: ONE TR, multiple tasks, same-object conflict ----
    results.append(run_scenario(
        label="SCENARIO 1: One TR (GMWK900800) with three tasks, "
              "same object locked in two of them",
        description=(
            "TR GMWK900800 holds three tasks:\n"
            "  - GMWK900801 (Alice): ZCL_ORDER_API + ZIF_ORDER\n"
            "  - GMWK900802 (Bob):   ZCL_ORDER_API + ZTBL_ORDER_HEADER  "
            "<- SAME class!\n"
            "  - GMWK900803 (Carol): ZCL_ORDER_REPORT (extends ZCL_ORDER_API)\n"
            "Expectation: the pipeline must flag the same-object conflict\n"
            "(CRITICAL) and produce a COORDINATE step. Carol's task is\n"
            "transitively pulled into the cluster via the inheritance edge."),
        input_ids=["GMWK900800"],
        expected_top_action=ACT_COORD,
    ))

    # ---- Scenario 2: Multiple TRs with dependent objects ----
    results.append(run_scenario(
        label="SCENARIO 2: Four independent TRs with dependent objects across "
              "TR boundaries",
        description=(
            "Four separate TRs from four developers:\n"
            "  - DEVK900100 (Dan):   ZTBL_CUSTOMER  (column type ZDE_CUSTID)\n"
            "  - DEVK900101 (Eve):   ZDE_CUSTID     (data element)\n"
            "  - DEVK900102 (Frank): ZCL_CUSTOMER_API (implements ZIF_CUSTOMER)\n"
            "  - DEVK900103 (Gina):  ZIF_CUSTOMER   (interface)\n"
            "Expectations:\n"
            "  - HIGH cluster {DEVK900112, DEVK900113} - IMPLEMENTS edge\n"
            "    must release together (or release ZIF_CUSTOMER's TR first)\n"
            "  - MEDIUM cluster {DEVK900110, DEVK900111} - TYPE_REF edge\n"
            "    on data element"),
        input_ids=["DEVK900100", "DEVK900101", "DEVK900102", "DEVK900103"],
        expected_top_action=ACT_TOGETHER,        # HIGH wins over MEDIUM
    ))

    # ---- Scenario 3: Five-TR chain - clearest demo of DEV->QA->PROD order ----
    results.append(run_scenario(
        label="SCENARIO 3: Five TRs forming a dependency chain "
              "(domain -> data element -> table; interface -> class)",
        description=(
            "Five TRs from five developers, two dependency chains:\n"
            "  Chain A (3 TRs):\n"
            "    DEVK900200 (Heidi) -> ZDOM_ARTID    (domain)\n"
            "    DEVK900201 (Ivan)  -> ZDE_ARTID     (data element using domain)\n"
            "    DEVK900202 (Judy)  -> ZTBL_ARTICLE  (table using data element)\n"
            "  Chain B (2 TRs):\n"
            "    DEVK900203 (Karen) -> ZIF_ARTICLE   (interface)\n"
            "    DEVK900204 (Leo)   -> ZCL_ARTICLE_API (implements interface)\n"
            "\n"
            "Expected DEV -> QA -> PROD release sequence:\n"
            "  Step 1: DEVK900200 (domain)        - no dependencies\n"
            "  Step 2: DEVK900203 (interface)     - no dependencies\n"
            "  Step 3: DEVK900201 (data element)  - waits on Step 1\n"
            "  Step 4: DEVK900204 (class)         - waits on Step 2\n"
            "  Step 5: DEVK900202 (table)         - waits on Step 3"),
        input_ids=["DEVK900200", "DEVK900201", "DEVK900202",
                   "DEVK900203", "DEVK900204"],
        expected_top_action=ACT_TOGETHER,        # HIGH cluster (IMPLEMENTS) is top
    ))

    print("=" * 78)
    if all(results):
        print(f"  All {len(results)} scenarios produced the expected top "
              f"recommendation.")
        return 0
    failed = sum(1 for r in results if not r)
    print(f"  {failed}/{len(results)} scenario(s) FAILED")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())