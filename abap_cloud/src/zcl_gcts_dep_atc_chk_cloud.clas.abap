"! <p class="shorttext synchronized">TR Analyser - Cloud ATC custom check</p>
"!
"! Cloud-released ATC custom check that surfaces dependency findings from
"! ZCL_GCTS_TR_ANALYZER_CLOUD inside the standard ATC result list.
"!
"! Register this class as an ATC check via:
"!   ATC > Check Variant > Custom Checks > Add ZCL_GCTS_DEP_ATC_CHK_CLOUD
"!
"! Inherits CL_CI_TEST_ROOT (the cloud-released ATC base class) instead
"! of the classic CL_CI_TEST_OBJECT, which is not on the cloud allow-list.
CLASS zcl_gcts_dep_atc_chk_cloud DEFINITION
  PUBLIC
  INHERITING FROM cl_ci_test_root
  CREATE PUBLIC.

  PUBLIC SECTION.

    METHODS constructor.

    METHODS if_ci_atc_check~run REDEFINITION.

  PRIVATE SECTION.

    CONSTANTS:
      c_check_code_dep TYPE sci_errc VALUE 'GCTS001',
      c_check_code_ext TYPE sci_errc VALUE 'GCTS002'.

    METHODS raise_finding
      IMPORTING iv_code     TYPE sci_errc
                iv_severity TYPE sychar01
                iv_text     TYPE string.

ENDCLASS.


CLASS zcl_gcts_dep_atc_chk_cloud IMPLEMENTATION.

  METHOD constructor.
    super->constructor( ).

    description = `TR Analyser - cross-task dependency check (cloud)`.
    category    = `ZGCTS_CLOUD`.
    version     = '0001'.

    APPEND VALUE scimessage(
      test     = me->myname
      code     = c_check_code_dep
      kind     = c_warning
      pcom     = ''
      pcom_lng = '' ) TO scimessages.

    APPEND VALUE scimessage(
      test     = me->myname
      code     = c_check_code_ext
      kind     = c_error
      pcom     = ''
      pcom_lng = '' ) TO scimessages.
  ENDMETHOD.


  METHOD if_ci_atc_check~run.

    " The ATC cloud framework passes the object-under-check via attributes.
    " For a TR-level check the relevant id is in object_name.
    DATA(lv_tr_id) = CONV string( object_name ).

    IF lv_tr_id IS INITIAL.
      RETURN.
    ENDIF.

    TRY.
        DATA(lo_an) = NEW zcl_gcts_tr_analyzer_cloud(
                       it_input            = VALUE #( ( id = lv_tr_id ) )
                       iv_include_external = abap_true ).
        lo_an->run( ).

        " Typed access to the dep table - no fragile CSV parsing.
        DATA(lt_deps) = lo_an->get_deps( ).

        LOOP AT lt_deps INTO DATA(ls_dep).
          CASE ls_dep-risk.
            WHEN 'CRITICAL' OR 'HIGH'.
              raise_finding(
                iv_code     = c_check_code_ext
                iv_severity = c_error
                iv_text     = |{ ls_dep-source_object } { ls_dep-kind } |
                           && |{ ls_dep-target_object } (risk: { ls_dep-risk })| ).
            WHEN 'MEDIUM'.
              raise_finding(
                iv_code     = c_check_code_dep
                iv_severity = c_warning
                iv_text     = |{ ls_dep-source_object } { ls_dep-kind } |
                           && |{ ls_dep-target_object } (risk: { ls_dep-risk })| ).
            WHEN OTHERS.
              " no finding for NONE / INVENTORIED rows
          ENDCASE.
        ENDLOOP.

      CATCH cx_root INTO DATA(lo_ex).
        raise_finding(
          iv_code     = c_check_code_dep
          iv_severity = c_warning
          iv_text     = |TR Analyser cloud check failed: { lo_ex->get_text( ) }| ).
    ENDTRY.

  ENDMETHOD.


  METHOD raise_finding.

    inform( p_sub_obj_type = ''
            p_sub_obj_name = object_name
            p_kind         = iv_severity
            p_test         = me->myname
            p_code         = iv_code
            p_param_1      = iv_text ).

  ENDMETHOD.

ENDCLASS.