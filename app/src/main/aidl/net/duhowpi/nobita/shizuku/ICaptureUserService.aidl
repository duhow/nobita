package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    String prepareCapture();
    String exportPcapng(String target, boolean saveRaw);
    void restoreCaptureEnvironment();
}
