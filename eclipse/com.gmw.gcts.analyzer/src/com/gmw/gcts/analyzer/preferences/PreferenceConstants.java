package com.gmw.gcts.analyzer.preferences;

/** Keys for Eclipse preference store (persisted per workspace). */
public final class PreferenceConstants {

    private PreferenceConstants() {}

    /** Base URL of the SAP system, e.g. https://my-system.hana.ondemand.com */
    public static final String PREF_SYSTEM_URL = "com.gmw.gcts.analyzer.systemUrl";

    /** SAP username for ICF authentication (stored in Eclipse secure storage). */
    public static final String PREF_USERNAME   = "com.gmw.gcts.analyzer.username";

    /** SAP password — stored in Eclipse SecurePreferences, NOT plain preference store. */
    public static final String PREF_PASSWORD   = "com.gmw.gcts.analyzer.password";

    /** HTTP connection timeout in seconds (default 30). */
    public static final String PREF_TIMEOUT_S  = "com.gmw.gcts.analyzer.timeoutSeconds";

    public static final int    DEFAULT_TIMEOUT  = 30;

    /**
     * When true, the plugin targets the BTP / Public Cloud variant:
     *   - HTTP method  POST
     *   - Body         {"input":[{"id":"&lt;tr&gt;"}, ...]}  (application/json)
     *   - URL          systemUrl + servicePath  (no ?tr= query string)
     *
     * When false (default), the plugin targets the on-prem / S/4 ICF service:
     *   - HTTP method  GET
     *   - URL          systemUrl + /sap/bc/zgcts/analyze?tr=&lt;tr&gt;
     */
    public static final String PREF_CLOUD_MODE   = "com.gmw.gcts.analyzer.cloudMode";

    /**
     * URL path appended to the System URL when Cloud mode is on.
     * Default points at the typical name a BTP HTTP Service binding gets.
     */
    public static final String PREF_SERVICE_PATH = "com.gmw.gcts.analyzer.servicePath";

    /** Default cloud service path; user can override in preferences. */
    public static final String DEFAULT_SERVICE_PATH = "/sap/bc/http/sap/zgcts_analyze_srv";
}
