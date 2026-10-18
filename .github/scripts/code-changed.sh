#!/usr/bin/env bash
# Writes the step output code=false when a pull request changes only docs (docs/ and Markdown files outside src/), so
# the CI workflows can skip their build steps and still report the checks master's ruleset requires. Pushes, and pull
# requests that change anything else, get code=true.
#
# Needs a checkout with fetch-depth 2: on pull_request, HEAD is the merge commit and HEAD^1 the base it merges into.
set -euo pipefail

code=true
if [ "$GITHUB_EVENT_NAME" = pull_request ]; then
  # --no-renames lists a renamed file's old path too, so moving code into docs/ or to a .md name counts as code.
  changed=$(git diff --name-only --no-renames HEAD^1 HEAD)
  echo "Files this pull request changes:"
  echo "$changed"
  # The first grep -v finds a line that isn't docs; an empty diff has one empty line, so it counts as code too. The
  # second finds Markdown under a src/ directory, which can be a test fixture (FormatConverterTest reads one).
  if ! grep -qvE '^docs/|\.md$' <<<"$changed" && ! grep -qE '(^|/)src/' <<<"$changed"; then
    code=false
    echo "Only docs changed, so the build steps are skipped."
  fi
fi
echo "code=$code" >> "$GITHUB_OUTPUT"
