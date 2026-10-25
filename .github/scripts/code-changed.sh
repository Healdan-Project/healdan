#!/usr/bin/env bash
# Writes two step outputs so the CI workflows can skip their build steps and still report the checks master's ruleset
# requires.
#   code=false    when a pull request changes only docs (docs/, the docs site in site/, and Markdown files outside
#                 src/) or the desktop clients (clients/), none of which the Java build or the Docker image covers.
#   clients=true  when a pull request changes anything under clients/ (or the clients workflow itself).
# Pushes, and pull requests that change anything else, get code=true; pushes always get clients=true. The docs site's
# own checks run in docs-site.yml, and the clients' in clients.yml.
#
# Needs a checkout with fetch-depth 2: on pull_request, HEAD is the merge commit and HEAD^1 the base it merges into.
set -euo pipefail

code=true
clients=true
if [ "$GITHUB_EVENT_NAME" = pull_request ]; then
  # --no-renames lists a renamed file's old path too, so moving code into docs/, site/ or to a .md name counts as code.
  changed=$(git diff --name-only --no-renames HEAD^1 HEAD)
  echo "Files this pull request changes:"
  echo "$changed"
  clients=false
  if grep -qE '^clients/|^\.github/workflows/clients\.yml$' <<<"$changed"; then
    clients=true
  fi
  # The first grep -v finds a line that isn't docs or a client; an empty diff has one empty line, so it counts as code
  # too. The second finds Markdown under a src/ directory outside clients/, which can be a test fixture
  # (FormatConverterTest reads one).
  if ! grep -qvE '^docs/|^site/|\.md$|^clients/' <<<"$changed" && ! grep -qE '(^|/)src/' <<<"$(grep -v '^clients/' <<<"$changed" || true)"; then
    code=false
    echo "Only docs and clients changed, so the Java build steps are skipped."
  fi
  if [ "$clients" = false ]; then
    echo "No client changed, so the client builds are skipped."
  fi
fi
echo "code=$code" >> "$GITHUB_OUTPUT"
echo "clients=$clients" >> "$GITHUB_OUTPUT"
