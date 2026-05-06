package com.nousresearch.hermesagent.device;

interface IHermesPrivilegedShellService {
    String runCommand(String command, int timeoutSeconds);
}
