# MultiCore Helper NeoForge 1.21.1

Minecraft version: `1.21.1`
Loader: `NeoForge`
NeoForge version: `21.1.228`
Java version: `21`

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

Build with Java 21:

```text
gradlew.bat build
```

Output:

```text
build/libs/multicore-helper-neoforge-1.21.1-1.0.0.jar
```
