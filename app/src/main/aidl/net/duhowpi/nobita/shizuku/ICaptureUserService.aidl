package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    void setCaptureDirectory(String path);
    String startCapture(String target, boolean saveRaw);
    String stopAndExport(String captureId);
    String exportFullCapture(String captureId);
    String getLastExportSummary();
    String getExportProgress();
    String getCaptureStatus();
    boolean hasPendingCapture(String captureId);
    void abortCapture(String captureId);
}
