#!/usr/bin/env bash
# Builds Healdan Helper for Apple silicon (spec §5.12): Healdan Helper.app, carrying the LibreOffice extension, signed
# and, when the notary's key is given, notarised, in Healdan-Helper-<version>.dmg.
#
#   clients/helper/packaging/mac/package.sh <version>        e.g. 0.1.0, or 0.1.0-rc1 (from the tag helper-v0.1.0-rc1)
#
# Environment (all optional):
#   SIGN_IDENTITY   the Developer ID Application identity to sign with, as `security find-identity` names it. Unset,
#                   or not in the keychains, the app gets only an ad-hoc signature: it runs on the Mac that built it,
#                   and Gatekeeper refuses it anywhere else.
#   SIGN_KEYCHAIN   the keychain holding it (CI's temporary one); otherwise the usual search list.
#   NOTARY_KEY      the App Store Connect API key's .p8 file, with NOTARY_KEY_ID and NOTARY_ISSUER: with all three and
#                   a signing identity, the app and the disk image are notarised and stapled.
#   OUT_DIR         where the app and the disk image go; clients/helper/packaging/mac/out by default.
#
# The steps: a self-contained, single-file .NET publish for osx-arm64 with no .pdb, since any file in Contents/MacOS
# that isn't native code breaks the bundle's signature (the trial); the bundle, with healdan.oxt from
# clients/libreoffice/build.sh in Contents/Resources; signing inside-out (each native library, the binary, then the
# bundle) with the hardened runtime and a timestamp; codesign's strict check; notarising and stapling; the disk image,
# with a link to Applications, signed (and notarised) too. Any failure stops it.
set -euo pipefail

say() { printf '%s\n' "$*"; }
warn() { printf '\n*** %s ***\n\n' "$*" >&2; }
die() { printf 'package.sh: %s\n' "$*" >&2; exit 1; }

[ "$#" -eq 1 ] || die "usage: package.sh <version>, such as 0.1.0 or 0.1.0-rc1"
version=$1
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "'$version' isn't a version like 1.2.3 or 1.2.3-rc1"
numeric=${version%%-*} # what CFBundleShortVersionString and CFBundleVersion take
[ "$(uname -s)" = Darwin ] || die "it builds only on macOS (codesign, hdiutil)"
for tool in dotnet codesign hdiutil plutil ditto lipo python3; do
  command -v "$tool" >/dev/null || die "$tool isn't installed"
done

here=$(cd "$(dirname "$0")" && pwd)
helper=$(cd "$here/../.." && pwd)
repo=$(cd "$helper/../.." && pwd)
out=${OUT_DIR:-"$here/out"}
mkdir -p "$out"
out=$(cd "$out" && pwd)
app="$out/Healdan Helper.app"
dmg="$out/Healdan-Helper-$version.dmg"
work=$(mktemp -d "${TMPDIR:-/tmp}/healdan-package.XXXXXX")
trap 'rm -rf "$work"' EXIT
rm -rf "$app" "$dmg"

# --- Who signs ------------------------------------------------------------------------------------------------------

keychain=()
if [ -n "${SIGN_KEYCHAIN:-}" ]; then
  keychain=(--keychain "$SIGN_KEYCHAIN")
fi
identity=${SIGN_IDENTITY:-}
if [ -n "$identity" ] && ! security find-identity -v -p codesigning ${SIGN_KEYCHAIN:+"$SIGN_KEYCHAIN"} | grep -qF "\"$identity\""; then
  warn "The signing identity \"$identity\" isn't in the keychains"
  identity=
fi
if [ -n "$identity" ]; then
  signer=$identity
  timestamp=--timestamp
  say "Signing with $identity"
else
  signer=-
  timestamp=--timestamp=none
  warn "NOT SIGNED with a Developer ID: SIGN_IDENTITY is unset or not found. The app gets an ad-hoc signature only, so Gatekeeper refuses it on any other Mac, and it isn't notarised."
fi
notarise=false
if [ -n "${NOTARY_KEY:-}" ] && [ -n "${NOTARY_KEY_ID:-}" ] && [ -n "${NOTARY_ISSUER:-}" ]; then
  if [ -z "$identity" ]; then
    warn "NOT NOTARISED: the notary's key is set, but there's no signing identity"
  else
    [ -f "$NOTARY_KEY" ] || die "NOTARY_KEY ($NOTARY_KEY) isn't a file"
    notarise=true
  fi
elif [ -n "$identity" ]; then
  warn "NOT NOTARISED: NOTARY_KEY, NOTARY_KEY_ID and NOTARY_ISSUER aren't all set, so Gatekeeper refuses the app on other Macs"
fi

# --- The app --------------------------------------------------------------------------------------------------------

say "Publishing Healdan Helper $version for osx-arm64"
dotnet publish "$helper/Healdan.Helper/Healdan.Helper.csproj" \
  --configuration Release --runtime osx-arm64 --self-contained true --output "$work/publish" \
  -p:PublishSingleFile=true -p:DebugType=none -p:DebugSymbols=false \
  -p:Version="$numeric" -p:InformationalVersion="$version" -p:IncludeSourceRevisionInInformationalVersion=false \
  -nologo

