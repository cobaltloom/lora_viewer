#!/usr/bin/env python3
"""LoraViewer track server.

Runs 24/7 on a small VM, collecting glider positions from both sources the app
supports and keeping the last 24 hours, so the app can show each flying
glider's trail from takeoff - including the part flown before the app was opened.

  * OGN: a read-only APRS-IS connection to aprs.glidernet.org, limited to 20 km
    around Menuma, with the same privacy rules as the app's OGNAPI / OgnApiClient.
  * LoRa: polls the club's trailrouteview mapapi.php, like the app does.

HTTP API (behind Caddy for HTTPS):
  GET /tracks?source=ogn|lora&groundAltM=60   (header X-Api-Key)
      -> {"generatedAt": <epoch s>, "tracks": {"<id>": [[t, lat, lon, altM], ...]}}
      One entry per glider that is currently flying (latest fix within 10 min and
      above groundAltM), holding its points since it was last at/below groundAltM.
  GET /flights?source=ogn|lora&groundAltM=60   (header X-Api-Key)
      -> {"generatedAt": <epoch s>, "flights": {"<id>": [[[t, lat, lon, altM], ...], ...]}}
      Every flight (run of points above groundAltM) since midnight JST, landed ones
      included, so the apps can fill in turnpoint passages and laps flown while they
      weren't watching that source.
  GET /health -> counts, for monitoring.

Standard library only, so it runs on a stock Debian python3.
Config (environment): API_KEY (required), LORA_BASE_URL, PORT, DATA_DIR.
"""

import gzip
import json
import logging
import math
import os
import random
import re
import socket
import string
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

API_KEY = os.environ.get("API_KEY", "")
LORA_BASE_URL = os.environ.get("LORA_BASE_URL", "https://www.trailrouteview.com/user/jsal/gmap/")
PORT = int(os.environ.get("PORT", "8080"))
DATA_DIR = os.environ.get("DATA_DIR", "/var/lib/loraviewer-track")

RETENTION_S = 24 * 3600          # OGN rule: don't redistribute data older than 24 h
MIN_POINT_SPACING_S = 5          # store at most one point per 5 s per glider
FLYING_RECENT_S = 10 * 60        # "currently flying" = latest fix this recent...
MAX_FLIGHT_S = 12 * 3600         # ...and a returned segment never spans more than this
MAX_POINTS_PER_TRACK = 1000      # downsampled to at most this many points per response
MAX_POINTS_PER_FLIGHT = 3000     # /flights: 4 h at 5 s, enough to time turnpoint entries
FLIGHT_GAP_S = 10 * 60           # /flights: a silence this long ends a flight
JST_OFFSET_S = 9 * 3600
LORA_POLL_S = 5                  # LoRa fixes come every ~7-15 s; 10 s polling missed some
TAKEOFF_GROUND_POINT_S = 60      # a flight starts with its last ground point this recent (see split_flights)
SNAPSHOT_S = 60
VERSION = "1.1"

OGN_HOST, OGN_PORT = "aprs.glidernet.org", 14580
# 20 km around Menuma airfield's center (JSAL guideline: N36°12'41" E139°25'08") - enough for the
# club's flights; the apps' live view still covers 150 km.
OGN_FILTER = "r/36.2114/139.4189/20"
OGN_MAX_CLOCK_SKEW_S = 300
DDB_URL = "https://ddb.glidernet.org/download/?j=1&t=1"
DDB_REFRESH_S = 24 * 3600

log = logging.getLogger("trackserver")


# ---------------------------------------------------------------- storage

