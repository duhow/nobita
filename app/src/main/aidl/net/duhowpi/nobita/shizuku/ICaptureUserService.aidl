package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    String startCapture(String target, boolean saveRaw);
    String stopAndExport();
    String exportFullCapture();
    String getLastExportSummary();
    String getExportProgress();
    boolean hasPendingCapture();
    void abortCapture();
}
