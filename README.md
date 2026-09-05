# CultAC

CultAC is an open source Minecraft anticheat designed to support the latest versions of Minecraft.

**This repository is a fork of upstream Grim.** It keeps the GPLv3 Grim 2.0-modern base
(checks, commands, config system, platform SPI) but replaces the PacketEvents dependency
with a raw-NMS (non-PacketEvents) networking pipeline, replaces the 2.0 prediction engine
with the 3.0 simulation engine, and adds a dedicated Bedrock simulation engine bridged
through Geyser. See [NOTICE.port](NOTICE.port) for the port's licensing and attribution
statement and [PORTING.md](PORTING.md) for the provenance index.

- Bukkit/Paper only; the Fabric modules were removed in this fork.
- Server support: 1.21.2+ (primary target Paper 26.2).
- Java client support: 1.21.2+ via ViaVersion.
- Bedrock client support: via Geyser/Floodgate. Bedrock players are simulated by the
  Bedrock engine; they are no longer exempt from the anticheat.

## Downloads

This fork is not published to Modrinth or Hangar; build it from source (see below).
Upstream Grim releases are on [Modrinth](https://modrinth.com/plugin/grimac).

## Requirements & Installation

- Java 21 or higher.
- A Bukkit-platform server (Spigot, Paper, or Folia) running 1.21.2+. Paper 26.2 is the
  primary and tested target.

If you use a proxy such as Velocity or BungeeCord:
- If you use Geyser, Floodgate must be installed on the backend server (where Cult is) so Cult can access the Floodgate API.
- If you use ViaVersion, it must be installed on the backend server (where Cult is) ONLY.
  Cult does not support having ViaVersion installed on the proxy, even if it is also installed on the backend.

## Resources

- For upstream documentation and examples visit the [Wiki](https://github.com/GrimAnticheat/Grim/wiki).
- For upstream answers to commonly asked questions visit the [FAQ](https://github.com/GrimAnticheat/Grim/wiki/FAQ).
- For community support and project discussion join upstream Grim’s [Discord](https://discord.grim.ac).

## Pull Requests

See [Contributing](CONTRIBUTING.md) for more information about contributing and what our guidelines
are.

## Developer Plugin API

CultAC preserves the published [GrimAPI](https://github.com/GrimAnticheat/GrimAPI)
packages, types, methods, Bukkit services, and events. Plugins compiled against
GrimAPI can keep their dependency on `GrimAC`; CultAC provides that plugin alias.
The implementation lives under `ac.cult.cultac`, while the external API stays under
`ac.grim.grimac.api`.

The [compatibility probe](validation/grimapi-compat/README.md) verifies this using
an independently compiled Bukkit plugin.

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

1. `git clone <this repository>`
2. `cd` into the cloned directory
3. `./gradlew build`
4. The final jar is at `bukkit/build/libs/`

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
* The world is recreated for each player to allow lag compensation
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
