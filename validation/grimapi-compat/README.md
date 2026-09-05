# GrimAPI compatibility probe

This independent Bukkit plugin compiles against published GrimAPI 1.6.0.9. It has
no CultAC implementation dependency and bundles no API classes. Its unchanged
`depend: [GrimAC]` also verifies CultAC's provided plugin alias.

From the repository root:

```sh
./gradlew -p validation/grimapi-compat jar
```

Copy `validation/grimapi-compat/build/libs/GrimApiProbe.jar` alongside the anticheat
jar into an isolated Paper server's `plugins/` directory. Start the server and run
`cult reload` (or `grim reload` against either product). Expect all three markers:

- `GRIM_API_PROBE_ENABLE_PASS`: synchronous/asynchronous providers, Bukkit service
  identity, version, plugin resolver, alert manager, event bus, and backend registry.
- `GRIM_API_PROBE_TYPED_RELOAD_PASS`: the current typed event interface.
- `GRIM_API_PROBE_BUKKIT_RELOAD_PASS`: the deprecated Bukkit event interface.

To verify binary compatibility, compile once and use the exact same jar before
and after rebranding. A warning about the deprecated Bukkit event is expected.
