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
- **Other players, as the capture sees them.** The entities around the player, and the tab list's entries for other
  online players: their names, UUIDs, game modes, latency, whether they are listed, and their skin's address on
  Mojang's texture server. They are uploaded as the player received them, less the text stripped below.
- **Diagnostics bundles, only on request.** When the intake service asks to watch a player, CultAC keeps a short ring
  of that player's prediction traces (positions, velocities, the nearby block grid, the boxes of nearby entities,
  other players included, and the world name, under a header with the client version, brand and ping) and uploads a
  window of it when the service asks for one. Nothing is kept or sent for unwatched players.

Each connection also announces the plugin's name, version and build commit, whether it runs observe-only or
enforcing, and a SHA-256 hash of the config files. The hash tells the service that the configuration changed; it does
not carry the files' contents.

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
  than one unsigned skin, and team display names, prefixes and suffixes.
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

No session fact carries a player's IP address, and no captured packet does where text is stripped as above.

## Configuration

The `cloud:` block in `plugins/CultAC/config.yml` (`plugins/cultac/config.yml` on Velocity):

| Key | Default | Meaning |
|---|---|---|
| `cloud.url` | `""` | The intake service's address. Must be `https`, or `http` to a loopback host. |
| `cloud.api-key` | `""` | The key your intake service issued. |
| `cloud.server-id` | `""` | Each upload socket's server id. Empty picks the platform's default, below. |
| `cloud.proxy-id` | `""` | On Velocity, this proxy's name. On Paper, this server's own name. |

Empty means off: the upload stays off while `url` or `api-key` is empty. It also stays off, with one warning, while
`proxy-id` is empty on Paper, or on Velocity while `server-id` uses `{proxy}`. Apply changes with `/cult reload`.

### Server ids

The intake service keeps one stream per server id, so each backend's players arrive under their own id.

- **Velocity:** the default is `{proxy}.{server}`, where `{proxy}` is `proxy-id` and `{server}` is the backend's name
  in `velocity.toml`. Players are uploaded under the backend they are on, and a server switch moves them to the new
  backend's id. A backend's server facts (software, tick length, combat settings) come from the CultAC Companion on
  it, and so do the outcomes of clicks and held-slot changes; without the Companion they are left out.
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

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/cult cloud` | `cult.cloud` | The upload's state: on or off and why, each socket's queue and counters, held frames, and the diagnostics watches. |
| `/cult findings <player> [count]` | `cult.cloud.view` | The player's latest findings from the intake service, newest first; 15 unless `count` (1 to 200) says otherwise. |
| `/cult top [check] [minutes]` | `cult.cloud.view` | The 10 players with the most confident findings over the last 30 minutes, or `minutes` (up to 7 days), for one check or all. |
| `/cult label <player> <cheat> <high\|extreme> <24h\|3d\|7d\|30d> [note]` | `cult.cloud.label` | Labels the player as using one cheat over that span, ending now. |
| `/cult label <player> clean <high\|extreme> <24h\|3d\|7d\|30d> [note]` | `cult.cloud.label` | Labels the player clean across every check over that span. |
| `/cult label list <player>` | `cult.cloud.label` | The player's labels. |
| `/cult label revoke <id> [reason]` | `cult.cloud.label` | Revokes a label. |

On Paper the three permissions default to operators. Findings come from the intake service while the upload is
configured and are kept in memory: 200 per player and 20,000 in all, for 30 minutes after the player leaves. Findings
about a player who is neither online nor kept are dropped. Labels go to the service's label API with the server's API
key, naming the labelling player's UUID; the service takes them only from a moderator (or above) of the key's
organization with that Minecraft account on their profile, so the console can only list. Players are named by UUID,
online name or a Mojang lookup, so `/cult findings` and `/cult label` refuse on an offline-mode server that has no
online-mode proxy in front. The service calls run off the calling thread.

## When the upload turns itself off

- **422 from the intake service.** The service accepted none of the protocols CultAC offered. The upload turns off
  for the whole server with one warning naming the offered protocols, and stays off until `/cult reload` or a restart.
- **zstd missing.** The runtime jar bundles zstd for Linux (x86-64 and ARM64), Windows (x86-64) and macOS (Intel and
  Apple silicon). Where it cannot load:
  - on Velocity, the upload stays off, with one warning, since a proxy offers only the zstd-compressed protocol;
  - on Paper, CultAC falls back to the older DEFLATE protocol with one warning. That protocol carries raw chunk
    packets instead of terrain sections, uses much more bandwidth, and offers no diagnostics.
- **Other errors** (the service unreachable, a refused or dropped connection) retry with backoff, from 1 up to 30
  seconds apart. Play during the outage is not uploaded; each player's stream starts again once the connection is
  back.

## Cost

- **While off:** nothing. With neither `cloud.url` nor `cloud.api-key` set, CultAC loads none of the upload's code,
  registers no listener and keeps nothing per player. Behind Velocity, each backend's Companion still sends its server
  facts once when a player's bridge session becomes ready, and that player's combat settings then and whenever a
  once-a-second check on its main thread finds them changed; with the upload off the proxy keeps nothing of them.
- **Bandwidth:** each streamed player's packets and terrain, compressed with zstd, and a burst of terrain at each
  session start. Combat, busy inventories, a larger terrain radius and diagnostics cost more. The DEFLATE fallback on
  Paper sends whole chunk packets and costs far more.
- **CPU:** the capture runs on each player's network thread and compression on a small shared pool (a quarter of the
  cores, 2 to 4 threads). The main thread does one facts read per join on Paper.
- **Memory:** a few KB per online player, up to 128 MiB of queued uploads per server process, and up to 64 MiB more
  of frames held until a player's upload is connected and answered. While `/cult update` drains the old runtime, each
  runtime has its own budgets. When the service falls behind, frames are dropped and counted, not buffered without
  bound.
- **Diagnostics:** only watched players pay. A watch whose capture gets slower than 1 ms at the 99th percentile ends
  on its own.
