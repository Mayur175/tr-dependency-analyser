"! <p class="shorttext synchronized">TR Analyser - Public Cloud variant</p>
"!
"! Cloud-clean rewrite of ZCL_GCTS_TR_ANALYZER. Uses ONLY APIs that are
"! confirmed-released for ABAP for Cloud Development:
"!
"!   - cl_abap_context_info=>get_system_date / get_system_time
"!   - cl_http_destination_provider=>create_by_destination
"!   - cl_web_http_client_manager=>create_by_http_destination
"!   - if_web_http_client / if_web_http_request / if_web_http_response
"!   - cl_abap_char_utilities=>newline
"!   - Standard OpenSQL with @-escaped host vars
"!   - Custom Z-table ZGCTS_HIST (always allowed in cloud)
"!
"! Deliberately NOT used:
"!   - XCO repository / dictionary traversal (content struct shapes
"!     vary by BTP ABAP Environment SP level; we don't ship code that
"!     compiles on one tenant and not another).
"!   - xco_cp_json (we hand-roll JSON for full predictability).
"!   - cl_demo_output (not on the cloud allow-list).
"!   - Direct E070 / E071 / DD03L / SEOMETAREL / TFDIR reads (blocked).
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

    "! One repository object pulled from gCTS for an input commit.
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

    METHODS stage1_inventory_via_gcts.
    METHODS stage2_inventory_to_deps.

    METHODS add_dep
      IMPORTING is_dep TYPE ty_dep.

    METHODS read_gcts_commit_objects
      IMPORTING iv_commit_id  TYPE string
      RETURNING VALUE(rt_obj) TYPE tt_objects.

    METHODS json_escape
      IMPORTING iv_value         TYPE string
      RETURNING VALUE(rv_escaped) TYPE string.

    METHODS extract_objects_from_json
      IMPORTING iv_body       TYPE string
                iv_commit_id  TYPE string
      RETURNING VALUE(rt_obj) TYPE tt_objects.

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

    stage1_inventory_via_gcts( ).
    stage2_inventory_to_deps( ).

    out( |Done. Found { lines( mt_objects ) } objects, |
      && |{ lines( mt_deps ) } dependency rows.| ).

    mv_executed = abap_true.

  ENDMETHOD.


  METHOD stage1_inventory_via_gcts.

    " Pull repository objects from gCTS for every input id.
    LOOP AT mt_input INTO DATA(ls_in).
      IF ls_in-id IS INITIAL.
        CONTINUE.
      ENDIF.

      DATA(lt_obj) = read_gcts_commit_objects( CONV #( ls_in-id ) ).
      APPEND LINES OF lt_obj TO mt_objects.
    ENDLOOP.

  ENDMETHOD.


  METHOD stage2_inventory_to_deps.

    " The cloud variant emits one INVENTORIED dependency row per object
    " found in the gCTS inventory. This guarantees the analyser produces
    " a useful, persistable result on every cloud tenant.
    "
    " Deeper dependency walking (class superclass / interfaces, function
    " group expansion, DDIC where-used) requires XCO API calls whose
    " content-struct shapes vary across BTP ABAP Environment SP levels.
    " Rather than ship code that activates on one tenant and breaks on
    " another, the cloud variant treats stage 2 as a customer extension
    " point. See abap_cloud/README_CLOUD.md "Roadmap" for the recommended
    " XCO call patterns - they should be added against the customer's
    " specific tenant's XCO version, not blind-coded.

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


  METHOD read_gcts_commit_objects.

    " Reads /sap/bc/cts_abapvcs/repository/<repo>/commits/<id>/objects
    " via a customer-configured HTTP destination called 'GCTS_LOCAL'.
    " If the destination doesn't exist or the call fails, the method
    " returns an empty table and logs the reason - run() then degrades
    " to "no objects found" rather than crashing.
    "
    " The GCTS_LOCAL destination is set up once per tenant via a
    " Communication Arrangement / Destination Service. See
    " manual_install_cloud/README.md section "Set up the gCTS destination".

    DATA(lv_repo) = `tr-dependency-analyser`.

    TRY.
        DATA(lo_dest) = cl_http_destination_provider=>create_by_destination(
                          i_name = 'GCTS_LOCAL' ).

        DATA(lo_client) = cl_web_http_client_manager=>create_by_http_destination(
                            i_destination = lo_dest ).

        DATA(lo_request) = lo_client->get_http_request( ).
        lo_request->set_uri_path( i_uri_path =
          |/sap/bc/cts_abapvcs/repository/{ lv_repo }/commits/{ iv_commit_id }/objects| ).
        lo_request->set_header_field( i_name  = 'Accept'
                                      i_value = 'application/json' ).

        DATA(lo_response) = lo_client->execute(
                              i_method = if_web_http_client=>get ).

        DATA(lv_status) = lo_response->get_status( )-code.
        DATA(lv_body)   = lo_response->get_text( ).

        IF lv_status >= 400.
          out( |gCTS HTTP { lv_status } for { iv_commit_id }| ).
          RETURN.
        ENDIF.

        rt_obj = extract_objects_from_json( iv_body      = lv_body
                                            iv_commit_id = iv_commit_id ).

      CATCH cx_root INTO DATA(lo_ex).
        out( |gCTS REST call failed for { iv_commit_id }: { lo_ex->get_text( ) }| ).
    ENDTRY.

  ENDMETHOD.


  METHOD extract_objects_from_json.

    " Hand-rolled, defensive JSON object extractor. Looks for triplets of
    "   "pgmid":"X","object":"Y","name":"Z"
    " inside the response body. This avoids depending on JSON library
    " shapes that may differ across cloud SP levels.
    "
    " gCTS response shape:
    "   { "objects": [
    "     { "pgmid":"R3TR", "object":"CLAS", "name":"ZCL_X" }, ...
    "   ] }

    DATA(lv_rest) = iv_body.

    DO.
      FIND FIRST OCCURRENCE OF REGEX
        `"pgmid"\s*:\s*"([^"]*)"\s*,\s*"object"\s*:\s*"([^"]*)"\s*,\s*"name"\s*:\s*"([^"]*)"`
        IN lv_rest
        SUBMATCHES DATA(lv_pgmid) DATA(lv_object) DATA(lv_name).

      IF sy-subrc <> 0.
        EXIT.
      ENDIF.

      APPEND VALUE #(
        task_id  = CONV #( iv_commit_id )
        pgmid    = lv_pgmid
        obj_type = lv_object
        obj_name = lv_name ) TO rt_obj.

      " Move past this match to find the next triplet.
      DATA(lv_idx) = find( val = lv_rest sub = lv_name ).
      IF lv_idx < 0.
        EXIT.
      ENDIF.
      lv_rest = lv_rest+lv_idx.
      lv_idx  = find( val = lv_rest sub = `"` ).
      IF lv_idx < 0.
        EXIT.
      ENDIF.
      lv_rest = lv_rest+lv_idx.
      lv_rest = lv_rest+1.
    ENDDO.

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

    " Minimal JSON string escaping. Handles the four characters that
    " MUST be escaped per RFC 8259 inside a string literal.
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
    DATA lv_deps TYPE string.
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
    " allowed in cloud (only SAP-internal tables are on the deny-list).
    " Field names match the DDIC definition exactly:
    "   tr_id, run_ts, src_task, src_obj, tgt_task, tgt_obj,
    "   kind, risk, detail, pull_step, pull_action.
    "
    " Timestamp is built from cl_abap_context_info to match the classic
    " analyser's persistence format (date * 1000000 + time, type DEC15).

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