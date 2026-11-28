#!/usr/bin/env bash
#
# Makes the Linux files of a release, in out/:
#
#   lucense_<version>_amd64.deb
#   Lucense-<version>-linux-x64.tar.gz           the app image, as before
#   Lucense-<version>-linux-x64.zip              the same image, for in-app updates
#   Lucense-<version>-linux-x64-manifest.json    every file of it, hashed
#   SHA256SUMS                                   over those four
#
# It builds, collects and checks. It never publishes: no network, no `gh`.
# Making the release itself is a separate step (docs/UPDATES.md).
#
#     bash tools/release-linux.sh                     version from gradle.properties
#     bash tools/release-linux.sh -PlucenseVersion=1.58.0
#     bash tools/release-linux.sh --dirty             allow uncommitted changes
#
# Needs the bundled tools in desktop/resources/linux-x64/ (bash tools/fetch-tools.sh linux)
# and a display: making the app image opens the app for a few seconds, to
# record the class archive that makes it start faster. Without a display this
# runs under xvfb-run when that is installed, as the CI does.
# GRADLE (default: gradle) and GRADLE_ARGS (for example --offline) can be set.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

dirty=0
version_arg=""
for arg in "$@"; do
  case "$arg" in
    --dirty) dirty=1 ;;
    -PlucenseVersion=*) version_arg="$arg" ;;
    *) echo "Unknown argument: $arg (expected --dirty or -PlucenseVersion=X.Y.Z)" >&2; exit 2 ;;
  esac
done

if [ -n "$version_arg" ]; then
  version="${version_arg#-PlucenseVersion=}"
else
  version="$(grep '^lucenseVersion=' gradle.properties | cut -d= -f2 | tr -d '\r')"
fi
[ -n "$version" ] || { echo "No lucenseVersion in gradle.properties." >&2; exit 1; }

# The release is made from what is committed, or it can't be made again.
if [ "$dirty" = 0 ] && [ -n "$(git status --porcelain)" ]; then
  echo "The working tree is not clean, so this would not be reproducible from a commit:" >&2
  git status --short >&2
  echo "Commit or stash, or pass --dirty." >&2
  exit 1
fi

for tool in ffmpeg qjs spotdl yt-dlp; do
  if [ ! -f "desktop/resources/linux-x64/$tool" ]; then
    echo "desktop/resources/linux-x64/$tool is missing. Fetch the bundled tools: bash tools/fetch-tools.sh linux" >&2
    exit 1
  fi
done

GRADLE="${GRADLE:-gradle}"
runner=()
if [ -z "${DISPLAY:-}" ]; then
  if command -v xvfb-run >/dev/null; then
    echo "NOTE: no DISPLAY; running Gradle under xvfb-run (the class archive needs a display)."
    runner=(xvfb-run -a)
  else
    echo "NOTE: no DISPLAY and no xvfb-run: there will be no class archive and this script will fail its check." >&2
    echo "      Set DISPLAY, or install xvfb. The build opens the app window for ~12 s; that is expected." >&2
  fi
else
  echo "NOTE: the build opens the app window for ~12 s (DISPLAY=$DISPLAY) to make the class archive; that is expected."
fi

echo "== Building $version"
# shellcheck disable=SC2086
"${runner[@]}" $GRADLE ${GRADLE_ARGS:-} ${version_arg:+"$version_arg"} \
  :desktop:packageDeb :desktop:createDistributable :desktop:appManifest :desktop:appZip --console=plain

build=desktop/build
image=$build/compose/binaries/main/app
deb="lucense_${version}_amd64.deb"
tarball="Lucense-$version-linux-x64.tar.gz"
zip="Lucense-$version-linux-x64.zip"
manifest="Lucense-$version-linux-x64-manifest.json"

echo "== Collecting into out/"
mkdir -p out
rm -f "out/$deb" "out/$tarball" "out/$zip" "out/$manifest" out/SHA256SUMS
cp "$build/compose/binaries/main/deb/$deb" out/
# The portable archive is the app image itself, which the .deb is packaged from.
tar -C "$image" -czf "out/$tarball" Lucense
cp "$build/release/$zip" "$build/release/$manifest" out/
(cd out && sha256sum "$deb" "$tarball" "$zip" "$manifest" > SHA256SUMS)

echo "== Checking"
tools_ere='resources/(yt-dlp|ffmpeg|qjs|spotdl)$'
# The class archive is in the zip, and the tools and the launcher can run.
unzip -Z1 "out/$zip" | grep -qx 'Lucense/lib/runtime/lib/server/classes.jsa' \
  || { echo "The zip has no class archive (lib/runtime/lib/server/classes.jsa): was there a display?" >&2; exit 1; }
unzip -Z "out/$zip" | grep -E "$tools_ere" | grep -c '^-rwx' | grep -qx 4 \
  || { echo "The zip's tools are not all executable." >&2; exit 1; }
unzip -Z "out/$zip" | grep -E ' Lucense/bin/Lucense$' | grep -q '^-rwx' \
  || { echo "The zip's launcher is not executable." >&2; exit 1; }
tar -tvzf "out/$tarball" | grep -E "$tools_ere" | grep -c '^-rwx' | grep -qx 4 \
  || { echo "The tar's tools are not all executable." >&2; exit 1; }
dpkg-deb --contents "out/$deb" | grep -E "$tools_ere" | grep -c '^-rwx' | grep -qx 4 \
  || { echo "The .deb's tools are not all executable." >&2; exit 1; }
# Every file of the image in the manifest and the zip, with the right bytes.
python3 tools/verify-manifest.py "out/$manifest" "out/$zip" --image "$image/Lucense"
(cd out && sha256sum -c SHA256SUMS)

echo
printf '%-52s %12s\n' File Size
for f in "$deb" "$tarball" "$zip" "$manifest" SHA256SUMS; do
  printf '%-52s %9s MB\n' "$f" "$(awk -v b="$(stat -c %s "out/$f")" 'BEGIN { printf "%.2f", b / 1048576 }')"
done
echo
echo "Done: out/ holds the Linux files of $version. Nothing was published."
