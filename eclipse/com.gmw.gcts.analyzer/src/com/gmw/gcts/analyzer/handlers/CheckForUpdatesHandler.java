package com.gmw.gcts.analyzer.handlers;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

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
 * Behaviour, in order:
 *
 *   1. Self-heal the registered update sites:
 *      a) Remove any p2 repository whose URI points at the legacy
 *         "github.com/.../tr-dependency-analyser/releases/.../updatesite/"
 *         URL — that path was a deliberate convention but GitHub Releases
 *         does not actually serve a p2 directory tree there, so Eclipse
 *         could only ever throw "No software site found" against it.
 *      b) Make sure the live GitHub Pages URL
 *         {@code https://mayur175.github.io/tr-dependency-analyser/}
 *         is registered as a known update site.
 *
 *   2. Trigger Eclipse's built-in <em>Help &gt; Check for Updates</em>
 *      flow ({@code org.eclipse.equinox.p2.ui.sdk.update}). With the URL
 *      from step 1 in place this is the only thing the user needs to do
 *      to pull a newer version.
 *
 *   3. Fallback: if p2 is unavailable for any reason, open the update
 *      site URL in the user's browser, then show a dialog with the URL
 *      so the user can paste it into Install New Software manually.
 *
 * All p2 calls in step 1 go through reflection so this bundle does not
 * have to declare a hard {@code Require-Bundle} on
 * {@code org.eclipse.equinox.p2.ui} / {@code .operations}. If those
 * bundles are absent the self-heal silently no-ops, the user still gets
 * step 2's standard update dialog (if p2 is around at all), and at worst
 * step 3 hands them the URL.
 */
public final class CheckForUpdatesHandler extends AbstractHandler {

    /** The single source of truth for the live update site URL. */
    public static final String UPDATE_SITE_URL =
        "https://mayur175.github.io/tr-dependency-analyser/";

    /** Display name registered against {@link #UPDATE_SITE_URL}. */
    private static final String UPDATE_SITE_NICKNAME =
        "TR Analyser Update Site";

    /**
     * Substring used to detect the legacy / broken release-asset URL that
     * earlier builds of this plugin shipped with. Any registered p2 site
     * whose URI contains this substring is removed silently.
     */
    private static final String LEGACY_BROKEN_URL_SUBSTRING =
        "github.com/Mayur175/tr-dependency-analyser/releases";

    /** P2 SDK command id for "Check for Updates". */
    private static final String P2_UPDATE_COMMAND =
        "org.eclipse.equinox.p2.ui.sdk.update";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        // Step 1 - clean up known repositories so the update dialog only
        // sees the live URL.
        ensureUpdateSiteRegistered();

        // Step 2 - run the standard Eclipse p2 update flow.
        if (tryRunP2UpdateCommand()) {
            return null;
        }

        // Step 3a - p2 unavailable: open the URL in the user's browser.
        if (tryOpenInBrowser(UPDATE_SITE_URL)) {
            return null;
        }

        // Step 3b - last resort: hand the URL to the user.
        MessageDialog.openInformation(shell, "TR Analyser - Check for Updates",
            "Could not launch the Eclipse updater automatically.\n\n"
                + "Please open Help -> Install New Software... and use this URL:\n\n"
                + UPDATE_SITE_URL);
        return null;
    }

    // ------------------------------------------------------------------
    // STEP 1 - update-site self-heal (via reflection on p2 UI APIs)
    // ------------------------------------------------------------------

    /**
     * Best-effort: remove broken legacy URIs and add the live one.
     * Silently does nothing if p2 UI bundles are not on the classpath.
     *
     * Equivalent to (but written with reflection):
     * <pre>
     *   ProvisioningUI ui    = ProvisioningUI.getDefaultUI();
     *   ProvisioningSession s = ui.getSession();
     *   RepositoryTracker rt  = ui.getRepositoryTracker();
     *   URI[] known           = rt.getKnownRepositories(s);
     *   // remove any "...releases..." URI
     *   rt.removeRepositories(brokenUris, s);
     *   // add the GitHub Pages URI if missing
     *   rt.addRepository(new URI(UPDATE_SITE_URL), UPDATE_SITE_NICKNAME, s);
     * </pre>
     */
    private static void ensureUpdateSiteRegistered() {
        try {
            Class<?> provUiCls = Class.forName(
                "org.eclipse.equinox.p2.ui.ProvisioningUI");

            Object ui = provUiCls.getMethod("getDefaultUI").invoke(null);
            if (ui == null) {
                return;
            }

            Object session = provUiCls.getMethod("getSession").invoke(ui);
            Object tracker = provUiCls.getMethod("getRepositoryTracker").invoke(ui);
            if (session == null || tracker == null) {
                return;
            }

            Class<?> sessionCls = session.getClass();
            Class<?> trackerCls = tracker.getClass();

            // 1. Snapshot of currently known repositories.
            Method getKnown = trackerCls.getMethod(
                "getKnownRepositories",
                Class.forName("org.eclipse.equinox.p2.operations.ProvisioningSession"));
            URI[] known = (URI[]) getKnown.invoke(tracker, session);

            URI liveUri = new URI(UPDATE_SITE_URL);

            // 2. Find legacy/broken entries to remove and decide whether to add.
            List<URI> toRemove = new ArrayList<>();
            boolean liveAlreadyKnown = false;
            if (known != null) {
                for (URI u : known) {
                    if (u == null) {
                        continue;
                    }
                    String s = u.toString();
                    if (s.contains(LEGACY_BROKEN_URL_SUBSTRING)) {
                        toRemove.add(u);
                    }
                    if (u.equals(liveUri)) {
                        liveAlreadyKnown = true;
                    }
                }
            }

            // 3. Remove the broken ones in one call.
            if (!toRemove.isEmpty()) {
                Method removeRepos = trackerCls.getMethod(
                    "removeRepositories",
                    URI[].class,
                    Class.forName("org.eclipse.equinox.p2.operations.ProvisioningSession"));
                removeRepos.invoke(
                    tracker,
                    (Object) toRemove.toArray(new URI[0]),
                    session);
            }

            // 4. Register the live URL if missing.
            if (!liveAlreadyKnown) {
                Method addRepo = trackerCls.getMethod(
                    "addRepository",
                    URI.class,
                    String.class,
                    Class.forName("org.eclipse.equinox.p2.operations.ProvisioningSession"));
                addRepo.invoke(tracker, liveUri, UPDATE_SITE_NICKNAME, session);
            }
        } catch (Throwable ignored) {
            // p2 UI not present, or some signature changed in a future Eclipse.
            // Step 2 / Step 3 still run, so the user is not blocked.
        }
    }

    // ------------------------------------------------------------------
    // STEP 2 - launch the standard Eclipse update wizard
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // STEP 3 - browser fallback
    // ------------------------------------------------------------------

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