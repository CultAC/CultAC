# CultAC

CultAC is an open source Minecraft anticheat designed to support the latest versions of Minecraft.

**This repository is a fork of upstream Grim.** It keeps the GPLv3 Grim 2.0-modern base
(checks, commands, config system, platform SPI) but replaces the PacketEvents dependency
with a custom bytebuffer-based networking pipeline, replaces the 2.0 prediction engine
with the 3.0 simulation engine written by DefineOutside in early 2023, and adds a dedicated Bedrock simulation engine bridged
through Geyser.

Discord: https://discord.gg/nyeVp7UFau

## Compatibility


* Java client support: 1.21.2 though 26.3
  * Pre-1.21.2 players will be disconnected if ViaVersion is on the backend and Cult is on the proxy
  * Pre-1.21.2 player support is deprecated
* Paper 1.21.2, 1.21.11, 26.2, or 26.3.
* Velocity; see the [CultAC Companion](paper-companion/README.md) for backend servers behind the Velocity plugin

Bedrock notes
* GeyserFloatingPoints must be installed as a Geyser addon if players are > 3000 blocks from 0,0
* Cult must be installed on the proxy if using Geyser on the proxy

## Pull Requests

See [Contributing](CONTRIBUTING.md) for more information about contributing and what our guidelines
are.

## Developer Plugin API

