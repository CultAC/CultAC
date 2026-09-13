# CultAC

CultAC is an open source Minecraft anticheat designed to support the latest versions of Minecraft.

**This repository is a fork of upstream Grim.** It keeps the GPLv3 Grim 2.0-modern base
(checks, commands, config system, platform SPI) but replaces the PacketEvents dependency
with a raw-NMS (non-PacketEvents) networking pipeline, replaces the 2.0 prediction engine
with the 3.0 simulation engine written by DefineOutside in early 2023, and adds a dedicated Bedrock simulation engine bridged
through Geyser.

- Paper
- Java client support: 1.21.2+ via ViaVersion. 1.8-1.21.1 clients are "best effort" supported.
- Bedrock client support: via Geyser/Floodgate. Bedrock players are simulated by the
  Bedrock engine; they are no longer exempt from the anticheat.

## Compatibility
* Java 21 or higher
* Paper 1.21.2+, Spigot is unsupported.

Bedrock notes
* GeyserFloatingPoints must be installed as a Geyser addon if players are > 3000 blocks from 0,0

If you use a proxy such as Velocity or BungeeCord:
- Geyser and Floodgate must be on the backend server so we may read the bedrock client's packets
- If you use ViaVersion, it must be installed on the backend server (where Cult is) ONLY.
  Cult does not support having ViaVersion installed on the proxy, even if it is also installed on the backend.

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

## Compiling From Source

Install JDK 25 and Git, and set `JAVA_HOME` to the JDK 25 installation.

1. `git clone https://github.com/CultAC/CultAC.git`
2. `cd` into the cloned directory
3. `./gradlew build`
4. The final jar is at `bukkit/build/libs/`

Normal builds use the checked-in Bedrock collision catalog. Generator code is maintained
separately; [Geyser's mappings-generator](https://github.com/GeyserMC/mappings-generator) is the public upstream.

## Cult changes from Grim

* Dedicated simulation engine for bedrock players
  * Written and validated to 0.001 accuracy without exemption scenarios
    * Tridents, elytra, slime, honey, water, lava, bubble columns, etc. all match bedrock client
  * Machine generated based upon replaying bedrock packets sequences thousands of times, human validated
  * Listens to bedrock packets to increase accuracy
* Chunk section deduplication. Only one copy per unique chunk section is stored reducing memory usage in common areas.
  * Copy on write on shared chunk sections preserves packet-based blocks, version differences, and latency differences
  * Servers with many players in one area will see a substantial memory decrease
* NMS based packets rather than PacketEvents
* Lower allocation transaction scheduling
* Fork of Grim 3.0 engine
* Uses native collision from the server to reduce maintenance burden
* Uses native block placing logic to increase accuracy and reduce maintenance burden
* Uses client-provided inventory changes to increase accuracy and reduce maintenance burden
* Packet driven piston simulation
* FairReach ported from Grim 3.0 to limit latency abuse for reach
* Loss of Fabric and Spigot support. Fabric support may be re-added eventually

## What's not done
* Few additional checks compared to upstream Grim 2.0, the focus is on the bedrock and 3.0 simulation engine
* Extensive bedrock validation
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
