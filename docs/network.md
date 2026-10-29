# Network messaging

On a network of several proxies or servers, CultAC instances can talk to each other through a message broker. With
it, staff commands that name a player reach the instance the player is on, tab completion offers players on every
instance, and alerts with the `global` scope arrive from everywhere. Without it, each instance sees only its own
players. Messages are JSON on the broker, signed with a key every instance shares; an instance drops any message it
cannot verify.

Everything is set in the `network` block of `config.yml`. Each key also has an environment variable that wins when it
is set and not blank: `CULTAC_NETWORK_URI`, `CULTAC_NETWORK_SECRET`, `CULTAC_NETWORK_INSTANCE_ID` and
`CULTAC_NETWORK_NATS_CREDENTIALS_FILE`. `/cult reload` reconnects when the block changes.

## Setup: one URI

Set `network.uri` to the same broker on every instance. Its scheme picks the transport:

| Broker | `uri` |
|---|---|
| Redis (and Valkey, KeyDB, Dragonfly) | `redis://:password@host:6379/0`, `redis://user:password@host:6379`, or `rediss://…` for TLS |
| NATS | `nats://token@host:4222`, or `tls://…` for TLS; several servers separated by commas |
| RedisBungee (Velocity only) | `redisbungee`: reuses RedisBungee's own Redis connection, nothing else to set |

An empty `uri` turns the network off. Any other scheme is an error at start or reload, and the instance stays local.
NATS accounts that authenticate with a `.creds` file (JWT and nkey) also set `network.nats-credentials-file`.

## The signing key

Every instance needs the same key. The first of these that is set is used:

1. `network.secret`.
2. On a Velocity proxy, its modern forwarding secret.
3. On a Paper server behind Velocity, the same forwarding secret, read from `proxies.velocity.secret` in
   `config/paper-global.yml` while `proxies.velocity.enabled` is on.
4. A key derived from the password (or `user:password`) of a Redis URI, or the token of a NATS URI.

A proxy and the servers behind it therefore agree with no extra setting, and so do instances sharing one broker
password. With none of these, the network stays off and the log asks for `network.secret`. Instances only share
channels with instances using the same key, so several networks can share one broker. When instances disagree on the
key, set the same `network.secret` everywhere.

Each instance logs one line when routing starts, naming its transport, its instance id, where its key came from and
the channel prefix the key gives, such as `cultac.1a2b3c4d.*`. Instances that cannot see each other show different
prefixes there; compare those lines to find the one whose key differs.

## Instance ids

Each instance needs an id no other instance uses, made of letters, digits, `_` and `-`. An empty `network.instance-id`
takes RedisBungee's proxy id, else `cloud.proxy-id`, else the instance's `host:port`. On a backend server, set it to
the name the proxy gives that server: alerts with the `server` scope match staff and players by that name.

## Commands across instances

A command that names a player on another instance runs there, with the sender's permissions, and the output comes
back to the sender: `profile` and the Java and Geyser packet logs, for example. `findings` and `history` look the
player up on the whole network. `spectate` connects the staff member to the player's server through their own proxy. `/cult alerts scope global`
receives alerts from every instance; `server` only from players on the staff member's own server.
