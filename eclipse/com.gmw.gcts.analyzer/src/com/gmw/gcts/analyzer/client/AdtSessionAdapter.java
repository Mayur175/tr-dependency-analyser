package com.gmw.gcts.analyzer.client;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Best-effort discovery of the active ABAP project's connection info.
 *
 * Why this exists
 * ---------------
 * In ADT the developer logs into an ABAP project once. The project carries
 * the SAP system URL (and username) in its own metadata. The TR Analyser
 * plugin is a separate Eclipse plugin and does NOT have a compile-time
 * dependency on SAP-internal {@code com.sap.adt.*} classes (they are
 * marked {@code @noreference} and may change between ADT versions).
 *
 * To get a "no second login" experience like the rest of ADT, this
 * adapter tries — reflectively, with full graceful fallback — to read
 * the URL and username from the currently selected ABAP project.
 *
 * Contract
 * --------
 * {@link #discover()} NEVER throws. On any failure (no ADT installed,
 * different ADT version, no ABAP project selected, reflection target
 * missing) it returns {@code null}. Callers that want a working URL
 * MUST fall back to their preference store on null.
 *
 * Reflection targets attempted (in order)
 * ---------------------------------------
 *   - {@code com.sap.adt.tools.core.project.IAbapProject}
 *       methods tried: {@code getDestinationData()},
 *                     {@code getDestinationId()},
 *                     {@code getProject()}
 *
 *   - {@code com.sap.adt.tools.core.model.adtcore.IDestinationData}
 *       methods tried: {@code getUrl()}, {@code getUser()}, {@code getClient()}
 *
 * If SAP renames or moves these classes in a future ADT release, every
 * lookup fails silently and the adapter returns null. The plugin keeps
 * working via the preference page.
 */
public final class AdtSessionAdapter {

    /** Container for what we managed to discover. Any field may be null/empty. */
    public static final class AdtConnection {
        public final String projectName;
        public final String url;
        public final String user;
        public final String client;

        public AdtConnection(String projectName, String url, String user, String client) {
            this.projectName = projectName == null ? "" : projectName;
            this.url         = url == null ? "" : url;
            this.user        = user == null ? "" : user;
            this.client      = client == null ? "" : client;
        }

        public boolean hasUrl()  { return !url.isEmpty(); }
        public boolean hasUser() { return !user.isEmpty(); }

        @Override
        public String toString() {
            return "AdtConnection{project=" + projectName
                 + ", url=" + url
                 + ", user=" + user
                 + ", client=" + client + "}";
        }
    }

    private AdtSessionAdapter() { /* static only */ }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /**
     * Try to discover the connection info for the currently active ABAP
     * project. Returns {@code null} if nothing could be discovered.
     */
    public static AdtConnection discover() {
        try {
            IProject project = findActiveProject();
            if (project == null) {
                return null;
            }
            return discoverFromProject(project);
        } catch (Throwable t) {
            // Anything went wrong - silently fall back.
            return null;
        }
    }

    /**
     * Try to discover the connection info from the supplied selection.
     * Used by command handlers that have access to the right-clicked
     * selection. Falls back to {@link #discover()} on null/empty.
     */
    public static AdtConnection discoverFromSelection(IStructuredSelection sel) {
        try {
            if (sel != null && !sel.isEmpty()) {
                Object first = sel.getFirstElement();
                IProject project = adaptToProject(first);
                if (project != null) {
                    AdtConnection c = discoverFromProject(project);
                    if (c != null) {
                        return c;
                    }
                }
            }
            return discover();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Walks the workbench selection to find the active {@link IProject}.
     * Public Eclipse API only - no SAP-internal classes here.
     */
    private static IProject findActiveProject() {
        final IProject[] holder = new IProject[1];

        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    IWorkbenchWindow window =
                        PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null || window.getActivePage() == null) {
                        return;
                    }
                    Object sel = window.getActivePage().getSelection();
                    if (sel instanceof IStructuredSelection) {
                        Object first = ((IStructuredSelection) sel).getFirstElement();
                        holder[0] = adaptToProject(first);
                    }
                } catch (Throwable ignored) {
                    // Best-effort.
                }
            }
        };

        Display display = Display.getCurrent();
        if (display == null) {
            display = Display.getDefault();
        }
        if (Display.getCurrent() == null) {
            display.syncExec(r);
        } else {
            r.run();
        }
        return holder[0];
    }

    /**
     * Converts a workbench selection element to an {@link IProject} via
     * the public {@link IAdaptable} contract.
     */
    private static IProject adaptToProject(Object element) {
        if (element instanceof IProject) {
            return (IProject) element;
        }
        if (element instanceof IAdaptable) {
            Object adapted = ((IAdaptable) element).getAdapter(IProject.class);
            if (adapted instanceof IProject) {
                return (IProject) adapted;
            }
        }
        return null;
    }

    /**
     * Reflectively probes the project for SAP ADT metadata. Every method
     * call is wrapped in try/catch so missing classes / methods just
     * yield empty fields.
     */
    private static AdtConnection discoverFromProject(IProject project) {
        if (project == null) {
            return null;
        }
        String projectName = project.getName();
        String url    = "";
        String user   = "";
        String client = "";

        // Attempt to load the SAP ADT IAbapProject interface reflectively.
        Class<?> iAbapProject = null;
        try {
            iAbapProject = Class.forName(
                "com.sap.adt.tools.core.project.IAbapProject");
        } catch (Throwable ignored) {
            // ADT not installed or different package - degrade silently.
        }

        if (iAbapProject != null && project instanceof IAdaptable) {
            try {
                Object abapProject =
                    ((IAdaptable) project).getAdapter(iAbapProject);
                if (abapProject != null) {
                    // IAbapProject.getDestinationData() typically returns
                    // an IDestinationData with getUrl / getUser / getClient.
                    Object destinationData = invokeOptional(
                        abapProject, "getDestinationData");
                    if (destinationData == null) {
                        destinationData = invokeOptional(
                            abapProject, "getDestination");
                    }
                    if (destinationData != null) {
                        url    = stringOf(invokeOptional(destinationData, "getUrl"));
                        user   = stringOf(invokeOptional(destinationData, "getUser"));
                        client = stringOf(invokeOptional(destinationData, "getClient"));
                    }
                }
            } catch (Throwable ignored) {
                // Reflection target moved or differs across ADT versions -
                // fall through with whatever we have (often nothing).
            }
        }

        if (url.isEmpty() && user.isEmpty()) {
            // Nothing useful discovered. Return only the project name so
            // callers can still use it as a preference-store key.
            return new AdtConnection(projectName, "", "", "");
        }
        return new AdtConnection(projectName, url, user, client);
    }

    /**
     * Reflectively invokes a no-arg method on the target. Returns
     * {@code null} on any failure (missing method, exception, etc.).
     */
    private static Object invokeOptional(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }
}