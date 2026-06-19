"! Local class used by Stage 2b for splitting comma-separated task lists.
"! Place this in the LOCAL TYPES tab of ZCL_GCTS_TR_ANALYZER in ADT.
CLASS lcl_string_util DEFINITION FINAL.
  PUBLIC SECTION.
    CLASS-METHODS split
      IMPORTING iv_str        TYPE string
                iv_sep        TYPE string
      RETURNING VALUE(rt_res) TYPE string_table.
ENDCLASS.

CLASS lcl_string_util IMPLEMENTATION.
  METHOD split.
    SPLIT iv_str AT iv_sep INTO TABLE rt_res.
    " Trim whitespace from each element
    LOOP AT rt_res REFERENCE INTO DATA(lr).
      lr->* = condense( lr->* ).
    ENDLOOP.
    DELETE rt_res WHERE table_line IS INITIAL.
  ENDMETHOD.
ENDCLASS.
