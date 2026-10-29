---
title: Install
linkTitle: Install
weight: 1
cascade:
  type: docs
---
How to run Healdan on your own server. Healdan runs as a Docker Compose stack: a MariaDB database and the Healdan app.

{{< callout type="warning" >}}
**Healdan is experimental and in early development,** as it grows from LogicalDOC Community Edition into a product
of its own. Its features, data model and API can change between releases, an upgrade can need work by hand (see
[Known Limitations](known-limitations/)), and it hasn't had a security audit. Try it with data you can afford to
lose, and keep [backups](backup-and-restore/).

To the extent the law allows, Healdan is provided "as is", without warranty of any kind, express or implied, and the
Healdan Project and its contributors aren't liable for any loss or damage that comes from using it. Sections 15 and
16 of the [GNU General Public License, version 3](https://github.com/Healdan-Project/healdan/blob/master/GPL-3.0.txt),
which Healdan's licence incorporates, set this out. You use Healdan at your own risk.
{{< /callout >}}

{{< cards >}}
  {{< card link="quick-start/" title="Quick Start" icon="play" >}}
  {{< card link="what-runs/" title="What Runs" icon="server" >}}
  {{< card link="configuration/" title="Configuration" icon="cog" >}}
  {{< card link="reverse-proxy/" title="Reverse Proxy" icon="globe-alt" >}}
  {{< card link="office-integration/" title="Office Integration" icon="desktop-computer" >}}
  {{< card link="backup-and-restore/" title="Backup and Restore" icon="archive" >}}
  {{< card link="upgrading/" title="Upgrading" icon="arrow-up" >}}
  {{< card link="host-folders/" title="Host Folders Instead of Volumes" icon="folder" >}}
  {{< card link="troubleshooting/" title="Troubleshooting" icon="support" >}}
  {{< card link="known-limitations/" title="Known Limitations" icon="information-circle" >}}
{{< /cards >}}
