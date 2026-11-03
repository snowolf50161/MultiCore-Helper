# MultiCore Helper — Forge 1.12.2

Safe adaptive CPU helper for Minecraft Forge 1.12.2.

- Uses a bounded, low-priority adaptive worker pool (1–4 threads).
- Never changes the global ForkJoinPool or Netty thread settings.
- Captures world data on the server thread and analyzes snapshots in the background.
- Reports entity pressure and can clean loose item/XP entities by command.
- Adds optional F3 CPU information, toggled with a configurable key (F8 by default).
- Includes the CPU Core item, advancement, icon, and English/Traditional Chinese/Simplified Chinese translations.

Commands: `/multicorecpu status`, `/multicorecpu entities`, `/multicorecpu cleanup`.
