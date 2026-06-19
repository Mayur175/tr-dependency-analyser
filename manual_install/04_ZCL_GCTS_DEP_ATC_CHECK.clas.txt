"! ATC (ABAP Test Cockpit) check for cross-task TR dependencies.
"!
"! Purpose (Gap 5):
"!   Runs automatically when a developer executes ATC on their transport task.
"!   Detects cross-task object dependencies and same-object conflicts,
"!   raising ATC findings so developers are alerted BEFORE pulling.
"!
"! Registration (SE92 / ATC Check):
"!   1. Create check variant in transaction ATC
"!   2. Add check class ZCL_GCTS_DEP_ATC_CHECK
"!   3. Include in your default ATC profile
"!   Alternative: add to ABAP Test Cockpit check set in ADT project settings
"!
"! How it works:
"!   - Reads the current task/TR from the ATC framework context
"!   - Runs ZCL_GCTS_TR_ANALYZER for that TR
"!   - Raises ATC messages for each CRITICAL / HIGH / MEDIUM dependency
"!
"! Severity mapping:
"!   CRITICAL  → Priority 1 (Error)   — must fix before pull
"!   HIGH      → Priority 2 (Warning) — should fix before pull
"!   MEDIUM    → Priority 3 (Info)    — review recommended
CLASS zcl_gcts_dep_atc_check DEFINITION
  PUBLIC
  INHERITING FROM cl_ci_test_root
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    METHODS constructor.

    "! ATC framework entry point — called per object under test
    METHODS run REDEFINITION.

    "! ATC framework: describe what this check tests
    METHODS get_message_text REDEFINITION.

  PRIVATE SECTION.
    CONSTANTS:
      c_check_id     TYPE string VALUE 'ZGCTS_DEP_CHECK',
      c_msg_critical TYPE string VALUE '001',  " same-object conflict
      c_msg_high     TYPE string VALUE '002',  " activation dependency
      c_msg_medium   TYPE string VALUE '003',  " type reference
      c_msg_ok       TYPE string VALUE '004'.  " no dependencies found

    "! Derive TR number from the ATC object context
    METHODS get_tr_for_object
      IMPORTING is_object      TYPE sci_atcobj
      RETURNING VALUE(rv_tr)   TYPE string.

    "! Raise one ATC finding per dependency edge
    METHODS raise_finding
      IMPORTING iv_prio   TYPE i
                iv_msg_id TYPE symsgid
                iv_msg_no TYPE symsgno
                iv_detail TYPE string.

ENDCLASS.


