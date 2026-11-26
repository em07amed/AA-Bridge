#!/usr/bin/env python3
"""Builds the offline roads pack for Drive Bridge from an OpenStreetMap extract.

    python3 build_pack.py egypt-latest.osm.pbf egypt-roads.zip

The pack is a zip with one entry per ~5.5 km chunk (0.05 degrees). Each chunk
holds the drivable roads of its ~550 m tiles (0.005 degrees, the app's road
tiles), clipped to each tile plus a 33 m margin, and the speed cameras in the
chunk. Roads keep the raw tags the app needs to work out a limit (signed
maxspeed, highway class, zone, names, ref), so the limit rules stay in one
place: RoadMath.kt. Read by dev.caraalink.road.RoadPack.

Chunk format "c/<cy>_<cx>.bin", big-endian (java.io.DataInputStream):
    "DBR1"
    u16 strings, then each string as DataOutputStream.writeUTF
    u32 cameras, each: i64 id, i32 lat, i32 lon (1e-5 deg), u8 limit (0 none), i16 heading (-1 none)
    u32 tiles, each: i32 row, i32 col, u32 pieces, each:
        i64 way id, u8 flags (1 = one-way, points in travel order), u8 forward limit, u8 backward limit,
        u16 highway, u16 zone, u16 name, u16 name:ar, u16 name:en, u16 ref (string index, 0xFFFF none),
        u16 points, i32 lat0, i32 lon0, then (points-1) x (i16 dlat, i16 dlon)
"""

import datetime
import io
import json
import math
import re
import struct
import sys
import zipfile
from collections import defaultdict

import osmium

CLASSES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}
UNIT = 100_000          # coordinates are stored in 1e-5 degrees (~1.1 m)
TILE = 500              # 0.005 degrees in units
CHUNK_TILES = 10        # 0.05 degrees
MARGIN = 30             # ~33 m around each tile, so matching near an edge finds the road
MAX_SEG = 400           # split segments longer than ~440 m so each touches only a few tiles
SIMPLIFY_M = 2.0        # Douglas-Peucker tolerance
NONE = 0xFFFF

CARDINAL = {"N": 0, "NNE": 22, "NE": 45, "ENE": 67, "E": 90, "ESE": 112, "SE": 135, "SSE": 157, "S": 180,
            "SSW": 202, "SW": 225, "WSW": 247, "W": 270, "WNW": 292, "NW": 315, "NNW": 337}


def parse_maxspeed(v):
    """Same rules as RoadMath.parseMaxspeed: first number, mph converted, 5..200 else None."""
    if not v:
        return None
    m = re.search(r"\d+(?:\.\d+)?", v)
    if not m:
        return None
    n = float(m.group(0))
    kmh = round(n * 1.609 if "mph" in v else n)
    return kmh if 5 <= kmh <= 200 else None


def parse_direction(v):
    if not v:
        return -1
    s = v.strip().upper()
    try:
        return int(round(float(s))) % 360
    except ValueError:
        return CARDINAL.get(s, -1)


def bmp(s):
    """Drops characters outside the BMP, which writeUTF encodes differently from UTF-8."""
    return "".join(c for c in s if ord(c) < 0x10000) if s else s


def simplify(pts, tol_m):
    if len(pts) < 3:
        return pts
    k = math.cos(math.radians(pts[0][0] / UNIT)) or 1.0
    scale = 1.11  # metres per unit of latitude

    def dist(p, a, b):
        ax, ay = a[1] * k * scale, a[0] * scale
        bx, by = b[1] * k * scale, b[0] * scale
        px, py = p[1] * k * scale, p[0] * scale
        dx, dy = bx - ax, by - ay
        if dx == 0 and dy == 0:
            return math.hypot(px - ax, py - ay)
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        return math.hypot(px - (ax + t * dx), py - (ay + t * dy))

    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        best, idx = 0.0, -1
        for m in range(i + 1, j):
            d = dist(pts[m], pts[i], pts[j])
            if d > best:
                best, idx = d, m
        if best > tol_m and idx > 0:
            keep[idx] = True
            stack.append((i, idx))
            stack.append((idx, j))
    return [p for p, kp in zip(pts, keep) if kp]


def densify(pts):
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        n = max(abs(b[0] - a[0]), abs(b[1] - a[1])) // MAX_SEG
        for s in range(1, int(n) + 1):
            f = s / (n + 1)
            out.append((round(a[0] + (b[0] - a[0]) * f), round(a[1] + (b[1] - a[1]) * f)))
        out.append(b)
    return out


