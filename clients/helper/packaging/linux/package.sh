#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Builds Healdan Helper for Ubuntu (spec §5.12): a .deb for amd64 or arm64 that installs the helper in
# /opt/healdan-helper with the LibreOffice extension, registers healdan: with a desktop entry, and starts the helper at
# sign-in.
#
#   clients/helper/packaging/linux/package.sh <version> <arch>     e.g. 0.1.0 amd64, or 0.1.0-rc1 arm64
#
# Environment (optional):
#   OUT_DIR   where the package goes; clients/helper/packaging/linux/out by default.
#
# The steps: a self-contained .NET publish for linux-x64 or linux-arm64 (not single-file: that is the Mac's signature
# rule) with no .pdb; the package tree; dpkg-deb, with everything owned by root; then the checks. The Debian version
# is the version with its first - made a ~, so that 0.1.0-rc1 sorts before 0.1.0. Any failure stops it.
set -euo pipefail

say() { printf '%s\n' "$*"; }
die() { printf 'package.sh: %s\n' "$*" >&2; exit 1; }

[ "$#" -eq 2 ] || die "usage: package.sh <version> <arch>, such as 0.1.0 amd64 or 0.1.0-rc1 arm64"
version=$1
arch=$2
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "'$version' isn't a version like 1.2.3 or 1.2.3-rc1"
case "$arch" in
  amd64) rid=linux-x64 ;;
  arm64) rid=linux-arm64 ;;
  *) die "'$arch' isn't an architecture: use amd64 or arm64" ;;
esac
numeric=${version%%-*}
debian=${version/-/\~} # the first - only
for tool in dotnet dpkg-deb du sed python3; do
  command -v "$tool" >/dev/null || die "$tool isn't installed"
done

here=$(cd "$(dirname "$0")" && pwd)
helper=$(cd "$here/../.." && pwd)
repo=$(cd "$helper/../.." && pwd)
out=${OUT_DIR:-"$here/out"}
mkdir -p "$out"
out=$(cd "$out" && pwd)
deb="$out/Healdan-Helper-$version-$arch.deb"
work=$(mktemp -d "${TMPDIR:-/tmp}/healdan-package.XXXXXX")
trap 'rm -rf "$work"' EXIT
rm -f "$deb"

# --- The publish ----------------------------------------------------------------------------------------------------

say "Publishing Healdan Helper $version for $rid"
dotnet publish "$helper/Healdan.Helper/Healdan.Helper.csproj" \
  --configuration Release --runtime "$rid" --self-contained true --output "$work/publish" \
  -p:DebugType=none -p:DebugSymbols=false \
  -p:Version="$numeric" -p:InformationalVersion="$version" -p:IncludeSourceRevisionInInformationalVersion=false \
  -nologo
[ -f "$work/publish/HealdanHelper" ] || die "the publish made no HealdanHelper binary"
# The runtime's LTTng trace provider links liblttng-ust.so.0, which Ubuntu 26.04 no longer has (it ships .so.1), and it
# loads only when tracing is switched on, so it stays out rather than leave an unmet library in the package.
rm -f "$work/publish/libcoreclrtraceptprovider.so"

# --- The tree -------------------------------------------------------------------------------------------------------

say "Building the package tree"
root="$work/root"
mkdir -p "$root/opt/healdan-helper" "$root/usr/share/applications" "$root/etc/xdg/autostart" \
  "$root/usr/share/icons/hicolor/scalable/apps" "$root/usr/share/doc/healdan-helper" "$root/DEBIAN"
cp -R "$work/publish/." "$root/opt/healdan-helper/"
sh "$repo/clients/libreoffice/build.sh" "$root/opt/healdan-helper/healdan.oxt"
install -m 644 "$here/healdan-helper.desktop" "$root/usr/share/applications/healdan-helper.desktop"
install -m 644 "$here/autostart.desktop" "$root/etc/xdg/autostart/healdan-helper.desktop"
install -m 644 "$repo/site/static/favicon.svg" "$root/usr/share/icons/hicolor/scalable/apps/healdan-helper.svg"
{
  printf 'Healdan Helper\nCopyright 2026 The Healdan Project\nLicense: LGPL-3.0-only\nSource: https://github.com/Healdan-Project/healdan\n\n'
  printf 'Third-party notices\n===================\n\n'
  cat "$helper/Healdan.Helper/THIRD-PARTY-NOTICES.txt"
} > "$root/usr/share/doc/healdan-helper/copyright"
install -m 755 "$here/postinst" "$root/DEBIAN/postinst"
install -m 755 "$here/postrm" "$root/DEBIAN/postrm"

# Directories 755, files 644, except the two programs (createdump is the runtime's crash-dump tool).
find "$root" -type d -exec chmod 755 {} +
find "$root" -type f ! -path "$root/DEBIAN/*" -exec chmod 644 {} +
chmod 755 "$root/opt/healdan-helper/HealdanHelper"
[ ! -f "$root/opt/healdan-helper/createdump" ] || chmod 755 "$root/opt/healdan-helper/createdump"

size=$(du -sk --exclude=DEBIAN "$root" | cut -f1)
sed -e "s/@VERSION@/$debian/g" -e "s/@ARCH@/$arch/g" -e "s/@SIZE@/$size/g" "$here/control.in" > "$root/DEBIAN/control"
! grep -q '@[A-Z_]*@' "$root/DEBIAN/control" || die "control.in has a placeholder package.sh doesn't fill"
chmod 644 "$root/DEBIAN/control"

# --- The package, and its checks --------------------------------------------------------------------------------------

if command -v desktop-file-validate >/dev/null; then
  desktop-file-validate "$root/usr/share/applications/healdan-helper.desktop"
  desktop-file-validate "$root/etc/xdg/autostart/healdan-helper.desktop"
else
  say "desktop-file-validate isn't installed (desktop-file-utils): the desktop entries are unchecked"
fi
dpkg-deb --build --root-owner-group "$root" "$deb" >/dev/null
dpkg-deb --info "$deb"
dpkg-deb --contents "$deb"

say ""
say "Built $deb"
say "  sha256 $(sha256sum "$deb" | cut -d' ' -f1)"
