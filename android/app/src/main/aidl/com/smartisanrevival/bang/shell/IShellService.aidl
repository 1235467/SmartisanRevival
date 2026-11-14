package com.smartisanrevival.bang.shell;

interface IShellService {
    String runForOutput(in String[] cmd);
    android.os.ParcelFileDescriptor runStreaming(in String[] cmd);
    android.os.ParcelFileDescriptor streamDevice(String path);
    void injectMotion(String action, int x, int y);
    void stopStreaming();
}
