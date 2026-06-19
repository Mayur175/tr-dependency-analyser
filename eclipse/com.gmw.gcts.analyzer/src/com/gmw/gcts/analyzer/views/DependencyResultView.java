package com.gmw.gcts.analyzer.views;

import java.util.List;

import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import com.gmw.gcts.analyzer.actions.ExportCsvAction;
import com.gmw.gcts.analyzer.model.AnalysisResult;
import com.gmw.gcts.analyzer.model.AnalysisResult.Cluster;
import com.gmw.gcts.analyzer.model.AnalysisResult.Edge;
import com.gmw.gcts.analyzer.model.AnalysisResult.PullStep;

/**
 * Eclipse View - "gCTS Dependency Analysis".
 *
 * Layout:
 *   +-------------------------------------------------+
 *   |  TR: GMWK900691  Tasks: 4  Objects: 12  Edges:3 |  <- header label
 *   +-------------------------------------------------+
 *   |  TreeViewer                                     |
 *   |  +- [CRITICAL] Same object conflict             |
 *   |  |  +- Tasks: GMWK900692, GMWK900693            |
 *   |  |  +- ZCL_FOO owned by both tasks [CONFLICT]   |
 *   |  +- [HIGH] Activation dependency                |
 *   |  +- [OK] Independent  -> GMWK900696             |
 *   +-------------------------------------------------+
 *   |  Pull Order:                                    |
 *   |    Step 1: COORDINATE -> GMWK900692, GMWK900693 |
 *   |    Step 2: Pull TOGETHER -> GMWK900694 + 695    |
 *   +-------------------------------------------------+
 */
public class DependencyResultView extends ViewPart {

    public static final String ID = "com.gmw.gcts.analyzer.views.dependencyResult";

    private Label           headerLabel;
    private TreeViewer      treeViewer;
    private Label           pullOrderLabel;
    private ExportCsvAction exportAction;

    // -- ViewPart lifecycle --------------------------------------------------

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        // Header summary bar
        headerLabel = new Label(parent, SWT.NONE);
        headerLabel.setFont(JFaceResources.getBannerFont());
        headerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        headerLabel.setText("TR Analyser - no result yet");

        // Tree viewer for clusters and edges
        treeViewer = new TreeViewer(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        treeViewer.setContentProvider(new ClusterContentProvider());
        treeViewer.setLabelProvider(new ClusterLabelProvider());

        // Pull order section
        pullOrderLabel = new Label(parent, SWT.WRAP);
        pullOrderLabel.setFont(JFaceResources.getTextFont());
        pullOrderLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        // Toolbar
        buildToolBar();
    }

    @Override
    public void setFocus() {
        if (treeViewer != null && !treeViewer.getControl().isDisposed()) {
            treeViewer.getControl().setFocus();
        }
    }

    // -- Public API called by AnalyzeTRHandler -------------------------------

    /**
     * Renders an analysis result.
     * Safe to call from a background thread - marshals to UI thread internally.
     */
    public void showResult(final AnalysisResult result) {
        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                if (treeViewer == null || treeViewer.getControl().isDisposed()) {
                    return;
                }

                if (result.hasError()) {
                    showErrorInternal(result.errorMessage);
                    return;
                }

                headerLabel.setForeground(null);
                headerLabel.setText(String.format(
                    "TR: %s   |   Tasks: %d   Objects: %d   Cross-task edges: %d",
                    result.tr, result.taskCount, result.objectCount, result.edgeCount));

                treeViewer.setInput(result.clusters);
                treeViewer.expandAll();

                pullOrderLabel.setText(buildPullOrderText(result.pullOrder));
                pullOrderLabel.getParent().layout(true, true);

                if (exportAction != null) {
                    exportAction.setResult(result);
                }
            }
        });
    }

    public void showLoading(final String tr) {
        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                if (headerLabel == null || headerLabel.isDisposed()) {
                    return;
                }
                headerLabel.setForeground(null);
                headerLabel.setText("Analysing TR " + tr + " ...");
                treeViewer.setInput(null);
                pullOrderLabel.setText("");
                if (exportAction != null) {
                    exportAction.setResult(null);
                }
            }
        });
    }

    // -- Private helpers -----------------------------------------------------

    private void showErrorInternal(String message) {
        Color red = Display.getDefault().getSystemColor(SWT.COLOR_RED);
        headerLabel.setForeground(red);
        headerLabel.setText("Error: " + message);
        treeViewer.setInput(null);
        pullOrderLabel.setText("");
    }

    private void buildToolBar() {
        exportAction = new ExportCsvAction(getSite().getShell());

        IToolBarManager tb = getViewSite().getActionBars().getToolBarManager();
        tb.add(exportAction);
    }

    private static String buildPullOrderText(List<PullStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("-- Recommended Pull Order --\n");
        for (PullStep step : steps) {
            sb.append("  ").append(step.label()).append("\n");
        }
        return sb.toString();
    }

    // -- TreeViewer content provider -----------------------------------------

    private static final class ClusterContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object input) {
            if (input instanceof List) {
                return ((List<?>) input).toArray();
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent instanceof Cluster) {
                Cluster cluster = (Cluster) parent;
                Object[] children = new Object[1 + cluster.edges.size()];
                children[0] = new TasksNode(cluster.tasks);
                for (int i = 0; i < cluster.edges.size(); i++) {
                    children[i + 1] = cluster.edges.get(i);
                }
                return children;
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            if (element instanceof Cluster) {
                Cluster c = (Cluster) element;
                return !c.tasks.isEmpty() || !c.edges.isEmpty();
            }
            return false;
        }
    }

    /** Pseudo-node holding the list of tasks belonging to a cluster. */
    private static final class TasksNode {
        final List<String> tasks;
        TasksNode(List<String> tasks) {
            this.tasks = tasks;
        }
    }

    // -- TreeViewer label provider -------------------------------------------

    private static final class ClusterLabelProvider extends LabelProvider {
        @Override
        public String getText(Object element) {
            if (element instanceof Cluster) {
                Cluster c = (Cluster) element;
                return c.riskLabel() + "   Tasks: " + c.tasksSummary();
            }
            if (element instanceof TasksNode) {
                TasksNode n = (TasksNode) element;
                return "Tasks: " + String.join(", ", n.tasks);
            }
            if (element instanceof Edge) {
                Edge e = (Edge) element;
                return e.kind + ": " + e.detail
                     + "   [" + e.fromTask + " -> " + e.toTask + "]";
            }
            return element == null ? "" : element.toString();
        }
    }
}