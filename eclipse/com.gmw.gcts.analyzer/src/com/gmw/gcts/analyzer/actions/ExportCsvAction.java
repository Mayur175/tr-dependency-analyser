package com.gmw.gcts.analyzer.actions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import com.gmw.gcts.analyzer.model.AnalysisResult;
import com.gmw.gcts.analyzer.model.AnalysisResult.Cluster;
import com.gmw.gcts.analyzer.model.AnalysisResult.Edge;
import com.gmw.gcts.analyzer.model.AnalysisResult.PullStep;

/**
 * Toolbar action: exports the current analysis result as CSV (UTF-8, RFC 4180).
 *
 * Columns:
 *   TR, RUN_TIMESTAMP, SRC_TASK, SRC_OBJECT, TGT_TASK, TGT_OBJECT,
 *   KIND, RISK, DETAIL, PULL_STEP, PULL_ACTION
 *
 * Added to DependencyResultView toolbar - enabled after a successful analysis.
 */
public final class ExportCsvAction extends Action {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter ROW_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CSV_HEADER =
            "TR,RUN_TIMESTAMP,SRC_TASK,SRC_OBJECT,TGT_TASK,TGT_OBJECT," +
            "KIND,RISK,DETAIL,PULL_STEP,PULL_ACTION\n";

    private final Shell          shell;
    private       AnalysisResult result;

    public ExportCsvAction(Shell shell) {
        super("Export CSV");
        this.shell = shell;
        setToolTipText("Export analysis result as CSV (UTF-8, Excel-compatible)");
        setEnabled(false);
    }

    /** Called by DependencyResultView whenever a result is loaded or cleared. */
    public void setResult(AnalysisResult result) {
        this.result = result;
        setEnabled(result != null && !result.hasError());
    }

    @Override
    public void run() {
        if (result == null || result.hasError()) {
            return;
        }

        FileDialog dlg = new FileDialog(shell, SWT.SAVE);
        dlg.setFilterExtensions(new String[] { "*.csv", "*.*" });
        dlg.setFilterNames(new String[] { "CSV files (*.csv)", "All files (*.*)" });
        dlg.setFileName(result.tr + "_analysis_"
                        + LocalDateTime.now().format(FILE_TS) + ".csv");
        dlg.setOverwrite(true);

        String path = dlg.open();
        if (path == null) {
            return; // user cancelled
        }

        try {
            String csv = buildCsv(result);
            // BOM helps Excel detect UTF-8 on Windows.
            byte[] bom    = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
            byte[] body   = csv.getBytes(StandardCharsets.UTF_8);
            Path   target = Paths.get(path);
            try (BufferedWriter ignored = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                // close immediately - we use Files.write below for atomic write with BOM
            }
            byte[] out = new byte[bom.length + body.length];
            System.arraycopy(bom,  0, out, 0,           bom.length);
            System.arraycopy(body, 0, out, bom.length,  body.length);
            Files.write(target, out);

            MessageDialog.openInformation(shell, "TR Analyser - Export",
                "CSV saved to:\n" + path + "\n\n"
                + result.edgeCount + " dependency edge(s) exported.");

        } catch (IOException e) {
            MessageDialog.openError(shell, "TR Analyser - Export Failed",
                "Could not write CSV:\n" + e.getMessage());
        }
    }

    // -- CSV builder ---------------------------------------------------------

    private static String buildCsv(AnalysisResult r) {
        StringBuilder sb = new StringBuilder(CSV_HEADER);

        String ts = LocalDateTime.now().format(ROW_TS);

        // Map task -> (step, action) from the authoritative pull order.
        Map<String, int[]>  taskStep   = new HashMap<>();
        Map<String, String> taskAction = new HashMap<>();
        for (PullStep ps : r.pullOrder) {
            for (String task : ps.tasks) {
                taskStep.put(task,   new int[] { ps.step });
                taskAction.put(task, ps.action);
            }
        }

        for (Cluster cluster : r.clusters) {
            if (cluster.edges.isEmpty()) {
                // Independent tasks - one row per task with no edge detail.
                for (String task : cluster.tasks) {
                    int    step   = lookupStep(taskStep, task);
                    String action = taskAction.getOrDefault(task, "ALONE");
                    sb.append(row(r.tr, ts, task, "", "", "",
                                  "NONE", cluster.risk, "", step, action));
                }
            } else {
                for (Edge edge : cluster.edges) {
                    int    step   = lookupStep(taskStep, edge.fromTask);
                    String action = taskAction.getOrDefault(edge.fromTask, "ALONE");
                    sb.append(row(r.tr, ts,
                                  edge.fromTask, edge.from,
                                  edge.toTask,   edge.to,
                                  edge.kind,     cluster.risk,
                                  edge.detail,   step, action));
                }
            }
        }

        // Append pull order summary as comment lines.
        sb.append("\n# Pull Order Summary\n");
        sb.append("# STEP,ACTION,TASKS\n");
        for (PullStep ps : r.pullOrder) {
            sb.append("# ").append(ps.step).append(",")
              .append(esc(ps.action)).append(",")
              .append(esc(String.join(" + ", ps.tasks))).append("\n");
        }

        return sb.toString();
    }

    private static int lookupStep(Map<String, int[]> map, String task) {
        int[] v = map.get(task);
        return v == null ? 0 : v[0];
    }

    private static String row(String tr, String ts,
                              String srcTask, String srcObj,
                              String tgtTask, String tgtObj,
                              String kind,    String risk,
                              String detail,  int step, String action) {
        return esc(tr)      + "," +
               esc(ts)      + "," +
               esc(srcTask) + "," +
               esc(srcObj)  + "," +
               esc(tgtTask) + "," +
               esc(tgtObj)  + "," +
               esc(kind)    + "," +
               esc(risk)    + "," +
               esc(detail)  + "," +
               step         + "," +
               esc(action)  + "\n";
    }

    /** RFC 4180 CSV escaping - wrap in quotes, double any embedded quotes. */
    private static String esc(String val) {
        if (val == null) {
            return "\"\"";
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}