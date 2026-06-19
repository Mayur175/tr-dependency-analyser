"! ICF HTTP handler for the TR Analyser.
"!
"! Registration:
"!   Transaction SICF -> /sap/bc/zgcts/analyze
"!   Handler class:    ZGCTS_ANALYZE_HANDLER
"!
"! Request parameters:
"!   tr        (required) - one or more TR / task ids, comma-separated
"!                          e.g. tr=GMWK900691         (single TR)
"!                               tr=GMWK900691,DEVK900042  (cross-TR)
"!                               tr=GMWK900692         (single task)
"!   format    (optional) - 'json' (default) | 'csv'
"!   persist   (optional) - '1' / 'true' -> save result to ZGCTS_DEP_HISTORY
"!   external  (optional) - '1' / 'true' -> include external INFO dependencies
"!
"! Authorisation:
"!   Caller must have S_TRANSPRT (TTYPE=CUST, ACTVT=03 Display) to read TR
"!   contents. Returns HTTP 403 otherwise.
"!
"! Examples:
"!   GET /sap/bc/zgcts/analyze?tr=GMWK900691
"!   GET /sap/bc/zgcts/analyze?tr=DEVK900042,DEVK900043
"!   GET /sap/bc/zgcts/analyze?tr=GMWK900691&format=csv
"!   GET /sap/bc/zgcts/analyze?tr=GMWK900691&persist=true&external=true
CLASS zgcts_analyze_handler DEFINITION
  PUBLIC FINAL CREATE PUBLIC.

  PUBLIC SECTION.
    INTERFACES if_http_extension.

  PRIVATE SECTION.
    CONSTANTS:
      c_param_tr       TYPE string VALUE 'tr',
      c_param_format   TYPE string VALUE 'format',
      c_param_persist  TYPE string VALUE 'persist',
      c_param_external TYPE string VALUE 'external'.

    " TR / task id pattern - identical shape: SYSID + 'K' + 6 digits
    CONSTANTS c_id_regex TYPE string VALUE '[A-Z0-9]{3,4}K[0-9]{6}'.

    " ----------------------------------------------------------------------
    " AUTHORITY-CHECK control flag.
    "
    " >>>>>  WARNING - SECURITY CRITICAL  <<<<<
    "
    " The DEFAULT in this open-source repo is ABAP_FALSE so that pilot
    " users on personal / dev tenants can install via abapGit and use
    " the tool immediately without waiting for Basis to grant the
    " S_TRANSPRT (TTYPE=CUST, ACTVT=03) authorisation.
    "
    " WHEN ABAP_FALSE (current default - sandbox / pilot only):
    "   - AUTHORITY-CHECK is skipped. Every authenticated SAP user with
    "     HTTP access can read ANY TR's contents via this endpoint.
    "   - The handler emits 'X-Auth-Bypass: yes' on every response so
    "     downstream monitoring can detect the open setting.
    "   - DO NOT use on shared development, QA, or PRODUCTION systems.
    "
    " WHEN ABAP_TRUE (production-safe - flip this for any non-sandbox):
    "   - Caller must hold S_TRANSPRT (TTYPE=CUST, ACTVT=03 Display).
    "   - Unauthorised callers receive HTTP 403.
    "   - Same authorisation pattern SE10 / SE09 already enforce.
    "
    " To enable the production-safe behaviour, change ABAP_FALSE below
    " to ABAP_TRUE, activate, and ship in your customer-namespace TR.
    " ----------------------------------------------------------------------
    CONSTANTS c_enforce_auth TYPE abap_bool VALUE abap_false.

    METHODS authorised
      RETURNING VALUE(rv_ok) TYPE abap_bool.

    METHODS parse_input
      IMPORTING iv_raw          TYPE string
      EXPORTING et_input        TYPE zcl_gcts_tr_analyzer=>tt_input
                ev_invalid_id   TYPE string.

    METHODS respond
      IMPORTING io_server TYPE REF TO if_http_server
                iv_code   TYPE i
                iv_body   TYPE string
                iv_ct     TYPE string DEFAULT 'application/json; charset=utf-8'.

    METHODS respond_csv
      IMPORTING io_server   TYPE REF TO if_http_server
                iv_body     TYPE string
                iv_filename TYPE string.

    METHODS is_truthy
      IMPORTING iv_val        TYPE string
      RETURNING VALUE(rv_yes) TYPE abap_bool.

    METHODS error_json
      IMPORTING iv_msg        TYPE string
      RETURNING VALUE(rv_out) TYPE string.

    METHODS escape_json_str
      IMPORTING iv_val        TYPE string
      RETURNING VALUE(rv_out) TYPE string.

