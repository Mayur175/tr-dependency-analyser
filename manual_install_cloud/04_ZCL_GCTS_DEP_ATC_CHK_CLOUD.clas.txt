"! <p class="shorttext synchronized">TR Analyser - Cloud ATC custom check</p>
"!
"! Cloud-released ATC custom check that surfaces dependency findings from
"! ZCL_GCTS_TR_ANALYZER_CLOUD inside the standard ATC result list.
"!
"! Register this class as an ATC check via:
"!   ATC > Check Variant > Custom Checks > Add ZCL_GCTS_DEP_ATC_CHK_CLOUD
"!
"! Unlike the classic check (which inherits CL_CI_TEST_OBJECT), this one
"! uses the cloud-released base class CL_CI_TEST_ROOT and the released
"! result-collection API CL_CI_RESULT_ROOT. Both are part of the ATC
"! cloud SDK and pass the strict allow-list.
"!
"! Inputs: triggered automatically by ATC when the user runs a check on
"! a transport. We pull the TR id from the ATC context and feed it to
"! the analyser; one ATC finding per dependency above MEDIUM risk.
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

    " Description and category for the ATC framework
    description = `TR Analyser - cross-task dependency check (cloud)`.
    category    = `ZGCTS_CLOUD`.
    version     = '0001'.

    " Define each finding code so ATC's UI knows the severity defaults.
    DATA(ls_msg_dep) = VALUE scimessage(
      test     = me->myname
      code     = c_check_code_dep
      kind     = c_warning
      pcom     = ''
      pcom_lng = '' ).
    APPEND ls_msg_dep TO scimessages.

    DATA(ls_msg_ext) = VALUE scimessage(
      test     = me->myname
      code     = c_check_code_ext
      kind     = c_error
      pcom     = ''
      pcom_lng = '' ).
    APPEND ls_msg_ext TO scimessages.
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

        " The analyzer's deps are exposed via to_json; for ATC we want
        " typed access. Re-use the public CSV API and parse, OR (preferred)
        " add a public getter for mt_deps in a future revision.
        DATA(lv_csv) = lo_an->to_csv( ).

        " Emit a finding per CSV row that has a HIGH/CRITICAL risk.
        SPLIT lv_csv AT cl_abap_char_utilities=>newline INTO TABLE DATA(lt_lines).
        LOOP AT lt_lines INTO DATA(lv_line) FROM 2.   " skip header
          IF lv_line IS INITIAL.
            CONTINUE.
          ENDIF.

          SPLIT lv_line AT ',' INTO TABLE DATA(lt_cols).
          IF lines( lt_cols ) < 7.
            CONTINUE.
          ENDIF.

          DATA(lv_kind) = lt_cols[ 5 ].
          DATA(lv_risk) = lt_cols[ 7 ].
          DATA(lv_src)  = lt_cols[ 2 ].
          DATA(lv_tgt)  = lt_cols[ 4 ].

          CASE lv_risk.
            WHEN 'CRITICAL' OR 'HIGH'.
              raise_finding(
                iv_code     = c_check_code_ext
                iv_severity = c_error
                iv_text     = |{ lv_src } { lv_kind } { lv_tgt } (risk: { lv_risk })| ).
            WHEN 'MEDIUM'.
              raise_finding(
                iv_code     = c_check_code_dep
                iv_severity = c_warning
                iv_text     = |{ lv_src } { lv_kind } { lv_tgt } (risk: { lv_risk })| ).
            WHEN OTHERS.
              " no finding for NONE
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