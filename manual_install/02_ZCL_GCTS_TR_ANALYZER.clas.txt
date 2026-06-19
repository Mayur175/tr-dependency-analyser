"! TR Analyser - cross-task / cross-TR dependency pipeline.
"!
"! Inputs: one or more TR or task ids.
"!   - TR id   -> all child tasks (E070-STRKORR = TR) are expanded automatically.
"!   - Task id -> just that task.
"!
"! Usage (instance-safe; no static state):
"!   DATA(lo) = NEW zcl_gcts_tr_analyzer( it_input            = VALUE #( ( id = 'GMWK900691' ) )
"!                                          iv_include_external = abap_false ).
"!   DATA(lv_json) = lo->to_json( ).
"!   DATA(lv_csv)  = lo->to_csv( ).
"!   lo->persist_result( ).
"!
"! Backwards-compatible legacy path (deprecated, will be removed in v2):
"!   ZCL_GCTS_TR_ANALYZER=>GV_TR_ID = 'GMWK900691'.
"!   NEW zcl_gcts_tr_analyzer( ).
"!   -> internally maps to it_input = (( id = GV_TR_ID )).
CLASS zcl_gcts_tr_analyzer DEFINITION
  PUBLIC FINAL CREATE PUBLIC.

  PUBLIC SECTION.

    "! Public input row - one TR or task id per row
    TYPES: BEGIN OF ty_input,
             id TYPE trkorr,
           END OF ty_input.
    TYPES tt_input TYPE STANDARD TABLE OF ty_input WITH EMPTY KEY.

    "! Deprecated - use the new constructor instead. Kept only so existing
    "! callers (the original ICF handler / F9 scripts) continue to compile.
    CLASS-DATA gv_tr_id TYPE string.

    "! Deprecated - same reason as gv_tr_id.
    CLASS-DATA gv_include_external TYPE abap_bool VALUE abap_false.

    "! Modern instance-based constructor (preferred).
    "!
    "! @parameter it_input            | one row per TR or task id to analyse
    "! @parameter iv_include_external | when ABAP_TRUE, also report objects
    "!                                   that depend on items outside the input set
    METHODS constructor
      IMPORTING it_input            TYPE tt_input            OPTIONAL
                iv_include_external TYPE abap_bool DEFAULT abap_false.

    METHODS to_json     RETURNING VALUE(rv_json) TYPE string.
    METHODS to_csv      RETURNING VALUE(rv_csv)  TYPE string.
    METHODS persist_result.

  PRIVATE SECTION.

    CONSTANTS:
      c_risk_critical TYPE string VALUE 'CRITICAL',
      c_risk_high     TYPE string VALUE 'HIGH',
      c_risk_medium   TYPE string VALUE 'MEDIUM',
      c_risk_none     TYPE string VALUE 'NONE'.

    TYPES: BEGIN OF ty_object,
             task_id  TYPE string,
             obj_type TYPE string,
             obj_name TYPE string,
           END OF ty_object.
    TYPES tt_objects TYPE STANDARD TABLE OF ty_object WITH EMPTY KEY.

    TYPES: BEGIN OF ty_dep,
             source_task   TYPE string,
             source_object TYPE string,
             target_task   TYPE string,
             target_object TYPE string,
             kind          TYPE string,
             detail        TYPE string,
           END OF ty_dep.
    TYPES tt_deps TYPE STANDARD TABLE OF ty_dep WITH EMPTY KEY.

    TYPES: BEGIN OF ty_uf,
             task   TYPE string,
             parent TYPE string,
           END OF ty_uf.
    TYPES tt_uf TYPE STANDARD TABLE OF ty_uf WITH EMPTY KEY.

    TYPES: BEGIN OF ty_cluster,
             root  TYPE string,
             tasks TYPE string,
             risk  TYPE string,
           END OF ty_cluster.
    TYPES tt_clusters TYPE STANDARD TABLE OF ty_cluster WITH EMPTY KEY.

    "! Header label for the to_json / cl_demo_output output. Built from
    "! the input set: single id -> the id, multiple ids -> "id1,id2,..." .
    DATA mv_label TYPE string.

    "! Resolved input - one row per task id we are going to scan.
    "! Built from it_input: each TR id is expanded to its tasks via E070,
    "! and bare task ids are passed through as-is.
    DATA mt_tasks TYPE STANDARD TABLE OF trkorr WITH EMPTY KEY.

    DATA mv_include_external TYPE abap_bool.

    DATA mt_objects  TYPE tt_objects.
    DATA mt_deps     TYPE tt_deps.
    DATA mt_clusters TYPE tt_clusters.

    METHODS resolve_input
      IMPORTING it_input TYPE tt_input.

    METHODS stage1_inventory.
    METHODS stage2_dependencies.
    METHODS stage2b_conflicts.
    METHODS stage3_clusters.
    METHODS stage4_output.

    METHODS deps_for_clas IMPORTING iv_name TYPE string iv_task TYPE string.
    METHODS deps_for_intf IMPORTING iv_name TYPE string iv_task TYPE string.
    METHODS deps_for_tabl IMPORTING iv_name TYPE string iv_task TYPE string.
    METHODS deps_for_dtel IMPORTING iv_name TYPE string iv_task TYPE string.
    METHODS deps_for_ddls IMPORTING iv_name TYPE string iv_task TYPE string.
    METHODS deps_for_ddlx IMPORTING iv_name TYPE string iv_task TYPE string.
    METHODS deps_for_bdef IMPORTING iv_name TYPE string iv_task TYPE string.
    METHODS deps_for_fugr IMPORTING iv_name TYPE string iv_task TYPE string.

    METHODS add_external_dep
      IMPORTING iv_src_task TYPE string
                iv_src_obj  TYPE string
                iv_tgt_obj  TYPE string
                iv_kind     TYPE string
                iv_detail   TYPE string DEFAULT ''.

    METHODS uf_find
      IMPORTING iv_task        TYPE string
      CHANGING  ct_uf          TYPE tt_uf
      RETURNING VALUE(rv_root) TYPE string.

    METHODS uf_union
      IMPORTING iv_a  TYPE string iv_b TYPE string
      CHANGING  ct_uf TYPE tt_uf.

    METHODS pull_step_of_task
      IMPORTING iv_task        TYPE string
      RETURNING VALUE(rv_step) TYPE i.

    METHODS pull_action_of_task
      IMPORTING iv_task          TYPE string
      RETURNING VALUE(rv_action) TYPE string.

    METHODS risk_of_task
      IMPORTING iv_task        TYPE string
      RETURNING VALUE(rv_risk) TYPE string.

    METHODS task_of_object
      IMPORTING iv_name        TYPE string
      RETURNING VALUE(rv_task) TYPE string.

    METHODS add_dep
      IMPORTING iv_src_task TYPE string
                iv_src_obj  TYPE string
                iv_tgt_task TYPE string
                iv_tgt_obj  TYPE string
                iv_kind     TYPE string
                iv_detail   TYPE string DEFAULT ''.

    METHODS out        IMPORTING iv_text TYPE string.
    METHODS out_header.

    METHODS json_escape
      IMPORTING iv_val        TYPE string
      RETURNING VALUE(rv_val) TYPE string.

    METHODS csv_esc
      IMPORTING iv_val        TYPE string
      RETURNING VALUE(rv_out) TYPE string.

