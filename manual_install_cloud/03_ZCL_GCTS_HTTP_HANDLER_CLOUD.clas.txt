"! <p class="shorttext synchronized">TR Analyser - Public Cloud HTTP handler</p>
"!
"! Cloud-released HTTP service extension. Wire this class to an HTTP
"! Service object (.srvb) bound to path /sap/bc/zgcts/analyze on your
"! BTP ABAP Environment / S/4HANA Cloud Public tenant.
"!
"! Request:
"!   POST /sap/bc/zgcts/analyze
"!   Content-Type: application/json
"!   Body: { "input": [ { "id": "GMWK900691" } ], "include_external": false }
"!
"! Response:
"!   200 OK
"!   { "label":"GMWK900691", "object_count":12, "dep_count":34, "deps":[ ... ] }
"!
"! Authority is enforced by the platform (Communication Scenario / Business
"! Role attached to the HTTP Service). No explicit AUTHORITY-CHECK in code.
CLASS zcl_gcts_http_handler_cloud DEFINITION
  PUBLIC FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.

    INTERFACES if_http_service_extension.

  PRIVATE SECTION.

    METHODS handle_post
      IMPORTING io_request  TYPE REF TO if_web_http_request
                io_response TYPE REF TO if_web_http_response
      RAISING   cx_static_check.

    METHODS write_error
      IMPORTING io_response TYPE REF TO if_web_http_response
                iv_status   TYPE i
                iv_text     TYPE string.

ENDCLASS.


CLASS zcl_gcts_http_handler_cloud IMPLEMENTATION.

  METHOD if_http_service_extension~handle_request.

    DATA(lo_request)  = request.
    DATA(lo_response) = response.

    TRY.
        DATA(lv_method) = lo_request->get_method( ).

        IF lv_method = if_web_http_client=>post.
          handle_post( io_request  = lo_request
                       io_response = lo_response ).
        ELSE.
          write_error( io_response = lo_response
                       iv_status   = 405
                       iv_text     = |Method { lv_method } not allowed; use POST| ).
        ENDIF.

      CATCH cx_static_check INTO DATA(lo_ex).
        write_error( io_response = lo_response
                     iv_status   = 500
                     iv_text     = lo_ex->get_text( ) ).
    ENDTRY.

  ENDMETHOD.


  METHOD handle_post.

    DATA(lv_body) = io_request->get_text( ).

    DATA: BEGIN OF ls_payload,
            input             TYPE STANDARD TABLE OF
                                zcl_gcts_tr_analyzer_cloud=>ty_input
                                WITH EMPTY KEY,
            include_external  TYPE abap_bool,
          END OF ls_payload.

    " xco_cp_json is a cloud-released JSON parser.
    xco_cp_json=>data->from_string( lv_body )->apply( VALUE #(
      ( xco_cp_json=>transformation->camel_case_to_underscore )
    ) )->write_to( REF #( ls_payload ) ).

    IF ls_payload-input IS INITIAL.
      write_error( io_response = io_response
                   iv_status   = 400
                   iv_text     = `Body must contain non-empty "input" array` ).
      RETURN.
    ENDIF.

    DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer_cloud(
      it_input            = ls_payload-input
      iv_include_external = ls_payload-include_external ).

    lo_analyzer->run( ).
    lo_analyzer->persist_result( ).

    DATA(lv_json) = lo_analyzer->to_json( ).

    io_response->set_status( i_code   = 200
                             i_reason = 'OK' ).
    io_response->set_header_field( i_name  = 'Content-Type'
                                   i_value = 'application/json; charset=utf-8' ).
    io_response->set_text( lv_json ).

  ENDMETHOD.


  METHOD write_error.

    DATA(lv_json) = |\{ "error": "{ escape( val = iv_text format = cl_abap_format=>e_json_string ) }" \}|.

    io_response->set_status( i_code   = iv_status
                             i_reason = 'Error' ).
    io_response->set_header_field( i_name  = 'Content-Type'
                                   i_value = 'application/json; charset=utf-8' ).
    io_response->set_text( lv_json ).

  ENDMETHOD.

ENDCLASS.