#!/usr/bin/env bash
#
# Merges upstream LogicalDOC Community into a pull request against this fork's
# master. Run by .github/workflows/upstream-sync.yml, which documents the token.
#
# Requires git (with user.name/user.email set, run from a full clone of this
# repo with push access to origin), gh (GH_TOKEN and GH_REPO set), jq and
# python3.
#
# Healdan's own modules (healdan-*) follow upstream's version. When a clean
# merge moves the root POM to a new LogicalDOC release, a follow-up commit
# sets their POM parent and plugin.xml versions to match.
#
# Exit status: 0 when there is nothing to do or a PR was opened or updated;
# 1 when the merge conflicted (a draft PR listing the conflicts is still
# opened) or anything else went wrong.
#
# Everything lives in main() so bash has parsed the whole script before
# `git checkout` can replace this file on disk.

set -euo pipefail

UPSTREAM_URL=${UPSTREAM_URL:-https://github.com/logicaldoc/community.git}
UPSTREAM_BRANCH=${UPSTREAM_BRANCH:-master}
BASE_BRANCH=${BASE_BRANCH:-master}
BRANCH_PREFIX=${BRANCH_PREFIX:-upstream-sync/}
LOG_LIMIT=${LOG_LIMIT:-50}

summary() {
  if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
    printf '%s\n' "$*" >>"$GITHUB_STEP_SUMMARY"
  fi
}

# Prints each line of $1 as a Markdown bullet in code formatting.
bullets() {
  local line
  while IFS= read -r line; do
    printf -- "- \`%s\`\n" "$line"
  done <<<"$1"
}

notice() {
  echo "::notice::$*"
  summary "$*"
}

warning() {
  echo "::warning::$*"
  summary "**Warning:** $*"
}

# Prints the project version of the root pom.xml at revision $1.
pom_version() {
  git show "$1:pom.xml" | python3 -c '
import sys, xml.etree.ElementTree as ET
version = ET.parse(sys.stdin).getroot().findtext("{http://maven.apache.org/POM/4.0.0}version", "").strip()
if not version:
    sys.exit("pom.xml has no project <version>")
print(version)
'
}

# Healdan's modules name the root POM as their <parent>. If that version falls
# behind the root's, Maven resolves the stock LogicalDOC release of the old
# version instead of this repo's build, and the fork's own classes go missing.
# Sets the parent version of each healdan-*/pom.xml whose parent is the root,
# and the version of each healdan-* plugin.xml, to $1 in the working tree.
# Prints each file it changed.
follow_version() {
  python3 - "$1" <<'PY'
import glob, re, sys, xml.etree.ElementTree as ET

version = sys.argv[1]
M = "{http://maven.apache.org/POM/4.0.0}"
root = ET.parse("pom.xml").getroot()
coords = (root.findtext(M + "groupId"), root.findtext(M + "artifactId"))

def rewrite(path, pattern):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    text, n = re.subn(pattern, lambda m: m.group(1) + version + m.group(2), text, count=1, flags=re.S)
    if n != 1:
        sys.exit(f"{path}: found no version to set")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(path)

for pom in sorted(glob.glob("healdan-*/pom.xml")):
    parent = ET.parse(pom).getroot().find(M + "parent")
    if parent is None or (parent.findtext(M + "groupId"), parent.findtext(M + "artifactId")) != coords:
        continue
    if parent.findtext(M + "version") != version:
        rewrite(pom, r"(<parent>(?:(?!</parent>).)*?<version>)[^<]*(</version>)")

for xml in sorted(glob.glob("healdan-*/src/main/resources/plugin.xml")):
    plugin = ET.parse(xml).getroot()
    if plugin.tag == "plugin" and plugin.get("version") != version:
        rewrite(xml, r'(<plugin\s[^>]*?\sversion=")[^"]*(")')
PY
}

main() {
  local base="origin/$BASE_BRANCH"
  local upstream="upstream/$UPSTREAM_BRANCH"
  local upstream_web=${UPSTREAM_URL%.git}
  local upstream_name=${upstream_web#https://github.com/}

  git fetch --no-tags origin "+refs/heads/$BASE_BRANCH:refs/remotes/$base"
  git fetch --no-tags "$UPSTREAM_URL" "+refs/heads/$UPSTREAM_BRANCH:refs/remotes/$upstream"

  local upstream_sha upstream_short
  upstream_sha=$(git rev-parse "$upstream")
  upstream_short=$(git rev-parse --short "$upstream")

  if git merge-base --is-ancestor "$upstream" "$base"; then
    notice "$BASE_BRANCH already contains $upstream_name $UPSTREAM_BRANCH ($upstream_short). Nothing to do."
    return 0
  fi

  # Reuse the oldest open sync PR whose head branch lives in this repo.
  local prs existing pr_number="" pr_draft="" branch=""
  prs=$(gh pr list --state open --base "$BASE_BRANCH" --limit 100 \
    --json number,isDraft,headRefName,isCrossRepository)
  existing=$(jq -r --arg prefix "$BRANCH_PREFIX" '
    [.[] | select((.isCrossRepository | not) and (.headRefName | startswith($prefix)))]
    | sort_by(.number) | first // empty
    | "\(.number) \(.isDraft) \(.headRefName)"' <<<"$prs")

  if [[ -n $existing ]]; then
    read -r pr_number pr_draft branch <<<"$existing"
    if [[ $pr_draft == true ]]; then
      # A draft means someone is still resolving conflicts; pushing more
      # merges now would only get in their way.
      warning "Sync PR #$pr_number is still a draft. Resolve its conflicts and mark it ready for review; newer upstream commits will be merged into it on the next run."
      return 0
    fi
    git fetch --no-tags origin "+refs/heads/$branch:refs/remotes/origin/$branch"
    if git merge-base --is-ancestor "$upstream" "origin/$branch"; then
      notice "Sync PR #$pr_number already contains $upstream_name $UPSTREAM_BRANCH ($upstream_short). Nothing to do."
      return 0
    fi
    git checkout -B "$branch" "origin/$branch"
  else
    branch="$BRANCH_PREFIX$(date -u +%Y-%m-%d)"
    # A branch of that name with no open PR belongs to a closed PR; leave it be.
    if git ls-remote --exit-code --heads origin "$branch" >/dev/null; then
      branch="$branch-${GITHUB_RUN_NUMBER:-$(date -u +%H%M%S)}"
    fi
    git checkout -B "$branch" "$base"
  fi

  # Always a real merge (never squash/rebase) so upstream stays an ancestor
  # and the next sync only has to merge what is new.
  local message="Merge $upstream_name $UPSTREAM_BRANCH ($upstream_short)"
  local conflicts=""
  if ! git merge --no-ff -m "$message" "$upstream"; then
    conflicts=$(git diff --name-only --diff-filter=U)
    if [[ -z $conflicts ]]; then
      echo "::error::git merge failed without reporting conflicts."
      return 1
    fi
    # Commit the markers so the conflicts are visible in the PR.
    local nl=$'\n'
    git add -A
    git commit -m "$message (conflicts unresolved)" \
      -m "Conflicted files:" -m "  ${conflicts//$nl/$nl  }"
  fi

  # On a clean merge, keep Healdan's own modules on upstream's version. After
  # conflicts the tree isn't trustworthy yet; whoever resolves them does this.
  local version="" base_version="" followed=""
  if [[ -z $conflicts ]]; then
    version=$(pom_version HEAD)
    base_version=$(pom_version "$base")
    followed=$(follow_version "$version")
    if [[ -n $followed ]]; then
      git add --pathspec-from-file=- <<<"$followed"
      git commit -m "Follow upstream to $version" \
        -m "Healdan's own modules inherit from the root POM, which upstream moved to $version."
      notice "Upstream moved to $version; set Healdan's own modules to match."
    fi
  fi

  git push origin "HEAD:refs/heads/$branch"

  local count commits title
  count=$(git rev-list --count "$base..$upstream")
  commits="$count commits"
  [[ $count == 1 ]] && commits="1 commit"
  title="Sync upstream $upstream_name $UPSTREAM_BRANCH ($commits)"
  [[ -n $conflicts ]] && title="[conflicts] $title"

  local body_file
  body_file=$(mktemp)
  {
    echo "Merges **$commits** from [$upstream_name]($upstream_web) \`$UPSTREAM_BRANCH\` (up to [\`$upstream_short\`]($upstream_web/commit/$upstream_sha)) into \`$BASE_BRANCH\`."
    if [[ $version != "$base_version" ]]; then
      echo
      echo "Upstream moves from \`$base_version\` to \`$version\`. Healdan's own modules (\`healdan-*\`) follow upstream's version, so a \"Follow upstream to $version\" commit after the merge sets their POM parent and \`plugin.xml\` versions to \`$version\` too."
    fi
    echo
    echo "> [!IMPORTANT]"
    echo "> Merge this PR with **Create a merge commit**. Squash or rebase would cut the link to upstream history, and the next sync would try to merge every upstream commit again."
    if [[ -n $conflicts ]]; then
      local pre_merge
      pre_merge=$(git rev-parse --short HEAD^1)
      echo
      echo "## Merge conflicts"
      echo
      echo "\`git merge\` stopped on conflicts in these files. The merge was committed **with the conflict markers** so they show up in this PR, and the PR is a draft so it can't be merged by accident."
      echo
      bullets "$conflicts"
      echo
      echo "Either fix the markers on the branch and push a follow-up commit, or redo the merge for a clean history:"
      echo
      echo '```bash'
      echo "git fetch origin"
      echo "git checkout -B $branch $pre_merge"
      echo "git fetch $UPSTREAM_URL $UPSTREAM_BRANCH"
      echo "git merge --no-ff $upstream_sha   # resolve, then git commit"
      echo "git push --force-with-lease origin $branch"
      echo '```'
      echo
      echo "Then mark the PR ready for review. Pushes from your own account trigger the build as usual."
    fi
    echo
    echo "## Upstream commits"
    echo
    echo "<details><summary><code>git log --oneline $BASE_BRANCH..upstream/$UPSTREAM_BRANCH</code> ($(
      if ((count > LOG_LIMIT)); then echo "newest $LOG_LIMIT of $count"; else echo "all $count"; fi
    ))</summary>"
    echo
    echo '```text'
    git log --oneline --no-decorate -n "$LOG_LIMIT" "$base..$upstream"
    if ((count > LOG_LIMIT)); then echo "... and $((count - LOG_LIMIT)) more"; fi
    echo '```'
    echo
    echo "</details>"
    if [[ -n ${GITHUB_RUN_ID:-} ]]; then
      echo
      echo "_Opened by the [upstream sync workflow](${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/$GITHUB_RUN_ID)._"
    fi
  } >"$body_file"

  local pr_url
  if [[ -n $pr_number ]]; then
    gh pr edit "$pr_number" --title "$title" --body-file "$body_file"
    if [[ -n $conflicts ]]; then
      gh pr ready "$pr_number" --undo
    fi
    pr_url=$(gh pr view "$pr_number" --json url --jq .url)
  else
    local create_args=(--base "$BASE_BRANCH" --head "$branch" --title "$title" --body-file "$body_file")
    if [[ -n $conflicts ]]; then
      create_args+=(--draft)
    fi
    pr_url=$(gh pr create "${create_args[@]}")
  fi
  rm -f "$body_file"

  if [[ -n $conflicts ]]; then
    summary "Merge of $upstream_name $UPSTREAM_BRANCH ($upstream_short) **conflicted**: $pr_url"
    summary "$(bullets "$conflicts")"
    echo "::error::Upstream merge conflicted in $(wc -l <<<"$conflicts" | tr -d ' ') file(s). Draft PR: $pr_url"
    return 1
  fi
  notice "Merged $commits from $upstream_name $UPSTREAM_BRANCH ($upstream_short): $pr_url"
}

main "$@"
