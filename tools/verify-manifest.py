#!/usr/bin/env python3
"""Checks a release's manifest against its zip (and, optionally, the app image).

    python3 tools/verify-manifest.py MANIFEST.json ZIP [--image APP_IMAGE_DIR] [--range] [--no-exec]

Every manifest file must be in the zip as prefix+path with the right size,
SHA-256 and (on Linux) exec bit, once and only once; the zip may hold nothing
else but folder entries. --image also checks that every regular file of the
image is listed exactly once. --range serves the zip from a tiny local HTTP
server and reads one small entry the way the updater does: the end of the file,
the central directory, then that entry's bytes by offset, inflated and hashed.
Exits 0 when everything holds, 1 otherwise.
"""
import argparse, hashlib, http.server, json, os, struct, sys, threading, urllib.request, zipfile, zlib

problems = []


def bad(message):
    problems.append(message)
    print("FAIL:", message)


def sha256_of(stream):
    h = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1 << 20), b""):
        h.update(chunk)
    return h.hexdigest()


def check_manifest(raw, manifest):
    if raw.startswith(b"\xef\xbb\xbf"):
        bad("manifest starts with a BOM")
    if set(manifest) != {"version", "platform", "zip", "prefix", "files"}:
        bad("manifest keys are %s" % sorted(manifest))
    if manifest.get("prefix") != "Lucense/":
        bad("prefix is %r" % manifest.get("prefix"))
    expected_zip = "Lucense-%s-%s.zip" % (manifest.get("version"), manifest.get("platform"))
    if manifest.get("zip") != expected_zip:
        bad("zip is %r, expected %r" % (manifest.get("zip"), expected_zip))
    paths = [f["path"] for f in manifest["files"]]
    if paths != sorted(paths):
        bad("files are not sorted by path")
    if len(set(paths)) != len(paths):
        bad("a path is listed twice")
    for f in manifest["files"]:
        if set(f) != {"path", "size", "sha256", "exec"}:
            bad("entry keys are %s (%s)" % (sorted(f), f.get("path")))
        p = f["path"]
        if "\\" in p or p.startswith("/") or p.endswith("/") or "//" in p or ".." in p.split("/"):
            bad("odd path %r" % p)
        if len(f["sha256"]) != 64 or f["sha256"] != f["sha256"].lower():
            bad("sha256 of %s is not 64 lowercase hex" % p)
        if manifest["platform"].startswith("windows") and f["exec"]:
            bad("%s is marked exec in a Windows manifest" % p)


def check_zip(manifest, zip_path, check_exec):
    prefix = manifest["prefix"]
    wanted = {prefix + f["path"]: f for f in manifest["files"]}
    seen = set()
    folders = 0
    with zipfile.ZipFile(zip_path) as z:
        infos = z.infolist()
        for info in infos:
            name = info.filename
            if name.endswith("/"):
                folders += 1
                continue
            if "\\" in name or name.startswith("/"):
                bad("zip entry name %r has a backslash or leading slash" % name)
            if name in seen:
                bad("zip entry %s appears twice" % name)
            seen.add(name)
            f = wanted.get(name)
            if f is None:
                bad("zip entry %s is not in the manifest" % name)
                continue
            if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                bad("%s uses compression method %d" % (name, info.compress_type))
            if info.file_size != f["size"]:
                bad("%s: zip size %d, manifest %d" % (name, info.file_size, f["size"]))
            with z.open(info) as s:
                if sha256_of(s) != f["sha256"]:
                    bad("%s: SHA-256 differs" % name)
            if check_exec:
                mode = info.external_attr >> 16
                if bool(mode & 0o100) != f["exec"]:
                    bad("%s: zip mode %o, manifest exec=%s" % (name, mode, f["exec"]))
        for name in wanted:
            if name not in seen:
                bad("%s is in the manifest but not in the zip" % name)
    print("zip: %d file entries, %d folder entries, %d in manifest" % (len(seen), folders, len(wanted)))
    return folders


