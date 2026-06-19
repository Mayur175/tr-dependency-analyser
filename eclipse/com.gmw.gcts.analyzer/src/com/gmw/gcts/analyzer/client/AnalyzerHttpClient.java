package com.gmw.gcts.analyzer.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPreferenceStore;

import com.gmw.gcts.analyzer.Activator;
import com.gmw.gcts.analyzer.client.AdtSessionAdapter.AdtConnection;
import com.gmw.gcts.analyzer.model.AnalysisResult;
import com.gmw.gcts.analyzer.preferences.PreferenceConstants;

/**
 * Calls the TR Analyser ABAP backend.
 *
 * Two modes are supported, selected by the {@code Cloud mode} preference:
 *
 *   1. Classic / on-prem (default):
 *        GET {systemUrl}/sap/bc/zgcts/analyze?tr=&lt;TR&gt;
 *        Handler class on the server: ZGCTS_ANALYZE_HANDLER
 *
 *   2. BTP / Public Cloud:
 *        POST {systemUrl}{servicePath}
 *        Body: {"input":[{"id":"&lt;TR1&gt;"},{"id":"&lt;TR2&gt;"}, ...]}
 *        Handler class on the server: ZCL_GCTS_HTTP_HANDLER_CLOUD
 *        servicePath defaults to /sap/bc/http/sap/zgcts_analyze_srv
 *
 * Connection settings precedence:
 *   1. Active ABAP project in ADT (URL auto-discovered, no second login).
 *   2. The Eclipse preference store (Window -> Preferences -> TR Analyser).
 *
 * Username + password are OPTIONAL. When both are blank the client sends
 * no Authorization header — appropriate for SSO / destination-based
 * service bindings on BTP, or for sandbox systems that do not require
 * Basic Auth at the dispatcher.
 */
public final class AnalyzerHttpClient {

    private static final String CLASSIC_ICF_PATH = "/sap/bc/zgcts/analyze";
    private static final String SECURE_NODE      = "com.gmw.gcts.analyzer";

    private final HttpClient httpClient;
    private final String     systemUrl;
    private final String     authHeader;
    private final int        timeoutSeconds;
    private final String     sourceLabel;     // "ADT project: X" or "Preferences"
    private final boolean    cloudMode;
    private final String     servicePath;     // only used when cloudMode = true

    // -- Constructor - merges ADT-project discovery + preference store -------

