package com.example.forcedmulticore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BackgroundAnalysisResult {
    final int entityCount;
    final int looseEntities;
    final List<Map.Entry<String, Integer>> topTypes;

    BackgroundAnalysisResult(int entityCount, int looseEntities, List<Map.Entry<String, Integer>> topTypes) {
        this.entityCount = entityCount;
        this.looseEntities = looseEntities;
        this.topTypes = topTypes;
    }

    static BackgroundAnalysisResult empty() {
        return new BackgroundAnalysisResult(0, 0, new ArrayList<Map.Entry<String, Integer>>());
    }

    static BackgroundAnalysisResult fromPressureReport(EntityPressureReport report) {
        return new BackgroundAnalysisResult(report.totalEntities, report.looseEntities, report.topTypes);
    }

    String toHumanLine() {
        return "entities=" + entityCount
                + ", looseItemsOrXp=" + looseEntities
                + ", topTypes=" + formatTopTypes();
    }

    private String formatTopTypes() {
        if (topTypes.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : topTypes) {
            parts.add(EntityPressureReport.shortName(entry.getKey()) + "=" + entry.getValue());
        }
        return parts.toString();
    }
}
