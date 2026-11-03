package com.example.forcedmulticore;

import java.util.Locale;

final class TuningReport {
    final boolean enabled;
    final int detectedProcessors;
    final int workerThreads;
    final String detail;

    TuningReport(boolean enabled, int detectedProcessors, int workerThreads, String detail) {
        this.enabled = enabled;
        this.detectedProcessors = detectedProcessors;
        this.workerThreads = workerThreads;
        this.detail = detail;
    }

    static TuningReport notApplied() {
        return new TuningReport(false, Math.max(1, Runtime.getRuntime().availableProcessors()), 0, "not applied yet");
    }

    String toLogLine() {
        return String.format(Locale.ROOT, "enabled=%s, detectedProcessors=%d, workerThreads=%d, detail=%s",
                enabled, detectedProcessors, workerThreads, detail);
    }

    String toHumanLine() {
        if (!enabled) {
            return String.format(Locale.ROOT, "disabled, detected CPU threads: %d, detail: %s", detectedProcessors, detail);
        }
        return String.format(Locale.ROOT, "enabled, detected CPU threads: %d, requested worker threads: %d, detail: %s",
                detectedProcessors, workerThreads, detail);
    }
}
