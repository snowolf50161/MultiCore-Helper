package com.example.multicorehelper.paper;

import java.util.List;

/** Only immutable values cross the server-thread boundary. Never Bukkit objects. */
public record Snapshot(long capturedAtMillis, double tps, double mspt, int players,
                       long heapUsed, long heapMax, boolean truncated, List<WorldSample> worlds) {
    public Snapshot { worlds = List.copyOf(worlds); }
    public record WorldSample(String name, int chunks, int entities) { }
}
