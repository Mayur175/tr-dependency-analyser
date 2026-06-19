package com.gmw.gcts.analyzer.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPreferenceStore;

import com.gmw.gcts.analyzer.Activator;
import com.gmw.gcts.analyzer.model.AnalysisResult;
import com.gmw.gcts.analyzer.preferences.PreferenceConstants;

/**
 * Calls the ABAP ICF service:  GET {systemUrl}/sap/bc/zgcts/analyze?tr=&lt;TR&gt;
 * and returns a parsed AnalysisResult.
 *
 * Connection settings (URL, user, password, timeout) come from the
 * Eclipse preference store - configured via Window -> Preferences -> gCTS Tools.
 */
public final class AnalyzerHttpClient {

    private static final String ICF_PATH    = "/sap/bc/zgcts/analyze";
    private static final String SECURE_NODE = "com.gmw.gcts.analyzer";

    private final HttpClient httpClient;
    private final String     systemUrl;
    private final String     authHeader;
    private final int        timeoutSeconds;

    // -- Constructor - reads preferences -------------------------------------

    public AnalyzerHttpClient() {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();

        this.systemUrl      = normalise(prefs.getString(PreferenceConstants.PREF_SYSTEM_URL));
        int t = prefs.getInt(PreferenceConstants.PREF_TIMEOUT_S);
        this.timeoutSeconds = t > 0 ? t : PreferenceConstants.DEFAULT_TIMEOUT;
        this.authHeader     = buildAuthHeader(
                prefs.getString(PreferenceConstants.PREF_USERNAME),
                loadPassword());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // -- Public API ----------------------------------------------------------

    /**
     * Runs the dependency analysis for the given TR number.
     *
     * @param tr validated TR number, e.g. "GMWK900691"
     * @return AnalysisResult - never null; check hasError() for failure details
     */
    public AnalysisResult analyze(String tr) {
        if (systemUrl.isEmpty()) {
            return AnalysisResult.error(
                "No SAP system URL configured.\n" +
                "Go to Window > Preferences > TR Analyser.");
        }
        if (tr == null || tr.trim().isEmpty()) {
            return AnalysisResult.error("TR number is empty.");
        }

        try {
            String encodedTr = URLEncoder.encode(tr.trim(), StandardCharsets.UTF_8);
            URI    uri       = new URI(systemUrl + ICF_PATH + "?tr=" + encodedTr);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();
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
                    "HTTP 401 Unauthorized - check username/password in preferences.");
            }
            if (sc == 403) {
                return AnalysisResult.error(
                    "HTTP 403 Forbidden - the user lacks authorization for /sap/bc/zgcts/analyze.");
            }
            if (sc == 404) {
                return AnalysisResult.error(
                    "HTTP 404 - ICF service not found.\n" +
                    "Activate /sap/bc/zgcts/analyze in transaction SICF.");
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
     * Sends a HEAD request to the configured base URL. Anything that produces
     * an HTTP response (even 401/403/404) means the system is reachable.
     */
    public String testConnection() {
        if (systemUrl.isEmpty()) {
            return "No SAP system URL configured.";
        }
        try {
            // Use the analyze endpoint itself with a deliberately empty TR.
            // The ABAP handler should respond with HTTP 400 'Missing query parameter'.
            URI uri = new URI(systemUrl + ICF_PATH + "?tr=");

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();
            if (!authHeader.isEmpty()) {
                rb.header("Authorization", authHeader);
            }

            HttpResponse<String> response = httpClient.send(
                    rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int sc = response.statusCode();
            // 200, 400 (bad request), 401 (auth required), 403 (forbidden) all
            // confirm the service is reachable. 404 means ICF node not active.
            if (sc == 404) {
                return "HTTP 404 - ICF service /sap/bc/zgcts/analyze is not active. "
                     + "Activate the node in transaction SICF.";
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