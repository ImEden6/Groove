# Groove

Minecraft music workstation backend, targeting Fabric / Minecraft 1.21.1 and Java 21.

The backend includes validated patch graphs, live cycle scheduling, Minecraft
audio streaming, and a server-authoritative shared music session. Run `/groove play`
in a world with cheats/operator permissions to start the demo.

See [backend usage](docs/BACKEND-USAGE.md) for commands, patch JSON, timing guarantees,
and tests, [samples and packs](docs/SAMPLES.md) for WAV/OGG playback and auditioning,
[the backend plan](docs/BACKEND-PLAN.md) for milestones, and
[future work](docs/FUTURE-WORK.md) for what's not implemented yet.

Run the engine without downloading Minecraft:

```powershell
.\gradlew.bat -p core-engine check
.\gradlew.bat -p core-engine renderDemo
```

The demo is written to `core-engine/build/demo.wav`. Full mod build:

```powershell
.\gradlew.bat build
```

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
