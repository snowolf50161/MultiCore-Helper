# Forced MultiCore CPU for Forge 1.16.5

Minecraft version: `1.16.5`
Forge version: `36.2.33`

This mod starts a real worker thread pool and dispatches server-side background work to multiple threads.

It currently does two things:

- Applies JVM and networking thread hints where Java/Netty already support parallel execution.
- Runs a real multi-threaded entity snapshot analysis on `ForcedMultiCore-Worker-*` threads.
- Watches entity pressure and can clean dropped items / XP orbs when entity count gets dangerous.

Important: this version does not directly tick vanilla entities or blocks on worker threads. Minecraft 1.16.5 world state is not thread-safe, so this mod captures immutable snapshots on the server thread and processes those snapshots in parallel. Direct parallel entity ticking is possible only as a much riskier coremod/mixin patch and can break worlds or other mods.

## Build

Install JDK 8, then run:

```bash
./gradlew build
```

The built jar will be in:

```text
build/libs/forced-multicore-cpu-forge-1.16.5-1.0.0.jar
```

## Config

After first launch, edit:

```text
config/forcedmulticore-common.toml
```

Useful defaults:

- `enabled = true`
- `workerThreads = 0` uses detected CPU cores automatically.
- `reserveThreadsForSystem = 1` keeps at least one logical CPU free.
- `parallelEntityAnalysis = true` enables real worker-thread dispatch.
- `analysisIntervalTicks = 20` dispatches parallel work about once per second.
- `entityBatchSize = 256` controls how entities are split between worker tasks.
- `entityPressureGuard = true` logs warnings when loaded entity count gets high.
- `autoCleanupLooseEntities = false` keeps automatic item/XP cleanup off until you choose to enable it.
- `entityWarningThreshold = 2500` logs a warning at this loaded entity count.
- `entityEmergencyThreshold = 5000` starts automatic loose entity cleanup when enabled.

## Command

In game or server console:

```text
/multicorecpu status
```

The status command shows worker thread count, active jobs, completed jobs, processed snapshots, and the latest parallel result.

Entity pressure commands:

```text
/multicorecpu entities
/multicorecpu cleanup
```

`entities` shows total loaded entities and the most common entity types. `cleanup` removes dropped items and XP orbs up to the configured manual limit.
