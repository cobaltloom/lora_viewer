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
  GET /results?source=ogn|lora&from=YYYY-MM-DD&to=YYYY-MM-DD   (header X-Api-Key)
      -> {"results": {"<date>": {"<course name>": [lap or partly flown course, ...]}}}
      Each day's laps on each task course, worked out here every minute from that day's flights
      and kept for RESULTS_RETENTION_DAYS (results only - no positions), so team standings
      survive even when no app was open.
  GET /standings   (header X-Api-Key)
      -> {"standings": {...} | null}: the team standings a representative last saved.
  PUT /standings   (header X-Api-Key, plus X-Editor-Key if an editor key is set; JSON body)
      Saves the competition - name, period, and per day the course flown and any corrected team
      scores, plus shared handicaps - for every app to show team standings from. If the instance metadata attribute
      "standings-key" (or env STANDINGS_KEY) is set, X-Editor-Key must match it; otherwise any
      app may save.
  GET /health -> counts, for monitoring.

Standard library only, so it runs on a stock Debian python3.
Config (environment): API_KEY (required), LORA_BASE_URL, PORT, DATA_DIR.
"""

import gzip
import hmac
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
from datetime import datetime, timedelta, timezone
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
VERSION = "1.5"

OGN_HOST, OGN_PORT = "aprs.glidernet.org", 14580
# 20 km around Menuma airfield's center (JSAL guideline: N36°12'41" E139°25'08") - enough for the
# club's flights; the apps' live view still covers 150 km.
OGN_FILTER = "r/36.2114/139.4189/20"
OGN_MAX_CLOCK_SKEW_S = 300
DDB_URL = "https://ddb.glidernet.org/download/?j=1&t=1"
DDB_REFRESH_S = 24 * 3600

STANDINGS_MAX_BYTES = 64 * 1024
RESULTS_INTERVAL_S = 60
RESULTS_RETENTION_DAYS = 60
METADATA_URL = "http://metadata.google.internal/computeMetadata/v1/instance/attributes/"

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


# ---------------------------------------------------------------- lap timing
#
# The apps' lap timing (TaskLapCalculator, TurnpointSector, PartialLapDistance in the iOS/Android
# code, which has the unit tests), ported so each day's laps are recorded here whether or not any app
# was open. Only the results are kept (lap times, goal altitudes, turnpoints reached) - never the
# positions beyond 24 h.


def _dms(d, m, s):
    return d + m / 60 + s / 3600


MENUMA = (_dms(36, 12, 41), _dms(139, 25, 8))  # 妻沼滑空場中心 (guideline reference)
TURNPOINTS = {
    "妻沼": MENUMA,
    "高林給水塔": (_dms(36, 14, 51), _dms(139, 22, 7)),
    "千代田": (_dms(36, 12, 26), _dms(139, 29, 13)),
    "邑楽タワー": (_dms(36, 15, 11), _dms(139, 27, 45)),
    "管理ポイント": (_dms(36, 12, 29), _dms(139, 25, 21)),
}
CHECKPOINT = "管理ポイント"
# Names exactly as the apps show them, which they use to match a day's results to a course.
COURSES = [
    ("① 妻沼-高林給水塔-千代田-(管理ポイント)-妻沼", 24.0, ["妻沼", "高林給水塔", "千代田", "管理ポイント", "妻沼"]),
    ("② 妻沼-千代田-高林給水塔-妻沼", 23.6, ["妻沼", "千代田", "高林給水塔", "妻沼"]),
    ("③ 妻沼-高林給水塔-邑楽タワー-千代田-(管理ポイント)-妻沼", 26.5, ["妻沼", "高林給水塔", "邑楽タワー", "千代田", "管理ポイント", "妻沼"]),
    ("④ 妻沼-千代田-邑楽タワー-高林給水塔-妻沼", 26.1, ["妻沼", "千代田", "邑楽タワー", "高林給水塔", "妻沼"]),
]
# 規則56(1): an unfinished flight's distance points, the way back to 妻沼 included (as the 2026-10-17
# official results scored them), keyed by the scoring turnpoints reached in order.
PARTIAL_DISTANCES_KM = {
    ("高林給水塔",): 11.4, ("千代田",): 12.8,
    ("高林給水塔", "千代田"): 23.6, ("千代田", "高林給水塔"): 23.6,
    ("高林給水塔", "邑楽タワー"): 20.2, ("千代田", "邑楽タワー"): 18.0,
    ("高林給水塔", "邑楽タワー", "千代田"): 26.1, ("千代田", "邑楽タワー", "高林給水塔"): 26.1,
}
TP_RADIUS_M = 2000.0
MAX_JOIN_GAP_S = 180
SAMPLE_SPACING_M = 25.0
START_POINT = (_dms(36, 12, 48), _dms(139, 24, 59))
GOAL_A = (_dms(36, 12, 47), _dms(139, 24, 57))
GOAL_B = (_dms(36, 12, 24), _dms(139, 24, 29))


def _distance_m(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlat, dlon = lat2 - lat1, math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(h))


def _bearing(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlon = math.radians(b[1] - a[1])
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return math.degrees(math.atan2(y, x)) % 360


def _angle_diff(a, b):
    d = (b - a) % 360
    return d - 360 if d > 180 else d


def sector_bearing(names, name):
    i = names.index(name)
    if i == 0 or i == len(names) - 1:
        return None
    bin_ = _bearing(TURNPOINTS[names[i - 1]], TURNPOINTS[name])
    bout = _bearing(TURNPOINTS[name], TURNPOINTS[names[i + 1]])
    return (bin_ + _angle_diff(bin_, bout) / 2) % 360


def in_sector(lat, lon, names, name):
    tp = TURNPOINTS[name]
    bisector = sector_bearing(names, name)
    return (bisector is not None and _distance_m(tp, (lat, lon)) <= TP_RADIUS_M
            and abs(_angle_diff(bisector, _bearing(tp, (lat, lon)))) <= 45)


def required_altitude_m(lat, lon):
    km = _distance_m(MENUMA, (lat, lon)) / 1000
    if km < 2.5:
        return None
    if km < 3:
        return 350.0
    if km >= 10:
        return 910.0
    return 420.0 + 70 * (int(km) - 3)


def _interpolate(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t)


def _ok_altitude(p):
    req = required_altitude_m(p[1], p[2])
    return req is None or p[3] >= req


def first_entry(a, b, names, name):
    """The first fraction along fix a → fix b (t, lat, lon, alt) inside the sector and not below the
    guideline altitude there, or None; fixes more than MAX_JOIN_GAP_S apart aren't joined."""
    if a is None or b[0] < a[0] or b[0] - a[0] > MAX_JOIN_GAP_S:
        return 1.0 if in_sector(b[1], b[2], names, name) and _ok_altitude(b) else None
    tp = TURNPOINTS[name]
    m_lon = 111320.0 * math.cos(math.radians(tp[0]))
    ax, ay = (a[2] - tp[1]) * m_lon, (a[1] - tp[0]) * 110574.0
    bx, by = (b[2] - tp[1]) * m_lon, (b[1] - tp[0]) * 110574.0
    length = math.hypot(bx - ax, by - ay)
    closest = 0.0 if length == 0 else min(max(-(ax * (bx - ax) + ay * (by - ay)) / (length * length), 0.0), 1.0)
    if math.hypot(ax + (bx - ax) * closest, ay + (by - ay) * closest) > TP_RADIUS_M * 1.05:
        return None
    samples = min(max(int(math.ceil(length / SAMPLE_SPACING_M)), 1), 400)
    for i in range(samples + 1):
        t = i / samples
        p = _interpolate(a, b, t)
        if in_sector(p[1], p[2], names, name) and _ok_altitude(p):
            return t
    return None