class Store:
    """Per-source, per-glider lists of (t, lat, lon, alt) in time order."""

    def __init__(self):
        self.lock = threading.Lock()
        self.tracks = {"ogn": {}, "lora": {}}

    def add(self, source, gid, t, lat, lon, alt):
        if alt is None:
            return
        with self.lock:
            pts = self.tracks[source].setdefault(gid, [])
            if pts and t < pts[-1][0] + MIN_POINT_SPACING_S:
                return
            pts.append((int(t), round(lat, 5), round(lon, 5), round(alt, 1)))

    def prune(self, now):
        cutoff = now - RETENTION_S
        with self.lock:
            for source in self.tracks.values():
                for gid in list(source):
                    pts = source[gid]
                    i = 0
                    while i < len(pts) and pts[i][0] < cutoff:
                        i += 1
                    if i:
                        del pts[:i]
                    if not pts:
                        del source[gid]

    def remove(self, source, gid):
        with self.lock:
            self.tracks[source].pop(gid, None)

    def flights(self, source, ground_alt, now):
        with self.lock:
            snapshot = {gid: list(pts) for gid, pts in self.tracks[source].items()}
        result = {}
        for gid, pts in snapshot.items():
            seg = current_flight(pts, ground_alt, now)
            if seg:
                result[gid] = downsample(seg, MAX_POINTS_PER_TRACK)
        return result

    def all_flights(self, source, ground_alt, since):
        with self.lock:
            snapshot = {gid: [p for p in pts if p[0] >= since] for gid, pts in self.tracks[source].items()}
        result = {}
        for gid, pts in snapshot.items():
            segs = [downsample(seg, MAX_POINTS_PER_FLIGHT) for seg in split_flights(pts, ground_alt)]
            if segs:
                result[gid] = segs
        return result

    def save(self, path):
        with self.lock:
            data = json.dumps(self.tracks)
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            f.write(data)
        os.replace(tmp, path)

    def load(self, path, now):
        try:
            with open(path) as f:
                data = json.load(f)
        except (OSError, ValueError):
            return
        with self.lock:
            for source in ("ogn", "lora"):
                for gid, pts in data.get(source, {}).items():
                    kept = [tuple(p) for p in pts if p[0] >= now - RETENTION_S]
                    if kept:
                        self.tracks[source][gid] = kept


def current_flight(pts, ground_alt, now):
    """Points since the glider was last at/below ground_alt, if it is flying now (with that last
    ground point, as split_flights does)."""
    if not pts:
        return None
    last = pts[-1]
    if now - last[0] > FLYING_RECENT_S or last[3] <= ground_alt:
        return None
    start = len(pts) - 1
    while start > 0 and pts[start - 1][3] > ground_alt and last[0] - pts[start - 1][0] <= MAX_FLIGHT_S:
        start -= 1
    if start > 0 and pts[start][0] - pts[start - 1][0] <= TAKEOFF_GROUND_POINT_S:
        start -= 1
    return pts[start:]


def split_flights(pts, ground_alt):
    """Runs of at least two points above ground_alt, broken at ground points and long silences.
    Each starts with the ground point just before takeoff, if recent: a winch launch can cross the
    start line between that and the first airborne fix, which sparse LoRa fixes often do."""
    flights, current, last_ground = [], [], None
    for p in pts:
        if p[3] <= ground_alt or (current and p[0] - current[-1][0] > FLIGHT_GAP_S):
            if sum(1 for q in current if q[3] > ground_alt) >= 2:
                flights.append(current)
            current = []
        if p[3] <= ground_alt:
            last_ground = p
        else:
            if not current and last_ground and p[0] - last_ground[0] <= TAKEOFF_GROUND_POINT_S:
                current.append(last_ground)
            current.append(p)
    if sum(1 for q in current if q[3] > ground_alt) >= 2:
        flights.append(current)
    return flights


def jst_midnight(now):
    return (now + JST_OFFSET_S) // 86400 * 86400 - JST_OFFSET_S


def downsample(pts, limit):
    if len(pts) <= limit:
        return pts
    step = (len(pts) - 1) / (limit - 1)
    return [pts[round(i * step)] for i in range(limit)]


# ---------------------------------------------------------------- OGN