ENDCLASS.


CLASS zgcts_analyze_handler IMPLEMENTATION.

  METHOD if_http_extension~handle_request.

    " 1. Authorisation gate
    IF authorised( ) = abap_false.
      respond( io_server = server
               iv_code   = 403
               iv_body   = error_json(
                 'Forbidden: S_TRANSPRT (TTYPE=CUST, ACTVT=03) is required.' ) ).
      RETURN.
    ENDIF.

    " 2. Read + validate the tr parameter (one or more comma-separated ids)
    DATA(lv_raw) = condense( server->request->get_form_field( c_param_tr ) ).

    IF lv_raw IS INITIAL.
      respond( io_server = server
               iv_code   = 400
               iv_body   = error_json( 'Missing query parameter: tr' ) ).
      RETURN.
    ENDIF.

    DATA lt_input      TYPE zcl_gcts_tr_analyzer=>tt_input.
    DATA lv_invalid_id TYPE string.

    parse_input( EXPORTING iv_raw = lv_raw
                 IMPORTING et_input      = lt_input
                           ev_invalid_id = lv_invalid_id ).

    IF lv_invalid_id IS NOT INITIAL.
      respond( io_server = server
               iv_code   = 400
               iv_body   = error_json(
                 |Invalid id '{ lv_invalid_id }'. | &&
                 |Expected pattern { c_id_regex }, e.g. GMWK900691.| ) ).
      RETURN.
    ENDIF.

    IF lt_input IS INITIAL.
      respond( io_server = server
               iv_code   = 400
               iv_body   = error_json( 'No valid TR / task id supplied.' ) ).
      RETURN.
    ENDIF.

    " 3. Read optional parameters
    DATA(lv_format)   = to_lower( server->request->get_form_field( c_param_format ) ).
    DATA(lv_persist)  = is_truthy( server->request->get_form_field( c_param_persist ) ).
    DATA(lv_external) = is_truthy( server->request->get_form_field( c_param_external ) ).

    IF lv_format IS INITIAL. lv_format = 'json'. ENDIF.

    " 4. Run the analyser (instance-safe, no static state)
    TRY.
        DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer(
                              it_input            = lt_input
                              iv_include_external = lv_external ).

        IF lv_persist = abap_true.
          lo_analyzer->persist_result( ).
        ENDIF.

        IF lv_format = 'csv'.
          DATA(lv_csv)      = lo_analyzer->to_csv( ).
          DATA lt_label_ids TYPE string_table.
          LOOP AT lt_input INTO DATA(ls_in).
            APPEND CONV string( ls_in-id ) TO lt_label_ids.
          ENDLOOP.
          DATA(lv_filename) = concat_lines_of( table = lt_label_ids sep = `_` ) && '_analysis.csv'.
          respond_csv( io_server   = server
                       iv_body     = lv_csv
                       iv_filename = lv_filename ).
        ELSE.
          respond( io_server = server
                   iv_code   = 200
                   iv_body   = lo_analyzer->to_json( ) ).
        ENDIF.

    CATCH cx_root INTO DATA(lx).
      respond( io_server = server
               iv_code   = 500
               iv_body   = error_json( lx->get_text( ) ) ).
    ENDTRY.

  ENDMETHOD.


