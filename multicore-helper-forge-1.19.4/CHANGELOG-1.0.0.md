# MultiCore Helper forge 1.19.4 1.0.0

- Initial Minecraft 1.19.4 Forge release, targeting Forge 45.4.0 and Java 17.
- Ported safe background snapshot analysis, bounded adaptive workers and shutdown-submission protection.
- Retained configurable F8 debug-information toggle, status/entity-pressure commands and optional client update notifications.
- Retained player-triggered TNT advancements, CPU core icon and English/Chinese translations.
- Adapted creative-tab registration and command feedback to the Forge 1.19.4 APIs.
- Automatic loose-entity cleanup is disabled by default; global ForkJoin/Netty tuning stays disabled.
- Correct author attribution: snowolf50161.

This mod does not turn Minecraft world ticking into a fully parallel engine and does not claim to repair shader/OpenGL errors.
