# CultAC Companion (Paper)

A small Paper plugin for backend servers behind the CultAC Velocity proxy plugin. It runs no checks. It gives the
proxy the server state and server actions it cannot get from packets, serves the Grim API to plugins on the
backend, and forwards the `/cult` commands to the proxy.

The proxy works without it ("proxy-only" mode); the companion turns on the features listed below for the servers
it is installed on.

## Prerequisites

- The proxy jar (`CultAC-velocity.jar`) is built against the Velocity 3.4.0 API for Java 21, and loads on Velocity
  3.5.1 and 4.2.0.
- The companion is built and tested against Paper 26.3. The Paper versions in the [root README](../README.md) apply to
  the standalone CultAC plugin, not to the companion.

## Install

1. Put `CultAC-velocity.jar` on the proxy and `CultAC-companion.jar` on each backend. Do not also install CultAC or
   Grim on the backend; the companion refuses to start next to them. Take both jars from the same release: a proxy
   and a companion that speak different bridge protocol versions refuse to bridge.
2. Use Velocity modern forwarding. The bridge handshake is authenticated in both directions with the forwarding
   secret the proxy and the backends already share; read [Security](#security) before relying on that alone.
   - To use a different secret, set `bridge.secret` in the proxy's CultAC config and `secret` in
     `plugins/CultAC-Companion/config.yml` to the same long random value (at least 32 characters). A companion
     `secret` also works on backends that don't use modern forwarding, and a proxy that doesn't use modern forwarding
     needs `bridge.secret`.
3. Check the logs: the backend prints `Bridge enabled ...; waiting for the CultAC proxy`, and the proxy prints
   `CultAC bridge active on <server>` with the server's capabilities when the first player joins it. A backend
   without the companion is logged once as `No CultAC companion answered on <server>; server-side features stay off
   there until a later join reaches one`.

`debug: true` in the companion config logs the bridge handshake and the bridge messages the backend receives.

## Security

- Only the handshake is authenticated; the frames after it are not signed. Their integrity rests on the proxy, which
  drops `cultac:bridge` payloads sent by clients in a network handler right after Velocity decodes them; CultAC's
  packet pipeline strips them too, as a second layer. Every route to a backend running the companion must therefore
  pass through a proxy running CultAC.
- With the forwarding secret, every backend that shares it can bridge. Any plugin on a bridged backend can then run
  CultAC commands as the proxy console (including `/cult reload` and `/cult sendalert`) or as any player connected to
  that backend with that player's proxy permissions, cancel flags and punishments for players on that backend, and
  see the checks and flags of those players. The loader's `/cult update`, `/cult devswap` and `/cult runtimes` are
  refused from a backend for every sender. Set a separate `bridge.secret` (step 2) to limit bridging to the
  backends you configure with it. Every backend the proxy connects to receives a handshake it can check guesses
  against offline, so that secret must be long and random; both sides warn when it is shorter than 16 bytes.
- The proxy limits the rate of command, suggestion and alert-toggle messages from each bridged connection, with
  separate budgets for the console and for players. A command over the limit is answered with an error, and an alert
  toggle over the limit is not applied and gets the proxy's current state back.
- Alert, verbose and brand toggles from a backend (the Grim `AlertManager`) turn a setting on only when the player
  holds the matching proxy permission (`cult.alerts`, `cult.verbose`, `cult.brand`); turning one off always applies.

## What the companion adds

| Feature | Proxy only | Proxy + companion |
|---|---|---|
| `/cult spectate` and `/cult stopspectating` (also across servers) | no | yes |
| `/cult debug validationblock` (a real server block) | no | yes |
| Punishment commands that only exist on the backend | no | yes (run on the backend console) |
| `%tps%` in alerts | NaN | the backend's TPS |
| Grim API for plugins on the backend | no | yes (see below) |
| `/cult` from the backend console, RCON and plugins dispatching as the console | no | yes |
| Backend plugins cancelling `FlagEvent` / `CommandExecuteEvent` | no | yes |

Block, inventory and item-use resyncs and NoSlow run on the proxy from the client's and backend's packets alone, with
or without the companion. With it, a definite NoSlow flag also ends the item use on the backend.

## Cost on the backend

When nothing changes the companion does no per-player work. It sends a message only when a player's world changes,
plus one server-stats message per second for the whole server. Bridge messages are read on the Netty thread; only
Bukkit actions hop to the main thread.

## Commands

`/cult` (aliases `/cultac`, `/grim`, `/grimac`) is registered on the backend and runs on the proxy. `/gl` is not
available through the companion; use `/cult log <id>`.
Players are normally handled by the proxy before the backend sees the command. The backend console and RCON run
as the proxy console; players run as themselves. Other senders (command blocks, `/execute` entities, plugin
senders) are refused. Console commands need at least one player with an active bridge on that backend, because
the bridge rides player connections. Output arrives after the command returns: once the command is sent, RCON gets
the fixed reply `Sent to the CultAC proxy; the output goes to this server's log`, and the output is written to the
backend log. When it cannot be sent (no bridged player, or too many commands waiting on the proxy), RCON gets the
reason as its reply.

Reloading or re-enabling the companion with players online leaves those players unbridged until they reconnect or
switch servers.

## Grim API on the backend

Plugins that depend on `GrimAC` load against the companion unchanged.

| Works | Notes |
|---|---|
| `getGrimUser`, name, UUID, world, keep-alive ping, `sendMessage`, `hasPermission` | local |
| Brand, client version, transaction ping, sensitivity | mirrored from the proxy; the ping updates when it changes by at least 5 ms and 10% |
| `getChecks` | names, descriptions and settings are mirrored from the proxy; violations start at 0 on each server, take the proxy's value only when a flag reaches a `FlagEvent` listener or a punishment reaches a `CommandExecuteEvent` listener here, and never decay |
| `FlagEvent` (cancellable), `CommandExecuteEvent` (cancellable), `GrimJoinEvent`, `GrimQuitEvent`, `GrimPlayerSetbackEvent`, `GrimTeleportEvent`, `GrimReloadEvent`, legacy Bukkit events | `GrimQuitEvent` runs on the main thread during `PlayerQuitEvent`, or on the Netty thread right after `GrimJoinEvent` returns when the player quits while it runs; the other events run on the Netty thread, `FlagEvent` as on Paper. `GrimJoinEvent` fires when the player's first state and checks arrive, or one second after the bridge is ready, and the player's later events wait for it. The legacy Bukkit events fire in the same order; a legacy quit event raised on the main thread is queued for an async thread and not awaited; `CommandExecuteEvent` covers fewer commands than on Paper (see below) |
| `AlertManager` | toggles apply on the proxy (see [Security](#security)) |
| `reload()`, `reloadAsync()` | run `/cult reload` on the proxy and complete when the proxy has run it, whatever the outcome; listen for `GrimReloadEvent` for the result |
| `getCurrentTick` | the backend's tick |
| `runSafely` | runs on the main thread |

Cancelling a `FlagEvent` stops the violation, alert and punishment that flag would cause. Setbacks do not wait for
the backend: the proxy sets the player back at once, as on Paper, and a cancel cannot undo that setback or a packet
the proxy already dropped inline for that flag. Backend listeners run after every proxy `FlagEvent`
listener and see only the flags no proxy plugin cancelled. A backend that doesn't answer within 100 ms is treated as
not cancelling; after 5 unanswered flags in a row on one connection, cancels stop applying for the rest of that
bridge session (until the player reconnects, switches servers or re-enters configuration). When the first
`FlagEvent` listener registers after startup, the proxy learns about it within one second; flags in that window are
not sent to the backend.

`CommandExecuteEvent` fires only for punishment commands the proxy runs on this backend's console, which are commands
the proxy itself doesn't know. It does not fire for `[alert]`, `[log]`, `[webhook]` or `[proxy]` actions, or for
commands a proxy plugin registers; cancel those from a proxy plugin.

Not available through the companion (they throw `UnsupportedOperationException` or are never fired):
`getPacketWorld`, `addRealTimeTask*`, `GrimTransactionSendEvent`, `GrimTransactionReceivedEvent`,
`CompletePredictionEvent`, `GrimVehicleSetbackEvent`.
`registerVariable`/`registerFunction` and `setResyncHandler` are accepted but have no effect, `GrimUser.reload()` does
nothing, and `AbstractCheck.setEnabled` changes only this backend's view of the check. `getLastTransactionSent/Received`
are coarse snapshots. `GrimUser.getFeatureManager()` has no features and ignores `setFeatureState` (it returns
false), `getConfigManager()` returns only the defaults you pass, and storage backends registered through
`getBackendRegistry()` are ignored. Proxy plugins get the full API.

PlaceholderAPI placeholders (`%cult_*%`) are not available on the companion.
