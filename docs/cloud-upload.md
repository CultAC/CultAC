# Cloud upload

CultAC can stream what it sees of each player's play to an intake service, which analyses it off the server. The
upload is off by default and stays off until you configure it with the address and key your intake service gives you.
This page is for server operators: what leaves the server, how to configure it, and what it costs.

## What is uploaded, and when

While the upload is on, each player's session is streamed from the moment they reach play:

- **Packet captures, in the player's own protocol.** The packets the player's client sent and received, as the
  client's protocol writes them, cut into one frame per client tick. The intake service chooses which packet types it
  wants; CultAC sends only those, never the withheld packets listed below, and strips player-written text from the
  rest. The client's own settings packet is among them: its language, view distance, chat and skin settings and main
  hand.
- **Session facts**, once per session and again after a server switch: the player's UUID, name and client brand, the
  client's protocol version, the server software and version, the target tick length, a few combat-related server
  settings and the player's attack speed, and the names in the damage-type and enchantment registries.
- **Terrain.** The blocks around the player, as CultAC's own world model holds them, so the service can replay
  movement against the same world.
- **Verdicts.** Every tick's check flags, the simulation engine's state and combat geometry (reach targets and
  hitboxes).
- **Outcomes.** What the server did with the player's inventory clicks, held-slot changes and hand swaps (the item
  keys and counts left in the slots, cursor and hands), and the player's totem pops. Items are named by their key.
- **Other players, as the capture sees them.** The entities around the player, and the tab list's entries for other
  online players: their names, UUIDs, game modes, latency, whether they are listed, and their skin's address on
  Mojang's texture server. Scoreboard teams are kept for their rules and members, under an opaque name per stream.
  They are uploaded as the player received them, less the text stripped below. The login packet's list of the
  server's world names is sent as it is.
- **Diagnostics bundles, only on request.** When the intake service asks to watch a player, CultAC keeps a short ring
  of that player's prediction traces (positions, velocities, the nearby block grid, the boxes of nearby entities,
  other players included, the player's name and the world name, under a header with the plugin version, client
  version, brand and ping, the server software, and the version of any enabled plugin known to rewrite movement or
  packets, such as ViaVersion or ProtocolLib) and uploads a window of it when the service asks for one. Nothing is kept or sent for
  unwatched players.
- **Performance profiles, only with `cloud.perf` and on request.** Off by default. When on, the intake service may ask
  for a short JFR profile of the whole server process: CPU and allocation stack samples (class and method names),
  the bytes CultAC's own code allocated by class, garbage-collection pauses, tick times, the online player count, and
  the JVM's name, version and flags, and on Linux the CPU time of the process and of the JVM's own GC, compiler, VM
  and JFR threads, read from `/proc`. It is recorded off the main thread and skipped while the server lags.
