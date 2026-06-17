"! gCTS Task Dependency Analyzer — 4-stage XCO analysis pipeline.
"!
"! Usage (F9 / ICF):
"!   ZCL_GCTS_TR_ANALYZER=>GV_TR_ID = 'GMWK900691'.
"!   DATA(lo) = NEW zcl_gcts_tr_analyzer( ).
"!   DATA(lv_json) = lo->to_json( ).      " for ICF handler
"!   DATA(lv_csv)  = lo->to_csv( ).       " for CSV export
"!   lo->persist_result( ).               " write to ZGCTS_DEP_HISTORY
CLASS zcl_gcts_tr_analyzer DEFINITION
  PUBLIC FINAL CREATE PUBLIC.

  PUBLIC SECTION.
    "! TR number — set before instantiation (static input)
    CLASS-DATA gv_tr_id TYPE string.

    "! When TRUE, also report objects that depend on items outside the TR
    CLASS-DATA gv_include_external TYPE abap_bool VALUE abap_false.

    METHODS constructor.

    "! Serialise analysis result as JSON for ICF response
    METHODS to_json
      RETURNING VALUE(rv_json) TYPE string.

    "! Serialise analysis result as CSV for download / Excel
    METHODS to_csv
      RETURNING VALUE(rv_csv) TYPE string.

    "! Persist result rows to ZGCTS_DEP_HISTORY
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

    DATA mv_tr       TYPE string.
    DATA mt_objects  TYPE tt_objects.
    DATA mt_deps     TYPE tt_deps.
    DATA mt_clusters TYPE tt_clusters.

    METHODS stage1_inventory      IMPORTING iv_tr TYPE string.
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

" ═════════════════════════════════════════════════════════════════════════════
" CONSTRUCTOR
" ═════════════════════════════════════════════════════════════════════════════
  METHOD constructor.
    mv_tr = gv_tr_id.
    IF mv_tr IS INITIAL.
      out( '*** ZCL_GCTS_TR_ANALYZER: set GV_TR_ID before instantiating ***' ).
      RETURN.
    ENDIF.
    stage1_inventory( mv_tr ).
    stage2_dependencies( ).
    stage2b_conflicts( ).
    stage3_clusters( ).
    stage4_output( ).
  ENDMETHOD.


" ═════════════════════════════════════════════════════════════════════════════
" STAGE 1 — Task Inventory
" Uses CTS tables E070/E071 directly — reliable, no XCO API uncertainty.
"   E070: Transport/Task header — STRKORR = parent TR identifies tasks
"   E071: Object entries per transport/task
" ═════════════════════════════════════════════════════════════════════════════
  METHOD stage1_inventory.

    " Read all objects from tasks of this TR in one join
    SELECT e071~trkorr AS task_id,
           e071~pgmid  AS pgmid,
           e071~object AS object,
           e071~obj_name AS obj_name
      FROM e071
      INNER JOIN e070 ON e070~trkorr = e071~trkorr
      WHERE e070~strkorr = @iv_tr
      INTO TABLE @DATA(lt_raw).

    IF sy-subrc <> 0 OR lt_raw IS INITIAL.
      out( |TR { iv_tr }: no objects found in tasks (verify TR exists in SE09)| ).
      RETURN.
    ENDIF.

    LOOP AT lt_raw INTO DATA(ls).
      APPEND VALUE #(
        task_id  = CONV string( ls-task_id )
        obj_type = CONV string( ls-pgmid ) && '/' && CONV string( ls-object )
        obj_name = CONV string( ls-obj_name ) ) TO mt_objects.
    ENDLOOP.

    out( |Stage 1: { lines( mt_objects ) } objects collected from TR { iv_tr }| ).

  ENDMETHOD.


