package com.gmw.gcts.analyzer.preferences;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.gmw.gcts.analyzer.Activator;
import com.gmw.gcts.analyzer.client.AnalyzerHttpClient;

/**
 * Preference page: Window -> Preferences -> TR Analyser.
 *
 * Stores:
 *   - System URL    -> standard preference store
 *   - Username      -> standard preference store
 *   - Password      -> Eclipse Secure Storage (encrypted)
 *   - Timeout       -> standard preference store
 *   - Cloud mode    -> standard preference store (boolean)
 *   - Service path  -> standard preference store (only used when Cloud mode is on)
 *
 * Cloud mode targets the BTP / Public Cloud HTTP Service binding for
 * ZCL_GCTS_HTTP_HANDLER_CLOUD (POST + JSON body). Classic mode targets
 * the on-prem ICF service /sap/bc/zgcts/analyze (GET + ?tr=...).
 *
 * Username and password are OPTIONAL. Leave blank for an anonymous /
 * SSO-only / destination-based service binding.
 */
public class AnalyzerPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final String SECURE_NODE = "com.gmw.gcts.analyzer";

    private Text   urlText;
    private Text   userText;
    private Text   passwordText;
    private Text   timeoutText;
    private Button cloudModeBtn;
    private Text   servicePathText;
    private Label  servicePathLabel;

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription(
            "Connection settings for the TR Analyser backend.\n" +
            "Cloud mode targets a BTP HTTP Service binding (POST + JSON).\n" +
            "Classic mode targets the on-prem ICF endpoint /sap/bc/zgcts/analyze (GET).");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        container.setLayout(layout);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        IPreferenceStore store = getPreferenceStore();

        // -- System URL ------------------------------------------------------
        new Label(container, SWT.NONE).setText("SAP System URL:");
        urlText = new Text(container, SWT.BORDER);
        urlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        urlText.setMessage("https://my-system.example.com:44300");
        urlText.setText(safeString(store.getString(PreferenceConstants.PREF_SYSTEM_URL)));

        // -- Username (optional) ---------------------------------------------
        new Label(container, SWT.NONE).setText("Username (optional):");
        userText = new Text(container, SWT.BORDER);
        userText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        userText.setText(safeString(store.getString(PreferenceConstants.PREF_USERNAME)));

        // -- Password (optional, Secure Storage) -----------------------------
        new Label(container, SWT.NONE).setText("Password (optional):");
        passwordText = new Text(container, SWT.BORDER | SWT.PASSWORD);
        passwordText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        passwordText.setText(loadSecurePassword());

        // -- Timeout ---------------------------------------------------------
        new Label(container, SWT.NONE).setText("Timeout (seconds):");
        timeoutText = new Text(container, SWT.BORDER);
        GridData timeoutGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        timeoutGd.widthHint = 80;
        timeoutText.setLayoutData(timeoutGd);
        int savedTimeout = store.getInt(PreferenceConstants.PREF_TIMEOUT_S);
        timeoutText.setText(String.valueOf(
            savedTimeout > 0 ? savedTimeout : PreferenceConstants.DEFAULT_TIMEOUT));

        // -- Separator -------------------------------------------------------
        Label sep1 = new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData sep1Gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sep1Gd.horizontalSpan = 2;
        sep1.setLayoutData(sep1Gd);

        // -- Cloud mode checkbox ---------------------------------------------
        new Label(container, SWT.NONE).setText("Cloud mode (BTP):");
        cloudModeBtn = new Button(container, SWT.CHECK);
        cloudModeBtn.setText("POST JSON to HTTP Service binding (uncheck for classic ICF GET)");
        cloudModeBtn.setSelection(store.getBoolean(PreferenceConstants.PREF_CLOUD_MODE));

        // -- Service path (only meaningful when Cloud mode is on) ------------
        servicePathLabel = new Label(container, SWT.NONE);
        servicePathLabel.setText("Service path (Cloud mode):");
        servicePathText = new Text(container, SWT.BORDER);
        servicePathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        String savedPath = store.getString(PreferenceConstants.PREF_SERVICE_PATH);
        if (savedPath == null || savedPath.isEmpty()) {
            savedPath = PreferenceConstants.DEFAULT_SERVICE_PATH;
        }
        servicePathText.setText(savedPath);
        servicePathText.setMessage(PreferenceConstants.DEFAULT_SERVICE_PATH);
        updateServicePathEnabled(cloudModeBtn.getSelection());

        cloudModeBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateServicePathEnabled(cloudModeBtn.getSelection());
            }
        });

        // -- Separator -------------------------------------------------------
        Label sep2 = new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData sep2Gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sep2Gd.horizontalSpan = 2;
        sep2.setLayoutData(sep2Gd);

        // -- Test Connection button ------------------------------------------
        Button testBtn = new Button(container, SWT.PUSH);
        testBtn.setText("Test Connection");
        GridData btnGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        btnGd.horizontalSpan = 2;
        testBtn.setLayoutData(btnGd);
        testBtn.addListener(SWT.Selection, new Listener() {
            @Override
            public void handleEvent(Event event) {
                testConnection();
            }
        });

        return container;
    }

    private void updateServicePathEnabled(boolean cloudOn) {
        if (servicePathText != null) {
            servicePathText.setEnabled(cloudOn);
        }
        if (servicePathLabel != null) {
            servicePathLabel.setEnabled(cloudOn);
        }
    }

    @Override
    protected void performDefaults() {
        urlText.setText("");
        userText.setText("");
        passwordText.setText("");
        timeoutText.setText(String.valueOf(PreferenceConstants.DEFAULT_TIMEOUT));
        cloudModeBtn.setSelection(false);
        servicePathText.setText(PreferenceConstants.DEFAULT_SERVICE_PATH);
        updateServicePathEnabled(false);
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        IPreferenceStore store = getPreferenceStore();
        store.setValue(PreferenceConstants.PREF_SYSTEM_URL, urlText.getText().trim());
        store.setValue(PreferenceConstants.PREF_USERNAME,   userText.getText().trim());
        store.setValue(PreferenceConstants.PREF_CLOUD_MODE, cloudModeBtn.getSelection());

        String path = servicePathText.getText().trim();
        if (path.isEmpty()) {
            path = PreferenceConstants.DEFAULT_SERVICE_PATH;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        store.setValue(PreferenceConstants.PREF_SERVICE_PATH, path);

        int timeout;
        try {
            timeout = Integer.parseInt(timeoutText.getText().trim());
            if (timeout <= 0) {
                timeout = PreferenceConstants.DEFAULT_TIMEOUT;
            }
        } catch (NumberFormatException e) {
            timeout = PreferenceConstants.DEFAULT_TIMEOUT;
        }
        store.setValue(PreferenceConstants.PREF_TIMEOUT_S, timeout);

        saveSecurePassword(passwordText.getText());
        return super.performOk();
    }

    // -- Private helpers -----------------------------------------------------

    private void testConnection() {
        // Persist current entries first so the HTTP client sees them.
        performOk();

        AnalyzerHttpClient client = new AnalyzerHttpClient();
        String error = client.testConnection();

        MessageBox mb = new MessageBox(getShell(),
            error == null ? SWT.ICON_INFORMATION | SWT.OK
                          : SWT.ICON_ERROR       | SWT.OK);
        mb.setText("TR Analyser - Connection Test");
        mb.setMessage(error == null
            ? "Connection OK - backend is reachable."
            : "Connection failed:\n\n" + error);
        mb.open();
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }

    private static String loadSecurePassword() {
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

    private static void saveSecurePassword(String password) {
        ISecurePreferences root = SecurePreferencesFactory.getDefault();
        if (root == null) {
            return;
        }
        try {
            ISecurePreferences node = root.node(SECURE_NODE);
            node.put(PreferenceConstants.PREF_PASSWORD,
                     password == null ? "" : password,
                     true /* encrypt */);
            node.flush();
        } catch (StorageException e) {
            // Secure storage failed - nothing we can do here, password not saved
        } catch (java.io.IOException e) {
            // Flush failed - password kept in memory only
        }
    }
}