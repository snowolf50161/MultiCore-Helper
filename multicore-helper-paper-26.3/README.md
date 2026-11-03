# MultiCore Helper Paper 26.3 — 1.0.0

Independent Paper plugin, compiled against Paper API **26.3.build.218-stable**, Java **25**.
Existing Forge, Fabric and NeoForge projects are separate. Folia is unsupported (no folia-supported declaration).

## Build

Set JAVA_HOME to a Java 25 JDK, then run `./gradlew.bat clean test build`.
Output: `build/libs/multicore-helper-paper-26.3-1.0.0.jar`.

## Commands

- `/multicorehelper status` (alias `/mch status`): TPS, average MSPT, snapshot age, loaded chunks, entities, online player count, heap, worker state and optional integration presence.
- `/mch analyze`: capture a fresh snapshot and request bounded background analysis.
- `/mch save`: explicit synchronous save of players and worlds; may pause ticks while disk writes complete.
- Requires `multicorehelper.admin` (operators by default); also works in console.

## Thread and performance boundaries

Paper scheduler captures only immutable world totals and primitive values on the main thread, normally every 200 ticks (minimum 100). No per-entity traversal, chunk loading, player identity capture, entity removal or world mutation occurs during monitoring. Up to 64 worlds are sampled by default; truncated samples are explicitly marked. Background analysis totals counts, identifies the world with the most entities and flags TPS/MSPT pressure; these are diagnostics, not a measured FPS/TPS improvement or proof of the cause of lag.

One private low-priority daemon worker accepts at most one analysis; overlapping requests are dropped. There are no global ForkJoinPool/Netty/JVM changes. Disabling cancels sampling, rejects submissions and interrupts/awaits the worker (bounded to one second). Normal server `stop` remains Paper-owned, including world saving. Never use `/reload` as a backup strategy.

Geyser-Spigot and Floodgate are optional soft dependencies. Monitoring does not require a client mod, inspect player names/UUID formats, change authentication, send custom packets or depend on their internal APIs. Both Java and Bedrock players count as ordinary online players. Integration detection is not proof of an end-to-end Bedrock login test.

## Isolated testing and cold backups

Only the `test-server` inside this project is used. The tools do not install to or operate on `C:\Minecraft-Lifetime-World`.
The test server binds to **127.0.0.1:25585**, retains online authentication and disables RCON/query.
Review Minecraft's EULA and set `test-server/eula.txt` yourself before a first world-start test if it is not already accepted.

1. Start the isolated server using `test-server.ps1` (interactive console; writes its own server.pid).
2. Run `mch analyze`, `mch status`, `mch save`, then `stop` in its console.
3. Wait for full process exit, then run `./cold-backup.ps1`.

Cold backup refuses live recorded PIDs and world session locks, rejects linked paths, and only accepts a directory named `multicore-helper-paper-26.3/test-server`. World locks remain held during archiving to prevent normal world restarts. Do not start a server concurrently with backup. ZIP files and per-file/archive SHA-256 manifests go to `backups/`; every archived entry is read back and hashed before the ZIP is committed. Runtime caches, libraries, server JAR, logs, PID and session locks are excluded; world data, plugin state and configuration are included. Keep the exact Paper JAR separately for restoration. Failures leave `.partial` archives for inspection.

Restore only into a **new independent directory**; verify hashes first. Never restore over a live world. These are same-disk test backups, not protection against disk failure; copy verified archives to another physical disk for disaster recovery.

## Verification — 2026-10-24

- `clean test build`: 7 tests passed, covering immutable snapshots, pressure calculation, bounded work, shutdown rejection, running-process/locked-world backup refusal and archive restoration.
- Real Paper 26.3 build 218, Java 25.0.4: plugin startup, `analyze`, `status`, `save` and normal `stop` passed without Geyser/Floodgate.
- Same checks passed with official Geyser 2.12.0 b1270 and Floodgate 2.2.5 b141 present. Both listeners were local-only. Actual Java/Bedrock player login and long-duration load were not tested.
- Two invulnerable, AI-disabled villagers were created only in the isolated world; monitoring reported 25 loaded chunks and 2 entities.
- Live-world cold backup was refused. After normal process exit, 103 files were archived, read back, restored into `restore-verification` and SHA-256 checked. The independent restored Paper server booted, loaded both villagers and all three plugins, reported 25 chunks/2 entities, then stopped with `worker terminated=true`.
- Paper emitted existing Windows OSHI performance-counter warnings; monitoring continued. Explicit world saving triggers Paper's expected manual-save performance warning. Geyser emitted a future NetherNet migration notice; no network/authentication settings on any production server were changed.

Official references: [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/), [Paper scheduling](https://docs.papermc.io/paper/dev/scheduler/), [Paper builds](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds), [Geyser downloads](https://geysermc.org/download/).