_M_LAT = 110574.0
_M_LON = 111320.0 * math.cos(math.radians(START_POINT[0]))


def _xy(lat, lon):
    return ((lon - START_POINT[1]) * _M_LON, (lat - START_POINT[0]) * _M_LAT)


_GA, _GB = _xy(*GOAL_A), _xy(*GOAL_B)
_gl = math.hypot(_GB[0] - _GA[0], _GB[1] - _GA[1])
_SA = (-(_GB[0] - _GA[0]) / _gl * 150, -(_GB[1] - _GA[1]) / _gl * 150)
_SB = (-_SA[0], -_SA[1])


def _intersection(p, q, a, b):
    rx, ry = q[0] - p[0], q[1] - p[1]
    sx, sy = b[0] - a[0], b[1] - a[1]
    den = rx * sy - ry * sx
    if den == 0:
        return None
    t = ((a[0] - p[0]) * sy - (a[1] - p[1]) * sx) / den
    u = ((a[0] - p[0]) * ry - (a[1] - p[1]) * rx) / den
    return t if 0 <= t <= 1 and 0 <= u <= 1 else None


def _first_crossing(pts, start, a, b, min_fraction=0.0):
    for i in range(max(start, 0), len(pts) - 1):
        p, q = pts[i], pts[i + 1]
        t = _intersection(_xy(p[1], p[2]), _xy(q[1], q[2]), a, b)
        if t is None or (i == start and t < min_fraction):
            continue
        return (p[0] + (q[0] - p[0]) * t, p[3] + (q[3] - p[3]) * t, i + 1)
    return None


