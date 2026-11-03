# MultiCore Helper Forge 1.20.1

Minecraft version: `1.20.1`
Forge version: `47.4.4`

This is the Forge 1.20.1 build of MultiCore Helper.

Features:

- Worker-thread background snapshot processing.
- Adaptive Helper worker count from 1 to the safe maximum according to batch load.
- F3 display for logical CPU count and Helper worker activity.
- Safe mode that leaves global ForkJoinPool and Netty settings unchanged.
- Entity pressure diagnostics.
- Manual cleanup for dropped items and XP orbs.
- CPU Core creative item and join-world advancement.

Commands:

```text
/multicorecpu status
/multicorecpu entities
/multicorecpu cleanup
```

Build with Java 17:

```bash
./gradlew build
```
