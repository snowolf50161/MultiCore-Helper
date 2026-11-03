package com.example.multicorehelper.paper;

public final class Analysis {
    private Analysis() { }
    public static Report analyze(Snapshot sample) {
        long chunks = 0, entities = 0;
        String busiest = "none";
        int highest = -1;
        for (var world : sample.worlds()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            chunks += world.chunks();
            entities += world.entities();
            if (world.entities() > highest) { highest = world.entities(); busiest = world.name(); }
        }
        return new Report(sample, chunks, entities, busiest, sample.mspt() > 50 || sample.tps() < 18);
    }
    public record Report(Snapshot snapshot, long chunks, long entities, String busiestWorld, boolean overloaded) { }
}