" ═════════════════════════════════════════════════════════════════════════════
" STAGE 2 — Dependency Extraction
" ═════════════════════════════════════════════════════════════════════════════
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
    TRY.
        DATA(lo_content) = xco_cp_oo=>class( iv_name )->content( xco_cp_language=>abap ).
        TRY.
            DATA(lv_super) = lo_content->get_super_class( )->name.
            add_dep( iv_src_task = iv_task  iv_src_obj = |CLAS/{ iv_name }|
                     iv_tgt_task = task_of_object( lv_super )
                     iv_tgt_obj  = |CLAS/{ lv_super }|
                     iv_kind     = 'INHERITS'
                     iv_detail   = |{ iv_name } extends { lv_super }| ).
        CATCH cx_root.
        ENDTRY.
        TRY.
            LOOP AT lo_content->get_implemented_interfaces( ) INTO DATA(lo_ir).
              DATA(lv_intf) = lo_ir->interface->name.
              add_dep( iv_src_task = iv_task  iv_src_obj = |CLAS/{ iv_name }|
                       iv_tgt_task = task_of_object( lv_intf )
                       iv_tgt_obj  = |INTF/{ lv_intf }|
                       iv_kind     = 'IMPLEMENTS'
                       iv_detail   = |{ iv_name } implements { lv_intf }| ).
            ENDLOOP.
        CATCH cx_root.
        ENDTRY.
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


  METHOD deps_for_intf.
    TRY.
        LOOP AT xco_cp_oo=>interface( iv_name
              )->content( xco_cp_language=>abap
              )->get_implemented_interfaces( ) INTO DATA(lo_par).
          DATA(lv_par) = lo_par->interface->name.
          add_dep( iv_src_task = iv_task  iv_src_obj = |INTF/{ iv_name }|
                   iv_tgt_task = task_of_object( lv_par )
                   iv_tgt_obj  = |INTF/{ lv_par }|
                   iv_kind     = 'IMPLEMENTS'
                   iv_detail   = |{ iv_name } extends { lv_par }| ).
        ENDLOOP.
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


  METHOD deps_for_tabl.
    TRY.
        LOOP AT xco_cp_abap_dictionary=>database_table( iv_name
              )->fields->all( ) INTO DATA(lo_f).
          TRY.
              DATA(lv_de) = lo_f->content( )->get_data_element( )->name.
              add_dep( iv_src_task = iv_task  iv_src_obj = |TABL/{ iv_name }|
                       iv_tgt_task = task_of_object( lv_de )
                       iv_tgt_obj  = |DTEL/{ lv_de }|
                       iv_kind     = 'TYPE_REF'
                       iv_detail   = |{ iv_name } column -> { lv_de }| ).
          CATCH cx_root.
          ENDTRY.
        ENDLOOP.
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


  METHOD deps_for_dtel.
    TRY.
        DATA(lv_dom) = xco_cp_abap_dictionary=>data_element( iv_name
                         )->content( )->get_domain( )->name.
        add_dep( iv_src_task = iv_task  iv_src_obj = |DTEL/{ iv_name }|
                 iv_tgt_task = task_of_object( lv_dom )
                 iv_tgt_obj  = |DOMA/{ lv_dom }|
                 iv_kind     = 'TYPE_REF'
                 iv_detail   = |{ iv_name } domain -> { lv_dom }| ).
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


  METHOD deps_for_ddls.
    TRY.
        LOOP AT xco_cp_cds=>view_entity( iv_name
              )->content( )->get_data_sources( ) INTO DATA(lo_src).
          DATA(lv_src) = lo_src->name.
          add_dep( iv_src_task = iv_task  iv_src_obj = |DDLS/{ iv_name }|
                   iv_tgt_task = task_of_object( lv_src )
                   iv_tgt_obj  = lv_src
                   iv_kind     = 'USES'
                   iv_detail   = |{ iv_name } FROM { lv_src }| ).
        ENDLOOP.
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


  METHOD deps_for_ddlx.
    TRY.
        DATA(lo_ddlx) = xco_cp_cds=>metadata_extension( iv_name ).
        DATA(lv_base) = lo_ddlx->content( )->get_cds_view( )->name.
        add_dep( iv_src_task = iv_task  iv_src_obj = |DDLX/{ iv_name }|
                 iv_tgt_task = task_of_object( lv_base )
                 iv_tgt_obj  = |DDLS/{ lv_base }|
                 iv_kind     = 'EXTENDS'
                 iv_detail   = |{ iv_name } annotates { lv_base }| ).
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


  METHOD deps_for_bdef.
    TRY.
        DATA(lo_bdef) = xco_cp_rap=>behavior_definition( iv_name ).
        DATA(lv_view) = lo_bdef->content( )->get_root_entity( )->name.
        add_dep( iv_src_task = iv_task  iv_src_obj = |BDEF/{ iv_name }|
                 iv_tgt_task = task_of_object( lv_view )
                 iv_tgt_obj  = |DDLS/{ lv_view }|
                 iv_kind     = 'IMPLEMENTS'
                 iv_detail   = |{ iv_name } behavior for { lv_view }| ).
    CATCH cx_root.
    ENDTRY.
  ENDMETHOD.


  METHOD deps_for_fugr.
    " Use TFDIR (function module directory) to find FMs in this function group
    " PNAME in TFDIR = function group name
    TRY.
        SELECT funcname FROM tfdir
          WHERE pname = @iv_name
          INTO TABLE @DATA(lt_fms).

        LOOP AT lt_fms INTO DATA(ls_fm).
          DATA(lv_fm_name) = CONV string( ls_fm-funcname ).
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


