package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    String startCapture(String target, boolean saveRaw);
    String stopAndExport();
    String exportFullCapture();
    String getLastExportSummary();
    String getExportProgress();
    String getCaptureStatus();
    boolean hasPendingCapture();
    void abortCapture();
}
