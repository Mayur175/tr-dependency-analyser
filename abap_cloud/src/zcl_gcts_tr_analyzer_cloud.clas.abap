"! <p class="shorttext synchronized">TR Analyser - Public Cloud variant</p>
"!
"! Cloud-clean rewrite of ZCL_GCTS_TR_ANALYZER. Uses ONLY APIs that are
"! confirmed-released for ABAP for Cloud Development:
"!
"!   - cl_abap_context_info=>get_system_date / get_system_time
"!   - cl_abap_char_utilities=>newline / cr_lf
"!   - Standard OpenSQL with @-escaped host vars
"!   - Custom Z-table ZGCTS_HIST (always allowed in cloud)
"!
"! Defensive coding choices:
"!   - Row counts done with a LOOP-AT counter rather than the lines( )
"!     built-in. Some cloud SP levels parse lines( ) inside expressions
"!     as a method-call attempt and reject it; an explicit LOOP is
"!     unambiguous and stable across releases.
"!   - All number-to-string conversion goes through |{ lv_int }| template
"!     literals, never through implicit && concatenation, to avoid type
"!     resolution surprises.
"!   - Stage 1 does NOT call out via HTTP. Outbound HTTP via
"!     cl_http_destination_provider has factory-method parameter names
"!     that vary by SP level and so is left as a per-tenant extension.
CLASS zcl_gcts_tr_analyzer_cloud DEFINITION
  PUBLIC FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.

    "! One TR / commit / task id per row.
    TYPES: BEGIN OF ty_input,
             id TYPE c LENGTH 20,
           END OF ty_input.
    TYPES tt_input TYPE STANDARD TABLE OF ty_input WITH EMPTY KEY.

    "! Output row for the JSON / persistence layer.
    TYPES: BEGIN OF ty_dep,
             source_task   TYPE string,
             source_object TYPE string,
             target_task   TYPE string,
             target_object TYPE string,
             kind          TYPE string,
             detail        TYPE string,
             risk          TYPE string,
           END OF ty_dep.
    TYPES tt_deps TYPE STANDARD TABLE OF ty_dep WITH EMPTY KEY.

    METHODS constructor
      IMPORTING it_input            TYPE tt_input            OPTIONAL
                iv_include_external TYPE abap_bool DEFAULT abap_false.

    METHODS run.
    METHODS to_json     RETURNING VALUE(rv_json) TYPE string.
    METHODS to_csv      RETURNING VALUE(rv_csv)  TYPE string.
    METHODS get_log     RETURNING VALUE(rv_log)  TYPE string.
    METHODS get_deps    RETURNING VALUE(rt_deps) TYPE tt_deps.
    METHODS persist_result.

  PRIVATE SECTION.

    CONSTANTS:
      c_risk_critical TYPE string VALUE 'CRITICAL',
      c_risk_high     TYPE string VALUE 'HIGH',
      c_risk_medium   TYPE string VALUE 'MEDIUM',
      c_risk_none     TYPE string VALUE 'NONE'.

    "! One repository object that we know about.
    TYPES: BEGIN OF ty_object,
             task_id  TYPE string,
             pgmid    TYPE string,
             obj_type TYPE string,
             obj_name TYPE string,
           END OF ty_object.
    TYPES tt_objects TYPE STANDARD TABLE OF ty_object WITH EMPTY KEY.

    DATA mt_input            TYPE tt_input.
    DATA mv_include_external TYPE abap_bool.
    DATA mv_label            TYPE string.
    DATA mt_objects          TYPE tt_objects.
    DATA mt_deps             TYPE tt_deps.
    DATA mv_log              TYPE string.
    DATA mv_executed         TYPE abap_bool.

    METHODS out
      IMPORTING iv_text TYPE string.

    METHODS stage1_inventory_from_input.
    METHODS stage2_inventory_to_deps.

    METHODS add_dep
      IMPORTING is_dep TYPE ty_dep.

    METHODS json_escape
      IMPORTING iv_value          TYPE string
      RETURNING VALUE(rv_escaped) TYPE string.

    METHODS count_objects
      RETURNING VALUE(rv_count) TYPE i.

    METHODS count_deps
      RETURNING VALUE(rv_count) TYPE i.

ENDCLASS.