CLASS zcl_gcts_dep_atc_check IMPLEMENTATION.

  METHOD constructor.
    super->constructor( ).
    " Register the message class this check uses
    "  (create message class ZGCTS_DEP_MSG in SE91 with messages 001–004)
    " NB: do NOT use the text-symbol form 'literal'(001) here - the class
    " is shipped without text elements, so referencing text symbol 001
    " causes activation to fail with "Text symbol 001 does not exist".
    me->description = 'gCTS Cross-Task Dependency Check'.
  ENDMETHOD.


  METHOD run.
    " ── Resolve TR from context ──────────────────────────────────────────────
    DATA(lv_tr) = get_tr_for_object( object ).
    IF lv_tr IS INITIAL.
      " Object not in a TR / not a gCTS task — nothing to check
      RETURN.
    ENDIF.

    " ── Run the full analysis pipeline ───────────────────────────────────────
    TRY.
        " Use the modern instance-based constructor instead of the deprecated
        " static GV_TR_ID. The static is not thread-safe and an ATC run can
        " execute multiple checks concurrently.
        DATA lt_input TYPE zcl_gcts_tr_analyzer=>tt_input.
        APPEND VALUE #( id = CONV trkorr( lv_tr ) ) TO lt_input.

        DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer( it_input = lt_input ).
        DATA(lv_json)     = lo_analyzer->to_json( ).

        " Parse the JSON minimally to extract clusters
        DATA(lo_result)   = lcl_atc_json_reader=>parse( lv_json ).

        IF lo_result->clusters IS INITIAL.
          " No cross-task dependencies found — clean bill of health
          RETURN.
        ENDIF.

        " ── Raise one ATC finding per cluster risk ───────────────────────────
        LOOP AT lo_result->clusters INTO DATA(ls_cluster).
          LOOP AT ls_cluster-edges INTO DATA(ls_edge).
            DATA lv_prio   TYPE i.
            DATA lv_msg_no TYPE symsgno.

            CASE ls_cluster-risk.
              WHEN 'CRITICAL'.
                lv_prio   = 1.
                lv_msg_no = c_msg_critical.
              WHEN 'HIGH'.
                lv_prio   = 2.
                lv_msg_no = c_msg_high.
              WHEN 'MEDIUM'.
                lv_prio   = 3.
                lv_msg_no = c_msg_medium.
              WHEN OTHERS.
                CONTINUE.
            ENDCASE.

            raise_finding(
              iv_prio   = lv_prio
              iv_msg_id = 'ZGCTS_DEP_MSG'
              iv_msg_no = lv_msg_no
              iv_detail = |TR { lv_tr }: { ls_edge-detail } [{ ls_edge-kind }]| ).
          ENDLOOP.
        ENDLOOP.

    CATCH cx_root INTO DATA(lx).
      " ATC check must not crash — log as low-priority info finding
      raise_finding(
        iv_prio   = 4
        iv_msg_id = 'ZGCTS_DEP_MSG'
        iv_msg_no = '000'
        iv_detail = |ATC check error: { lx->get_text( ) }| ).
    ENDTRY.
  ENDMETHOD.


  METHOD get_message_text.
    " Return human-readable description of this ATC check
    rv_text = 'Detects cross-task gCTS transport dependencies that cause activation failures'.
  ENDMETHOD.


  METHOD get_tr_for_object.
    " Attempt to resolve the TR from the object's CTS lock entry.
    " In BTP ABAP the object under ATC test carries its transport assignment.
    TRY.
        " Try XCO: find any open TR that contains this object
        DATA(lt_trs) = xco_cp_cts=>transports->where(
          VALUE #( ( xco_cp_cts=>transport_request_filter->object(
                       pgmid    = is_object-pgmid
                       obj_type = is_object-object
                       obj_name = is_object-obj_name ) ) ) )->all( ).

        IF lt_trs IS NOT INITIAL.
          rv_tr = lt_trs[ 1 ]->value.
        ENDIF.
    CATCH cx_root.
      rv_tr = ''.
    ENDTRY.
  ENDMETHOD.


  METHOD raise_finding.
    " Delegate to ATC framework to record the finding against the current object
    "
    " NB: do NOT use the (50) / +50(50) substring form directly on iv_detail.
    " The (len) form raises CX_SY_RANGE_OUT_OF_BOUNDS when iv_detail is
    " shorter than the offset (e.g. a 12-char detail dumps on iv_detail(50)).
    " We compute safe slice lengths first.
    DATA lv_total TYPE i.
    DATA lv_v1    TYPE string.
    DATA lv_v2    TYPE string.

    lv_total = strlen( iv_detail ).

    IF lv_total <= 50.
      lv_v1 = iv_detail.
    ELSE.
      lv_v1 = iv_detail+0(50).
      IF lv_total - 50 <= 50.
        lv_v2 = iv_detail+50.
      ELSE.
        lv_v2 = iv_detail+50(50).
      ENDIF.
    ENDIF.

    TRY.
        DATA(ls_finding) = VALUE sci_finding(
          test       = c_check_id
          kind       = 'E'
          priority   = iv_prio
          errmsgid   = iv_msg_id
          errmsgno   = iv_msg_no
          errmsgv1   = lv_v1
          errmsgv2   = lv_v2 ).
        append_message( ls_finding ).
    CATCH cx_root.  " ATC API may differ — best-effort
    ENDTRY.
  ENDMETHOD.

ENDCLASS.