def evaluate_lap(pts, names):
    """One flight's lap around a course (TaskLapCalculator): start line crossing, the turnpoints in
    order, then the goal line. pts are (t, lat, lon, alt). None without a start."""
    tps = names[1:-1]
    start = _first_crossing(pts, 0, _SA, _SB)
    if start is None:
        return None
    passed, index, last = [], start[2], 0.0
    while index < len(pts) and len(passed) < len(tps):
        a, b = pts[index - 1], pts[index]
        t = first_entry(a, b, names, tps[len(passed)])
        if t is not None:
            passed.append((tps[len(passed)], a[0] + (b[0] - a[0]) * t))
            last = t
        else:
            index += 1
    finish = _first_crossing(pts, index - 1, _GA, _GB, last) if len(passed) == len(tps) else None
    return {"start": start[0], "startAltitudeM": start[1], "passed": passed,
            "finish": finish[0] if finish else None, "finishAltitudeM": finish[1] if finish else None}


def day_results(flights, now):
    """Each course's laps and partly flown courses for a day's flights ({id: [flight points]}): one
    entry per flight that finished, or that has ended after reaching at least one scoring turnpoint."""
    results = {}
    for name, distance, names in COURSES:
        entries = []
        for gid, segs in flights.items():
            for index, seg in enumerate(segs):
                pts = [tuple(p) for p in seg]
                lap = evaluate_lap(pts, names)
                if not lap:
                    continue
                if lap["finish"] is not None:
                    entries.append({"id": gid, "start": round(lap["start"], 1), "finish": round(lap["finish"], 1),
                                    "lapSeconds": round(lap["finish"] - lap["start"], 1), "distanceKm": distance,
                                    "goalAltitudeM": round(lap["finishAltitudeM"], 1)})
                    continue
                scored = [n for n, _ in lap["passed"] if n != CHECKPOINT]
                still_flying = index == len(segs) - 1 and now - pts[-1][0] < 180
                partial = PARTIAL_DISTANCES_KM.get(tuple(scored))
                if partial and not still_flying:
                    entries.append({"id": gid, "start": round(lap["start"], 1), "passed": scored, "distanceKm": partial})
        results[name] = entries
    return results


