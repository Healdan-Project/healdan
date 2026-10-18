#!/usr/bin/env bash
# Writes the step output code=false when a pull request changes only docs (docs/ and Markdown files), so the CI
# workflows can skip their build steps and still report the checks master's ruleset requires. Pushes, and pull
# requests that change anything else, get code=true.
#
# Needs a checkout with fetch-depth 2: on pull_request, HEAD is the merge commit and HEAD^1 the base it merges into.
set -euo pipefail

code=true
if [ "$GITHUB_EVENT_NAME" = pull_request ]; then
  changed=$(git diff --name-only HEAD^1 HEAD)
  echo "Files this pull request changes:"
  echo "$changed"
  # grep -v finds a line that isn't docs; an empty diff has one empty line, so it counts as code too.
  if ! grep -qvE '^docs/|\.md$' <<<"$changed"; then
    code=false
    echo "Only docs changed, so the build steps are skipped."
  fi
fi
echo "code=$code" >> "$GITHUB_OUTPUT"