" ═════════════════════════════════════════════════════════════════════════════
" STAGE 2b — Same-Object Conflict Detection
" ═════════════════════════════════════════════════════════════════════════════
  METHOD stage2b_conflicts.
    " Build a map: object name -> comma-separated list of owning tasks
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

    " Any object owned by more than one task = CONFLICT
    LOOP AT lt_map INTO DATA(ls_map).
      IF NOT ls_map-tasks CA ','. CONTINUE. ENDIF.

      " Split comma-separated tasks manually (no local class dependency)
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


" ═════════════════════════════════════════════════════════════════════════════
" STAGE 3 — Cluster Detection (Union-Find)
" ═════════════════════════════════════════════════════════════════════════════
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


" ═════════════════════════════════════════════════════════════════════════════
" STAGE 4 — Console Output
" ═════════════════════════════════════════════════════════════════════════════
  METHOD stage4_output.
    DATA lt_unique_tasks TYPE SORTED TABLE OF string WITH UNIQUE KEY table_line.
    LOOP AT mt_objects INTO DATA(ls_o).
      INSERT ls_o-task_id INTO TABLE lt_unique_tasks.
    ENDLOOP.

    out_header( ).
    out( |  TR: { mv_tr }  Tasks: { lines( lt_unique_tasks ) }  Objects: { lines( mt_objects ) }  Edges: { lines( mt_deps ) }| ).
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


