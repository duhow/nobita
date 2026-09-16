package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    String startCapture(String target, boolean saveRaw);
    String stopAndExport(String captureId);
    String exportFullCapture(String captureId);
    String getLastExportSummary();
    String getExportProgress();
    String getCaptureStatus();
    boolean hasPendingCapture(String captureId);
    void abortCapture();
}