- **Staff portal roles, only with `cloud.team-sync`.** Off by default. When on, the UUID, name and portal role of
  players holding a `cult.portal.*` permission, and of synced players who lost it, are sent to the intake service so
  its portal lists them in your organization. See [Team sync](#team-sync).

Each connection also announces the plugin's name, version and build commit, whether it runs observe-only or
enforcing, and a SHA-256 hash of the parsed config files with `bridge.secret` and `cloud.api-key` left out. The hash
tells the service that the configuration changed; it does not carry the files' contents or reveal the secrets, and
comments and formatting do not change it.

Java clients are streamed. Bedrock players are announced with their session facts and the reason they are not
streamed, and nothing more.

## What is never uploaded

Whatever the service asks for, these packets are never sent:

- **What the client sends privately.** Chat messages and acknowledgements, commands and command suggestions, its chat
  session key, signs, books, item renames, command-block, structure-block, jigsaw and test-block edits, dialog
  actions, plugin messages (including the client's brand message, though the brand string itself is a session fact)
  and cookie and resource-pack replies.
- **What the server sends that carries chat or text plugins write.** Player, system and disguised chat, chat
  deletions, command completions and the command tree; titles, subtitles and action bars; boss bars; the tab list's
  header and footer; scoreboard objectives, scores and display slots; disconnect messages; server data, links and
  report details.
- **Other server packets with data that is not stripped.** Plugin messages, cookie requests and stored cookies,
  transfers to another server, villager trades, map data, tag query answers, advancements, recipes and the recipe
  book's additions, tags, game rules, post-processing effects, waypoints, and debug and test data.
- **Any server or client packet not known here.** A packet CultAC does not know to be free of player text, or to
  strip, is withheld, including those a newer protocol adds.

Player-written text is cut out of the packets that are sent, when CultAC can strip text for the client's version (see
below):

- **Items**, wherever a stack appears (inventories, equipment, entity data, particles, explosions, items inside
  items, and stacks a client sends): names, lore, books, custom, entity and block entity data, and the text inside
  other components. A profile on an item keeps only its skin's address on Mojang's texture server.
- **Entities:** text components and command-block minecart commands, a painting's title and author, a shoulder
  parrot's saved data, and a mannequin's profile but for its skin.
- **Screens and messages:** window titles, death messages, inline dialogs, and a resource pack's prompt and address.
- **The tab list and teams:** display names, chat session keys and their Mojang signatures, profile properties other
  than one unsigned skin, and team display names, prefixes and suffixes. A team's name is replaced by an opaque name,
  and of its members only these are kept: names in player-name syntax (1 to 16 letters, digits and `_`), such a name
  of up to 15 behind the crossplay prefix `.`, UUIDs, and names the stream's tab list already carries, such as an NPC.
- **Block entities** in chunks and block entity updates: their data.

A packet that should be stripped and does not read to its end, or takes more than a fixed amount of work to read, is
not sent. Two things are kept by design: from 1.21.5 on, a client's click carries each component of the clicked
stacks as a 32-bit hash, and those hashes are sent as they are, a name's included; and other players' names and UUIDs
in the tab list.

If the code that strips text cannot load for a client version, CultAC logs a warning once for that version and, until
it restarts, says `textStripped: false` when it connects to the intake; the intake then strips text itself. Packets on
the lists above are still never sent, and without a client version's packet ids none of its packets are. Packets that
would have been stripped go out as they came, text included, but only on a connection that said
`textStripped: false`. Until the intake strips them, they can carry whatever a server or player put in that text,
item data and resource pack addresses included. If stripping stops working on a connection that said
`textStripped: true`, those packets are withheld and the connection reconnects once to say `false`.

The upload has no field for a player's network address. Text it uploads unstripped, on a connection that said
`textStripped: false`, can still contain one, as can strings the client or server provide, such as the client brand,
which are uploaded as they report them.

## Configuration

The `cloud:` block in `plugins/CultAC/config.yml` (`plugins/cultac/config.yml` on Velocity):

| Key | Default | Meaning |
|---|---|---|
| `cloud.url` | `""` | The intake service's address. Must be `https`, or `http` to a loopback host. |
| `cloud.api-key` | `""` | The key your intake service issued. |
| `cloud.server-id` | `""` | Each upload socket's server id. Empty picks the platform's default, below. |
| `cloud.proxy-id` | `""` | On Velocity, this proxy's name. On Paper, this server's own name. |
| `cloud.perf` | `false` | Lets the intake service ask for performance profiles, below. `CULTAC_CLOUD_PERF` (`true`/`1`/`yes`, `false`/`0`/`no`) overrides it when set. |
| `cloud.team-sync` | `false` | Reports staff portal roles to the intake service, below. `CULTAC_CLOUD_TEAM_SYNC` (`true`/`1`/`yes`, `false`/`0`/`no`) overrides it when set. |

Empty means off: the upload stays off while `url` or `api-key` is empty. It also stays off, with one warning, while
`proxy-id` is empty on Paper, or on Velocity while `server-id` uses `{proxy}`, and while `api-key` holds anything but
visible ASCII characters (a space, line break or invisible character inside it); that warning does not show the key.
The key is checked after surrounding whitespace is stripped, so `" key "` or a key with a pasted trailing line break
is used as `key`. Apply changes with `/cult reload`.

The service names the WebSocket each upload streams to. CultAC uses it only over `wss`, or over plain `ws` when `url`
is `http` to a loopback host and the socket's host is loopback too.

### Server ids

The intake service keeps one stream per server id, so each backend's players arrive under their own id.

- **Velocity:** the default is `{proxy}.{server}`, where `{proxy}` is `proxy-id` and `{server}` is the backend's name
  in `velocity.toml`. Players are uploaded under the backend they are on, and a server switch moves them to the new
  backend's id. A backend's server facts (software, tick length, combat settings) come from the CultAC Companion on
  it, and so do the outcomes of clicks and held-slot changes; without the Companion they are left out. The proxy
  keeps only the combat settings it knows from a Companion and drops anything else a backend sends.
- **Paper:** the default is `{server}`, which is `proxy-id`, the name you give this server. A `/cult reload` that
  changes `proxy-id` names the segments that start after it; players already uploading keep the old name until they
  rejoin.

A dot inside `proxy-id` or a backend's name becomes `-` before it is put in the template, so the template's own dots
are the only ones in an id: `{proxy}.{server}` never gives two proxy and backend pairs the same id. Ids are then
lowercased, characters outside letters, digits, `.`, `_` and `-` become `-`, anything before the first letter or
digit is dropped, and ids are cut at 128 characters. The first time two different names end up as the same id, a
warning names them; give names that differ in letters or digits.

Every writer sharing one API key needs distinct ids:

- Give each proxy its own `proxy-id`. If two writers connect with the same id, the intake service keeps the newer
  one; the older logs a warning and tries again a minute later, so a duplicate `proxy-id` shows as a slow flap.
- Upload from the proxy or from its backends, not both. A backend that uploads on its own while its proxy also uploads
  sends every player twice.

### Team sync

With `cloud.team-sync: true`, players holding `cult.portal.viewer`, `cult.portal.moderator` or `cult.portal.admin` are
reported to the intake service, over the upload's own sockets, with the highest role they hold. Each socket offers
team sync at connect; the service answers whether your organization has it on, and says so again whenever that
changes. The service's organization settings decide what a report does: it makes the
player's Minecraft account a member of your organization in the portal with that role, or suggests it for an owner's
approval; an admin role always waits for an owner. The nodes default to false, so operators do not get them
implicitly, but a LuckPerms `*` grants admin.

- A player still online two minutes after joining who holds a node is reported.
- With LuckPerms, a permission change for that player reports the new role when it differs, and none once every node
  is gone. When the service sends the accounts it manages, CultAC reports the role LuckPerms stores for those not
  online, at most once an hour. Without LuckPerms these lists are ignored, and the service removes members not
  reported for 30 days.
- Only version-4 (Mojang) UUIDs are reported, at most 100 players in one message every 30 seconds, on one socket the
  service has team sync on for, never from the main thread or a connection's event loop. While no socket has it on,
  nothing is sent and what was queued is dropped; when one turns it on, every checked player online is reported again.
- It needs the upload configured: nothing is sent while the upload is off (`url`, `api-key` or a required `proxy-id`
  missing), or while no player keeps a socket open. Observe-only does not change it. Turning `cloud.team-sync` on or
  off and running `/cult reload` reconnects the sockets, as `cloud.perf` does.

On Velocity the online check asks the proxy's permission provider (LuckPerms-Velocity answers it), and the LuckPerms
change listener and offline sweep run when LuckPerms is installed on the proxy. On Paper they use Paper's permissions
and LuckPerms the same way.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/cult cloud` | `cult.cloud` | The upload's state: on or off and why, each socket's queue and counters, held frames, and the diagnostics watches. |
| `/cult findings <player> [count]` | `cult.cloud.view` | The player's latest findings from the intake service, newest first; 15 unless `count` (1 to 200) says otherwise. |
| `/cult top [check] [minutes]` | `cult.cloud.view` | The 10 players with the most confident findings over the last 30 minutes, or `minutes` (up to 7 days), for one check or all. |
| `/cult label <player> <cheat> <high\|extreme> <24h\|3d\|7d\|30d> [note]` | `cult.cloud.label` | Labels the player as using one cheat over that span, ending now. |
| `/cult label <player> clean <high\|extreme> [note]` | `cult.cloud.label` | Labels the player clean across every check, with no span: their stored play and all play from now on, until revoked. |
| `/cult label list <player>` | `cult.cloud.label` | The player's labels. |
| `/cult label revoke <id> [reason]` | `cult.cloud.label` | Revokes a label. |
| `/cult label <player>` | `cult.cloud.label` | On Paper, for a Java player: the label menu, a dialog for the verdict (Cheating, Suspected, or Clean with `cult.cloud.label.clean`), the checks, the confidence and a 1 to 30 day span (each staff member's last span is remembered), then a review with each check's flags over the span and any clashing label on file. Confirm closes the menu and one chat line says what was labelled. Bedrock players are pointed at the commands above. |

On Paper the four permissions default to operators. Behind Velocity these commands run only on the proxy, from its
console or from a player in game: the proxy refuses them when a backend forwards them, whoever it names as the sender.

Findings come from the intake service while the upload is configured and are kept in memory: 200 per player and
20,000 in all, for up to 30 minutes after the player leaves. Past 20,000 the findings of the player gone longest go
first. Each socket's findings are taken only for players uploaded on that socket, or who left it within those 30
minutes and are among the last 4,096 to leave it; others are dropped, so a late finding can be lost. Labels go to the
service's label API with the server's API key, naming the labelling player's UUID; the service takes them only from a
moderator (or above) of the key's organization with that Minecraft account on their profile, so the console can only
list. `/cult label list` shows the first 50 labels. Players are named by UUID, online name or a Mojang lookup (at most
8 at once, each name remembered for 10 minutes), so `/cult findings` and `/cult label` refuse on an offline-mode
server that has no online-mode proxy in front. The service calls run off the calling thread, and the service's text is
shown on one line.

## When the upload turns itself off

CultAC speaks intake protocol 5 only, and offers nothing else.

- **422 from the intake service.** The service does not accept protocol 5. The upload turns off for the whole server
  with one warning naming the offered protocol, and stays off until `/cult reload` or a restart.
- **Another protocol agreed.** If the service answers the connect with any protocol but 5, or none, CultAC streams
  nothing, warns once ("The cloud did not agree intake protocol 5; not streaming") and asks again with backoff.
- **zstd missing.** The runtime jar bundles zstd for Linux (x86-64 and ARM64), Windows (x86-64) and macOS (Intel and
  Apple silicon). Where it cannot load, on Paper and Velocity alike, the upload stays off with one warning, since
  protocol 5 is zstd-compressed.
- **Other errors** (the service unreachable, a refused or dropped connection) retry with backoff, from 1 up to 30
  seconds apart. Play during the outage is not uploaded; each player's stream starts again once the connection is
  back.

## Cost

- **While off:** nothing. With neither `cloud.url` nor `cloud.api-key` set, CultAC loads none of the upload's code,
  registers no listener and keeps nothing per player. Behind Velocity, each backend's Companion still sends its server
  facts once when a player's bridge session becomes ready, and that player's combat settings then and whenever a
  once-a-second check on its main thread finds them changed; with the upload off the proxy keeps nothing of them.
- **Bandwidth:** each streamed player's packets and terrain, compressed with zstd, and a burst of terrain at each
  session start. Combat, busy inventories, a larger terrain radius and diagnostics cost more.
- **CPU:** the capture runs on each player's network thread and compression on a small shared pool (a quarter of the
  cores, 2 to 4 threads). The main thread does one facts read per join on Paper.
- **Memory:** a few KB per online player (a frame of more than 256 packets holds more until it is sent), and at most
  16 diagnostics cuts, one capture policy and, between two cuts, one watch stop and start from the service waiting for
  a player's network thread; further cuts are dropped and counted. Up to 128 MiB of queued uploads per server process,
  and up to 64 MiB more of capture frames not yet queued, shared by every player and at most 16 MiB of it for one
  player. While `/cult update` drains the old runtime, each runtime has its own budgets. When the service falls
  behind, or a budget is full, frames are dropped and counted, not buffered without bound.
- **Diagnostics:** only watched players pay. A watch whose capture gets slower than 1 ms at the 99th percentile ends
  on its own.
