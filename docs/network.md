# Running CultAC across several servers

If your network has more than one proxy or server, CultAC can link them up. Staff commands that name a player then
work whichever server that player is on, tab completion offers players from every server, and alerts with the
`global` scope arrive from everywhere. Without it, each server only knows about its own players.

Your servers talk to each other through a Redis or NATS server you already run, or through RedisBungee's connection.
Everything is set in the `network` block of `config.yml`. Every setting can also be set with an environment variable,
which takes priority when it is set and not blank: `CULTAC_NETWORK_TYPE`, `CULTAC_NETWORK_INSTANCE_ID`,
`CULTAC_NETWORK_REDIS_ADDRESS`, `_USERNAME`, `_PASSWORD` and `_SSL`, and `CULTAC_NETWORK_NATS_ADDRESS`, `_USERNAME`,
`_PASSWORD`, `_TOKEN`, `_CREDENTIALS_FILE` and `_SSL`. `/cult reload` applies changes.

## Choosing how your servers talk

Set `network.type` to the same value on every proxy and server, then fill in the matching section. Only that section
is read. An address is `host` or `host:port`; put an IPv6 address in brackets (`[::1]:6379`). Write passwords and
tokens exactly as they are.

### none

The default. Cross-server features are off, and each server only knows about its own players. Nothing to fill in.

### redis

Uses a Redis server; Valkey, KeyDB and Dragonfly work too. Fill in its address and, if it has one, its password. Add a
username only if you created one for CultAC (Redis ACL), and turn on `ssl` if your Redis server uses TLS.

```yaml
network:
  type: redis
  redis:
    address: redis.example.com:6379
    username: ""
    password: "your redis password"
    ssl: false
```

The port defaults to 6379 when you leave it out.

### nats

Uses a NATS server, or several. List them in `address`, separated by commas. Fill in whichever login your NATS server
uses: a token, a username and password, or, if it uses accounts, the path to your `.creds` file. Turn on `ssl` if
your NATS servers use TLS.

```yaml
network:
  type: nats
  nats:
    address: nats-1.example.com:4222, nats-2.example.com:4222
    username: ""
    password: ""
    token: "your nats token"
    credentials-file: ""
    ssl: false
```

The port defaults to 4222 when you leave it out.

### redisbungee

For Velocity proxies that run RedisBungee. CultAC uses RedisBungee's own Redis connection, so there is nothing else to
fill in.

```yaml
network:
  type: redisbungee
```

Any other `type`, or an address that is not `host` or `host:port` (a Redis address is a single server, without
`redis://` in front), is reported as an error when the server starts or reloads, and cross-server features stay off.

## Message signing

Messages between your servers are signed, so nothing else that can reach your Redis or NATS server can send CultAC
commands. There is nothing to set: the key comes from Velocity's forwarding secret, or from your Redis or NATS
password. In order, CultAC uses:

1. On a Velocity proxy, its modern forwarding secret.
2. On a Paper server behind Velocity, the same forwarding secret, read from `proxies.velocity.secret` in
   `config/paper-global.yml` while `proxies.velocity.enabled` is on.
3. Your Redis password (with the username in front when you set one), or your NATS token, else your NATS password
   (with the username in front when you set one).

A proxy and the servers behind it therefore agree without any setup, and so do servers without Velocity that log into
Redis or NATS with the same login. If none of these exist, for example a Redis server without a password and no Velocity,
cross-server features stay off and the log says so: set a password on your Redis or NATS server, or run behind
Velocity with modern forwarding.

Servers only hear from servers that sign with the same key, so several networks can share one Redis or NATS server
when each signs with its own key. Networks whose keys come from the same login or the same forwarding secret sign with
the same key and merge; give each of them its own `network.secret`. When a server starts linking up, it logs one line naming where its key came from and the channel prefix that key
gives, such as `cultac.1a2b3c4d.*`. If two servers cannot see each other, compare those lines: the one with a
different prefix signs with a different key.

**Advanced:** `network.secret` (or `CULTAC_NETWORK_SECRET`) overrides the key on that server. Use it when your servers
log into Redis or NATS with different passwords: add `secret: "something long and random"` to the `network` block and
use the same value on every proxy and server.

