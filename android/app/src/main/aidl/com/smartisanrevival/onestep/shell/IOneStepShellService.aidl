package com.smartisanrevival.onestep.shell;

interface IOneStepShellService {
    String runForOutput(in String[] cmd);
    android.os.ParcelFileDescriptor streamDevice(String path);
}
