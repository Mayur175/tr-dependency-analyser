package com.gmw.gcts.analyzer.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import com.gmw.gcts.analyzer.client.AnalyzerHttpClient;
import com.gmw.gcts.analyzer.model.AnalysisResult;
import com.gmw.gcts.analyzer.views.DependencyResultView;

/**
 * Command handler for "Analyse gCTS Dependencies".
 *
 * Flow:
 *   1. Detect TR via TrDetector (IAdaptable + regex toString fallback)
 *   2. InputDialog - pre-filled, user confirms or edits
 *   3. Open DependencyResultView, show loading state
 *   4. Background Eclipse Job runs AnalyzerHttpClient.analyze(tr)
 *   5. View receives showResult() - marshalled to UI thread inside the view
 */
public class AnalyzeTRHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection sel = HandlerUtil.getCurrentSelection(event);

        String detected = detectTrFromSelection(sel);
        String tr = promptForTr(shell, detected);
        if (tr == null) {
            return null;
        }

        runAnalysis(tr, shell);
        return null;
    }

    // -- Step 1: TR detection (IAdaptable -> regex fallback) -----------------

    private String detectTrFromSelection(ISelection selection) {
        if (selection instanceof IStructuredSelection) {
            return TrDetector.detect((IStructuredSelection) selection);
        }
        return null;
    }

    // -- Step 2: Confirm TR via InputDialog ----------------------------------

    private String promptForTr(Shell shell, String detected) {
        IInputValidator validator = new IInputValidator() {
            @Override
            public String isValid(String input) {
                if (input == null || input.trim().isEmpty()) {
                    return "Please enter one or more TR / task numbers (comma-separated).";
                }
                if (!TrDetector.isValidTrList(input)) {
                    return "Invalid format. Expected one or more ids matching "
                         + "[A-Z0-9]{3,4}K[0-9]{6} (e.g. GMWK900691 or "
                         + "DEVK900042,DEVK900043).";
                }
                return null;
            }
        };

        InputDialog dlg = new InputDialog(
            shell,
            "TR Analyser",
            "TR / Task number(s), comma-separated\n"
                + "(e.g. GMWK900691  or  DEVK900042,DEVK900043):",
            detected != null ? detected : "",
            validator);

        if (dlg.open() != Window.OK) {
            return null;
        }
        // Normalise: strip whitespace around commas, upper-case all ids
        return dlg.getValue().toUpperCase().replaceAll("\\s+", "");
    }

    // -- Steps 3-5: Open view, call ICF, render result -----------------------

    private void runAnalysis(final String tr, final Shell shell) {
        IWorkbenchPage page = PlatformUI.getWorkbench()
                                        .getActiveWorkbenchWindow()
                                        .getActivePage();

        final DependencyResultView view = openTableView(page, shell);
        if (view == null) {
            return;
        }

        view.showLoading(tr);

        Job job = new Job("TR Analyser: " + tr) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Analysing " + tr, IProgressMonitor.UNKNOWN);
                try {
                    AnalyzerHttpClient client = new AnalyzerHttpClient();
                    AnalysisResult result = client.analyze(tr);
                    view.showResult(result);
                    return Status.OK_STATUS;
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private DependencyResultView openTableView(IWorkbenchPage page, Shell shell) {
        try {
            IViewPart part = page.showView(DependencyResultView.ID, null,
                                           IWorkbenchPage.VIEW_ACTIVATE);
            if (part instanceof DependencyResultView) {
                return (DependencyResultView) part;
            }
            return null;
        } catch (PartInitException e) {
            MessageDialog.openError(shell, "TR Analyser",
                "Could not open the analysis view:\n" + e.getMessage());
            return null;
        }
    }
}