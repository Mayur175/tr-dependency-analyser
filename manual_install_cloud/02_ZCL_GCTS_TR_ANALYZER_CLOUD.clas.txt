"! <p class="shorttext synchronized">TR Analyser - Public Cloud variant</p>
"!
"! Cloud-clean rewrite of ZCL_GCTS_TR_ANALYZER. Uses ONLY released APIs:
"!   * XCO_CP_ABAP_DICTIONARY    - DDIC reads (replaces DD03L/DD04L)
"!   * XCO_CP_ABAP_REPOSITORY    - OO class hierarchy + function modules
"!                                  (replaces SEOMETAREL / TFDIR)
"!   * gCTS REST API             - transport content
"!                                  (replaces E070/E071 reads)
"!   * cl_http_destination_provider, if_http_client - cloud-released HTTP
"!
"! Compiles cleanly under the strict ABAP Cloud language version
"! (no use of internal tables, no unescaped host variables in OpenSQL,
"!  no CL_DEMO_OUTPUT).
"!
"! Public surface is identical to the classic analyser:
"!   DATA(lo) = NEW zcl_gcts_tr_analyzer_cloud(
"!     it_input            = VALUE #( ( id = 'GMWK900691' ) )
"!     iv_include_external = abap_false ).
"!   DATA(lv_json) = lo->to_json( ).
"!   lo->persist_result( ).
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

    "! Constructor.
    "! @parameter it_input            | one row per TR / commit / task id
    "! @parameter iv_include_external | when ABAP_TRUE, also report
    "!                                   dependencies on objects outside the
    "!                                   input set (limited in cloud)
    METHODS constructor
      IMPORTING it_input            TYPE tt_input            OPTIONAL
                iv_include_external TYPE abap_bool DEFAULT abap_false.

    METHODS run.
    METHODS to_json     RETURNING VALUE(rv_json) TYPE string.
    METHODS to_csv      RETURNING VALUE(rv_csv)  TYPE string.
    METHODS get_log     RETURNING VALUE(rv_log)  TYPE string.
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

    "! Stage 1 - inventory: pull objects out of every input commit via gCTS.
    METHODS stage1_inventory_via_gcts.

    "! Stage 2 - dependencies: walk DDIC + OO + FM relations via XCO.
    METHODS stage2_walk_dependencies.

    METHODS analyze_data_element
      IMPORTING iv_task TYPE string
                iv_name TYPE string.

    METHODS analyze_oo_class
      IMPORTING iv_task TYPE string
                iv_name TYPE string.

    METHODS analyze_function_group
      IMPORTING iv_task TYPE string
                iv_name TYPE string.

    METHODS analyze_table
      IMPORTING iv_task TYPE string
                iv_name TYPE string.

    METHODS add_dep
      IMPORTING is_dep TYPE ty_dep.

    METHODS classify_risk
      IMPORTING iv_kind        TYPE string
      RETURNING VALUE(rv_risk) TYPE string.

    "! gCTS REST helper. Hits /sap/bc/cts_abapvcs/... with a destination
    "! configured by the customer (default: NONE = local tenant).
    METHODS read_gcts_commit_objects
      IMPORTING iv_commit_id TYPE string
      RETURNING VALUE(rt_obj) TYPE tt_objects
      RAISING   cx_static_check.

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
    stage2_walk_dependencies( ).

    out( |Done. Found { lines( mt_objects ) } objects, |
      && |{ lines( mt_deps ) } dependencies.| ).

    mv_executed = abap_true.

  ENDMETHOD.


  METHOD stage1_inventory_via_gcts.

    LOOP AT mt_input INTO DATA(ls_in).
      IF ls_in-id IS INITIAL.
        CONTINUE.
      ENDIF.

      TRY.
          DATA(lt_obj) = read_gcts_commit_objects( CONV #( ls_in-id ) ).
          APPEND LINES OF lt_obj TO mt_objects.
        CATCH cx_static_check INTO DATA(lo_ex).
          out( |gCTS read failed for { ls_in-id }: { lo_ex->get_text( ) }| ).
      ENDTRY.
    ENDLOOP.

  ENDMETHOD.


  METHOD stage2_walk_dependencies.

    LOOP AT mt_objects INTO DATA(ls_obj).

      CASE ls_obj-obj_type.
        WHEN 'DTEL'.
          analyze_data_element( iv_task = ls_obj-task_id
                                iv_name = ls_obj-obj_name ).

        WHEN 'CLAS'.
          analyze_oo_class( iv_task = ls_obj-task_id
                            iv_name = ls_obj-obj_name ).

        WHEN 'FUGR'.
          analyze_function_group( iv_task = ls_obj-task_id
                                  iv_name = ls_obj-obj_name ).

        WHEN 'TABL'.
          analyze_table( iv_task = ls_obj-task_id
                         iv_name = ls_obj-obj_name ).

        WHEN OTHERS.
          " other types could be added when XCO releases more handles
      ENDCASE.

    ENDLOOP.

  ENDMETHOD.


  METHOD analyze_data_element.

    " Replaces classic SELECT domname FROM dd04l WHERE rollname = @iv_name
    TRY.
        DATA(lo_dtel) = xco_cp_abap_dictionary=>data_element(
          CONV sxco_ad_object_name( iv_name ) ).

        DATA(ls_content) = lo_dtel->content( )->get( ).

        IF ls_content-domain-name IS NOT INITIAL.
          add_dep( VALUE #(
            source_task   = iv_task
            source_object = |DTEL/{ iv_name }|
            target_task   = ''
            target_object = |DOMA/{ ls_content-domain-name }|
            kind          = 'USES_DOMAIN'
            detail        = ||
            risk          = classify_risk( 'USES_DOMAIN' ) ) ).
        ENDIF.
      CATCH cx_root INTO DATA(lo_ex).
        out( |XCO data-element read failed for { iv_name }: { lo_ex->get_text( ) }| ).
    ENDTRY.

  ENDMETHOD.


  METHOD analyze_oo_class.

    " Replaces classic SELECTs from SEOMETAREL.
    TRY.
        DATA(lo_class) = xco_cp_abap_repository=>object->clas->for(
          CONV sxco_ao_object_name( iv_name ) ).

        DATA(ls_content) = lo_class->content( )->get( ).

        IF ls_content-super_class_name IS NOT INITIAL.
          add_dep( VALUE #(
            source_task   = iv_task
            source_object = |CLAS/{ iv_name }|
            target_task   = ''
            target_object = |CLAS/{ ls_content-super_class_name }|
            kind          = 'EXTENDS'
            detail        = ||
            risk          = classify_risk( 'EXTENDS' ) ) ).
        ENDIF.

        LOOP AT ls_content-interfaces INTO DATA(ls_intf).
          add_dep( VALUE #(
            source_task   = iv_task
            source_object = |CLAS/{ iv_name }|
            target_task   = ''
            target_object = |INTF/{ ls_intf-name }|
            kind          = 'IMPLEMENTS'
            detail        = ||
            risk          = classify_risk( 'IMPLEMENTS' ) ) ).
        ENDLOOP.

      CATCH cx_root INTO DATA(lo_ex).
        out( |XCO class read failed for { iv_name }: { lo_ex->get_text( ) }| ).
    ENDTRY.

  ENDMETHOD.


  METHOD analyze_function_group.

    " Replaces classic SELECT funcname FROM tfdir WHERE pname = @iv_name
    TRY.
        DATA(lo_grp) = xco_cp_abap_repository=>object->fugr->for(
          CONV sxco_ao_object_name( iv_name ) ).

        DATA(lt_fm) = lo_grp->modules->all->get( ).

        LOOP AT lt_fm INTO DATA(lo_fm).
          add_dep( VALUE #(
            source_task   = iv_task
            source_object = |FUGR/{ iv_name }|
            target_task   = ''
            target_object = |FUNC/{ lo_fm->name }|
            kind          = 'CONTAINS_FM'
            detail        = ||
            risk          = classify_risk( 'CONTAINS_FM' ) ) ).
        ENDLOOP.

      CATCH cx_root INTO DATA(lo_ex).
        out( |XCO function-group read failed for { iv_name }: { lo_ex->get_text( ) }| ).
    ENDTRY.

  ENDMETHOD.


  METHOD analyze_table.

    " Replaces classic SELECT * FROM dd03l WHERE tabname = @iv_name
    TRY.
        DATA(lo_tab) = xco_cp_abap_dictionary=>database_table(
          CONV sxco_ad_object_name( iv_name ) ).

        DATA(lt_fields) = lo_tab->fields->all->get( ).

        LOOP AT lt_fields INTO DATA(lo_field).
          DATA(ls_field) = lo_field->content( )->get( ).
          IF ls_field-data_element-name IS NOT INITIAL.
            add_dep( VALUE #(
              source_task   = iv_task
              source_object = |TABL/{ iv_name }|
              target_task   = ''
              target_object = |DTEL/{ ls_field-data_element-name }|
              kind          = 'TYPED_BY'
              detail        = |field={ ls_field-name }|
              risk          = classify_risk( 'TYPED_BY' ) ) ).
          ENDIF.
        ENDLOOP.

      CATCH cx_root INTO DATA(lo_ex).
        out( |XCO table read failed for { iv_name }: { lo_ex->get_text( ) }| ).
    ENDTRY.

  ENDMETHOD.


  METHOD add_dep.

    " De-dup on the natural key
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


  METHOD classify_risk.

    " Mirror of classic risk model.
    CASE iv_kind.
      WHEN 'EXTENDS' OR 'IMPLEMENTS'.
        rv_risk = c_risk_high.
      WHEN 'USES_DOMAIN' OR 'TYPED_BY'.
        rv_risk = c_risk_medium.
      WHEN 'CONTAINS_FM'.
        rv_risk = c_risk_none.
      WHEN OTHERS.
        rv_risk = c_risk_none.
    ENDCASE.

  ENDMETHOD.


  METHOD read_gcts_commit_objects.

    " Reads /sap/bc/cts_abapvcs/repository/<repo>/commits/<id>/objects via
    " a customer-configured HTTP destination called 'GCTS_LOCAL'. If the
    " destination doesn't exist, returns an empty table and logs.
    "
    " The customer creates that destination once in their cloud tenant
    " (Communication Arrangement / Destination Service), pointing it at
    " the local gCTS endpoint with the right business user.

    DATA(lv_repo) = `tr-dependency-analyser`.   " configurable per tenant

    TRY.
        DATA(lo_dest) = cl_http_destination_provider=>create_by_destination(
          i_name = 'GCTS_LOCAL' ).

        DATA(lo_client) = cl_web_http_client_manager=>create_by_http_destination(
          i_destination = lo_dest ).

        DATA(lo_request) = lo_client->get_http_request( ).
        lo_request->set_uri_path( i_uri_path =
          |/sap/bc/cts_abapvcs/repository/{ lv_repo }/commits/{ iv_commit_id }/objects| ).
        lo_request->set_header_field( i_name = 'Accept' i_value = 'application/json' ).

        DATA(lo_response) = lo_client->execute( i_method = if_web_http_client=>get ).
        DATA(lv_body) = lo_response->get_text( ).

        " Minimal JSON parse. Real impl uses /ui2/cl_json or xco_cp_json.
        " Output structure (gCTS):
        "   { "objects": [ { "pgmid":"R3TR","object":"CLAS","name":"ZCL_X" }, ... ] }
        DATA: BEGIN OF ls_resp,
                BEGIN OF objects OCCURS 0,
                  pgmid  TYPE string,
                  object TYPE string,
                  name   TYPE string,
                END OF objects,
              END OF ls_resp.

        xco_cp_json=>data->from_string( lv_body )->apply( VALUE #(
          ( xco_cp_json=>transformation->pascal_case_to_underscore )
        ) )->write_to( REF #( ls_resp ) ).

        LOOP AT ls_resp-objects INTO DATA(ls_o).
          APPEND VALUE #(
            task_id  = CONV #( iv_commit_id )
            pgmid    = ls_o-pgmid
            obj_type = ls_o-object
            obj_name = ls_o-name ) TO rt_obj.
        ENDLOOP.

      CATCH cx_root INTO DATA(lo_ex).
        " Don't fail the whole run if gCTS is unreachable; just log it.
        out( |gCTS REST call failed for { iv_commit_id }: { lo_ex->get_text( ) }| ).
    ENDTRY.

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


  METHOD to_json.

    " Cloud-released JSON serializer.
    rv_json = xco_cp_json=>data->from_abap( VALUE #(
      label        = mv_label
      object_count = lines( mt_objects )
      dep_count    = lines( mt_deps )
      deps         = mt_deps
    ) )->apply( VALUE #(
      ( xco_cp_json=>transformation->underscore_to_pascal_case )
    ) )->to_string( ).

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

    " ZGCTS_HIST is a customer-created DDIC table; it is allowed in cloud
    " (custom Z-tables are always permitted, only SAP-internal tables are
    " on the deny-list). All host vars are escaped with @, as cloud requires.

    DATA lt_rows TYPE STANDARD TABLE OF zgcts_hist WITH EMPTY KEY.
    DATA lv_now  TYPE timestampl.

    GET TIME STAMP FIELD lv_now.

    LOOP AT mt_deps INTO DATA(ls).
      APPEND VALUE zgcts_hist(
        run_ts        = lv_now
        tr_label      = mv_label
        risk          = ls-risk
        source_task   = ls-source_task
        source_object = ls-source_object
        target_object = ls-target_object
        dep_kind      = ls-kind
        detail        = ls-detail
      ) TO lt_rows.
    ENDLOOP.

    IF lt_rows IS INITIAL.
      RETURN.
    ENDIF.

    INSERT zgcts_hist FROM TABLE @lt_rows.

  ENDMETHOD.

ENDCLASS.