" =============================================================================
" Authorisation
"
" Default behaviour:
"   Calls AUTHORITY-CHECK OBJECT 'S_TRANSPRT' (TTYPE=CUST, ACTVT=03).
"   Returns ABAP_TRUE only when SY-SUBRC = 0.
"
" Optional bypass:
"   When c_enforce_auth = ABAP_FALSE (set above), this method short-circuits
"   to ABAP_TRUE for every caller. This is intended for sandbox / pilot
"   tenants where every authenticated SAP user is expected to be allowed to
"   analyse any TR. The class constant must be flipped back to ABAP_TRUE
"   before promoting the code to QA / PROD.
" =============================================================================
  METHOD authorised.
    IF c_enforce_auth = abap_false.
      " Sandbox / pilot bypass - documented in c_enforce_auth comment.
      rv_ok = abap_true.
      RETURN.
    ENDIF.

    rv_ok = abap_false.
    AUTHORITY-CHECK OBJECT 'S_TRANSPRT'
                    ID 'TTYPE' FIELD 'CUST'
                    ID 'ACTVT' FIELD '03'.
    IF sy-subrc = 0.
      rv_ok = abap_true.
    ENDIF.
  ENDMETHOD.


" =============================================================================
" PARSE_INPUT
" Splits the comma-separated list, trims, validates each id against c_id_regex,
" returns the list of ty_input rows. First invalid id stops parsing and is
" reported back via ev_invalid_id (caller turns it into HTTP 400).
" =============================================================================
  METHOD parse_input.

    CLEAR et_input.
    CLEAR ev_invalid_id.

    DATA lt_parts TYPE string_table.
    SPLIT iv_raw AT ',' INTO TABLE lt_parts.

    LOOP AT lt_parts INTO DATA(lv_part).
      DATA(lv_id) = to_upper( condense( lv_part ) ).
      IF lv_id IS INITIAL. CONTINUE. ENDIF.

      IF NOT matches( val = lv_id regex = c_id_regex ).
        ev_invalid_id = lv_id.
        CLEAR et_input.
        RETURN.
      ENDIF.

      APPEND VALUE #( id = CONV trkorr( lv_id ) ) TO et_input.
    ENDLOOP.

  ENDMETHOD.


  METHOD respond.
    io_server->response->set_status( code = iv_code reason = '' ).
    io_server->response->set_header_field(
      name = 'Content-Type' value = iv_ct ).
    io_server->response->set_header_field(
      name = 'Cache-Control' value = 'no-store' ).
    io_server->response->set_header_field(
      name = 'X-Content-Type-Options' value = 'nosniff' ).
    " Surface the auth-bypass state so downstream monitoring / Basis tooling
    " can detect a sandbox build accidentally promoted to a higher landscape.
    IF c_enforce_auth = abap_false.
      io_server->response->set_header_field(
        name = 'X-Auth-Bypass' value = 'yes' ).
    ENDIF.
    io_server->response->set_cdata( iv_body ).
  ENDMETHOD.


  METHOD respond_csv.
    io_server->response->set_status( code = 200 reason = '' ).
    io_server->response->set_header_field(
      name = 'Content-Type' value = 'text/csv; charset=utf-8' ).
    io_server->response->set_header_field(
      name  = 'Content-Disposition'
      value = |attachment; filename="{ iv_filename }"| ).
    io_server->response->set_header_field(
      name = 'Cache-Control' value = 'no-store' ).
    io_server->response->set_cdata( iv_body ).
  ENDMETHOD.


  METHOD is_truthy.
    DATA(lv) = to_lower( condense( iv_val ) ).
    rv_yes = xsdbool( lv = '1' OR lv = 'true' OR lv = 'yes' ).
  ENDMETHOD.


  METHOD error_json.
    rv_out = |{"error":"{ escape_json_str( iv_msg ) }"}|.
  ENDMETHOD.


  METHOD escape_json_str.
    rv_out = iv_val.
    REPLACE ALL OCCURRENCES OF '\' IN rv_out WITH '\\'.
    REPLACE ALL OCCURRENCES OF '"' IN rv_out WITH '\"'.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>newline IN rv_out WITH '\n'.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>cr_lf   IN rv_out WITH '\n'.
  ENDMETHOD.

ENDCLASS.