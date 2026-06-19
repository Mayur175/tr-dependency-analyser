package com.gmw.gcts.analyzer.handlers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.viewers.IStructuredSelection;

/**
 * Extracts a TR number from an Eclipse structured selection.
 *
 * Strategy (tried in order, first non-null result wins):
 *
 *   1. IAdaptable → ICtsTransportRequest  (direct object from SAP ADT — most reliable)
 *   2. IAdaptable → IResource → name scan  (file/folder name contains TR number)
 *   3. toString() regex scan               (fallback — works for most ADT tree nodes)
 *
 * SAP ADT exposes ICtsTransportRequest via IAdaptable on Transport Organizer
 * tree nodes in ADT 3.x+. When available this is exact; when unavailable the
 * regex fallback still covers the common case.
 */
final class TrDetector {

    private static final Pattern TR_PATTERN =
            Pattern.compile("\\b([A-Z0-9]{3,4}K[0-9]{6})\\b");

    /**
     * Comma-separated list of TR / task ids.
     * Examples that match: "GMWK900691", "DEVK900042,DEVK900043",
     *                      " GMWK900691 , DEVK900042 ".
     */
    private static final Pattern TR_LIST_PATTERN =
            Pattern.compile("^\\s*[A-Z0-9]{3,4}K[0-9]{6}"
                          + "(\\s*,\\s*[A-Z0-9]{3,4}K[0-9]{6})*\\s*$");

    /** Fully-qualified ADT interface name — loaded reflectively to avoid hard compile dependency. */
    private static final String ADT_TR_INTERFACE =
            "com.sap.adt.cts.core.model.ICtsTransportRequest";

    private TrDetector() {}

    /**
     * Returns the first TR number found in the selection, or null if none detected.
     */
    static String detect(IStructuredSelection selection) {
        if (selection == null || selection.isEmpty()) return null;

        for (Object element : selection.toList()) {
            String tr = tryAdaptable(element);
            if (tr != null) return tr;

            tr = tryToString(element);
            if (tr != null) return tr;
        }
        return null;
    }

    // ── Strategy 1: IAdaptable → ICtsTransportRequest ────────────────────────

    private static String tryAdaptable(Object element) {
        if (!(element instanceof org.eclipse.core.runtime.IAdaptable adaptable)) return null;

        try {
            // Load the SAP ADT interface reflectively — avoids hard compile-time
            // dependency on com.sap.adt.cts.core which may not always be present.
            Class<?> iCtsClass = Class.forName(ADT_TR_INTERFACE);
            Object ctsObject = adaptable.getAdapter(iCtsClass);
            if (ctsObject == null) return null;

            // ICtsTransportRequest typically exposes getTransportRequestId() or getName()
            try {
                Object id = ctsObject.getClass().getMethod("getTransportRequestId").invoke(ctsObject);
                if (id instanceof String s && isValidTr(s)) return s.toUpperCase();
            } catch (NoSuchMethodException ignored) {}

            try {
                Object name = ctsObject.getClass().getMethod("getName").invoke(ctsObject);
                if (name instanceof String s && isValidTr(s)) return s.toUpperCase();
            } catch (NoSuchMethodException ignored) {}

        } catch (ClassNotFoundException ignored) {
            // SAP ADT CTS interface not available in this Eclipse installation — use fallback
        } catch (Exception ignored) {}

        return null;
    }

    // ── Strategy 2: toString() regex scan ─────────────────────────────────────

    private static String tryToString(Object element) {
        if (element == null) return null;
        Matcher m = TR_PATTERN.matcher(element.toString());
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /** Strict single-id validation (one TR or task). */
    static boolean isValidTr(String tr) {
        return tr != null && TR_PATTERN.matcher(tr.trim()).matches();
    }

    /**
     * Validates a comma-separated list of one or more TR / task ids.
     * Used by the input dialog so the user can request a cross-TR analysis.
     */
    static boolean isValidTrList(String list) {
        return list != null && TR_LIST_PATTERN.matcher(list).matches();
    }
}