## Server names

Every proxy and server needs its own name on the network, made of the letters A to Z (either case), digits, `_` and
`-`, and not `all`. Leave `network.instance-id` empty to use the RedisBungee proxy id, then `cloud.proxy-id`, then the
server's IP and port. On a backend server, set it to the name your proxy uses for that server: alerts with the
`server` scope match staff and players by that name.

## Commands across servers

A command that names a player on another server runs there, with the sender's permissions, and the output comes back
to the sender: `profile` and the Java and Geyser packet logs, for example. `findings` and `history` look the player up
across the whole network. `spectate` sends the staff member to the player's server through their own proxy.
`/cult alerts scope global` receives alerts from every server; `server` only from players on the staff member's own
server. Staff who never chose a scope get `alerts.default-scope` from `config.yml` (`server` unless set to
`global`); their own choice wins, and `global` acts as `server` while network-wide alerts are off. `/cult findplayer` counts the client brands of everyone online on the network, and
`/cult findplayer brand=<text>` lists the players whose brand contains that text. While the network runs, a server
does not also send its alerts through the proxy (`alerts.proxy.send`), as the network already brings them.

## Running commands on several servers

`/cult net <target> <command>` runs one command on every CultAC proxy and server the target names. Each one's answer
shows as it arrives, then a summary: how many answered, which did not answer in time (5 seconds for `status`), and
which run an older CultAC build that cannot take network commands.

The target is one or more of these, joined by commas without spaces (`BRG_*,lobby`):

