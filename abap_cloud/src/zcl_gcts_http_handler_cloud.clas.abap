"! <p class="shorttext synchronized">TR Analyser - Public Cloud HTTP handler</p>
"!
"! Cloud-released HTTP service extension. Wire this class to an HTTP
"! Service object (.srvb) on your BTP ABAP Environment / S/4HANA Cloud
"! Public tenant.
"!
"! Uses ONLY confirmed-released cloud APIs:
"!   - if_http_service_extension (interface)
"!   - if_web_http_request->get_method / get_text
"!   - if_web_http_response->set_status / set_header_field / set_text
"!
"! Input  (POST body, application/json):
"!   { "input": [ { "id": "GMWK900691" }, { "id": "DEVK900042" } ] }
"!
"! Output (200 OK, application/json):
"!   { "label":"GMWK900691,DEVK900042",
"!     "objectCount":12, "depCount":34, "deps":[ ... ] }
"!
"! Error (4xx / 5xx, application/json):
"!   { "error": "<message>" }
"!
"! Authority is enforced by the platform (Communication Scenario /
"! Business Role attached to the HTTP Service). No explicit
"! AUTHORITY-CHECK in code.
CLASS zcl_gcts_http_handler_cloud DEFINITION
  PUBLIC FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.

    INTERFACES if_http_service_extension.

  PRIVATE SECTION.

    METHODS handle_post
      IMPORTING io_request  TYPE REF TO if_web_http_request
                io_response TYPE REF TO if_web_http_response.

    METHODS write_error
      IMPORTING io_response TYPE REF TO if_web_http_response
                iv_status   TYPE i
                iv_text     TYPE string.

    METHODS extract_input_ids
      IMPORTING iv_body         TYPE string
      RETURNING VALUE(rt_input) TYPE zcl_gcts_tr_analyzer_cloud=>tt_input.

    METHODS json_escape
      IMPORTING iv_value         TYPE string
      RETURNING VALUE(rv_escaped) TYPE string.

ENDCLASS.


CLASS zcl_gcts_http_handler_cloud IMPLEMENTATION.

  METHOD if_http_service_extension~handle_request.

    DATA(lo_request)  = request.
    DATA(lo_response) = response.

    TRY.
        DATA(lv_method) = lo_request->get_method( ).

        IF lv_method = 'POST'.
          handle_post( io_request  = lo_request
                       io_response = lo_response ).
        ELSE.
          write_error( io_response = lo_response
                       iv_status   = 405
                       iv_text     = |Method { lv_method } not allowed; use POST| ).
        ENDIF.

      CATCH cx_root INTO DATA(lo_ex).
        write_error( io_response = lo_response
                     iv_status   = 500
                     iv_text     = lo_ex->get_text( ) ).
    ENDTRY.

  ENDMETHOD.


  METHOD handle_post.

    DATA(lv_body) = io_request->get_text( ).

    DATA(lt_input) = extract_input_ids( lv_body ).

    IF lt_input IS INITIAL.
      write_error( io_response = io_response
                   iv_status   = 400
                   iv_text     = `Body must contain non-empty "input" array of {"id":"..."} entries` ).
      RETURN.
    ENDIF.

    DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer_cloud(
      it_input            = lt_input
      iv_include_external = abap_false ).

    lo_analyzer->run( ).
    lo_analyzer->persist_result( ).

    DATA(lv_json) = lo_analyzer->to_json( ).

    io_response->set_status( i_code   = 200
                             i_reason = 'OK' ).
    io_response->set_header_field( i_name  = 'Content-Type'
                                   i_value = 'application/json; charset=utf-8' ).
    io_response->set_text( lv_json ).

  ENDMETHOD.


  METHOD extract_input_ids.

    " Hand-rolled extractor. Looks for every  "id":"VALUE"  occurrence
    " inside the body and treats each as one input row. Defensive
    " against whitespace and the order of keys in the JSON object;
    " avoids depending on JSON library shapes that may differ across
    " cloud SP levels.

    DATA(lv_rest) = iv_body.

    DO.
      FIND FIRST OCCURRENCE OF REGEX
        `"id"\s*:\s*"([^"]*)"`
        IN lv_rest
        SUBMATCHES DATA(lv_id).

      IF sy-subrc <> 0.
        EXIT.
      ENDIF.

      IF lv_id IS NOT INITIAL.
        APPEND VALUE #( id = lv_id ) TO rt_input.
      ENDIF.

      " Move past this id occurrence to find the next one.
      DATA(lv_idx) = find( val = lv_rest sub = lv_id ).
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


  METHOD write_error.

    DATA(lv_json) = `{"error":"` && json_escape( iv_text ) && `"}`.

    io_response->set_status( i_code   = iv_status
                             i_reason = 'Error' ).
    io_response->set_header_field( i_name  = 'Content-Type'
                                   i_value = 'application/json; charset=utf-8' ).
    io_response->set_text( lv_json ).

  ENDMETHOD.


  METHOD json_escape.

    rv_escaped = iv_value.
    REPLACE ALL OCCURRENCES OF `\` IN rv_escaped WITH `\\`.
    REPLACE ALL OCCURRENCES OF `"` IN rv_escaped WITH `\"`.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>newline
            IN rv_escaped WITH `\n`.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>cr_lf
            IN rv_escaped WITH `\n`.

  ENDMETHOD.

ENDCLASS.