class Collector(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.ways = []
        self.cameras = []

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in CLASSES:
            return
        pts = []
        for n in w.nodes:
            if n.location.valid():
                p = (round(n.location.lat * UNIT), round(n.location.lon * UNIT))
                if not pts or pts[-1] != p:
                    pts.append(p)
        if len(pts) < 2:
            return
        t = w.tags
        ow = t.get("oneway", "")
        oneway = ow in ("yes", "true", "1") or t.get("junction") == "roundabout" or hw == "motorway"
        if ow == "-1":
            pts.reverse()
            oneway = True
        all_ = parse_maxspeed(t.get("maxspeed"))
        fwd = parse_maxspeed(t.get("maxspeed:forward")) or all_
        back = parse_maxspeed(t.get("maxspeed:backward")) or all_
        if ow == "-1":
            fwd, back = back, fwd
        zone = next((z for z in (t.get("maxspeed"), t.get("maxspeed:type"), t.get("source:maxspeed")) if z and ":" in z), None)
        self.ways.append((w.id, oneway, fwd, back, hw, zone,
                          bmp(t.get("name")), bmp(t.get("name:ar")), bmp(t.get("name:en")), bmp(t.get("ref")), pts))

    def node(self, n):
        if n.tags.get("highway") != "speed_camera" or not n.location.valid():
            return
        self.cameras.append((n.id, round(n.location.lat * UNIT), round(n.location.lon * UNIT),
                             parse_maxspeed(n.tags.get("maxspeed")) or 0,
                             parse_direction(n.tags.get("direction") or n.tags.get("camera:direction"))))


def build(src, dst):
    c = Collector()
    c.apply_file(src, locations=True, idx="flex_mem")
    print(f"read {len(c.ways)} roads, {len(c.cameras)} cameras", flush=True)

    # chunk -> tile -> pieces
    chunks = defaultdict(lambda: defaultdict(list))
    points_in = points_out = pieces = 0
    lat_min = lon_min = 10**9
    lat_max = lon_max = -10**9
    for (wid, oneway, fwd, back, hw, zone, name, name_ar, name_en, ref, pts) in c.ways:
        points_in += len(pts)
        pts = densify(simplify(pts, SIMPLIFY_M))
        for la, lo in pts:
            lat_min, lat_max = min(lat_min, la), max(lat_max, la)
            lon_min, lon_max = min(lon_min, lo), max(lon_max, lo)
        segs = defaultdict(list)  # tile -> segment indices
        for i in range(len(pts) - 1):
            (a0, o0), (a1, o1) = pts[i], pts[i + 1]
            r0, r1 = (min(a0, a1) - MARGIN) // TILE, (max(a0, a1) + MARGIN) // TILE
            q0, q1 = (min(o0, o1) - MARGIN) // TILE, (max(o0, o1) + MARGIN) // TILE
            for r in range(r0, r1 + 1):
                for q in range(q0, q1 + 1):
                    segs[(r, q)].append(i)
        for (r, q), idx in segs.items():
            run = [idx[0]]
            for i in idx[1:] + [None]:
                if i is not None and i == run[-1] + 1:
                    run.append(i)
                    continue
                piece = pts[run[0]:run[-1] + 2]
                chunks[(r // CHUNK_TILES, q // CHUNK_TILES)][(r, q)].append(
                    (wid, oneway, fwd, back, hw, zone, name, name_ar, name_en, ref, piece))
                points_out += len(piece)
                pieces += 1
                if i is not None:
                    run = [i]

    cams = defaultdict(list)
    for cam in c.cameras:
        cams[(cam[1] // (TILE * CHUNK_TILES), cam[2] // (TILE * CHUNK_TILES))].append(cam)

    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for key in sorted(set(chunks) | set(cams)):
            z.writestr(f"c/{key[0]}_{key[1]}.bin", encode_chunk(chunks.get(key, {}), cams.get(key, [])))
        meta = {
            "format": 1,
            "version": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
            "source": "OpenStreetMap contributors (ODbL), extract by Geofabrik",
            "roads": len(c.ways), "cameras": len(c.cameras), "chunks": len(set(chunks) | set(cams)),
            "bounds": [lat_min / UNIT, lon_min / UNIT, lat_max / UNIT, lon_max / UNIT],
        }
        z.writestr("meta.json", json.dumps(meta, ensure_ascii=False))
    print(f"points {points_in} -> {points_out} in {pieces} tile pieces; {meta['chunks']} chunks", flush=True)
    return meta


def encode_chunk(tiles, cameras):
    strings, index = [], {}

    def sid(s):
        if not s:
            return NONE
        if s not in index:
            if len(strings) >= NONE:
                raise ValueError("too many strings in one chunk")
            index[s] = len(strings)
            strings.append(s)
        return index[s]

    body = io.BytesIO()
    w = body.write
    w(struct.pack(">I", len(cameras)))
    for (cid, la, lo, limit, heading) in cameras:
        w(struct.pack(">qiiBh", cid, la, lo, min(limit, 255), heading))
    w(struct.pack(">I", len(tiles)))
    for (r, q), plist in sorted(tiles.items()):
        w(struct.pack(">iiI", r, q, len(plist)))
        for (wid, oneway, fwd, back, hw, zone, name, name_ar, name_en, ref, pts) in plist:
            w(struct.pack(">qBBB", wid, 1 if oneway else 0, fwd or 0, back or 0))
            w(struct.pack(">HHHHHH", sid(hw), sid(zone), sid(name), sid(name_ar), sid(name_en), sid(ref)))
            w(struct.pack(">Hii", len(pts), pts[0][0], pts[0][1]))
            for (a, b), (c_, d) in zip(pts, pts[1:]):
                w(struct.pack(">hh", c_ - a, d - b))

    head = io.BytesIO()
    head.write(b"DBR1")
    head.write(struct.pack(">H", len(strings)))
    for s in strings:
        raw = s.encode("utf-8")
        head.write(struct.pack(">H", len(raw)) + raw)
    return head.getvalue() + body.getvalue()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print(json.dumps(build(sys.argv[1], sys.argv[2]), ensure_ascii=False))