def check_image(manifest, image):
    listed = {f["path"]: f for f in manifest["files"]}
    found = set()
    for folder, _, names in os.walk(image):
        for n in names:
            full = os.path.join(folder, n)
            rel = os.path.relpath(full, image).replace(os.sep, "/")
            if os.path.islink(full) or not os.path.isfile(full):
                print("note: not a regular file, not expected in the manifest:", rel)
                continue
            found.add(rel)
            f = listed.get(rel)
            if f is None:
                bad("image file %s is not in the manifest" % rel)
            elif os.path.getsize(full) != f["size"]:
                bad("image file %s: size differs from the manifest" % rel)
    for p in listed:
        if p not in found:
            bad("%s is in the manifest but not in the image" % p)
    print("image: %d regular files, %d in manifest" % (len(found), len(listed)))


class RangeHandler(http.server.BaseHTTPRequestHandler):
    path_on_disk = None
    requests = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        size = os.path.getsize(self.path_on_disk)
        header = self.headers.get("Range")
        start, end = 0, size - 1
        if header and header.startswith("bytes="):
            a, b = header[6:].split("-", 1)
            if a == "":  # suffix: the last b bytes
                start = max(0, size - int(b))
            else:
                start = int(a)
                if b:
                    end = min(int(b), size - 1)
        self.requests.append((start, end))
        self.send_response(206 if header else 200)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        if header:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        with open(self.path_on_disk, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(left, 1 << 20))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)


def ranged_read(manifest, zip_path):
    """Reads the smallest non-empty file of the zip over HTTP ranges, from the offsets in its central directory."""
    RangeHandler.path_on_disk = zip_path
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RangeHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = "http://127.0.0.1:%d/z" % server.server_address[1]

    def get(range_header):
        req = urllib.request.Request(url, headers={"Range": range_header})
        with urllib.request.urlopen(req) as r:
            if r.status != 206:
                bad("server did not answer 206 to %s" % range_header)
            return r.read()

    try:
        size = os.path.getsize(zip_path)
        tail = get("bytes=-65557")
        eocd = tail.rfind(b"PK\x05\x06")
        _, _, _, _, count, cd_size, cd_offset, _ = struct.unpack("<4sHHHHIIH", tail[eocd:eocd + 22])
        if cd_offset == 0xFFFFFFFF or count == 0xFFFF:
            bad("zip uses ZIP64 (not expected for this size)")
            return
        cd = get("bytes=%d-%d" % (cd_offset, cd_offset + cd_size - 1))
        entries, pos = {}, 0
        for _ in range(count):
            (sig, _, _, flags, method, _, _, crc, csize, usize, nlen, elen, clen, _, _, _, local) = \
                struct.unpack("<IHHHHHHIIIHHHHHII", cd[pos:pos + 46])
            name = cd[pos + 46:pos + 46 + nlen].decode("utf-8")
            entries[name] = (method, csize, usize, local)
            pos += 46 + nlen + elen + clen
        prefix = manifest["prefix"]
        small = min((f for f in manifest["files"] if f["size"] > 0), key=lambda f: f["size"])
        method, csize, usize, local = entries[prefix + small["path"]]
        head = get("bytes=%d-%d" % (local, local + 29))
        nlen, elen = struct.unpack("<HH", head[26:30])
        start = local + 30 + nlen + elen
        data = get("bytes=%d-%d" % (start, start + csize - 1))
        content = zlib.decompressobj(-15).decompress(data) if method == 8 else data
        ok = len(content) == small["size"] and hashlib.sha256(content).hexdigest() == small["sha256"]
        print("ranged read of %s: %d requests, %d bytes fetched of %d, %s" % (
            small["path"], len(RangeHandler.requests),
            sum(e - s + 1 for s, e in RangeHandler.requests), size, "hash matches" if ok else "HASH DIFFERS"))
        if not ok:
            bad("ranged read of %s does not match the manifest" % small["path"])
    finally:
        server.shutdown()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("zip")
    ap.add_argument("--image")
    ap.add_argument("--range", action="store_true")
    ap.add_argument("--no-exec", action="store_true", help="don't compare the zip's Unix modes (Windows zips)")
    args = ap.parse_args()
    raw = open(args.manifest, "rb").read()
    manifest = json.loads(raw.decode("utf-8"))
    check_manifest(raw, manifest)
    check_zip(manifest, args.zip, check_exec=not args.no_exec and manifest["platform"].startswith("linux"))
    if args.image:
        check_image(manifest, args.image)
    if args.range:
        ranged_read(manifest, args.zip)
    print("%d files, %d exec" % (len(manifest["files"]), sum(1 for f in manifest["files"] if f["exec"])))
    print("FAILED: %d problem(s)" % len(problems) if problems else "OK")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
