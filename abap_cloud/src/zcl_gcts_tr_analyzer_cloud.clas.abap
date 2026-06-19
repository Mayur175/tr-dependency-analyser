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
"! Deliberately NOT used in the MVP:
"!   - XCO repository / dictionary traversal
"!   - xco_cp_json
"!   - cl_demo_output
"!   - Outbound HTTP via cl_http_destination_provider / cl_web_http_client_manager
"!     (factory-method parameter names vary by BTP ABAP Environment SP level;
"!      the MVP does not call out to gCTS REST. Stage 1 simply records the
"!      input ids as inventory; deeper transport-content reads are a per-tenant
"!      extension once the customer has verified the right factory signature.)
"!
"! Public surface mirrors the classic analyser so the Eclipse plugin
"! works against either backend without code changes.
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

    "! One repository object that we know about (from input or from a
    "! per-tenant gCTS extension once added).
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

ENDCLASS.


CLASS zcl_gcts_tr_analyzer_cloud IMPLEMENTATION.

  METHOD constructor.

    mt_input            = it_input.
    mv_include_external = iv_include_external.

    " Build label "id1,id2,id3"
    DATA lv_first TYPE abap_bool VALUE abap_true.
    LOOP AT mt_input INTO DATA(ls).
      IF ls-id IS INITIAL.
        CONTINUE.
      ENDIF.
      IF lv_first = abap_true.
        mv_label = CONV string( ls-id ).
        lv_first = abap_false.
      ELSE.
        mv_label = mv_label && `,` && CONV string( ls-id ).
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

    out( |Done. Found { lines( mt_objects ) } objects, |
      && |{ lines( mt_deps ) } dependency rows.| ).

    mv_executed = abap_true.

  ENDMETHOD.


  METHOD stage1_inventory_from_input.

    " MVP: each input id becomes one inventory row of unknown type.
    " A per-tenant extension can replace this with an outbound gCTS REST
    " call once the customer verifies the correct factory-method
    " signature for cl_http_destination_provider on their BTP ABAP
    " Environment SP level (parameter names vary across releases).
    LOOP AT mt_input INTO DATA(ls_in).
      IF ls_in-id IS INITIAL.
        CONTINUE.
      ENDIF.

      APPEND VALUE #(
        task_id  = CONV string( ls_in-id )
        pgmid    = `R3TR`
        obj_type = `UNKNOWN`
        obj_name = CONV string( ls_in-id )
      ) TO mt_objects.
    ENDLOOP.

  ENDMETHOD.


  METHOD stage2_inventory_to_deps.

    " Emit one INVENTORIED dependency row per object found in stage 1.
    " Deeper dependency walking (class superclass / interfaces, FUGR
    " expansion, DDIC where-used) is documented as a per-tenant
    " extension because the XCO content-struct shapes vary across
    " BTP ABAP Environment SP levels.
    LOOP AT mt_objects INTO DATA(ls_obj).
      add_dep( VALUE #(
        source_task   = ls_obj-task_id
        source_object = |{ ls_obj-obj_type }/{ ls_obj-obj_name }|
        target_task   = ''
        target_object = ''
        kind          = 'INVENTORIED'
        detail        = |pgmid={ ls_obj-pgmid }|
        risk          = c_risk_none ) ).
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
    DATA lv_deps  TYPE string.
    DATA lv_first TYPE abap_bool VALUE abap_true.

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

    rv_json = `{"label":"`     && json_escape( mv_label ) && `"`
           && `,"objectCount":` && lines( mt_objects )
           && `,"depCount":`    && lines( mt_deps )
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

    DATA lv_date TYPE d.
    DATA lv_time TYPE t.
    DATA lv_ts   TYPE zgcts_hist-run_ts.

    lv_date = cl_abap_context_info=>get_system_date( ).
    lv_time = cl_abap_context_info=>get_system_time( ).
    lv_ts   = lv_date * 1000000 + lv_time.

    DATA lt_rows TYPE STANDARD TABLE OF zgcts_hist WITH EMPTY KEY.

    LOOP AT mt_deps INTO DATA(ls_dep).
      APPEND VALUE zgcts_hist(
        tr_id       = mv_label
        run_ts      = lv_ts
        src_task    = ls_dep-source_task
        src_obj     = ls_dep-source_object
        tgt_task    = ls_dep-target_task
        tgt_obj     = ls_dep-target_object
        kind        = ls_dep-kind
        risk        = ls_dep-risk
        detail      = ls_dep-detail
        pull_step   = 0
        pull_action = 'INVENTORIED'
      ) TO lt_rows.
    ENDLOOP.

    IF lt_rows IS INITIAL.
      RETURN.
    ENDIF.

    INSERT zgcts_hist FROM TABLE @lt_rows.
    IF sy-subrc <> 0.
      out( |WARN: persist_result INSERT returned sy-subrc { sy-subrc }| ).
    ELSE.
      out( |INFO: { lines( lt_rows ) } rows saved to ZGCTS_HIST (run { lv_ts })| ).
    ENDIF.

  ENDMETHOD.

ENDCLASS.