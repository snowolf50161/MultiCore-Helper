# MultiCore Helper — Forge 1.19.4

Version 1.0.1. Author: snowolf50161. Java 17, Minecraft 1.19.4, Forge 45.1.0 through 45.x. Independent port of the Forge 1.20.1 safe-mode branch; not Fabric, NeoForge, Paper or Folia.

## Retained features

- Low-priority bounded background entity snapshot analysis; world access remains on the server thread.
- Adaptive worker target, maximum four Helper workers. No global ForkJoin/Netty tuning.
- Configurable F8 key toggles Helper information in the F3 debug display (this port retains the existing debug-display behavior, not a new movable HUD).
- Status and entity-pressure commands: `/multicorecpu status`, `/multicorecpu entities`. Permission-level-2 `/multicorecpu cleanup` deletes loose items and XP orbs; use intentionally.
- Automatic cleanup remains disabled by default.
- Player-owned TNT advancements and the CPU core creative-tab icon; English, Traditional and Simplified Chinese resources.
- Optional client update check filtered to Forge 1.19.4; no automatic mod installation.

## Build

Select a Java 17 JDK, then `./gradlew.bat build --max-workers=2`.
Release: `build/libs/multicore-helper-forge-1.19.4-1.0.1.jar`. Previous 1.0.0 is retained.

## Port-specific changes

Forge 1.19.4 uses `CreativeModeTabEvent.Register` rather than the 1.20.1 creative-tab registry. Command success messages accept `Component` directly. Resource pack format is 12. Minecraft dependency is restricted to `[1.19.4,1.20)`.

Only the project-local `run` directory is used for tests. No existing game installation or production world is modified. The user explicitly accepted Minecraft EULA solely for this isolated Forge 1.19.4 test server on 2026-10-29. Runtime verification results are recorded separately; compilation alone does not establish gameplay compatibility or performance improvement.
