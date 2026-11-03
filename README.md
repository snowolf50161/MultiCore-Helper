# MultiCore Helper

Public source snapshot of MultiCore Helper by **snowolf50161**, organized by Minecraft version and loader. Each directory is an independent Gradle project, not one combined build.

## Projects

- Forge: 1.7.10, 1.12.2, 1.16.5 (directory `forced-multicore-forge-1.16.5`), 1.19.4, 1.20.1, 26.3
- Fabric: 1.21.1, 1.21.8, 26.2, 26.3
- NeoForge: 1.21.1, 26.3
- Paper plugin: 26.3 (not a client mod or Folia plugin)

Coffee Expansion and unrelated projects are not included.

## Building

Enter the desired project and use its Gradle wrapper (`./gradlew build`, or `gradlew.bat build` on Windows). Select the JDK required by that project's build file: legacy Forge generally requires Java 8; modern versions use Java 17, 21 or 25. Consult each project's README and build configuration. Dependencies require internet access; legacy toolchains may need additional setup. This publication does not claim all projects have been freshly rebuilt.

Source represents the local snapshot published on 2026-11-04; it is not a reconstruction of every historic CurseForge binary. Runtime worlds, logs, caches, credentials, third-party mod JARs and development test servers are excluded. Gradle wrapper JARs are build tooling, not bundled game mods.

## Safety

Background workers analyze snapshots; this is not a full multithreaded Minecraft engine. Back up worlds and test on a separate instance before deployment. Performance improvements depend on workload and are not guaranteed.

## License

Project code is provided under the MIT license in `LICENSE`. Retain third-party license notices, including any Gradle/loader template notices. See each project for implementation details.

[CurseForge project](https://www.curseforge.com/minecraft/mc-mods/multicore-helper)
