"! ICF HTTP handler for the gCTS Dependency Analyzer.
"!
"! Registration:
"!   Transaction SICF -> /sap/bc/zgcts/analyze
"!   Handler class: ZGCTS_ANALYZE_HANDLER
"!
"! Request parameters:
"!   tr        (required) - Transport Request number e.g. GMWK900691
"!   format    (optional) - 'json' (default) | 'csv'
"!   persist   (optional) - '1' or 'true' -> save result to ZGCTS_DEP_HISTORY
"!   external  (optional) - '1' or 'true' -> include external INFO dependencies
"!
"! Examples:
"!   GET /sap/bc/zgcts/analyze?tr=GMWK900691
"!   GET /sap/bc/zgcts/analyze?tr=GMWK900691&format=csv
"!   GET /sap/bc/zgcts/analyze?tr=GMWK900691&persist=true&external=true
"!
"! Response:
"!   JSON: Content-Type application/json
"!   CSV:  Content-Type text/csv with Content-Disposition attachment
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

    " TR regex as a constant avoids string template brace issues
    CONSTANTS c_tr_regex TYPE string VALUE '[A-Z0-9]{3,4}K[0-9]{6}'.

    METHODS respond
      IMPORTING io_server TYPE REF TO if_http_server
                iv_code   TYPE i
                iv_body   TYPE string
                iv_ct     TYPE string DEFAULT 'application/json; charset=utf-8'.

    METHODS respond_csv
      IMPORTING io_server  TYPE REF TO if_http_server
                iv_body    TYPE string
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

    " Read + validate TR parameter
    DATA(lv_tr) = to_upper( condense( server->request->get_form_field( c_param_tr ) ) ).

    IF lv_tr IS INITIAL.
      respond( io_server = server
               iv_code   = 400
               iv_body   = error_json( 'Missing query parameter: tr' ) ).
      RETURN.
    ENDIF.

    IF NOT matches( val = lv_tr regex = c_tr_regex ).
      respond( io_server = server
               iv_code   = 400
               iv_body   = error_json( |Invalid TR format. Expected pattern: [A-Z0-9]{{3,4}}K[0-9]{{6}}| ) ).
      RETURN.
    ENDIF.

    " Read optional parameters
    DATA(lv_format)   = to_lower( server->request->get_form_field( c_param_format ) ).
    DATA(lv_persist)  = is_truthy( server->request->get_form_field( c_param_persist ) ).
    DATA(lv_external) = is_truthy( server->request->get_form_field( c_param_external ) ).

    IF lv_format IS INITIAL. lv_format = 'json'. ENDIF.

    " Run the 4-stage analysis pipeline
    TRY.
        zcl_gcts_tr_analyzer=>gv_tr_id            = lv_tr.
        zcl_gcts_tr_analyzer=>gv_include_external = lv_external.

        DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer( ).

        IF lv_persist = abap_true.
          lo_analyzer->persist_result( ).
        ENDIF.

        IF lv_format = 'csv'.
          DATA(lv_csv)      = lo_analyzer->to_csv( ).
          DATA(lv_filename) = lv_tr && '_analysis.csv'.
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


  METHOD respond.
    io_server->response->set_status( code = iv_code reason = '' ).
    io_server->response->set_header_field(
      name = 'Content-Type' value = iv_ct ).
    io_server->response->set_header_field(
      name = 'Access-Control-Allow-Origin' value = '*' ).
    io_server->response->set_header_field(
      name = 'X-Content-Type-Options' value = 'nosniff' ).
    io_server->response->set_cdata( iv_body ).
  ENDMETHOD.


  METHOD respond_csv.
    " Set status and Content-Type separately from Content-Disposition
    io_server->response->set_status( code = 200 reason = '' ).
    io_server->response->set_header_field(
      name = 'Content-Type' value = 'text/csv; charset=utf-8' ).
    io_server->response->set_header_field(
      name  = 'Content-Disposition'
      value = |attachment; filename="{ iv_filename }"| ).
    io_server->response->set_header_field(
      name = 'Access-Control-Allow-Origin' value = '*' ).
    io_server->response->set_cdata( iv_body ).
  ENDMETHOD.


  METHOD is_truthy.
    DATA(lv) = to_lower( condense( iv_val ) ).
    rv_yes = xsdbool( lv = '1' OR lv = 'true' OR lv = 'yes' ).
  ENDMETHOD.


  METHOD error_json.
    " Escape the message before embedding in JSON
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