class Results:
    """Each day's lap results per source and course (see day_results), saved to disk."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        try:
            with open(path) as f:
                self.days = json.load(f)
        except (OSError, ValueError):
            self.days = {}

    def update(self, store, now):
        date = datetime.fromtimestamp(now + JST_OFFSET_S, timezone.utc).strftime("%Y-%m-%d")
        day = {source: day_results(store.all_flights(source, 60.0, jst_midnight(now)), now) for source in ("ogn", "lora")}
        cutoff = datetime.fromtimestamp(now + JST_OFFSET_S - RESULTS_RETENTION_DAYS * 86400, timezone.utc).strftime("%Y-%m-%d")
        with self.lock:
            # A day keeps its last results once its flights have aged out of the store.
            if any(entries for courses in day.values() for entries in courses.values()) or date not in self.days:
                self.days[date] = day
            self.days = {d: v for d, v in self.days.items() if d >= cutoff}
            data = json.dumps(self.days, ensure_ascii=False)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            f.write(data)
        os.replace(tmp, self.path)

    def get(self, source, start, end):
        with self.lock:
            return {d: v.get(source, {}) for d, v in self.days.items() if start <= d <= end}


def run_results(store, results, standings_path, standings_lock):
    while True:
        try:
            now = time.time()
            results.update(store, now)
            # FINALIZE_AFTER_DAYS after a competition's last day, confirm its scores into it.
            with standings_lock:
                competition = load_standings(standings_path)
                today = datetime.fromtimestamp(now + JST_OFFSET_S, timezone.utc).strftime("%Y-%m-%d")
                with results.lock:
                    days = json.loads(json.dumps(results.days))
                final = finalize_competition(competition, days, today) if isinstance(competition, dict) else None
                if final:
                    save_standings(standings_path, final)
                    log.info("competition finalized: %s", final.get("name", ""))
        except Exception as e:  # noqa: BLE001 - keep going; next round may succeed
            log.warning("results failed: %s", e)
        time.sleep(RESULTS_INTERVAL_S)


# ---------------------------------------------------------------- scoring
#
# The apps' LapScore (競技規則53, 56(1), 細則7 別表(4)) and CompetitionScoring, ported so a finished
# competition's scores can be confirmed here (see finalize_competition).

GOAL_MIN_ALTITUDE_M = 330.0
GOAL_NO_SPEED_BELOW_M = 280.0
LOW_GOAL_DEDUCTION = 50.0
FINALIZE_AFTER_DAYS = 3
# Known club gliders that aren't 1.00, as in the apps (LoRa ids and the FLARM each was seen carrying).
BUILT_IN_HANDICAPS = {
    "0006": 0.90, "ICA84C247": 0.90,  # 早稲田 LS4
    "0007": 0.95, "FLRDB0774": 0.95,  # 明治 DG-100G
    "0008": 0.90, "FLRDB0775": 0.90,  # 慶應 Discus
    "000B": 0.90, "ICA841083": 0.90,  # 法政 Discus
}


def lap_scores(laps):
    """Reference scores (LapScore.scores): laps are dicts with key, distanceKm, lapSeconds (None for a
    partly flown course), handicap and goalAltitudeM. Returns {key: points}."""
    def penalty(lap):
        if lap.get("lapSeconds") is None or lap.get("goalAltitudeM") is None:
            return None
        if lap["goalAltitudeM"] < GOAL_NO_SPEED_BELOW_M:
            return "nospeed"
        return "deduct" if lap["goalAltitudeM"] < GOAL_MIN_ALTITUDE_M else None

    def speed(lap):
        if lap.get("lapSeconds") is None or penalty(lap) == "nospeed":
            return 0.0
        return lap["distanceKm"] / (lap["lapSeconds"] / 3600)

    valid = [l for l in laps if l["distanceKm"] > 0 and (l.get("lapSeconds") is None or l["lapSeconds"] > 0)]
    if not valid:
        return {}
    max_d = max(l["distanceKm"] for l in valid)
    max_v = max(speed(l) for l in valid)
    reference = [l for l in valid if speed(l) == max_v] if max_v > 0 else [l for l in valid if l["distanceKm"] == max_d]
    ref_h = max(l["handicap"] for l in reference)
    scores = {l["key"]: l["distanceKm"] / max_d * (500 + 500 * (speed(l) / max_v if max_v > 0 else 0)) * l["handicap"] / ref_h
              for l in valid}
    top = max(scores.values())
    if top > 1000:
        scores = {k: v * 1000 / top for k, v in scores.items()}
    for l in valid:
        if penalty(l) == "deduct":
            scores[l["key"]] = max(0.0, scores[l["key"]] - LOW_GOAL_DEDUCTION)
    return scores


def day_course(competition, date, day_results):
    """The day's course: as set, else the one with the most laps completed (then the most entries)."""
    course = (competition.get("days", {}).get(date) or {}).get("course")
    if course:
        return course
    candidates = [(sum(1 for e in v if "finish" in e), len(v), name) for name, v in (day_results or {}).items() if v]
    return max(candidates)[2] if candidates else None