ENDCLASS.


CLASS zcl_gcts_tr_analyzer IMPLEMENTATION.

" =============================================================================
" CONSTRUCTOR
" Accepts either the new it_input table or the legacy gv_tr_id static.
" =============================================================================
  METHOD constructor.

    DATA lt_input TYPE tt_input.

    IF it_input IS NOT INITIAL.
      lt_input            = it_input.
      mv_include_external = iv_include_external.
    ELSEIF gv_tr_id IS NOT INITIAL.
      " Legacy path - keeps existing ICF handler / F9 callers working.
      APPEND VALUE #( id = CONV trkorr( gv_tr_id ) ) TO lt_input.
      mv_include_external = gv_include_external.
    ELSE.
      out( '*** TR Analyser: provide it_input, or set gv_tr_id (deprecated) ***' ).
      RETURN.
    ENDIF.

    resolve_input( lt_input ).

    IF mt_tasks IS INITIAL.
      out( |No tasks resolved from input { mv_label }| ).
      RETURN.
    ENDIF.

    stage1_inventory( ).
    stage2_dependencies( ).
    stage2b_conflicts( ).
    stage3_clusters( ).
    stage4_output( ).
  ENDMETHOD.


" =============================================================================
" RESOLVE_INPUT
" Each input id is checked against E070:
"   - If it has child tasks (other rows whose STRKORR = id) -> expand to children.
"   - Otherwise treat the id itself as a single task.
" Builds:
"   mt_tasks  - flat list of task TRKORRs to scan
"   mv_label  - human-readable input description for headers / JSON
" =============================================================================
  METHOD resolve_input.

    DATA lt_label_parts TYPE string_table.

    LOOP AT it_input INTO DATA(ls_in).
      DATA(lv_id) = ls_in-id.
      IF lv_id IS INITIAL. CONTINUE. ENDIF.

      APPEND CONV string( lv_id ) TO lt_label_parts.

      " Children of this id (treat lv_id as a TR)
      DATA lt_children TYPE STANDARD TABLE OF trkorr WITH EMPTY KEY.
      SELECT trkorr FROM e070
        WHERE strkorr = @lv_id
        INTO TABLE @lt_children.

      IF lt_children IS NOT INITIAL.
        " It is a TR - expand to its tasks
        LOOP AT lt_children INTO DATA(lv_child).
          IF NOT line_exists( mt_tasks[ table_line = lv_child ] ).
            APPEND lv_child TO mt_tasks.
          ENDIF.
        ENDLOOP.
      ELSE.
        " Treat as a task id directly
        IF NOT line_exists( mt_tasks[ table_line = lv_id ] ).
          APPEND lv_id TO mt_tasks.
        ENDIF.
      ENDIF.
    ENDLOOP.

    mv_label = concat_lines_of( table = lt_label_parts sep = `,` ).
  ENDMETHOD.


