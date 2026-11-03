# MultiCore Helper forge 1.19.4 1.0.1

- Fixed an unnecessarily restrictive Forge requirement that prevented loading in existing Forge 45.1.0 modpacks.
- Built against Minecraft 1.19.4 / Forge 45.1.0 / Java 17; the accepted Forge range is now 45.1.0 through 45.x.
- Retained existing features, icons, translations, safe worker behavior and author attribution.
- Added a regression test for the reported modpack's loader version and rejection of other Forge major branches.

This change addresses the confirmed loader-dependency failure. It does not add a global access transformer, modify Oculus classes, or claim to fix unrelated shader rendering errors.