" ═════════════════════════════════════════════════════════════════════════════
" TO_JSON
" ═════════════════════════════════════════════════════════════════════════════
  METHOD to_json.
    DATA lt_unique_tasks TYPE SORTED TABLE OF string WITH UNIQUE KEY table_line.
    LOOP AT mt_objects INTO DATA(ls_o).
      INSERT ls_o-task_id INTO TABLE lt_unique_tasks.
    ENDLOOP.

    DATA(lv_summary) = |"tr":"{ json_escape( mv_tr ) }",| &&
                       |"taskCount":{ lines( lt_unique_tasks ) },| &&
                       |"objectCount":{ lines( mt_objects ) },| &&
                       |"edgeCount":{ lines( mt_deps ) }|.

    " Build clusters array
    DATA lv_clusters TYPE string.
    CLEAR lv_clusters.

    LOOP AT mt_clusters INTO DATA(ls_cl).
      " Build tasks JSON array — CLEAR before each cluster iteration
      DATA lv_tasks_arr TYPE string.
      CLEAR lv_tasks_arr.
      DATA lt_tasks TYPE string_table.
      SPLIT ls_cl-tasks AT ',' INTO TABLE lt_tasks.
      LOOP AT lt_tasks INTO DATA(lv_t).
        DATA(lv_tc) = condense( lv_t ).
        IF lv_tasks_arr IS NOT INITIAL. lv_tasks_arr = lv_tasks_arr && ','. ENDIF.
        lv_tasks_arr = lv_tasks_arr && |"{ json_escape( lv_tc ) }"|.
      ENDLOOP.

      " Build edges JSON array — CLEAR before each cluster iteration
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

    " Build pull order array
    DATA lv_pull_order TYPE string.
    CLEAR lv_pull_order.
    DATA lv_step TYPE i VALUE 1.

    LOOP AT mt_clusters INTO DATA(ls_step).
      " Build step tasks array — CLEAR before each iteration
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

    rv_json = `{` && lv_summary &&
              `,"clusters":[` && lv_clusters && `]` &&
              `,"pullOrder":[` && lv_pull_order && `]` &&
              `}`.
  ENDMETHOD.


" ═════════════════════════════════════════════════════════════════════════════
" HELPERS
" ═════════════════════════════════════════════════════════════════════════════
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
    IF gv_include_external = abap_false. RETURN. ENDIF.
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
    out( |  gCTS Task Dependency Analyzer  -  TR { mv_tr }| ).
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


" ═════════════════════════════════════════════════════════════════════════════
" TO_CSV
" ═════════════════════════════════════════════════════════════════════════════
  METHOD to_csv.
    DATA(lv_ts) = cl_abap_context_info=>get_system_date( ) &&
                  cl_abap_context_info=>get_system_time( ).

    rv_csv = 'TR,RUN_TS,SRC_TASK,SRC_OBJ,TGT_TASK,TGT_OBJ,KIND,RISK,DETAIL,PULL_STEP,PULL_ACTION' &&
             cl_abap_char_utilities=>newline.

    LOOP AT mt_deps INTO DATA(ls_dep).
      " Look up risk via helper — avoids invalid KEY+WHERE+CS syntax
      DATA(lv_risk)   = risk_of_task(         ls_dep-source_task ).
      DATA(lv_step)   = pull_step_of_task(    ls_dep-source_task ).
      DATA(lv_action) = pull_action_of_task(  ls_dep-source_task ).

      rv_csv = rv_csv &&
               csv_esc( mv_tr )                    && ',' &&
               csv_esc( lv_ts )                    && ',' &&
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


" ═════════════════════════════════════════════════════════════════════════════
" PERSIST_RESULT — write to ZGCTS_DEP_HISTORY
" Note: create table ZGCTS_DEP_HISTORY first (see abap/zgcts_dep_history/)
" ═════════════════════════════════════════════════════════════════════════════
  METHOD persist_result.
    DATA(lv_ts) = CONV dec14(
        cl_abap_context_info=>get_system_date( ) &&
        cl_abap_context_info=>get_system_time( ) ).

    DATA lt_rows TYPE STANDARD TABLE OF zgcts_dep_history WITH EMPTY KEY.

    LOOP AT mt_deps INTO DATA(ls_dep).
      DATA(lv_risk)   = risk_of_task(        ls_dep-source_task ).
      DATA(lv_step)   = pull_step_of_task(   ls_dep-source_task ).
      DATA(lv_action) = pull_action_of_task( ls_dep-source_task ).

      APPEND VALUE #(
        tr_id       = mv_tr
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
      INSERT zgcts_dep_history FROM TABLE lt_rows.
      IF sy-subrc <> 0.
        out( |WARN: persist_result - INSERT returned sy-subrc { sy-subrc }| ).
      ELSE.
        out( |INFO: { lines( lt_rows ) } rows saved to ZGCTS_DEP_HISTORY (run { lv_ts })| ).
      ENDIF.
    ENDIF.
  ENDMETHOD.

ENDCLASS.
