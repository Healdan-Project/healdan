---
title: Reverse Proxy
weight: 4
---

Out of the box, Healdan listens on `127.0.0.1:8080`, so only the machine running Docker can reach it, and only
over plain HTTP. To use it from other machines, put a reverse proxy in front of it that handles HTTPS.

Whichever proxy you use, set `LOGICALDOC_PUBLIC_URL` in `.env` to the public HTTPS address, so links and emails use
it. Forwarded headers are trusted only from private and loopback addresses.

{{< tabs >}}

{{< tab name="Nginx Proxy Manager" >}}

Nginx Proxy Manager (NPM) runs in its own container, so it reaches Healdan over a shared Docker network rather than
a port on the host.

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
     `8080`. Turn on **Block Common Exploits** and **Websockets Support**: Healdan pushes live updates over a
     WebSocket at `/wk-event`.
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

{{< tab name="On Another Machine" >}}

Set `LOGICALDOC_BIND` in `.env` to this host's LAN address, run `docker compose up -d`, and point the proxy at
that address and port `8080`. The proxy must forward WebSockets and allow large uploads, as in the other tabs.

{{< /tab >}}

{{< /tabs >}}