def day_glider_scores(competition, date, day_results):
    """{glider id: {"total": best three floored, "national": best one}} for one day and source."""
    course = day_course(competition, date, day_results)
    handicaps = competition.get("handicaps", {})
    laps = [{"key": i, "id": e["id"], "distanceKm": e["distanceKm"], "lapSeconds": e.get("lapSeconds"),
             "goalAltitudeM": e.get("goalAltitudeM"),
             "handicap": handicaps.get(e["id"], BUILT_IN_HANDICAPS.get(e["id"], 1.0))}
            for i, e in enumerate((day_results or {}).get(course, []))]
    scores = lap_scores(laps)
    by_glider = {}
    for lap in laps:
        if lap["key"] in scores:
            by_glider.setdefault(lap["id"], []).append(math.floor(scores[lap["key"]]))
    return {gid: {"total": float(sum(sorted(v, reverse=True)[:3])), "national": float(max(v))} for gid, v in by_glider.items()}


def finalize_competition(competition, results, today):
    """The competition with every day's scores filled in from the results, both sources, where not
    already entered - so its standings outlive the results' retention. Returns None if not due
    (FINALIZE_AFTER_DAYS after the last day) or already done."""
    end = competition.get("endDate")
    start = competition.get("startDate")
    if not start or not end or competition.get("finalizedAt"):
        return None
    if today < (datetime.strptime(end, "%Y-%m-%d") + timedelta(days=FINALIZE_AFTER_DAYS)).strftime("%Y-%m-%d"):
        return None
    final = json.loads(json.dumps(competition))
    days = final.setdefault("days", {})
    date = datetime.strptime(start, "%Y-%m-%d")
    while date.strftime("%Y-%m-%d") <= end:
        key = date.strftime("%Y-%m-%d")
        for source in ("ogn", "lora"):
            computed = day_glider_scores(final, key, results.get(key, {}).get(source, {}))
            if not computed:
                continue
            entered = days.setdefault(key, {}).setdefault("scores", {})
            for gid, score in computed.items():
                own = entered.setdefault(gid, {})
                for field in ("total", "national"):
                    if own.get(field) is None:
                        own[field] = score[field]
        date += timedelta(days=1)
    final["finalizedAt"] = int(time.time())
    return final


# ---------------------------------------------------------------- standings

_editor_key = {"value": None, "at": 0.0}


def editor_key():
    """The key required for saving standings, or "" when anyone may save: env STANDINGS_KEY, else the
    instance metadata attribute "standings-key" (re-read every 5 min, so it can be set or changed
    without a restart)."""
    env = os.environ.get("STANDINGS_KEY")
    if env:
        return env
    now = time.time()
    if _editor_key["value"] is None or now - _editor_key["at"] > 300:
        try:
            req = urllib.request.Request(METADATA_URL + "standings-key", headers={"Metadata-Flavor": "Google"})
            with urllib.request.urlopen(req, timeout=3) as r:
                _editor_key["value"] = r.read().decode().strip()
        except Exception:  # noqa: BLE001 - not on GCE, or not set: anyone may save
            _editor_key["value"] = ""
        _editor_key["at"] = now
    return _editor_key["value"]


def _is_date(value):
    return isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is not None