CultAC preserves the published [GrimAPI](https://github.com/GrimAnticheat/GrimAPI)
packages, types, methods, Bukkit services, and events. Plugins compiled against
GrimAPI can keep their dependency on `GrimAC`; CultAC provides that plugin alias.
The implementation lives under `ac.cult.cultac`, while the external API stays under
`ac.grim.grimac.api`.

## Commands and configuration

`/cult` is the primary command. `/cultac`, `/grim`, and `/grimac` are aliases.
Permissions and placeholders use `cult` (for example, `cult.alerts` and `%cult_ping%`).
CultAC loads configuration from `plugins/CultAC/`. When upgrading an existing
installation, move your configuration/data directory from `plugins/GrimAC/` to
`plugins/CultAC/`, update `grim.*` permissions and `%grim_*%` placeholders, and
retain your configured database names and table mappings to keep existing data.
New defaults use CultAC branding. GrimAPI's logical storage IDs and legacy
migration schema names remain unchanged for compatibility.

CultAC can upload captured play to an intake service for analysis. The upload is off until configured;
[Cloud upload](docs/cloud-upload.md) says what it sends, how to configure it and what it costs.

Set `observe-only: true` in `config.yml` to flag and alert without changing gameplay: no punishment commands,
kicks or setbacks, no cancelled or rewritten packets, no block or inventory resyncs and no health spoofing.
Teleports the server sends are left to the server, even when the client ends up elsewhere. A player who
answers no ping within `max-transaction-time` is let go instead of disconnected, and on Velocity a client on
an unsupported version, a Bedrock client without Geyser, or a player whose connection cannot be attached joins
uninspected. The server receives every packet exactly as the client sent it: what a check, crash protection,
movement integrity, `max-clicks-per-tick` or the plugin message limit would cancel, rewrite or drop is still
inspected and flagged, then forwarded unchanged, and a client past `packet-spam-threshold` is logged once
instead of disconnected. Observe-only still does the following:

* CultAC sends its own pings, bundled with the packets they follow, and withholds the pong that answers one;
* a non-finite rotation from the server is sent as 0, and the player's own pose metadata is stripped;
* the Bedrock movement bridge, including disconnecting a session whose bridge hooks fail;
* while staff `/cult spectate`, their spectator game mode is hidden from the tab list, and staff
  `/cult spectate` and `/cult debug` actions run;
* on Velocity, client plugin messages on `cultac:bridge` are dropped.

## Updating without a restart

Install `bukkit/build/libs/CultAC-paper.jar` on Paper or
`velocity-bootstrap/build/libs/CultAC-velocity.jar` on Velocity once to enable the loader.
The native plugin stays loaded while each anticheat generation runs in its own classloader.

1. Copy a compatible plugin release or runtime jar into the plugin data directory's `updates/` folder
   (`plugins/CultAC/updates/` on Paper, `plugins/cultac/updates/` on Velocity).
2. Run `/cult update <filename.jar>` with `cult.update` permission.
3. Run `/cult runtimes` with `cult.runtimes` permission to see current and draining generations.

## Compiling From Source

Install JDK 25 and Git, and set `JAVA_HOME` to the JDK 25 installation.

1. `git clone https://github.com/CultAC/CultAC.git`
2. `cd` into the cloned directory
3. `./gradlew shadowJar`
4. The final jar is at `bukkit/build/libs/`

Normal builds use the checked-in Bedrock collision catalog. Generator code is maintained
separately; [Geyser's mappings-generator](https://github.com/GeyserMC/mappings-generator) is the public upstream.

## Cult changes from Grim

* Dedicated simulation engine for bedrock players
  * Written and validated to 0.001 accuracy without exemption scenarios
    * Tridents, elytra, slime, honey, water, lava, bubble columns, etc. all match bedrock client
  * Machine generated based upon replaying bedrock packets sequences thousands of times, human validated
  * Listens to bedrock packets directly to increase accuracy
  * Supports client-sided bedrock vehicles such as boats and horses
* Chunk section deduplication. Only one copy per unique chunk section is stored reducing memory usage in common areas.
  * Copy on write on shared chunk sections preserves packet-based blocks, version differences, and latency differences
  * Servers with many players in one area will see a substantial memory decrease
* Bytebuffer based packets rather than PacketEvents
* Lower allocation transaction scheduling
* Lower allocation collision handling
* Fork of Grim 3.0 engine
* Packet driven piston simulation
* FairReach ported from Grim 3.0 to limit latency abuse for reach
* Loss of Fabric and Spigot support. Fabric support may be re-added eventually

## Major added checks from 2.0
- Added visibility checks to prevent hitting through walls, placing through walls, and digging through walls
- Added more resilient item use NoSlow checks
- Added a KeepSprint check

## What's not done
* Few additional checks compared to upstream Grim 2.0, the focus is on the bedrock and 3.0 simulation engine
* Extensive bedrock validation
* Some bedrock actions such as riptide aren't perfect, and some feature from the most recent versions of the game aren't implemented
* Reach support for bedrock players. The basis is the same as Java, but Geyser and Bedrock clients change a bunch of small things.
* Validation for non-latest bedrock versions.
* Validation on a variety of bedrock platforms, the primary target was bedrock clients running on Android via Linux
* Validation for bedrock block placing, breaking, interactions etc, especially on touchscreen

## Cult Supremacy

What makes Cult stand out against other anticheats?

### Movement Simulation Engine

* We have a 1:1 replication of the player's possible movements
    * This covers everything from basic walking, swimming, knockback, cobwebs, to bubble columns
    * It even covers riding entities from boats to pigs to striders
* Built upon covering edge cases to confirm accuracy
* The order of collisions depends on the client version and is correct
* Accounts for minor bounding box differences between client versions, for example:
    * Blocks that do not exist in the client's version use ViaVersion's replacement block
    * Block data that cannot be translated to previous versions is replaced correctly
    * All vanilla collision boxes have been implemented
* Bedrock players are simulated by a dedicated Bedrock engine validated against
  vanilla Bedrock client/server behavior, not exempted

### Fully asynchronous and multithreaded design

* All movement checks and the overwhelming majority of listeners run on the Netty thread
* Velocity decodes consumed values with stateless, isolated Via types for the protocol at its physical codecs.
  Java connections use their channel event loop; Bedrock connections use Geyser's tick loop.
* Paper and Velocity share a pinned, isolated ViaVersion/ViaBackwards library for numeric mappings and tag rewrites.
  It uses Via's directed paths and tag handlers without private player connections or exported rewrite tables.
* Vanilla holders keep their bootstrap defaults. Immutable connection snapshots supply tags and item defaults,
  and the isolated placement runtime reads tags from each request without a shared model lock.
* Older Velocity protocols resolve dynamic registry entries by name against the 26.3 defaults, while preserving
  received dimension types. Custom dynamic entries unavailable in those defaults are logged once.
* The anticheat can scale to many hundreds of players, if not more
* Thread safety is carefully thought out
* The next core allows for this design

### Full world replication

* The anticheat keeps a replica of the world for each player
* The replica is created by listening to chunk data packets, block places, and block changes
* On all versions, chunks are compressed to 16-64 kb per chunk using palettes
* Using this cache, the anticheat can safely access the world state
* Per player, the cache allows for multithreaded design
* Sending players fake blocks with packets is safe and does not lead to falses
* The world is recreated and deduplicated for each player to allow lag compensation
* Client sided blocks cause no issues with packet based blocks. Block glitching does not false the
  anticheat.

### Latency compensation

* World changes are queued until they reach the player
* This means breaking blocks under a player does not false the anticheat
* Everything from flying status to movement speed will be latency compensated

### Inventory compensation

* The player's inventory is tracked to prevent ghost blocks at high latency, and other errors

### Secure by design, not obscurity

* All systems are designed to be highly secure and mathematically impossible to bypass
* For example, the prediction engine knows all possible movements and cannot be bypassed
