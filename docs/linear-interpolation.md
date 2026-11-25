# Linear entity interpolation

Minecraft 26.3 changed how a client interpolates the movement of other living entities between position updates.
AntiStutter and Interpolation Fix restore vanilla's linear interpolation for them. CultAC models whichever kind a
client uses, so it has to know which one that is: the client says so over a plugin channel. This page is for server
owners and for developers of clients that want to be supported.

## Configuration

```yaml
linear-interpolation-opt-in: false
linear-interpolation-channels:
  - antistutter:linear_3
```

- `linear-interpolation-opt-in` turns the offer on. It only has an effect on a Velocity proxy running CultAC.
- `linear-interpolation-channels` lists the channels offered, at most 8. Entries must be `namespace:path` channel ids
  outside the `minecraft` namespace; CultAC's own bridge channel and `interpolationfix:linear_3` cannot be used.
  Entries that cannot be used are left out with a warning in the log. A list with no usable entry means
  `antistutter:linear_3`.

A change to either key applies from a player's next join or server switch.

CultAC on Velocity is the only component that offers these channels. Registrations of a listed channel, or of
`interpolationfix:linear_3`, by the proxy, by backend servers or by other plugins are not passed on to clients. This
happens whether the opt-in is on or off. List only channels made for this handshake: a channel another plugin needs
to register with clients stops working once it is listed.

## The handshake

With the opt-in on, CultAC sends a 26.3 or newer Java client one `minecraft:register` frame naming every listed
channel just before each play login.

A client is supported when it does the following:

1. When the server registers one of the listed channels, give every living entity other than a shulker vanilla's
   `LinearInterpolationHandler` with 3 steps, from the moment the entity is created.
2. Register the channel back with `minecraft:register` in play.
3. Never send anything on the channel.

A registration lasts until the client unregisters the channel or the connection reconfigures. After a reconfiguration
the server offers the channels again before the next play login, and the client registers one back again.

AntiStutter and Interpolation Fix implement this handshake on `antistutter:linear_3`.

## Other clients

- A client that implements the handshake on `antistutter:linear_3` is supported as it is.
- A client that implements the same handshake on its own channel is supported by adding that channel to
  `linear-interpolation-channels`.
- A client that reports its interpolation through another protocol needs support added in CultAC.
