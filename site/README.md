# Healdan's docs site

The source of https://docs.healdan.com. It's built with [Hugo](https://gohugo.io) and the
[Hextra](https://github.com/imfing/hextra) theme. The design is
[`docs/superpowers/specs/2026-10-23-docs-site-design.md`](../docs/superpowers/specs/2026-10-23-docs-site-design.md).

## Running it

You need Hugo 0.168.0, the standard edition (`brew install hugo` on macOS). The theme is vendored in
`_vendor/`, so you don't need Go or a network connection to build.

```bash
hugo server --source site                                    # preview at http://localhost:1313
hugo --source site --gc --minify --panicOnWarning            # the build CI runs, into site/public
python3 -m unittest discover -s .github/scripts/tests -v     # the site's tests
```

Run these from the repository's root. The preview server doesn't version its CSS file names, so after changing
`assets/css/custom.css`, force-reload the page or restart the server.

## Writing pages

The writing rules, interim pages, screenshots and the rule that docs change in the same pull request as the code
are on the Developer Guide's [Writing Docs](https://docs.healdan.com/developer/writing-docs/) page
(`content/developer/writing-docs.md`).

## Upgrading Hextra

Upgrading needs Go, because Hugo fetches modules with it:

```bash
cd site
hugo mod get github.com/imfing/hextra@vX.Y.Z
hugo mod vendor
```

`layouts/_partials/sidebar.html` is a copy of Hextra's with Healdan's changes marked `Healdan:`. Copy the new
version's file over it, re-apply those changes, and run the tests: `test_site_build.py` checks the nav.