def _is_points(value):
    return value is None or (isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value))


def validate_standings(data):
    """The saved competition - name, period, per day the course flown and any corrected scores (by
    glider id), and shared handicaps - checked for shape; raises ValueError if unusable."""
    if not isinstance(data, dict):
        raise ValueError("not an object")
    name = data.get("name", "")
    if not isinstance(name, str) or len(name) > 120:
        raise ValueError("name")
    for key in ("startDate", "endDate"):
        if data.get(key) is not None and not _is_date(data[key]):
            raise ValueError(key)
    days = data.get("days", {})
    if not isinstance(days, dict) or len(days) > 60:
        raise ValueError("days")
    for date, day in days.items():
        if not _is_date(date) or not isinstance(day, dict):
            raise ValueError("day " + str(date)[:20])
        course = day.get("course")
        if course is not None and (not isinstance(course, str) or len(course) > 120):
            raise ValueError("course")
        scores = day.get("scores", {})
        if not isinstance(scores, dict) or len(scores) > 100:
            raise ValueError("scores")
        for glider, score in scores.items():
            if len(glider) > 40 or not isinstance(score, dict) or not _is_points(score.get("total")) or not _is_points(score.get("national")):
                raise ValueError("score " + str(glider)[:20])
    handicaps = data.get("handicaps", {})
    if not isinstance(handicaps, dict) or len(handicaps) > 200 or not all(
            isinstance(k, str) and len(k) <= 40 and _is_points(v) and v is not None for k, v in handicaps.items()):
        raise ValueError("handicaps")
    return data


def load_standings(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def save_standings(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    store = None
    results = None
    standings_path = None
    standings_lock = threading.Lock()
    server_version = "LoraViewerTrack/" + VERSION

    def do_PUT(self):
        url = urllib.parse.urlparse(self.path)
        if url.path != "/standings":
            return self._json({"error": "not found"}, 404)
        if not API_KEY or self.headers.get("X-Api-Key") != API_KEY:
            return self._json({"error": "unauthorized"}, 401)
        key = editor_key()
        if key and not hmac.compare_digest(self.headers.get("X-Editor-Key", ""), key):
            return self._json({"error": "forbidden"}, 403)
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > STANDINGS_MAX_BYTES:
            return self._json({"error": "bad length"}, 400)
        try:
            data = validate_standings(json.loads(self.rfile.read(length)))
        except ValueError as e:
            return self._json({"error": "bad standings: %s" % e}, 400)
        data["updatedAt"] = int(time.time())
        with self.standings_lock:
            save_standings(self.standings_path, data)
        log.info("competition saved: %s %s..%s", data.get("name", ""), data.get("startDate"), data.get("endDate"))
        self._json({"standings": data})

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/health":
            with self.store.lock:
                counts = {s: len(v) for s, v in self.store.tracks.items()}
            return self._json({"ok": True, "gliders": counts})
        if url.path not in ("/tracks", "/flights", "/standings", "/results"):
            return self._json({"error": "not found"}, 404)
        if not API_KEY or self.headers.get("X-Api-Key") != API_KEY:
            return self._json({"error": "unauthorized"}, 401)
        if url.path == "/standings":
            with self.standings_lock:
                return self._json({"standings": load_standings(self.standings_path)})
        q = urllib.parse.parse_qs(url.query)
        if url.path == "/results":
            source = q.get("source", [""])[0]
            start, end = q.get("from", ["0000-00-00"])[0], q.get("to", ["9999-99-99"])[0]
            if source not in ("ogn", "lora"):
                return self._json({"error": "bad source"}, 400)
            return self._json({"results": self.results.get(source, start, end)})
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
    Handler.standings_path = os.path.join(DATA_DIR, "standings.json")
    Handler.results = Results(os.path.join(DATA_DIR, "results.json"))
    threading.Thread(target=run_results, args=(store, Handler.results, Handler.standings_path, Handler.standings_lock),
                     daemon=True).start()
    log.info("listening on 127.0.0.1:%d", PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
