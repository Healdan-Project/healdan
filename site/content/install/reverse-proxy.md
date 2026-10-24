---
title: Reverse Proxy
weight: 4
---

Out of the box, Healdan listens on `127.0.0.1:8080`, so only the machine running Docker can reach it, and only
over plain HTTP. To use it from other machines, put a reverse proxy in front of it that handles HTTPS.

Whichever proxy you use:

- **Set `LOGICALDOC_PUBLIC_URL`** in `.env` to the public HTTPS address, so links and emails use it.
- **Forwarded headers.** The proxy sends `X-Forwarded-For` and `X-Forwarded-Proto`. Tomcat trusts them only from
  private, loopback and link-local addresses, so Healdan sees HTTPS and each user's real IP address only when the
  proxy reaches it from one of those. The sign-in throttle counts failures by that address: see
  [The Sign-In Throttle Behind a Proxy](#the-sign-in-throttle-behind-a-proxy).
- **WebSockets and large uploads.** The proxy must forward WebSockets (Healdan pushes live updates at
  `/wk-event`) and allow large uploads and long requests.
- **Office add-ins need HTTPS.** Office loads an add-in's pages over HTTPS, and those pages can't call a plain HTTP
  address. So an add-in that uses Healdan's [API](../../developer/api/) needs Healdan behind the proxy, with a
  certificate the computer running Office trusts.

{{< tabs >}}

{{< tab name="Nginx Proxy Manager" >}}

With Nginx Proxy Manager (NPM) on the same Docker host, NPM runs in its own container, so it reaches Healdan over
a shared Docker network rather than a port on the host.

1. **Find NPM's network.** Run `docker network ls`. If NPM was started from a folder named `npm`, the network is
   `npm_default`. To confirm, run `docker inspect <npm-container> --format '{{json .NetworkSettings.Networks}}'`.
2. **Edit `.env` and restart.** Set these, then run `docker compose up -d`:

   ```bash {filename=".env"}
   COMPOSE_FILE=compose.yaml:compose.proxy.yaml
   PROXY_NETWORK=npm_default
   LOGICALDOC_PUBLIC_URL=https://docs.example.org
   ```

   The app no longer publishes a port on the host. NPM reaches it on that network as `logicaldoc:8080`.
3. **Add a proxy host in NPM** (Hosts → Proxy Hosts → Add Proxy Host):
   - **Details:** Domain Names `docs.example.org`, Scheme `http`, Forward Hostname `logicaldoc`, Forward Port
     `8080`. Turn on **Block Common Exploits** and **Websockets Support**.
   - **SSL:** request a new Let's Encrypt certificate, and turn on **Force SSL** and **HTTP/2 Support**.
   - **Advanced**, under Custom Nginx Configuration, so large uploads and long conversions aren't cut off:

     ```nginx
     client_max_body_size 0;
     proxy_request_buffering off;
     proxy_read_timeout 300s;
     proxy_send_timeout 300s;
     ```

4. **Save, then open** `https://docs.example.org`.

| Symptom | Cause |
|---|---|
| `network npm_default declared as external, but could not be found` | `PROXY_NETWORK` doesn't match NPM's network |
| 502 Bad Gateway | NPM isn't on that network, or the app isn't healthy. Check `docker compose ps`; `docker network inspect <network>` should list both containers |
| The app loads but live updates fail | Turn on Websockets Support |
| 413 Request Entity Too Large | Add the Advanced snippet |

{{< /tab >}}

{{< tab name="NPM on Another Machine" >}}

With NPM on another machine there's no Docker network to share, so you don't use the proxy overlay. Instead the
app publishes its port on the Healdan host's LAN address, and NPM forwards to that address. The examples use
`192.168.1.20` for the Healdan host and `192.168.1.10` for NPM's machine.

1. **Give the Healdan host a fixed address,** for example with a DHCP reservation on your router. NPM forwards to
   that address, and the app won't start if the host loses it.
2. **Edit `.env` and restart.** Leave `compose.proxy.yaml` out of `COMPOSE_FILE`, and set:

   ```bash {filename=".env"}
   LOGICALDOC_BIND=192.168.1.20
   LOGICALDOC_PUBLIC_URL=https://docs.example.org
   ```

   Then run `docker compose up -d`. The app listens on `192.168.1.20:8080`, or on `LOGICALDOC_PORT` if you set
   it. `LOGICALDOC_BIND=0.0.0.0` works too, but it publishes the port on every network the host is on.
3. **Check that NPM's machine can reach it.** On NPM's machine, run:

   ```bash
   curl -sI http://192.168.1.20:8080/
   ```

   Any `HTTP/1.1` status line means it can. A timeout or `Connection refused` means it can't: check the address,
   the port, and any firewall between the two machines.
4. **Add a proxy host in NPM** as in the same-host steps, but on the Details tab set Forward Hostname / IP
   `192.168.1.20` and Forward Port `8080` (or your `LOGICALDOC_PORT`). Everything else is the same: Block Common
   Exploits, Websockets Support, the SSL tab and the Advanced snippet.
5. **Save, then open** `https://docs.example.org`.
6. **Let only NPM's machine reach the port.** Until you do, anything on your LAN can bypass NPM and talk plain HTTP
   to `192.168.1.20:8080`, and hand Healdan a false client IP.
   - Docker's published ports bypass `ufw`, so a `ufw` rule won't stop them. Add a rule to Docker's `DOCKER-USER`
     chain on the Healdan host instead. With `eth0` as its LAN interface (`ip -br addr` lists them):

     ```bash
     sudo iptables -I DOCKER-USER -i eth0 -p tcp -m conntrack --ctorigdstport 8080 --ctdir ORIGINAL ! -s 192.168.1.10 -j DROP
     ```

     Put your `LOGICALDOC_PORT` in place of `8080` if you changed it. The rule only affects connections that arrive
     on `eth0` for that port, so the app's own outgoing traffic is unaffected.
   - The rule is gone after a reboot unless you save it, for example with the `iptables-persistent` package on
     Debian and Ubuntu.
   - If your router or firewall sits between the Healdan host and the rest of your LAN, you can allow only NPM's
     address there instead.

**The network between them:**

- NPM and the app talk plain HTTP, so passwords and documents cross that network unencrypted. That's fine on a
  network you trust. Otherwise connect the two machines over a VPN such as WireGuard or Tailscale, and use the
  Healdan host's VPN address for `LOGICALDOC_BIND` and the Forward Hostname.
- Tomcat trusts forwarded headers only from `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`
  (which includes Tailscale's addresses), loopback, link-local and private IPv6 addresses.
  - If NPM reaches the Healdan host from a public address, Healdan sees plain HTTP, and it records NPM's address as
    every user's IP. The sign-in throttle then blocks everyone after 20 failed sign-ins in a row, whoever made
    them.
  - Use a VPN rather than publishing port 8080 to the internet.

| Symptom | Cause |
|---|---|
| `cannot assign requested address` when the app starts | `LOGICALDOC_BIND` isn't one of this host's addresses, for example after DHCP gave it a new one |
| 502 Bad Gateway or 504 Gateway Time-out | NPM can't reach the app. Run the `curl` check from step 3 on NPM's machine. If it times out since you added the firewall rule, check that the rule names NPM's address |
| Every user shows the same IP address | NPM reaches the Healdan host from a public address (see above) |

The WebSocket and 413 rows of the same-host table apply here too.

{{< /tab >}}

{{< tab name="Caddy" >}}

Run Caddy on the same host. It handles WebSockets and forwarded headers itself:

```text {filename="Caddyfile"}
docs.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

{{< /tab >}}

{{< tab name="nginx" >}}

Run nginx on the same host:

```nginx
# In the http block:
map $http_upgrade $connection_upgrade { default upgrade; '' close; }

server {
    listen 443 ssl;
    http2 on;
    server_name docs.example.org;
    # ssl_certificate / ssl_certificate_key ...

    client_max_body_size 0;
    proxy_request_buffering off;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
    }
}
```

{{< /tab >}}

{{< tab name="Other Proxy on Another Machine" >}}

Set `LOGICALDOC_BIND` in `.env` to this host's LAN address, run `docker compose up -d`, and point the proxy at that
address in place of `127.0.0.1`. The NPM on Another Machine tab applies to any proxy: the fixed address, the
firewall rule, and which addresses Tomcat trusts forwarded headers from.

{{< /tab >}}

{{< /tabs >}}

## The Sign-In Throttle Behind a Proxy

Healdan blocks a network address for 15 minutes after 20 failed sign-ins in a row from it, and a username after five
wrong passwords ([Healdan Settings](../../admin/healdan-settings/#the-sign-in-throttle) has the details). The
address is the one Tomcat works out from `X-Forwarded-For`, so:

- **Each person needs their own address.** Behind a proxy that reaches Healdan from a public address, everyone has
  the proxy's, and one person's mistyped passwords block everyone. Have the proxy reach Healdan over a private
  network or a VPN instead.
- **Known limitation: a machine on a private network can claim any address.** Tomcat trusts `X-Forwarded-For` from
  every private, loopback and link-local address, not just from your proxy. nginx and NPM also pass on a header
  the client made up, with the client's own address added at the end. So a machine on your LAN or VPN can send a
  false address, through such a proxy or straight to the app's port, and Healdan believes it.
  - It can claim a new address for each attempt, and never meet the per-address limit. The per-username limit
    still holds.
  - It can claim `127.0.0.1`. LogicalDOC never blocks the `admin` account signing in from that address, so it can
    try `admin`'s password without any limit.
  - So give `admin` a long password that isn't used anywhere else, and keep the app's port reachable only from the
    proxy, as the NPM on Another Machine tab describes.