" =============================================================================
" STAGE 1 - Task Inventory
" Reads E071 directly for every task in mt_tasks. Works on every release of
" SAP NetWeaver / S/4HANA since R/3 4.6C, regardless of XCO availability.
" =============================================================================
  METHOD stage1_inventory.

    SELECT trkorr   AS task_id,
           pgmid    AS pgmid,
           object   AS object,
           obj_name AS obj_name
      FROM e071
      FOR ALL ENTRIES IN @mt_tasks
      WHERE trkorr = @mt_tasks-table_line
      INTO TABLE @DATA(lt_raw).

    IF sy-subrc <> 0 OR lt_raw IS INITIAL.
      out( |Input { mv_label }: no objects found in any task (verify ids in SE09)| ).
      RETURN.
    ENDIF.

    LOOP AT lt_raw INTO DATA(ls).
      APPEND VALUE #(
        task_id  = CONV string( ls-task_id )
        obj_type = CONV string( ls-pgmid ) && '/' && CONV string( ls-object )
        obj_name = CONV string( ls-obj_name ) ) TO mt_objects.
    ENDLOOP.

    out( |Stage 1: { lines( mt_objects ) } objects collected from { lines( mt_tasks ) } task(s)| ).

  ENDMETHOD.


" =============================================================================
" STAGE 2 - Dependency Extraction (per object type)
" =============================================================================
  METHOD stage2_dependencies.
    LOOP AT mt_objects INTO DATA(ls_obj).
      DATA(lv_type) = ls_obj-obj_type.
      FIND REGEX '[^/]+$' IN lv_type MATCH OFFSET DATA(lo) MATCH LENGTH DATA(ll).
      IF sy-subrc = 0. lv_type = lv_type+lo(ll). ENDIF.

      CASE lv_type.
        WHEN 'CLAS'. deps_for_clas( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
        WHEN 'INTF'. deps_for_intf( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
        WHEN 'TABL'. deps_for_tabl( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
        WHEN 'DTEL'. deps_for_dtel( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
        WHEN 'DDLS'. deps_for_ddls( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
        WHEN 'DDLX'. deps_for_ddlx( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
        WHEN 'BDEF'. deps_for_bdef( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
        WHEN 'FUGR'. deps_for_fugr( iv_name = ls_obj-obj_name iv_task = ls_obj-task_id ).
      ENDCASE.
    ENDLOOP.
  ENDMETHOD.


  METHOD deps_for_clas.
    DATA: lv_clsname TYPE seometarel-clsname,
          lv_reltype TYPE seometarel-reltype,
          lv_super   TYPE seometarel-refclsname.

    lv_clsname = to_upper( iv_name ).

    " Superclass: RELTYPE 'EX' = extends/inherits
    lv_reltype = 'EX'.
    SELECT SINGLE refclsname FROM seometarel
      WHERE clsname = @lv_clsname AND reltype = @lv_reltype
      INTO @lv_super.
    IF sy-subrc = 0 AND lv_super IS NOT INITIAL.
      add_dep( iv_src_task = iv_task  iv_src_obj = |CLAS/{ iv_name }|
               iv_tgt_task = task_of_object( CONV string( lv_super ) )
               iv_tgt_obj  = |CLAS/{ lv_super }|
               iv_kind     = 'INHERITS'
               iv_detail   = |{ iv_name } extends { lv_super }| ).
    ENDIF.

    " Implemented interfaces: RELTYPE 'EI' = implements interface
    lv_reltype = 'EI'.
    DATA lt_intfs TYPE STANDARD TABLE OF seometarel-refclsname WITH EMPTY KEY.
    SELECT refclsname FROM seometarel
      WHERE clsname = @lv_clsname AND reltype = @lv_reltype
      INTO TABLE @lt_intfs.
    LOOP AT lt_intfs INTO DATA(lv_intf_raw).
      DATA(lv_intf) = CONV string( lv_intf_raw ).
      add_dep( iv_src_task = iv_task  iv_src_obj = |CLAS/{ iv_name }|
               iv_tgt_task = task_of_object( lv_intf )
               iv_tgt_obj  = |INTF/{ lv_intf }|
               iv_kind     = 'IMPLEMENTS'
               iv_detail   = |{ iv_name } implements { lv_intf }| ).
    ENDLOOP.
  ENDMETHOD.


  METHOD deps_for_intf.
    DATA: lv_clsname TYPE seometarel-clsname,
          lv_reltype TYPE seometarel-reltype.
    DATA lt_parents TYPE STANDARD TABLE OF seometarel-refclsname WITH EMPTY KEY.

    lv_clsname = to_upper( iv_name ).
    lv_reltype = 'EI'.

    SELECT refclsname FROM seometarel
      WHERE clsname = @lv_clsname AND reltype = @lv_reltype
      INTO TABLE @lt_parents.
    LOOP AT lt_parents INTO DATA(lv_par_raw).
      DATA(lv_par) = CONV string( lv_par_raw ).
      add_dep( iv_src_task = iv_task  iv_src_obj = |INTF/{ iv_name }|
               iv_tgt_task = task_of_object( lv_par )
               iv_tgt_obj  = |INTF/{ lv_par }|
               iv_kind     = 'IMPLEMENTS'
               iv_detail   = |{ iv_name } extends { lv_par }| ).
    ENDLOOP.
  ENDMETHOD.


  METHOD deps_for_tabl.
    DATA: lv_tabname TYPE dd03l-tabname,
          lv_local   TYPE dd03l-as4local.
    DATA lt_dtel TYPE STANDARD TABLE OF dd03l-rollname WITH EMPTY KEY.

    lv_tabname = to_upper( iv_name ).
    lv_local   = 'A'.

    SELECT DISTINCT rollname FROM dd03l
      WHERE tabname  = @lv_tabname
        AND rollname <> ''
        AND as4local = @lv_local
      INTO TABLE @lt_dtel.
    LOOP AT lt_dtel INTO DATA(lv_de_raw).
      DATA(lv_de) = CONV string( lv_de_raw ).
      add_dep( iv_src_task = iv_task  iv_src_obj = |TABL/{ iv_name }|
               iv_tgt_task = task_of_object( lv_de )
               iv_tgt_obj  = |DTEL/{ lv_de }|
               iv_kind     = 'TYPE_REF'
               iv_detail   = |{ iv_name } column -> { lv_de }| ).
    ENDLOOP.
  ENDMETHOD.


  METHOD deps_for_dtel.
    DATA: lv_rollname TYPE dd04l-rollname,
          lv_local    TYPE dd04l-as4local,
          lv_dom      TYPE dd04l-domname.

    lv_rollname = to_upper( iv_name ).
    lv_local    = 'A'.

    SELECT SINGLE domname FROM dd04l
      WHERE rollname = @lv_rollname AND as4local = @lv_local
      INTO @lv_dom.
    IF sy-subrc = 0 AND lv_dom IS NOT INITIAL.
      add_dep( iv_src_task = iv_task  iv_src_obj = |DTEL/{ iv_name }|
               iv_tgt_task = task_of_object( CONV string( lv_dom ) )
               iv_tgt_obj  = |DOMA/{ lv_dom }|
               iv_kind     = 'TYPE_REF'
               iv_detail   = |{ iv_name } domain -> { lv_dom }| ).
    ENDIF.
  ENDMETHOD.


  METHOD deps_for_ddls.
    " CDS view dependencies require system-specific knowledge of the
    " DDLDEPENDENCY table layout (column names changed across releases).
    " Skipped here; handled by Phase 2 of the SOLUTION_ARCHITECTURE roadmap
    " once the target release is confirmed in SE11.
    RETURN.
  ENDMETHOD.


  METHOD deps_for_ddlx.
    " Metadata extension dependencies skipped - see deps_for_ddls note.
    RETURN.
  ENDMETHOD.


  METHOD deps_for_bdef.
    " RAP behavior definition dependencies require XCO_CP_BDL on systems
    " where it is available; not yet implemented to avoid release coupling.
    RETURN.
  ENDMETHOD.


  METHOD deps_for_fugr.
    " Use TFDIR (function module directory) to find FMs in this function group.
    " PNAME in TFDIR = function group name.
    TRY.
        SELECT funcname FROM tfdir
          WHERE pname = @iv_name
          INTO TABLE @DATA(lt_fms).

        LOOP AT lt_fms INTO DATA(ls_fm).
          DATA(lv_fm_name)  = CONV string( ls_fm-funcname ).
          DATA(lv_tgt_task) = task_of_object( lv_fm_name ).
          add_dep( iv_src_task = iv_task  iv_src_obj = |FUGR/{ iv_name }|
                   iv_tgt_task = lv_tgt_task
                   iv_tgt_obj  = |FUGR/{ lv_fm_name }|
                   iv_kind     = 'CALLS'
                   iv_detail   = |{ iv_name } -> FM { lv_fm_name }| ).
        ENDLOOP.
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


" =============================================================================
" STAGE 2b - Same-Object Conflict Detection
" =============================================================================
  METHOD stage2b_conflicts.
    TYPES: BEGIN OF ty_obj_tasks,
             obj_name TYPE string,
             tasks    TYPE string,
           END OF ty_obj_tasks.
    DATA lt_map TYPE HASHED TABLE OF ty_obj_tasks WITH UNIQUE KEY obj_name.

    LOOP AT mt_objects INTO DATA(ls_o).
      DATA(lr_entry) = REF #( lt_map[ obj_name = ls_o-obj_name ] OPTIONAL ).
      IF lr_entry IS NOT BOUND.
        INSERT VALUE #( obj_name = ls_o-obj_name tasks = ls_o-task_id ) INTO TABLE lt_map.
      ELSE.
        IF NOT lr_entry->tasks CS ls_o-task_id.
          lr_entry->tasks = lr_entry->tasks && ',' && ls_o-task_id.
        ENDIF.
      ENDIF.
    ENDLOOP.

    LOOP AT lt_map INTO DATA(ls_map).
      IF NOT ls_map-tasks CA ','. CONTINUE. ENDIF.

      DATA lt_task_list TYPE string_table.
      SPLIT ls_map-tasks AT ',' INTO TABLE lt_task_list.

      DATA lv_first TYPE string VALUE ''.
      LOOP AT lt_task_list INTO DATA(lv_task).
        DATA(lv_task_c) = condense( lv_task ).
        IF sy-tabix = 1.
          lv_first = lv_task_c.
        ELSE.
          APPEND VALUE #(
            source_task   = lv_first
            source_object = ls_map-obj_name
            target_task   = lv_task_c
            target_object = ls_map-obj_name
            kind          = 'CONFLICT'
            detail        = |{ ls_map-obj_name } owned by both { lv_first } and { lv_task_c }| )
            TO mt_deps.
        ENDIF.
      ENDLOOP.
    ENDLOOP.
  ENDMETHOD.


" =============================================================================
" STAGE 3 - Cluster Detection (Union-Find)
" =============================================================================
  METHOD stage3_clusters.
    DATA lt_uf TYPE tt_uf.

    LOOP AT mt_objects INTO DATA(ls_o).
      IF NOT line_exists( lt_uf[ task = ls_o-task_id ] ).
        APPEND VALUE #( task = ls_o-task_id parent = ls_o-task_id ) TO lt_uf.
      ENDIF.
    ENDLOOP.

    LOOP AT mt_deps INTO DATA(ls_d).
      uf_union( EXPORTING iv_a = ls_d-source_task iv_b = ls_d-target_task
                CHANGING  ct_uf = lt_uf ).
    ENDLOOP.

    LOOP AT lt_uf INTO DATA(ls_uf).
      DATA(lv_root) = uf_find( EXPORTING iv_task = ls_uf-task CHANGING ct_uf = lt_uf ).
      DATA(lr_cl) = REF #( mt_clusters[ root = lv_root ] OPTIONAL ).
      IF lr_cl IS NOT BOUND.
        APPEND VALUE #( root = lv_root tasks = ls_uf-task risk = c_risk_none ) TO mt_clusters.
        lr_cl = REF #( mt_clusters[ root = lv_root ] ).
      ELSE.
        IF NOT lr_cl->tasks CS ls_uf-task.
          lr_cl->tasks = lr_cl->tasks && ',' && ls_uf-task.
        ENDIF.
      ENDIF.
    ENDLOOP.

    LOOP AT mt_clusters REFERENCE INTO DATA(lr_cluster).
      LOOP AT mt_deps INTO DATA(ls_dep).
        DATA(lv_src_root) = uf_find( EXPORTING iv_task = ls_dep-source_task
                                     CHANGING  ct_uf = lt_uf ).
        IF lv_src_root <> lr_cluster->root. CONTINUE. ENDIF.
        CASE ls_dep-kind.
          WHEN 'CONFLICT'.
            lr_cluster->risk = c_risk_critical.
          WHEN 'IMPLEMENTS' OR 'INHERITS'.
            IF lr_cluster->risk <> c_risk_critical.
              lr_cluster->risk = c_risk_high.
            ENDIF.
          WHEN 'TYPE_REF' OR 'USES' OR 'EXTENDS' OR 'CALLS'.
            IF lr_cluster->risk = c_risk_none.
              lr_cluster->risk = c_risk_medium.
            ENDIF.
        ENDCASE.
      ENDLOOP.
    ENDLOOP.

    SORT mt_clusters BY risk ASCENDING.
  ENDMETHOD.


  METHOD uf_find.
    TRY.
        DATA(ls_node) = ct_uf[ task = iv_task ].
        IF ls_node-parent = ls_node-task.
          rv_root = ls_node-task.
        ELSE.
          rv_root = uf_find( EXPORTING iv_task = ls_node-parent CHANGING ct_uf = ct_uf ).
          ct_uf[ task = iv_task ]-parent = rv_root.
        ENDIF.
    CATCH cx_sy_itab_line_not_found.
      rv_root = iv_task.
    ENDTRY.
  ENDMETHOD.


  METHOD uf_union.
    DATA(lv_ra) = uf_find( EXPORTING iv_task = iv_a CHANGING ct_uf = ct_uf ).
    DATA(lv_rb) = uf_find( EXPORTING iv_task = iv_b CHANGING ct_uf = ct_uf ).
    IF lv_ra <> lv_rb.
      TRY.
          ct_uf[ task = lv_rb ]-parent = lv_ra.
      CATCH cx_sy_itab_line_not_found.
      ENDTRY.
    ENDIF.
  ENDMETHOD.


" =============================================================================
" STAGE 4 - Console Output (cl_demo_output)
" =============================================================================
  METHOD stage4_output.
    DATA lt_unique_tasks TYPE SORTED TABLE OF string WITH UNIQUE KEY table_line.
    LOOP AT mt_objects INTO DATA(ls_o).
      INSERT ls_o-task_id INTO TABLE lt_unique_tasks.
    ENDLOOP.

    out_header( ).
    out( |  Input: { mv_label }  Tasks: { lines( lt_unique_tasks ) }  Objects: { lines( mt_objects ) }  Edges: { lines( mt_deps ) }| ).
    out( '' ).

    DATA lv_step TYPE i VALUE 1.
    LOOP AT mt_clusters INTO DATA(ls_cl).
      CASE ls_cl-risk.
        WHEN c_risk_critical.
          out( |[CRITICAL] CONFLICT - same object in multiple tasks!| ).
          out( |  Tasks: { ls_cl-tasks }| ).
        WHEN c_risk_high.
          out( |[HIGH]     Must pull together (activation dependency)| ).
          out( |  Tasks: { ls_cl-tasks }| ).
        WHEN c_risk_medium.
          out( |[MEDIUM]   Recommend pulling together (type reference)| ).
          out( |  Tasks: { ls_cl-tasks }| ).
        WHEN c_risk_none.
          out( |[OK]       Independent - safe to pull alone| ).
          out( |  Task: { ls_cl-tasks }| ).
      ENDCASE.
      LOOP AT mt_deps INTO DATA(ls_dep).
        CHECK ls_cl-tasks CS ls_dep-source_task.
        out( |    { ls_dep-kind }: { ls_dep-detail }| ).
      ENDLOOP.
      out( '' ).
    ENDLOOP.

    out( '-----------------------------------------------------------------' ).
    out( 'Recommended Pull Order:' ).
    LOOP AT mt_clusters INTO DATA(ls_step).
      DATA lv_act TYPE string.
      CASE ls_step-risk.
        WHEN c_risk_critical. lv_act = 'COORDINATE first, then pull TOGETHER'.
        WHEN c_risk_high.     lv_act = 'Pull TOGETHER'.
        WHEN c_risk_medium.   lv_act = 'Pull together (recommended)'.
        WHEN OTHERS.          lv_act = 'Pull alone'.
      ENDCASE.
      out( |  Step { lv_step }: { lv_act } -> { ls_step-tasks }| ).
      lv_step += 1.
    ENDLOOP.
    out( '-----------------------------------------------------------------' ).
  ENDMETHOD.


" =============================================================================
" TO_JSON
" =============================================================================
  METHOD to_json.
    DATA lt_unique_tasks TYPE SORTED TABLE OF string WITH UNIQUE KEY table_line.
    LOOP AT mt_objects INTO DATA(ls_o).
      INSERT ls_o-task_id INTO TABLE lt_unique_tasks.
    ENDLOOP.

    DATA(lv_summary) = |"tr":"{ json_escape( mv_label ) }",| &&
                       |"taskCount":{ lines( lt_unique_tasks ) },| &&
                       |"objectCount":{ lines( mt_objects ) },| &&
                       |"edgeCount":{ lines( mt_deps ) }|.

    DATA lv_clusters TYPE string.
    CLEAR lv_clusters.

    LOOP AT mt_clusters INTO DATA(ls_cl).
      DATA lv_tasks_arr TYPE string.
      CLEAR lv_tasks_arr.
      DATA lt_tasks TYPE string_table.
      SPLIT ls_cl-tasks AT ',' INTO TABLE lt_tasks.
      LOOP AT lt_tasks INTO DATA(lv_t).
        DATA(lv_tc) = condense( lv_t ).
        IF lv_tasks_arr IS NOT INITIAL. lv_tasks_arr = lv_tasks_arr && ','. ENDIF.
        lv_tasks_arr = lv_tasks_arr && |"{ json_escape( lv_tc ) }"|.
      ENDLOOP.

      DATA lv_edges_arr TYPE string.
      CLEAR lv_edges_arr.
      LOOP AT mt_deps INTO DATA(ls_dep).
        CHECK ls_cl-tasks CS ls_dep-source_task.
        IF lv_edges_arr IS NOT INITIAL. lv_edges_arr = lv_edges_arr && ','. ENDIF.
        lv_edges_arr = lv_edges_arr &&
          `{` &&
          |"from":"{ json_escape( ls_dep-source_object ) }",| &&
          |"fromTask":"{ json_escape( ls_dep-source_task ) }",| &&
          |"to":"{ json_escape( ls_dep-target_object ) }",| &&
          |"toTask":"{ json_escape( ls_dep-target_task ) }",| &&
          |"kind":"{ json_escape( ls_dep-kind ) }",| &&
          |"detail":"{ json_escape( ls_dep-detail ) }"| &&
          `}`.
      ENDLOOP.

      IF lv_clusters IS NOT INITIAL. lv_clusters = lv_clusters && ','. ENDIF.
      lv_clusters = lv_clusters &&
        `{` &&
        |"risk":"{ ls_cl-risk }",| &&
        |"tasks":[{ lv_tasks_arr }],| &&
        |"edges":[{ lv_edges_arr }]| &&
        `}`.
    ENDLOOP.

    DATA lv_pull_order TYPE string.
    CLEAR lv_pull_order.
    DATA lv_step TYPE i VALUE 1.

    LOOP AT mt_clusters INTO DATA(ls_step).
      DATA lv_step_tasks TYPE string.
      CLEAR lv_step_tasks.
      DATA lt_st TYPE string_table.
      SPLIT ls_step-tasks AT ',' INTO TABLE lt_st.
      LOOP AT lt_st INTO DATA(lv_st).
        DATA(lv_stc) = condense( lv_st ).
        IF lv_step_tasks IS NOT INITIAL. lv_step_tasks = lv_step_tasks && ','. ENDIF.
        lv_step_tasks = lv_step_tasks && |"{ json_escape( lv_stc ) }"|.
      ENDLOOP.

      DATA lv_action TYPE string.
      CASE ls_step-risk.
        WHEN c_risk_critical. lv_action = 'COORDINATE'.
        WHEN c_risk_high.     lv_action = 'TOGETHER'.
        WHEN c_risk_medium.   lv_action = 'TOGETHER_RECOMMENDED'.
        WHEN OTHERS.          lv_action = 'ALONE'.
      ENDCASE.

      IF lv_pull_order IS NOT INITIAL. lv_pull_order = lv_pull_order && ','. ENDIF.
      lv_pull_order = lv_pull_order &&
        `{"step":` && lv_step &&
        `,"action":"` && lv_action && `"` &&
        `,"tasks":[` && lv_step_tasks && `]}`.
      lv_step += 1.
    ENDLOOP.

    rv_json = `{"version":"1.1",` && lv_summary &&
              `,"clusters":[` && lv_clusters && `]` &&
              `,"pullOrder":[` && lv_pull_order && `]` &&
              `}`.
  ENDMETHOD.


" =============================================================================
" HELPERS
" =============================================================================
  METHOD task_of_object.
    TRY.
        rv_task = mt_objects[ obj_name = iv_name ]-task_id.
    CATCH cx_sy_itab_line_not_found.
        rv_task = ''.
    ENDTRY.
  ENDMETHOD.


  METHOD add_dep.
    IF iv_tgt_task IS INITIAL OR iv_tgt_task = iv_src_task. RETURN. ENDIF.
    APPEND VALUE #(
      source_task   = iv_src_task
      source_object = iv_src_obj
      target_task   = iv_tgt_task
      target_object = iv_tgt_obj
      kind          = iv_kind
      detail        = iv_detail ) TO mt_deps.
  ENDMETHOD.


  METHOD add_external_dep.
    IF mv_include_external = abap_false. RETURN. ENDIF.
    IF iv_src_task IS INITIAL. RETURN. ENDIF.
    APPEND VALUE #(
      source_task   = iv_src_task
      source_object = iv_src_obj
      target_task   = ''
      target_object = iv_tgt_obj
      kind          = |EXT_{ iv_kind }|
      detail        = |[EXTERNAL] { iv_detail }| ) TO mt_deps.
  ENDMETHOD.


  METHOD pull_step_of_task.
    DATA lv_step TYPE i VALUE 1.
    LOOP AT mt_clusters INTO DATA(ls_cl).
      IF ls_cl-tasks CS iv_task.
        rv_step = lv_step.
        RETURN.
      ENDIF.
      lv_step += 1.
    ENDLOOP.
    rv_step = lv_step.
  ENDMETHOD.


  METHOD pull_action_of_task.
    LOOP AT mt_clusters INTO DATA(ls_cl).
      IF ls_cl-tasks CS iv_task.
        CASE ls_cl-risk.
          WHEN c_risk_critical. rv_action = 'COORDINATE'.
          WHEN c_risk_high.     rv_action = 'TOGETHER'.
          WHEN c_risk_medium.   rv_action = 'TOGETHER_RECOMMENDED'.
          WHEN OTHERS.          rv_action = 'ALONE'.
        ENDCASE.
        RETURN.
      ENDIF.
    ENDLOOP.
    rv_action = 'ALONE'.
  ENDMETHOD.


  METHOD risk_of_task.
    LOOP AT mt_clusters INTO DATA(ls_cl).
      IF ls_cl-tasks CS iv_task.
        rv_risk = ls_cl-risk.
        RETURN.
      ENDIF.
    ENDLOOP.
    rv_risk = c_risk_none.
  ENDMETHOD.


  METHOD out.
    cl_demo_output=>write_text( iv_text ).
  ENDMETHOD.


  METHOD out_header.
    out( '=================================================================' ).
    out( |  TR Analyser  -  Input: { mv_label }| ).
    out( '=================================================================' ).
  ENDMETHOD.


  METHOD json_escape.
    rv_val = iv_val.
    REPLACE ALL OCCURRENCES OF '\' IN rv_val WITH '\\'.
    REPLACE ALL OCCURRENCES OF '"' IN rv_val WITH '\"'.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>newline IN rv_val WITH '\n'.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>cr_lf   IN rv_val WITH '\n'.
  ENDMETHOD.


  METHOD csv_esc.
    DATA(lv) = iv_val.
    REPLACE ALL OCCURRENCES OF '"' IN lv WITH '""'.
    rv_out = '"' && lv && '"'.
  ENDMETHOD.


" =============================================================================
" TO_CSV
" =============================================================================
  METHOD to_csv.
    DATA(lv_ts) = cl_abap_context_info=>get_system_date( ) &&
                  cl_abap_context_info=>get_system_time( ).

    rv_csv = 'INPUT,RUN_TS,SRC_TASK,SRC_OBJ,TGT_TASK,TGT_OBJ,KIND,RISK,DETAIL,PULL_STEP,PULL_ACTION' &&
             cl_abap_char_utilities=>newline.

    LOOP AT mt_deps INTO DATA(ls_dep).
      DATA(lv_risk)   = risk_of_task(         ls_dep-source_task ).
      DATA(lv_step)   = pull_step_of_task(    ls_dep-source_task ).
      DATA(lv_action) = pull_action_of_task(  ls_dep-source_task ).

      rv_csv = rv_csv &&
               csv_esc( mv_label )                  && ',' &&
               csv_esc( lv_ts )                     && ',' &&
               csv_esc( ls_dep-source_task )        && ',' &&
               csv_esc( ls_dep-source_object )      && ',' &&
               csv_esc( ls_dep-target_task )        && ',' &&
               csv_esc( ls_dep-target_object )      && ',' &&
               csv_esc( ls_dep-kind )               && ',' &&
               csv_esc( lv_risk )                   && ',' &&
               csv_esc( ls_dep-detail )             && ',' &&
               |{ lv_step }|                        && ',' &&
               csv_esc( lv_action )                 &&
               cl_abap_char_utilities=>newline.
    ENDLOOP.
  ENDMETHOD.


" =============================================================================
" PERSIST_RESULT - write to ZGCTS_HIST
" =============================================================================
  METHOD persist_result.
    " For multi-input runs we persist mv_label as the "TR" value so the
    " analysis can be located later by querying ZGCTS_HIST-tr_id.
    DATA lv_ts   TYPE zgcts_hist-run_ts.
    DATA lv_date TYPE d.
    DATA lv_time TYPE t.
    lv_date = cl_abap_context_info=>get_system_date( ).
    lv_time = cl_abap_context_info=>get_system_time( ).
    lv_ts = lv_date * 1000000 + lv_time.

    DATA lt_rows TYPE STANDARD TABLE OF zgcts_hist WITH EMPTY KEY.

    LOOP AT mt_deps INTO DATA(ls_dep).
      DATA(lv_risk)   = risk_of_task(        ls_dep-source_task ).
      DATA(lv_step)   = pull_step_of_task(   ls_dep-source_task ).
      DATA(lv_action) = pull_action_of_task( ls_dep-source_task ).

      APPEND VALUE #(
        tr_id       = mv_label
        run_ts      = lv_ts
        src_task    = ls_dep-source_task
        src_obj     = ls_dep-source_object(60)
        tgt_task    = ls_dep-target_task
        tgt_obj     = ls_dep-target_object(60)
        kind        = ls_dep-kind(20)
        risk        = lv_risk(10)
        detail      = ls_dep-detail(200)
        pull_step   = lv_step
        pull_action = lv_action(30) ) TO lt_rows.
    ENDLOOP.

    IF lt_rows IS NOT INITIAL.
      INSERT zgcts_hist FROM TABLE lt_rows.
      IF sy-subrc <> 0.
        out( |WARN: persist_result - INSERT returned sy-subrc { sy-subrc }| ).
      ELSE.
        out( |INFO: { lines( lt_rows ) } rows saved to ZGCTS_HIST (run { lv_ts })| ).
      ENDIF.
    ENDIF.
  ENDMETHOD.

ENDCLASS.