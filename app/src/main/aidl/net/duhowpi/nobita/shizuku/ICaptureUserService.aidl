package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    String prepareCapture();
    String exportPcapng(String target, boolean saveRaw, String previousMode, String previousDefaultMode, boolean propertyModeChanged, boolean bluetoothInitiallyEnabled);
    String exportFullCapture(String previousMode, String previousDefaultMode, boolean propertyModeChanged, boolean bluetoothInitiallyEnabled);
    String getLastExportSummary();
    String getExportProgress();
    boolean hasPendingCapture();
    void restoreCaptureEnvironment(String previousMode, String previousDefaultMode, boolean propertyModeChanged, boolean bluetoothInitiallyEnabled);
    void abortCapture();
}
