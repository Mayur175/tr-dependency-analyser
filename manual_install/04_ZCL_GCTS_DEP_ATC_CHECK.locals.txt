"! Minimal JSON cluster reader for use inside the ATC check.
"! Parses only the fields needed: clusters[].risk, clusters[].edges[].detail, kind.
"! Place in LOCAL TYPES tab of ZCL_GCTS_DEP_ATC_CHECK in ADT.
CLASS lcl_atc_json_reader DEFINITION FINAL.
  PUBLIC SECTION.
    TYPES: BEGIN OF ty_edge,
             kind   TYPE string,
             detail TYPE string,
           END OF ty_edge.
    TYPES tt_edges TYPE STANDARD TABLE OF ty_edge WITH EMPTY KEY.

    TYPES: BEGIN OF ty_cluster,
             risk  TYPE string,
             edges TYPE tt_edges,
           END OF ty_cluster.
    TYPES tt_clusters TYPE STANDARD TABLE OF ty_cluster WITH EMPTY KEY.

    DATA clusters TYPE tt_clusters.

    CLASS-METHODS parse
      IMPORTING iv_json        TYPE string
      RETURNING VALUE(ro_inst) TYPE REF TO lcl_atc_json_reader.

  PRIVATE SECTION.
    CLASS-METHODS extract_string
      IMPORTING iv_src          TYPE string
                iv_key          TYPE string
      RETURNING VALUE(rv_val)   TYPE string.
ENDCLASS.

CLASS lcl_atc_json_reader IMPLEMENTATION.
  METHOD parse.
    ro_inst = NEW #( ).
    " Locate "clusters":[ ... ] array
    DATA(lv_arr_start) = find( val = iv_json sub = '"clusters":[' ) + 12.
    IF lv_arr_start < 12. RETURN. ENDIF.

    DATA(lv_remaining) = iv_json+lv_arr_start.

    " Split top-level { } objects
    DATA lv_depth TYPE i VALUE 0.
    DATA lv_obj_start TYPE i VALUE -1.
    DATA lv_i TYPE i VALUE 0.

    WHILE lv_i < strlen( lv_remaining ).
      DATA(lv_char) = lv_remaining+lv_i(1).
      CASE lv_char.
        WHEN '{'.
          IF lv_depth = 0. lv_obj_start = lv_i. ENDIF.
          lv_depth += 1.
        WHEN '}'.
          lv_depth -= 1.
          IF lv_depth = 0 AND lv_obj_start >= 0.
            DATA(lv_obj) = lv_remaining+lv_obj_start( lv_i - lv_obj_start + 1 ).
            DATA(ls_cl) = VALUE ty_cluster(
              risk = extract_string( iv_src = lv_obj iv_key = 'risk' ) ).

            " Extract edges array — simplified: scan for kind + detail pairs
            DATA(lv_edges_start) = find( val = lv_obj sub = '"edges":[' ) + 9.
            IF lv_edges_start >= 9.
              DATA(lv_edges_str) = lv_obj+lv_edges_start.
              DATA lv_ed TYPE i VALUE 0.
              DATA lv_ed_depth TYPE i VALUE 0.
              DATA lv_ed_obj_start TYPE i VALUE -1.
              WHILE lv_ed < strlen( lv_edges_str ).
                DATA(lv_ec) = lv_edges_str+lv_ed(1).
                CASE lv_ec.
                  WHEN '{'.
                    IF lv_ed_depth = 0. lv_ed_obj_start = lv_ed. ENDIF.
                    lv_ed_depth += 1.
                  WHEN '}'.
                    lv_ed_depth -= 1.
                    IF lv_ed_depth = 0 AND lv_ed_obj_start >= 0.
                      DATA(lv_edge_obj) = lv_edges_str+lv_ed_obj_start(
                                              lv_ed - lv_ed_obj_start + 1 ).
                      APPEND VALUE #(
                        kind   = extract_string( iv_src = lv_edge_obj iv_key = 'kind' )
                        detail = extract_string( iv_src = lv_edge_obj iv_key = 'detail' )
                      ) TO ls_cl-edges.
                      lv_ed_obj_start = -1.
                    ENDIF.
                  WHEN ']'.
                    IF lv_ed_depth = 0. EXIT. ENDIF.
                ENDCASE.
                lv_ed += 1.
              ENDWHILE.
            ENDIF.

            APPEND ls_cl TO ro_inst->clusters.
            lv_obj_start = -1.
          ENDIF.
        WHEN ']'.
          IF lv_depth = 0. EXIT. ENDIF.
      ENDCASE.
      lv_i += 1.
    ENDWHILE.
  ENDMETHOD.

  METHOD extract_string.
    DATA(lv_pattern) = |"{ iv_key }":"|.
    DATA(lv_pos) = find( val = iv_src sub = lv_pattern ).
    IF lv_pos < 0. RETURN. ENDIF.
    DATA(lv_start) = lv_pos + strlen( lv_pattern ).
    DATA(lv_end)   = find( val = iv_src off = lv_start sub = '"' ).
    IF lv_end < 0. RETURN. ENDIF.
    rv_val = iv_src+lv_start( lv_end - lv_start ).
    REPLACE ALL OCCURRENCES OF '\"' IN rv_val WITH '"'.
  ENDMETHOD.
ENDCLASS.
