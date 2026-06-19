package com.gmw.gcts.analyzer.handlers;

import java.net.URL;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * "Check for Updates" command for TR Analyser.
 *
 * Triggers Eclipse's standard P2 update flow (the same dialog you get from
 * <em>Help -&gt; Check for Updates</em>) so the user can pull the latest
 * TR Analyser plugin from the configured update site
 * {@code https://mayur175.github.io/tr-dependency-analyser/}.
 *
 * Fallback: if the P2 command isn't available (e.g. headless Eclipse
 * without the SDK), opens the update site URL in the user's browser.
 */
public final class CheckForUpdatesHandler extends AbstractHandler {

    private static final String UPDATE_SITE_URL =
        "https://mayur175.github.io/tr-dependency-analyser/";

    /** P2 SDK command id for "Check for Updates". */
    private static final String P2_UPDATE_COMMAND =
        "org.eclipse.equinox.p2.ui.sdk.update";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        // Path A — invoke the standard Eclipse update dialog.
        if (tryRunP2UpdateCommand()) {
            return null;
        }

        // Path B — open the update site URL in a browser as a fallback.
        if (tryOpenInBrowser(UPDATE_SITE_URL)) {
            return null;
        }

        // Path C — show the URL so the user can paste it manually.
        MessageDialog.openInformation(shell, "TR Analyser - Check for Updates",
            "Could not launch the Eclipse updater automatically.\n\n"
                + "Please open Help -> Install New Software... and use this URL:\n\n"
                + UPDATE_SITE_URL);
        return null;
    }

    private static boolean tryRunP2UpdateCommand() {
        try {
            ICommandService cs = PlatformUI.getWorkbench()
                                           .getService(ICommandService.class);
            IHandlerService hs = PlatformUI.getWorkbench()
                                           .getService(IHandlerService.class);
            if (cs == null || hs == null) {
                return false;
            }
            if (cs.getCommand(P2_UPDATE_COMMAND) == null) {
                return false;
            }
            hs.executeCommand(P2_UPDATE_COMMAND, null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryOpenInBrowser(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport()
                .createBrowser("tr-dep-update")
                .openURL(new URL(url));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}