CLASS zcl_gcts_tr_analyzer_cloud IMPLEMENTATION.

  METHOD constructor.

    mt_input            = it_input.
    mv_include_external = iv_include_external.

    " Build label "id1,id2,id3".
    DATA lv_first TYPE abap_bool.
    DATA lv_id    TYPE string.
    lv_first = abap_true.

    LOOP AT mt_input INTO DATA(ls).
      IF ls-id IS INITIAL.
        CONTINUE.
      ENDIF.
      lv_id = ls-id.
      IF lv_first = abap_true.
        mv_label = lv_id.
        lv_first = abap_false.
      ELSE.
        mv_label = mv_label && `,` && lv_id.
      ENDIF.
    ENDLOOP.

  ENDMETHOD.


  METHOD run.

    IF mv_executed = abap_true.
      RETURN.
    ENDIF.

    out( |TR Analyser (cloud) starting for input: { mv_label }| ).

    stage1_inventory_from_input( ).
    stage2_inventory_to_deps( ).

    DATA lv_obj_count TYPE i.
    DATA lv_dep_count TYPE i.
    lv_obj_count = count_objects( ).
    lv_dep_count = count_deps( ).

    out( |Done. Found { lv_obj_count } objects, { lv_dep_count } dependency rows.| ).

    mv_executed = abap_true.

  ENDMETHOD.


  METHOD stage1_inventory_from_input.

    " MVP: each input id becomes one inventory row of unknown type.
    " A per-tenant extension can replace this with an outbound gCTS REST
    " call once the customer verifies the right factory-method signature
    " for cl_http_destination_provider on their BTP ABAP Environment SP
    " level (parameter names vary across releases).
    DATA lv_id TYPE string.

    LOOP AT mt_input INTO DATA(ls_in).
      IF ls_in-id IS INITIAL.
        CONTINUE.
      ENDIF.

      lv_id = ls_in-id.

      APPEND VALUE #(
        task_id  = lv_id
        pgmid    = `R3TR`
        obj_type = `UNKNOWN`
        obj_name = lv_id
      ) TO mt_objects.
    ENDLOOP.

  ENDMETHOD.


  METHOD stage2_inventory_to_deps.

    " Emit one INVENTORIED dependency row per object found in stage 1.
    " Deeper dependency walking (class superclass / interfaces, FUGR
    " expansion, DDIC where-used) is documented as a per-tenant
    " extension because the XCO content-struct shapes vary across
    " BTP ABAP Environment SP levels.
    DATA ls_dep TYPE ty_dep.

    LOOP AT mt_objects INTO DATA(ls_obj).
      CLEAR ls_dep.
      ls_dep-source_task   = ls_obj-task_id.
      ls_dep-source_object = ls_obj-obj_type && `/` && ls_obj-obj_name.
      ls_dep-target_task   = ``.
      ls_dep-target_object = ``.
      ls_dep-kind          = `INVENTORIED`.
      ls_dep-detail        = `pgmid=` && ls_obj-pgmid.
      ls_dep-risk          = c_risk_none.
      add_dep( ls_dep ).
    ENDLOOP.

  ENDMETHOD.


  METHOD add_dep.

    " De-dup on the natural key.
    READ TABLE mt_deps WITH KEY
        source_task   = is_dep-source_task
        source_object = is_dep-source_object
        target_object = is_dep-target_object
        kind          = is_dep-kind
      TRANSPORTING NO FIELDS.
    IF sy-subrc <> 0.
      APPEND is_dep TO mt_deps.
    ENDIF.

  ENDMETHOD.


  METHOD count_objects.

    " Manual row count to avoid lines( ) parsing issues in some cloud SP
    " levels where it is rejected as an unknown method.
    rv_count = 0.
    LOOP AT mt_objects ASSIGNING FIELD-SYMBOL(<o>).
      rv_count = rv_count + 1.
    ENDLOOP.

  ENDMETHOD.


  METHOD count_deps.

    rv_count = 0.
    LOOP AT mt_deps ASSIGNING FIELD-SYMBOL(<d>).
      rv_count = rv_count + 1.
    ENDLOOP.

  ENDMETHOD.


  METHOD out.
    IF mv_log IS INITIAL.
      mv_log = iv_text.
    ELSE.
      mv_log = mv_log && cl_abap_char_utilities=>newline && iv_text.
    ENDIF.
  ENDMETHOD.


  METHOD get_log.
    rv_log = mv_log.
  ENDMETHOD.


  METHOD get_deps.
    rt_deps = mt_deps.
  ENDMETHOD.


  METHOD json_escape.

    " Minimal JSON string escaping per RFC 8259 inside string literals.
    rv_escaped = iv_value.
    REPLACE ALL OCCURRENCES OF `\` IN rv_escaped WITH `\\`.
    REPLACE ALL OCCURRENCES OF `"` IN rv_escaped WITH `\"`.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>newline
            IN rv_escaped WITH `\n`.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>cr_lf
            IN rv_escaped WITH `\n`.

  ENDMETHOD.


  METHOD to_json.

    " Hand-rolled JSON. Produces:
    "   {"label":"...","objectCount":N,"depCount":M,"deps":[ ... ]}
    DATA lv_deps      TYPE string.
    DATA lv_first     TYPE abap_bool.
    DATA lv_obj_count TYPE i.
    DATA lv_dep_count TYPE i.
    DATA lv_obj_str   TYPE string.
    DATA lv_dep_str   TYPE string.

    lv_first     = abap_true.
    lv_obj_count = count_objects( ).
    lv_dep_count = count_deps( ).
    lv_obj_str   = |{ lv_obj_count }|.
    lv_dep_str   = |{ lv_dep_count }|.

    LOOP AT mt_deps INTO DATA(ls).
      IF lv_first = abap_false.
        lv_deps = lv_deps && `,`.
      ENDIF.
      lv_first = abap_false.

      lv_deps = lv_deps
        && `{"sourceTask":"`   && json_escape( ls-source_task )   && `"`
        && `,"sourceObject":"` && json_escape( ls-source_object ) && `"`
        && `,"targetTask":"`   && json_escape( ls-target_task )   && `"`
        && `,"targetObject":"` && json_escape( ls-target_object ) && `"`
        && `,"kind":"`         && json_escape( ls-kind )          && `"`
        && `,"detail":"`       && json_escape( ls-detail )        && `"`
        && `,"risk":"`         && json_escape( ls-risk )          && `"}`.
    ENDLOOP.

    rv_json = `{"label":"`      && json_escape( mv_label ) && `"`
           && `,"objectCount":` && lv_obj_str
           && `,"depCount":`    && lv_dep_str
           && `,"deps":[`       && lv_deps && `]`
           && `}`.

  ENDMETHOD.


  METHOD to_csv.

    rv_csv = `source_task,source_object,target_task,target_object,kind,detail,risk`
           && cl_abap_char_utilities=>newline.

    LOOP AT mt_deps INTO DATA(ls).
      rv_csv = rv_csv
        && ls-source_task   && `,`
        && ls-source_object && `,`
        && ls-target_task   && `,`
        && ls-target_object && `,`
        && ls-kind          && `,`
        && ls-detail        && `,`
        && ls-risk          && cl_abap_char_utilities=>newline.
    ENDLOOP.

  ENDMETHOD.


  METHOD persist_result.

    " ZGCTS_HIST is a customer Z-table; custom Z-tables are always
    " allowed in cloud. Field names match the DDIC definition exactly:
    "   tr_id, run_ts, src_task, src_obj, tgt_task, tgt_obj,
    "   kind, risk, detail, pull_step, pull_action.
    "
    " Timestamp matches the classic analyser's format
    " (date * 1000000 + time, type DEC15).

    DATA lv_date     TYPE d.
    DATA lv_time     TYPE t.
    DATA lv_ts       TYPE zgcts_hist-run_ts.
    DATA lt_rows     TYPE STANDARD TABLE OF zgcts_hist WITH EMPTY KEY.
    DATA ls_row      TYPE zgcts_hist.
    DATA lv_count    TYPE i.

    lv_date = cl_abap_context_info=>get_system_date( ).
    lv_time = cl_abap_context_info=>get_system_time( ).
    lv_ts   = lv_date * 1000000 + lv_time.

    LOOP AT mt_deps INTO DATA(ls_dep).
      CLEAR ls_row.
      ls_row-tr_id       = mv_label.
      ls_row-run_ts      = lv_ts.
      ls_row-src_task    = ls_dep-source_task.
      ls_row-src_obj     = ls_dep-source_object.
      ls_row-tgt_task    = ls_dep-target_task.
      ls_row-tgt_obj     = ls_dep-target_object.
      ls_row-kind        = ls_dep-kind.
      ls_row-risk        = ls_dep-risk.
      ls_row-detail      = ls_dep-detail.
      ls_row-pull_step   = 0.
      ls_row-pull_action = `INVENTORIED`.
      APPEND ls_row TO lt_rows.
      lv_count = lv_count + 1.
    ENDLOOP.

    IF lv_count = 0.
      RETURN.
    ENDIF.

    INSERT zgcts_hist FROM TABLE @lt_rows.
    IF sy-subrc <> 0.
      out( |WARN: persist_result INSERT returned sy-subrc { sy-subrc }| ).
    ELSE.
      out( |INFO: { lv_count } rows saved to ZGCTS_HIST (run { lv_ts })| ).
    ENDIF.

  ENDMETHOD.

ENDCLASS.