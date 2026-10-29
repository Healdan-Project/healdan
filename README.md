# Healdan

Healdan is a modified version of [LogicalDOC Community Edition](https://github.com/logicaldoc/community), © 2006-2027 LOGICALDOC Srl. The Healdan Project has been changing it since 2026, and the commit history records each change and its date. Like LogicalDOC Community Edition, Healdan is free software under the GNU Lesser General Public License, version 3 (see [LICENSE](LICENSE)). LogicalDOC is a trademark of LOGICALDOC Srl, which doesn't endorse Healdan.

Healdan is a self-hosted, open-source, matter-centric document management system: a smaller alternative to iManage
Work and NetDocuments, built on LogicalDOC Community Edition.

## Status

Healdan is experimental and in early development, as it grows from LogicalDOC Community Edition into a product of
its own. Its features, data model and API can change between releases, and it hasn't had a security audit. Try it
with data you can afford to lose, and keep backups.

To the extent the law allows, Healdan is provided "as is", without warranty of any kind, express or implied, and the
Healdan Project and its contributors aren't liable for any loss or damage that comes from using it. Sections 15 and
16 of the GNU General Public License, version 3 ([GPL-3.0.txt](GPL-3.0.txt)), which the LGPL incorporates, set this
out. You use Healdan at your own risk.

## Documentation

Healdan's docs are at https://docs.healdan.com:

- [Install](https://docs.healdan.com/install/): run Healdan on your own server with Docker.
- [User Guide](https://docs.healdan.com/user/): file, find and work with documents in your matters.
- [Admin Guide](https://docs.healdan.com/admin/): set up Stores, clients and matters, templates, users and permissions.
- [Developer Guide](https://docs.healdan.com/developer/): build Healdan from source and work on it.

Their source is in [`site/`](site/), and they change in the same pull request as the code they describe.

## Licence

Healdan's code is free software under the GNU Lesser General Public License, version 3 ([LICENSE](LICENSE)), which
adds permissions to the GNU General Public License, version 3 ([GPL-3.0.txt](GPL-3.0.txt)). The docs site's content,
in `site/content/`, is licensed under CC BY 4.0; [site/LICENSE](site/LICENSE) covers the rest of `site/`.
