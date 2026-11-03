# MultiCore Helper Paper 26.3 1.0.0

- Initial standalone Paper plugin for Minecraft 26.3, Paper API 26.3 and Java 25.
- Added administrator status monitoring and on-demand immutable snapshot analysis.
- Bukkit data capture stays on the main thread; background analysis uses one private bounded worker.
- Added TPS/MSPT, entity/chunk totals, heap and worker diagnostics, including snapshot age and truncation.
- Added optional Geyser/Floodgate presence detection without client-mod, UUID, authentication or packet assumptions.
- Added explicit main-thread save command, clean worker shutdown and isolated test-server cold backup tool with lock checks and SHA-256 read-back verification.
- Automatic entity cleanup and global thread-pool tuning are absent in this release.
