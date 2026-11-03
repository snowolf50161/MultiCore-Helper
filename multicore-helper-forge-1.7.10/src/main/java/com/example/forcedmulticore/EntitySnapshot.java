package com.example.forcedmulticore;

final class EntitySnapshot {
    final String typeName;
    final boolean loose;

    EntitySnapshot(String typeName, boolean loose) {
        this.typeName = typeName;
        this.loose = loose;
    }
}