# Only native code may sit in Contents/MacOS: a managed .dll, a .pdb or a .json there breaks the bundle's signature.
for file in "$work/publish"/*; do
  file -b "$file" | grep -q '^Mach-O' || die "the publish left $(basename "$file"), which isn't native code, beside the binary"
done
[ -f "$work/publish/HealdanHelper" ] || die "the publish made no HealdanHelper binary"

say "Building $app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$work/publish"/* "$app/Contents/MacOS/"
for library in "$app/Contents/MacOS"/*.dylib; do
  [ -e "$library" ] || continue
  # NuGet's native libraries are universal: Apple silicon needs only its own half.
  if lipo "$library" -verify_arch arm64 2>/dev/null && [ "$(lipo -archs "$library")" != arm64 ]; then
    lipo "$library" -thin arm64 -output "$library.arm64"
    mv "$library.arm64" "$library"
  fi
done
chmod 755 "$app/Contents/MacOS"/*
sed -e "s/@VERSION@/$numeric/g" "$here/Info.plist.in" > "$app/Contents/Info.plist"
! grep -q '@[A-Z_]*@' "$app/Contents/Info.plist" || die "Info.plist.in has a placeholder package.sh doesn't fill"
plutil -lint "$app/Contents/Info.plist" >/dev/null
printf 'APPL????' > "$app/Contents/PkgInfo"
sh "$repo/clients/libreoffice/build.sh" "$app/Contents/Resources/healdan.oxt"
chmod 644 "$app/Contents/Info.plist" "$app/Contents/PkgInfo" "$app/Contents/Resources/healdan.oxt"
xattr -cr "$app" 2>/dev/null || true # Finder info or a resource fork would make codesign refuse it

# --- Signing, inside out --------------------------------------------------------------------------------------------

sign() {
  codesign --force --options runtime "$timestamp" --sign "$signer" ${keychain[@]+"${keychain[@]}"} "$@"
}
for library in "$app/Contents/MacOS"/*.dylib; do
  [ -e "$library" ] || continue
  sign "$library"
done
sign --entitlements "$here/entitlements.plist" "$app/Contents/MacOS/HealdanHelper"
sign --entitlements "$here/entitlements.plist" "$app"
codesign --verify --strict --deep --verbose=2 "$app"
codesign --display --verbose=2 "$app" 2>&1 | grep -E '^(Identifier|Format|TeamIdentifier|Authority|Timestamp|Runtime Version|CodeDirectory)' || true

if [ "$notarise" = true ]; then
  notarise() { # <file> <what>
    local result status id
    say "Notarising $2; Apple usually answers within a few minutes"
    result=$(xcrun notarytool submit "$1" --key "$NOTARY_KEY" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER" \
      --wait --timeout 30m --output-format json) || true
    status=$(python3 -I -c 'import json, sys; print(json.loads(sys.stdin.read() or "{}").get("status", ""))' <<<"$result")
    id=$(python3 -I -c 'import json, sys; print(json.loads(sys.stdin.read() or "{}").get("id", ""))' <<<"$result")
    if [ "$status" != Accepted ]; then
      [ -z "$id" ] || xcrun notarytool log "$id" --key "$NOTARY_KEY" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER" >&2 || true
      die "notarising $2 ended with '${status:-no answer}'"
    fi
    say "Notarised $2 ($id)"
  }
  ditto -c -k --keepParent "$app" "$work/app.zip"
  notarise "$work/app.zip" "the app"
  xcrun stapler staple "$app"
  xcrun stapler validate "$app"
  spctl --assess --type execute --verbose=2 "$app"
elif [ -n "$identity" ]; then
  # Before notarisation Gatekeeper may say "rejected": shown, not fatal.
  spctl --assess --type execute --verbose=2 "$app" || true
fi

# --- The disk image -------------------------------------------------------------------------------------------------

say "Building $dmg"
mkdir "$work/image"
ditto "$app" "$work/image/Healdan Helper.app"
ln -s /Applications "$work/image/Applications"
made=false
for attempt in 1 2 3; do # hdiutil is sometimes "busy" on CI's runners
  if hdiutil create -volname "Healdan Helper" -srcfolder "$work/image" -fs APFS -format ULFO -ov "$dmg" >/dev/null; then
    made=true
    break
  fi
  say "hdiutil failed (attempt $attempt); trying again"
  sleep 5
done
[ "$made" = true ] || die "hdiutil couldn't make the disk image"
codesign --force "$timestamp" --sign "$signer" ${keychain[@]+"${keychain[@]}"} "$dmg"
codesign --verify --strict --verbose=2 "$dmg"
if [ "$notarise" = true ]; then
  notarise "$dmg" "the disk image"
  xcrun stapler staple "$dmg"
  xcrun stapler validate "$dmg"
  spctl --assess --type open --context context:primary-signature --verbose=2 "$dmg"
fi

say ""
say "Built $dmg"
say "  sha256 $(shasum -a 256 "$dmg" | cut -d' ' -f1)"
if [ -z "$identity" ]; then
  warn "NOT SIGNED with a Developer ID (ad-hoc only), and not notarised"
elif [ "$notarise" = false ]; then
  warn "Signed with $identity, but NOT NOTARISED"
else
  say "  signed with $identity, notarised and stapled"
fi
