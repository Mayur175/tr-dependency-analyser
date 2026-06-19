package com.gmw.gcts.analyzer.preferences;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
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
 * Preference page: Window -> Preferences -> gCTS Tools.
 *
 * Stores:
 *   - System URL  -> standard preference store
 *   - Username    -> standard preference store
 *   - Password    -> Eclipse Secure Storage (encrypted)
 *   - Timeout     -> standard preference store
 */
public class AnalyzerPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final String SECURE_NODE = "com.gmw.gcts.analyzer";

    private Text urlText;
    private Text userText;
    private Text passwordText;
    private Text timeoutText;

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("SAP system connection settings for the TR Analyser ICF endpoint /sap/bc/zgcts/analyze");
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

        // -- Username --------------------------------------------------------
        new Label(container, SWT.NONE).setText("Username:");
        userText = new Text(container, SWT.BORDER);
        userText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        userText.setText(safeString(store.getString(PreferenceConstants.PREF_USERNAME)));

        // -- Password (Secure Storage) ---------------------------------------
        new Label(container, SWT.NONE).setText("Password:");
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
        Label sep = new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData sepGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sepGd.horizontalSpan = 2;
        sep.setLayoutData(sepGd);

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

    @Override
    protected void performDefaults() {
        urlText.setText("");
        userText.setText("");
        passwordText.setText("");
        timeoutText.setText(String.valueOf(PreferenceConstants.DEFAULT_TIMEOUT));
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        IPreferenceStore store = getPreferenceStore();
        store.setValue(PreferenceConstants.PREF_SYSTEM_URL, urlText.getText().trim());
        store.setValue(PreferenceConstants.PREF_USERNAME,   userText.getText().trim());

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
            ? "Connection OK - SAP ICF service is reachable."
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