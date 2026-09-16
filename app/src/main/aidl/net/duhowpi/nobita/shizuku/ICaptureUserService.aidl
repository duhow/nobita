package net.duhowpi.nobita.shizuku;

interface ICaptureUserService {
    String prepareCapture();
    String exportBtsnoop();
    void restoreCaptureEnvironment();
}