- `all`: every proxy and server running CultAC.
- `proxies`: every proxy.
- a server name (see [Server names](#server-names)): that proxy or server.
- a backend server as your proxies name it (`BRG_1`): every proxy that lists it runs the command for the players on
  it, and the backend itself does too when it runs CultAC under that name.
- a pattern with `*` (`BRG_*`): every server and backend name it matches.

Names ignore case. A server named `proxies` is reached as `id:proxies`. Tab completion offers the keywords, the
servers heard from, every backend registered with a proxy (with players or not) and a pattern for each group of names
that share a prefix, such as `BRG_*`. A complete name is also offered with a comma after it, and after a comma it
completes the next name, leaving out the names a pattern already given covers. A name that no proxy or server knows is
refused, and nothing is sent.

Commands:

- `status`: for each server, its CultAC build, whether it is a proxy or a server, the players online and inspected,
  whether it checks players, observe-only mode (and the environment variable that pins it), whether punishment
  commands run, the checks switched off, the average time per prediction, and how long the server and its running
  CultAC engine have been up. For a backend name, each proxy reports the players and settings on the backends named.
- `reload`: reloads CultAC's configuration, as `/cult reload` does. It runs on proxies and servers only; a backend name
  is refused.
- `observe on|off`: turns observe-only mode on or off for the players the target names. Without `on` or `off` it shows
  the current mode and where it comes from.
- `punish on|off`: runs or withholds the commands of `punishments.yml` for the players the target names; checks,
  alerts and setbacks are unaffected. Without `on` or `off` it shows the current state.
- `check <check> <on|off>`: switches one check on or off for the players the target names. Tab completion offers each
  check's current name; any name a check has or had works.
- `update <build>` or `update latest [channel]`, optionally followed by `--canary`: installs a signed numbered build
  on every proxy the target names, as `/cult update` does on each one (see
  [Numbered builds on Velocity](../README.md#numbered-builds-on-velocity)). `latest` is the newest build of a channel,
  by default the channel of the build running where the command is typed. That server looks it up once, and every
  proxy installs that build only if it is signed for that channel; when it cannot look it up, each proxy looks it up
  itself, one GitHub API request each (60 an hour per address without `update.token`). Each proxy answers when it
  starts and again with its install line, which it also logs and posts to `update.webhook` with the sender's name.
  Servers that are not proxies answer that they skip it, and a backend name is refused. The proxy the command is typed
  on updates itself last, once the others have answered or the wait for them has ended. With `--canary`, one proxy
  installs the build first: the first by name other than the one the command is typed on. The others, the one the
  command is typed on included, update only once it runs the new build. The wait ends for a
  proxy that has not answered within 10 seconds, and for an install line after 11 minutes. To roll back, install an
  older build number; a proxy with `update.build: latest` still starts the newest build of its channel when it
  restarts. A proxy whose CultAC plugin jar is older than this command answers that it needs a restart: restart it
  once (its running build replaces the plugin jar as it stops) and it takes network updates from then on.

Each command needs `cult.net` and its own node, `cult.net.<command>` (`cult.net.status`, `cult.net.reload`,
`cult.net.observe`, `cult.net.punish`, `cult.net.check`, `cult.net.update`), on the server it is typed on; the console
holds them all. A receiving server runs it only when the sender held that node, and a command forwarded from another
server can never start one.

`observe`, `punish` and `check` apply per player: on a proxy, each player follows the setting issued last for the
backend server they are on or for the proxy itself, and a player who moves to another backend takes its settings at
once. `/cult observe` and the `observe-only` key set the mode of their own server.

`network.commands` in `config.yml` sets what a server takes; each key's environment variable wins when set and not
blank:

| Key | Default | Environment variable | Meaning |
|---|---|---|---|
| `enabled` | `true` | `CULTAC_NETWORK_COMMANDS` | Whether this server runs network commands. |
| `accept` | `[status, reload, observe, toggle, check, punish, update]` | `CULTAC_NETWORK_COMMANDS_ACCEPT` (a comma list) | The commands it runs; it refuses the others. |
| `webhook` | `summary` | `CULTAC_NETWORK_COMMANDS_WEBHOOK` | What goes to `update.webhook`: `summary`, `every` or `none`. |

An `accept` list already in `config.yml` keeps the commands it lists; add `punish` to it for the server to take
`punish`.

Every server that runs a network command, and the server it was typed on, logs one line for it:
`net <id> <sender> (<uuid>|console) from <server> → <target>: <command> = <result>`. With `webhook: summary` the server
it was typed on posts the command and its summary to `update.webhook`; with `every` each server that runs it posts its
own line; `none` posts nothing. While network routing is off, a network command runs only on the server it is typed
on, when the target names that server.

### Settings that stay set

A network command that changes a setting (whether a server checks players, observe-only mode, the checks switched
off, whether punishment commands run) leaves a record: the setting, its value, the target, when it was issued and by
whom. Every server keeps the same records. They travel with each server's announcements, and a server that starts, or
comes back after losing the network, asks the others for theirs and catches up. When two records for one setting and
target disagree, every server takes the one issued last; a record for `all` replaces the older records of its setting.

A record for a backend name applies to the players on that backend, on every proxy that lists it, and to the backend
itself when it runs CultAC under that name. A setting command may name a backend that no proxy lists yet: it notes
that, and the setting applies once the backend appears.

Each server stores the records in `network-state.json` in its data folder and writes the values that apply to it into
`config.yml`, so they survive a restart without the network. Editing one of these keys in `config.yml` yourself changes
it on that server only, when it starts or on `/cult reload`; a later network command still wins. A server with
`network.commands.enabled: false`, or whose `accept` list lacks the command that changes a setting, keeps its own
`config.yml` value for it, while still keeping and passing on the records.

| Key | Default | Environment variable | Meaning |
|---|---|---|---|
| `dormant` | `false` | `CULTAC_DORMANT` | CultAC checks no one and frees the memory checking uses. |
| `observe-only` | `false` | `CULTAC_OBSERVE_ONLY` | Flag and alert without acting. |
| `checks-off` | `[]` | `CULTAC_CHECKS_OFF` (a comma list of check names) | Checks that neither flag nor set back, by check name. |
| `pause-punishments` | `false` | `CULTAC_PAUSE_PUNISHMENTS` | Withhold the punishment commands of `punishments.yml`. |

The environment variables pin a setting on their server: `true`, `1` or `yes` and `false`, `0` or `no` hold it at that
value, and `CULTAC_CHECKS_OFF` keeps the checks it lists off. The network's records still arrive and are kept, but do
not change a pinned setting, and `status` shows the pin.

`checks-off` and `CULTAC_CHECKS_OFF` take any name a check has or had, in any case; a name that is not a check is
logged once and ignored. When CultAC writes `checks-off`, it writes each check's current name.