    public AnalyzerHttpClient() {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();

        // 1. Try to learn URL/user from the active ABAP project (no login).
        AdtConnection adt = AdtSessionAdapter.discover();

        String prefUrl  = normalise(prefs.getString(PreferenceConstants.PREF_SYSTEM_URL));
        String prefUser = prefs.getString(PreferenceConstants.PREF_USERNAME);
        String pwd      = loadPassword();

        // 2. Pick the URL: ADT project wins if it has one.
        String resolvedUrl;
        String label;
        if (adt != null && adt.hasUrl()) {
            resolvedUrl = normalise(adt.url);
            label = "ADT project: " + adt.projectName;
        } else if (!prefUrl.isEmpty()) {
            resolvedUrl = prefUrl;
            label = "Preferences";
            if (adt != null && !adt.projectName.isEmpty()) {
                label += " (project " + adt.projectName + " did not expose URL)";
            }
        } else {
            resolvedUrl = "";
            label = "(no URL found)";
        }
        this.systemUrl   = resolvedUrl;
        this.sourceLabel = label;

        // 3. Pick the username: preference value wins; fall back to ADT-discovered user.
        //    Auth is OPTIONAL — empty creds means no Authorization header is sent.
        String resolvedUser = prefUser != null ? prefUser : "";
        if (resolvedUser.isEmpty() && adt != null && adt.hasUser()) {
            resolvedUser = adt.user;
        }
        this.authHeader = buildAuthHeader(resolvedUser, pwd);

        // 4. Mode + service path.
        this.cloudMode = prefs.getBoolean(PreferenceConstants.PREF_CLOUD_MODE);
        String path = prefs.getString(PreferenceConstants.PREF_SERVICE_PATH);
        if (path == null || path.isEmpty()) {
            path = PreferenceConstants.DEFAULT_SERVICE_PATH;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        this.servicePath = path;

        // 5. Timeout from preferences (or default).
        int t = prefs.getInt(PreferenceConstants.PREF_TIMEOUT_S);
        this.timeoutSeconds = t > 0 ? t : PreferenceConstants.DEFAULT_TIMEOUT;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Where the URL came from - useful in error messages and logs. */
    public String getSourceLabel() {
        return sourceLabel;
    }

    /** True when targeting the BTP / cloud variant (POST + JSON body). */
    public boolean isCloudMode() {
        return cloudMode;
    }

    // -- Public API ----------------------------------------------------------

    /**
     * Runs the dependency analysis for the given TR number (or comma-separated
     * list of TRs / tasks).
     *
     * @param tr validated TR list, e.g. "GMWK900691" or "DEVK900042,DEVK900043"
     * @return AnalysisResult - never null; check hasError() for failure details
     */
    public AnalysisResult analyze(String tr) {
        if (systemUrl.isEmpty()) {
            return AnalysisResult.error(
                "No SAP system URL found.\n" +
                "Either log into an ABAP project in ADT (URL is detected automatically),\n" +
                "or set the URL manually in Window > Preferences > TR Analyser.");
        }
        if (tr == null || tr.trim().isEmpty()) {
            return AnalysisResult.error("TR number is empty.");
        }

        try {
            URI                 uri;
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest");

            if (cloudMode) {
                // BTP cloud path: POST JSON to the service binding URL.
                uri = new URI(systemUrl + servicePath);
                String body = buildCloudJsonBody(tr);
                rb.uri(uri)
                  .header("Content-Type", "application/json; charset=utf-8")
                  .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                // Classic on-prem path: GET ICF service with ?tr= query.
                String encodedTr = URLEncoder.encode(tr.trim(), StandardCharsets.UTF_8);
                uri = new URI(systemUrl + CLASSIC_ICF_PATH + "?tr=" + encodedTr);
                rb.uri(uri).GET();
            }

            if (!authHeader.isEmpty()) {
                rb.header("Authorization", authHeader);
            }

            HttpResponse<String> response = httpClient.send(
                    rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int sc = response.statusCode();
            if (sc == 200) {
                return AnalysisResult.fromJson(response.body());
            }
            if (sc == 401) {
                return AnalysisResult.error(
                    "HTTP 401 Unauthorized.\n" +
                    "The backend requires credentials. Either fill in Username/Password\n" +
                    "in Window > Preferences > TR Analyser, or configure the BTP service\n" +
                    "binding for anonymous / SSO access.");
            }
            if (sc == 403) {
                return AnalysisResult.error(
                    "HTTP 403 Forbidden.\n" +
                    "The user lacks scope/authorisation for the endpoint:\n  " +
                    uri.toString() + "\n" +
                    (cloudMode
                        ? "On BTP, attach a Communication Arrangement that exposes the\n" +
                          "service binding to the calling business user."
                        : "On on-prem, grant S_TRANSPRT (TTYPE=CUST, ACTVT=03) or set\n" +
                          "c_enforce_auth = abap_false in ZGCTS_ANALYZE_HANDLER."));
            }
            if (sc == 404) {
                return AnalysisResult.error(
                    "HTTP 404 - endpoint not found at:\n  " + uri.toString() + "\n" +
                    (cloudMode
                        ? "Check that the HTTP Service binding for\n" +
                          "ZCL_GCTS_HTTP_HANDLER_CLOUD is created and active in ADT,\n" +
                          "and that the 'Service path' in preferences matches its URL."
                        : "Activate /sap/bc/zgcts/analyze in transaction SICF."));
            }
            if (sc == 405) {
                return AnalysisResult.error(
                    "HTTP 405 Method Not Allowed at:\n  " + uri.toString() + "\n" +
                    (cloudMode
                        ? "The cloud handler requires POST. The plugin is sending POST,\n" +
                          "so the server-side binding is configured for a different method."
                        : "Toggle 'Cloud mode' in preferences if you are on BTP — the\n" +
                          "cloud handler expects POST, the classic handler expects GET."));
            }
            return AnalysisResult.error(
                "HTTP " + sc + " from SAP system:\n" + truncate(response.body(), 500));

        } catch (ConnectException e) {
            return AnalysisResult.error(
                "Cannot reach " + systemUrl + "\n" +
                "Check system URL in preferences and network connectivity.\n" +
                "Details: " + e.getMessage());
        } catch (URISyntaxException e) {
            return AnalysisResult.error("Invalid system URL: " + e.getMessage());
        } catch (IOException e) {
            return AnalysisResult.error("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AnalysisResult.error("Request interrupted.");
        }
    }

    /**
     * Quick connectivity check - returns null on success, error message on failure.
     *
     * In classic mode: GET the ICF service with an empty TR — handler answers 400.
     * In cloud mode:   POST an empty input array — handler answers 400.
     * Anything that comes back (even 4xx) means the server is reachable. 404
     * means the binding/SICF node is missing.
     */
    public String testConnection() {
        if (systemUrl.isEmpty()) {
            return "No SAP system URL configured.";
        }
        try {
            URI                 uri;
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json");

            if (cloudMode) {
                uri = new URI(systemUrl + servicePath);
                rb.uri(uri)
                  .header("Content-Type", "application/json; charset=utf-8")
                  .POST(BodyPublishers.ofString("{\"input\":[]}", StandardCharsets.UTF_8));
            } else {
                uri = new URI(systemUrl + CLASSIC_ICF_PATH + "?tr=");
                rb.uri(uri).GET();
            }

            if (!authHeader.isEmpty()) {
                rb.header("Authorization", authHeader);
            }

            HttpResponse<String> response = httpClient.send(
                    rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int sc = response.statusCode();
            // 200, 400 (bad request), 401 (auth required), 403 (forbidden) all
            // confirm the service is reachable. 404 means the binding/SICF node
            // is not active.
            if (sc == 404) {
                return "HTTP 404 - endpoint " + uri.toString() + " not found. " +
                       (cloudMode
                            ? "Check the HTTP Service binding for ZCL_GCTS_HTTP_HANDLER_CLOUD."
                            : "Activate /sap/bc/zgcts/analyze in SICF.");
            }
            return null;
        } catch (ConnectException e) {
            return "Cannot reach " + systemUrl + " - " + e.getMessage();
        } catch (URISyntaxException e) {
            return "Invalid system URL: " + e.getMessage();
        } catch (IOException e) {
            return "Network error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Request interrupted.";
        }
    }

    // -- Private helpers -----------------------------------------------------

    /**
     * Build the JSON body the cloud handler expects:
     *   {"input":[{"id":"GMWK900691"},{"id":"DEVK900042"}, ...]}
     */
    private static String buildCloudJsonBody(String trList) {
        String[] parts = trList.trim().split("\\s*,\\s*");
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"input\":[");
        boolean first = true;
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{\"id\":\"").append(jsonEscape(p)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static String normalise(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String buildAuthHeader(String user, String password) {
        // Auth is OPTIONAL. No user means no Authorization header at all.
        if (user == null || user.isEmpty()) {
            return "";
        }
        String credentials = user + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String loadPassword() {
        ISecurePreferences root = SecurePreferencesFactory.getDefault();
        if (root == null) {
            return "";
        }
        try {
            ISecurePreferences node = root.node(SECURE_NODE);
            return node.get(PreferenceConstants.PREF_PASSWORD, "");
        } catch (StorageException e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}