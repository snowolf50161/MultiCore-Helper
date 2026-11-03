# MultiCore Helper Fabric 26.2

Minecraft version: `26.2`
Loader: `Fabric`
Fabric Loader: `0.19.3`
Fabric API tested against: `0.155.2+26.2`

This is the Fabric build of MultiCore Helper. It keeps the same conservative design as the Forge version:

- Worker-thread background snapshot processing.
- Adaptive Helper worker count from 1 to the safe maximum according to batch load.
- F3 display for logical CPU count and Helper worker activity.
- Safe mode that leaves global ForkJoinPool and Netty settings unchanged.
- Entity pressure diagnostics.
- Manual cleanup for dropped items and XP orbs.
- CPU Core creative item and join-world advancement.
- Sodium/Lithium cooperative assist mode.

## Compatibility

When Sodium or Lithium is detected, MultiCore Helper does not modify their systems.

- Sodium detected: rendering pipeline is left untouched.
- Lithium detected: world/entity tick logic is left untouched.
- MultiCore Helper continues to run background worker tasks and entity pressure tools.

## Commands

```text
/multicorecpu status
/multicorecpu entities
/multicorecpu cleanup
```

## Build

Use Java 25:

```bash
./gradlew build
```

The built jar is in:

```text
build/libs/multicore-helper-fabric-26.2-1.0.0.jar
```
