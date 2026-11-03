package com.example.forcedmulticore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class EntityPressureReport {
    final int totalEntities;
    final int looseEntities;
    final List<Map.Entry<String, Integer>> topTypes;

    EntityPressureReport(int totalEntities, int looseEntities, List<Map.Entry<String, Integer>> topTypes) {
        this.totalEntities = totalEntities;
        this.looseEntities = looseEntities;
        this.topTypes = topTypes;
    }

    static EntityPressureReport empty() {
        return new EntityPressureReport(0, 0, new ArrayList<Map.Entry<String, Integer>>());
    }

    String toHumanLine() {
        return "total=" + totalEntities
                + ", looseItemsOrXp=" + looseEntities
                + ", topTypes=" + formatTopTypes();
    }

    private String formatTopTypes() {
        if (topTypes.isEmpty()) {
            return "none";
        }

        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : topTypes) {
            parts.add(shortName(entry.getKey()) + "=" + entry.getValue());
        }
        return parts.toString();
    }

    static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            return name.substring(dot + 1);
        }
        return name;
    }
}
