package com.example.multicorehelper.paper;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {
    @Test void snapshotOwnsItsWorldDataAndAnalysisTotalsAreCorrect() {
        var worlds = new ArrayList<>(List.of(new Snapshot.WorldSample("a", 10, 100),
                new Snapshot.WorldSample("b", 20, 250)));
        var sample = new Snapshot(1, 19.5, 42, 2, 100, 200, false, worlds);
        worlds.clear();
        assertThrows(UnsupportedOperationException.class, () -> sample.worlds().clear());
        var report = Analysis.analyze(sample);
        assertEquals(30, report.chunks());
        assertEquals(350, report.entities());
        assertEquals("b", report.busiestWorld());
        assertFalse(report.overloaded());
    }
    @Test void tickPressureIsDetectedWithoutChangingSnapshot() {
        var sample = new Snapshot(2, 15, 75, 0, 1, 2, true, List.of());
        assertTrue(Analysis.analyze(sample).overloaded());
        assertTrue(sample.truncated());
    }
    @Test void workerDropsOverlappingWorkAndRejectsAfterStop() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AnalysisWorker(s -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Analysis.analyze(s);
        });
        var sample = new Snapshot(1, 20, 1, 0, 0, 1, false, List.of());
        try {
            assertTrue(worker.submit(sample));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(worker.submit(sample));
            assertEquals(1, worker.skipped());
        } finally { release.countDown(); worker.close(); }
        assertTrue(worker.terminated());
        assertFalse(worker.submit(sample));
    }
}