BEACON_RE = re.compile(
    r"^([A-Za-z0-9]{3,9})>([A-Z0-9]+)[^:]*:[/@](\d{2})(\d{2})(\d{2})h"
    r"(\d{2})(\d{2}\.\d{2})([NS]).(\d{3})(\d{2}\.\d{2})([EW])."
    r"(?:\d{3}/\d{3})?/A=(-?\d{5,6})(.*)$"
)
PRECISION_RE = re.compile(r"!W(\d)(\d)!")
ID_RE = re.compile(r"\bid([0-9A-Fa-f]{2})([0-9A-Fa-f]{6})\b")


def parse_beacon(line, now):
    """Same rules as the apps' OGN beacon parsers. Returns None for non-aircraft lines."""
    if line.startswith("#"):
        return None
    m = BEACON_RE.match(line)
    if not m:
        return None
    g = m.groups()
    rest = g[12]
    idm = ID_RE.search(rest)
    if not idm:
        return None
    flags = int(idm.group(1), 16)
    prec = PRECISION_RE.search(rest)
    lat_extra = int(prec.group(1)) / 1000 if prec else 0.0
    lon_extra = int(prec.group(2)) / 1000 if prec else 0.0
    lat = int(g[5]) + (float(g[6]) + lat_extra) / 60
    if g[7] == "S":
        lat = -lat
    lon = int(g[8]) + (float(g[9]) + lon_extra) / 60
    if g[10] == "W":
        lon = -lon
    day = datetime.fromtimestamp(now, timezone.utc).replace(
        hour=int(g[2]) % 24, minute=int(g[3]) % 60, second=int(g[4]) % 60, microsecond=0)
    t = day.timestamp()
    if t - now > 12 * 3600:
        t -= 86400
    elif now - t > 12 * 3600:
        t += 86400
    return {
        "callsign": g[0],
        "address": idm.group(2).upper(),
        "lat": lat,
        "lon": lon,
        "alt": int(g[11]) * 0.3048,
        "t": t,
        "aircraft_type": (flags >> 2) & 0x0F,
        "address_type": flags & 0x03,
        "stealth": bool(flags & 0x80),
        "no_tracking": bool(flags & 0x40),
        "destination": g[1],
    }


def displayable(b):
    return (not b["stealth"] and not b["no_tracking"] and b["address_type"] != 0
            and b["aircraft_type"] not in (9, 15) and not b["destination"].startswith("OGADSB"))


class DeviceDatabase:
    """OGN DDB privacy choices. Until loaded, nothing from OGN is stored."""

    def __init__(self):
        self.devices = None  # address -> (tracked, identified)

    def refresh_forever(self):
        while True:
            try:
                req = urllib.request.Request(DDB_URL, headers={"User-Agent": "LoraViewer-trackserver/" + VERSION})
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = json.load(r)
                self.devices = {
                    d["device_id"].upper(): (d.get("tracked") != "N", d.get("identified") != "N")
                    for d in data["devices"]
                }
                log.info("DDB loaded: %d devices", len(self.devices))
                time.sleep(DDB_REFRESH_S)
            except Exception as e:  # noqa: BLE001 - keep retrying
                log.warning("DDB load failed: %s", e)
                time.sleep(600)

    def allows_history(self, address):
        """Untracked aircraft are never shown; unidentified ones only live in the app, so no history."""
        if self.devices is None:
            return False
        tracked, identified = self.devices.get(address, (True, True))
        return tracked and identified


def run_ogn(store, ddb):
    callsign = "LVS" + "".join(random.choice(string.ascii_uppercase + string.digits) for _ in range(6))
    while True:
        try:
            with socket.create_connection((OGN_HOST, OGN_PORT), timeout=90) as s:
                login = f"user {callsign} pass -1 vers LoraViewer-trackserver {VERSION} filter {OGN_FILTER}\r\n"
                s.sendall(login.encode())
                log.info("OGN connected")
                f = s.makefile("r", encoding="latin-1", newline="\n")
                last_keepalive = time.time()
                for line in f:
                    now = time.time()
                    if now - last_keepalive > 60:
                        s.sendall(b"#keepalive\r\n")
                        last_keepalive = now
                    b = parse_beacon(line.strip(), now)
                    if not b or not displayable(b) or abs(b["t"] - now) > OGN_MAX_CLOCK_SKEW_S:
                        continue
                    if not ddb.allows_history(b["address"]):
                        store.remove("ogn", b["callsign"])
                        continue
                    store.add("ogn", b["callsign"], b["t"], b["lat"], b["lon"], b["alt"])
        except Exception as e:  # noqa: BLE001 - reconnect on anything
            log.warning("OGN connection lost: %s", e)
        time.sleep(5)


