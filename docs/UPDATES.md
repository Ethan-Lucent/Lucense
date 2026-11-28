# In-app updates: the release side

Lucense (desktop) can update itself by downloading only the files that changed
between the version you run and the new one. This file is what a release has to
carry for that, how it is made, and how to cut a release. The reading side is
in `desktop/src/`.

## What every release carries

For each platform `P` in `linux-x64` and `windows-x64`, version `X.Y.Z`:

| Asset | What |
|---|---|
| `Lucense-X.Y.Z-P-manifest.json` | every file of the app image: path, size, SHA-256, exec bit |
| `Lucense-X.Y.Z-linux-x64.zip` | the Linux app image, one zip entry per file |
| `Lucense-X.Y.Z-windows-x64.zip` | the Windows app image (the portable build), likewise |

Besides the files that were always there:

| Linux (`SHA256SUMS`) | Windows (`SHA256SUMS-windows`) |
|---|---|
| `lucense_X.Y.Z_amd64.deb`, `lucense-X.Y.Z-1.x86_64.rpm`, `Lucense-X.Y.Z-linux-x64.tar.gz`, the `.zip` and the manifest | `Lucense-X.Y.Z-Setup.exe`, `Lucense-X.Y.Z.msi`, the `.zip` and the manifest |

Both checksum files list every asset of their platform, the new ones too. The
installers, the `.tar.gz` and the `.rpm` are unchanged and stay: a full install
is always possible.

## The manifest

```json
{"version":"1.58.0","platform":"linux-x64","zip":"Lucense-1.58.0-linux-x64.zip","prefix":"Lucense/",
"files":[
{"path":"bin/Lucense","size":22008,"sha256":"b6c11c88fd797451e6c81f075fef8d4d044a1187eb91698ecf932914f57e4654","exec":true},
{"path":"lib/app/desktop-ab12.jar","size":4400000,"sha256":"<64 lowercase hex>","exec":false}
]}
```

- `path` is relative to the **install root**: the app image folder
  (`desktop/build/compose/binaries/main/app/Lucense`), which holds `bin/Lucense`
  and `lib/` on Linux, and `Lucense.exe`, `app/` and `runtime/` on Windows.
  Forward slashes; every regular file; sorted by path (plain character order);
  no folders and no symlinks. (Symlinks are skipped with a warning; the Linux
  image has none today.)
- `sha256` is lowercase hex of the file's bytes; `size` is in bytes; `exec` is
  true when the file's owner-execute bit is set on Linux, and always false in the
  Windows manifest.
- `zip` is the name of the zip asset; `prefix` is the folder inside it. The zip
  entry for a file is `prefix + path`, e.g. `Lucense/bin/Lucense`.
- UTF-8, no BOM, one file per line.

## The zip, and why entries are compressed one by one

The updater does not download the zip. It asks GitHub's download URL for byte
ranges: the end of the file (the zip's central directory, which gives every
entry's name, size and offset), then, for each file that changed, just that
entry's bytes. That works only when each file is its own, individually
compressed entry (DEFLATED or STORED): the bytes of one entry can then be
inflated by themselves. A `.tar.gz` is one compressed stream and can't be read
this way, which is why the zip exists next to it.

- One entry per file, named `prefix + path`, forward slashes, no leading `/`.
- Folder entries (`Lucense/lib/`) may be present; readers ignore them.
- No ZIP64 (the image is ~600 MB, with no file over 4 GB and far fewer than
  65535 files).
- Linux: the Unix mode is kept in each entry's external attributes, so
  `unzip -Z` shows `-rwxr-xr-x` on the launcher and the four tools. The updater
  uses the manifest's `exec` for this, but the zip is also a correct archive for
  a person.
- The Linux zip is made by Gradle (`appZip`) from the app image after
  `createDistributable`, so the class archive
  (`lib/runtime/lib/server/classes.jsa`, made in that task) is in it; no
  timestamps, fixed order.
- The Windows zip is made by `Compress-Archive` in `.github/workflows/windows.yml`
  (step *Collect*, `shell: pwsh`). PowerShell 7 writes forward-slash names, one
  entry per file; Windows PowerShell 5.1 writes backslashes, so that step must
  stay on `pwsh`. The same step then runs `tools/verify-manifest.py`, which fails
  the build if the zip and the manifest don't agree on every path, size and hash.

The Gradle tasks (`desktop/build.gradle.kts`):

- `:desktop:appManifest` — writes `desktop/build/release/Lucense-<version>-<platform>-manifest.json`
  from the app image; Linux or Windows on x64 only, anything else fails with a message.
- `:desktop:appZip` — Linux only (not registered on Windows):
  `desktop/build/release/Lucense-<version>-linux-x64.zip`.

Both take the version from `lucenseVersion` (`-PlucenseVersion=...` overrides),
and both run after `createDistributable`: build them in the same Gradle run, so
the manifest and the zip describe the same image (the image's jar names change
when it's rebuilt).

`python3 tools/verify-manifest.py MANIFEST ZIP [--image DIR] [--range]` checks a
pair: every entry's name, size, SHA-256 and exec bit, that nothing is missing or
extra, that every file of the image is listed once, and (`--range`) that one small
entry can be read through HTTP range requests alone.

## Cutting a release

1. Bump `lucenseVersion` in `gradle.properties`; commit it (the script refuses a
   dirty tree unless given `--dirty`).
2. On Linux, with the bundled tools in `desktop/resources/linux-x64/`
   (`bash tools/fetch-tools.sh linux`) and a display (or `xvfb-run`; the build
   opens the app for ~12 s to make the class archive): `bash tools/release-linux.sh`.
   It builds, collects `out/` (`.deb`, `.tar.gz`, `.zip`, manifest, `SHA256SUMS`),
   checks it, and prints the sizes. It publishes nothing.
3. Push the commit and the tag: `git tag vX.Y.Z && git push origin vX.Y.Z`. The
   *Windows* workflow builds on `v*` tags and attaches its files to the release.
4. Create the release for the tag as a **draft** and upload `out/*` from step 2
   (e.g. `gh release create vX.Y.Z --draft --verify-tag out/*`). Do it before the
   Windows run reaches *Attach to the release*; if it got there first and failed,
   run *Windows* again from the Actions tab (`workflow_dispatch`, tag `vX.Y.Z`).
5. Wait for the Windows run to go green (it starts the app and resizes it, and
   attaches `Setup.exe`, `.msi`, the zip, its manifest and `SHA256SUMS-windows`
   only if that passed).
6. Check that the draft holds all of: for Linux the `.deb`, `.tar.gz`, `.zip`,
   `-manifest.json`, `SHA256SUMS`; for Windows the `-Setup.exe`, `.msi`, `.zip`,
   `-manifest.json`, `SHA256SUMS-windows` (and the APKs, as before). Then publish.

Publish only after both manifests are there: an updater has nothing to compare
against in a release that lacks the manifest for its platform.

## What is not signed

Nothing. The installers aren't code-signed, and neither are the manifests or the
zips: they are as trustworthy as the GitHub release they're downloaded from over
HTTPS. The SHA-256 in the manifest protects against a damaged or incomplete
download, not against someone who can change the release's assets (who could
change the manifest too). `SHA256SUMS` and `SHA256SUMS-windows` are likewise
checksums, not signatures.
