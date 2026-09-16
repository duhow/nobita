package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    String prepareCapture();
    String exportPcapng(String target, boolean saveRaw, String previousMode, boolean bluetoothInitiallyEnabled);
    void restoreCaptureEnvironment(String previousMode, boolean bluetoothInitiallyEnabled);
    void abortCapture();
}