# ---------------------------------------------------------------- LoRa

def _num(v):
    try:
        f = float(v)
        return f if math.isfinite(f) else None
    except (TypeError, ValueError):
        return None


def run_lora(store):
    while True:
        try:
            url = urllib.parse.urljoin(LORA_BASE_URL, "mapapi.php") + "?rdm=" + str(random.random())
            req = urllib.request.Request(url, headers={"User-Agent": "LoraViewer-trackserver/" + VERSION})
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.load(r)
            if str(data.get("result")) == "0":
                for p in data.get("positions", []):
                    lat, lon, alt = _num(p.get("lat")), _num(p.get("lon")), _num(p.get("alt"))
                    dt = p.get("position_datetime")
                    if lat is None or lon is None or not dt:
                        continue
                    t = datetime.strptime(dt, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
                    store.add("lora", str(p.get("imei")), t, lat, lon, alt)
        except Exception as e:  # noqa: BLE001 - keep polling
            log.warning("LoRa poll failed: %s", e)
        time.sleep(LORA_POLL_S)


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    store = None
    server_version = "LoraViewerTrack/" + VERSION

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/health":
            with self.store.lock:
                counts = {s: len(v) for s, v in self.store.tracks.items()}
            return self._json({"ok": True, "gliders": counts})
        if url.path not in ("/tracks", "/flights"):
            return self._json({"error": "not found"}, 404)
        if not API_KEY or self.headers.get("X-Api-Key") != API_KEY:
            return self._json({"error": "unauthorized"}, 401)
        q = urllib.parse.parse_qs(url.query)
        source = q.get("source", [""])[0]
        if source not in ("ogn", "lora"):
            return self._json({"error": "bad source"}, 400)
        ground = _num(q.get("groundAltM", ["60"])[0])
        ground = 60.0 if ground is None else ground
        now = time.time()
        if url.path == "/flights":
            flights = self.store.all_flights(source, ground, jst_midnight(now))
            return self._json({"generatedAt": int(now),
                               "flights": {k: [[list(p) for p in seg] for seg in v] for k, v in flights.items()}})
        tracks = self.store.flights(source, ground, now)
        self._json({"generatedAt": int(now), "tracks": {k: [list(p) for p in v] for k, v in tracks.items()}})

    def _json(self, obj, status=200):
        body = json.dumps(obj, separators=(",", ":")).encode()
        gz = "gzip" in self.headers.get("Accept-Encoding", "")
        if gz:
            body = gzip.compress(body)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        if gz:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        log.debug("%s " + fmt, self.address_string(), *args)


def run_housekeeping(store, path):
    while True:
        time.sleep(SNAPSHOT_S)
        try:
            store.prune(time.time())
            store.save(path)
        except Exception as e:  # noqa: BLE001
            log.warning("snapshot failed: %s", e)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not API_KEY:
        raise SystemExit("API_KEY is required")
    os.makedirs(DATA_DIR, exist_ok=True)
    snapshot = os.path.join(DATA_DIR, "tracks.json")
    store = Store()
    store.load(snapshot, time.time())
    ddb = DeviceDatabase()
    for target, args in ((ddb.refresh_forever, ()), (run_ogn, (store, ddb)), (run_lora, (store,)),
                         (run_housekeeping, (store, snapshot))):
        threading.Thread(target=target, args=args, daemon=True).start()
    Handler.store = store
    log.info("listening on 127.0.0.1:%d", PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
