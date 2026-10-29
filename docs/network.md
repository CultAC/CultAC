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
server. `/cult findplayer` counts the client brands of everyone online on the network, and
`/cult findplayer brand=<text>` lists the players whose brand contains that text. While the network runs, alerts that
`alerts.proxy.receive` takes from the proxy are not shown, as the network already brings them.
