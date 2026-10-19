import math
import unittest

import trackserver as ts


class TrackServerTest(unittest.TestCase):
    def test_parses_wiki_example_beacon(self):
        from datetime import datetime, timezone
        now = datetime(2026, 10, 12, 7, 46, tzinfo=timezone.utc).timestamp()
        b = ts.parse_beacon(
            "FLRDDE626>APRS,qAS,EGHL:/074548h5111.32N/00102.04W'086/007/A=000607 id0ADDE626 -019fpm", now)
        self.assertEqual(b["callsign"], "FLRDDE626")
        self.assertAlmostEqual(b["lat"], 51 + 11.32 / 60)
        self.assertAlmostEqual(b["lon"], -(1 + 2.04 / 60))
        self.assertAlmostEqual(b["alt"], 607 * 0.3048)
        self.assertEqual(b["t"], datetime(2026, 10, 12, 7, 45, 48, tzinfo=timezone.utc).timestamp())
        self.assertTrue(ts.displayable(b))

    def test_privacy_and_airliners_not_displayable(self):
        def beacon(flags, dest="OGFLR"):
            return ts.parse_beacon(
                f"FLR111111>{dest},qAS,Menuma:/030020h3612.68N/13925.13E'000/000/A=001000 id{flags}111111", 0)
        self.assertTrue(ts.displayable(beacon("06")))
        for flags in ("86", "46", "04", "25"):
            self.assertFalse(ts.displayable(beacon(flags)), flags)
        self.assertFalse(ts.displayable(beacon("06", "OGADSB")))

    def test_current_flight_is_since_last_ground_point(self):
        now = 10_000
        pts = [(now - 600, 0, 0, 30), (now - 500, 0, 0, 200), (now - 330, 0, 0, 40),
               (now - 300, 0, 0, 150), (now - 200, 0, 0, 400), (now - 10, 0, 0, 500)]
        seg = ts.current_flight(pts, 60, now)
        self.assertEqual([p[3] for p in seg], [40, 150, 400, 500])  # with the ground point before takeoff

    def test_landed_or_stale_glider_has_no_current_flight(self):
        now = 10_000
        self.assertIsNone(ts.current_flight([(now - 20, 0, 0, 300), (now - 5, 0, 0, 40)], 60, now))
        self.assertIsNone(ts.current_flight([(now - 3600, 0, 0, 300)], 60, now))

    def test_split_flights_breaks_at_ground_and_silence(self):
        pts = [(0, 0, 0, 30), (10, 0, 0, 200), (20, 0, 0, 300), (30, 0, 0, 40),  # flight 1, landed
               (100, 0, 0, 150), (110, 0, 0, 160),                               # flight 2...
               (110 + ts.FLIGHT_GAP_S + 1, 0, 0, 500), (120 + ts.FLIGHT_GAP_S + 1, 0, 0, 510),  # ...silence: 3
               (2000, 0, 0, 30), (2010, 0, 0, 100)]                              # single point: dropped
        # Each flight starts with the ground point just before takeoff, when within 60 s (not 30 -> 100).
        self.assertEqual([[p[0] for p in f] for f in ts.split_flights(pts, 60)],
                         [[0, 10, 20], [100, 110], [110 + ts.FLIGHT_GAP_S + 1, 120 + ts.FLIGHT_GAP_S + 1]])

    def test_jst_midnight(self):
        from datetime import datetime, timezone
        now = datetime(2026, 10, 17, 7, 0, tzinfo=timezone.utc).timestamp()  # 16:00 JST
        self.assertEqual(ts.jst_midnight(now), datetime(2026, 10, 16, 15, 0, tzinfo=timezone.utc).timestamp())
        early = datetime(2026, 10, 16, 16, 0, tzinfo=timezone.utc).timestamp()  # 01:00 JST on the 17th
        self.assertEqual(ts.jst_midnight(early), datetime(2026, 10, 16, 15, 0, tzinfo=timezone.utc).timestamp())

    def test_all_flights_since(self):
        store = ts.Store()
        for t, alt in ((0, 300), (10, 310), (100, 30), (200, 300), (210, 320)):
            store.add("lora", "0001", t, 36.2, 139.4, alt)
        self.assertEqual([[p[0] for p in f] for f in store.all_flights("lora", 60, 150)["0001"]], [[200, 210]])  # ground point at 100 is >60 s before
        self.assertEqual(len(store.all_flights("lora", 60, 0)["0001"]), 2)

    def test_store_spacing_and_downsample(self):
        s = ts.Store()
        for t in range(0, 100):
            s.add("ogn", "A", t, 1, 2, 300)
        self.assertEqual(len(s.tracks["ogn"]["A"]), 20)  # one per 5 s
        pts = list(range(5000))
        d = ts.downsample(pts, 1000)
        self.assertEqual(len(d), 1000)
        self.assertEqual((d[0], d[-1]), (0, 4999))


if __name__ == "__main__":
    unittest.main()

class StandingsTest(unittest.TestCase):
    def test_validation(self):
        ok = {"name": "第42回関東", "startDate": "2026-10-11", "endDate": "2026-10-18",
              "days": {"2026-10-17": {"course": "③", "scores": {"FLRDB0786": {"total": 900, "national": None}}}},
              "handicaps": {"ICA84C247": 0.9}}
        self.assertIs(ts.validate_standings(ok), ok)
        for bad in ({"startDate": "17/10"}, {"days": {"x": {}}}, {"days": {"2026-10-17": {"course": 3}}},
                    {"days": {"2026-10-17": {"scores": {"a": {"total": "1"}}}}}, {"handicaps": {"a": "0.9"}}):
            with self.assertRaises(ValueError):
                ts.validate_standings(bad)

    def test_http_round_trip(self):
        import json, os, tempfile, threading, time, urllib.request, urllib.error
        from http.server import ThreadingHTTPServer
        tmp = tempfile.mkdtemp()
        ts.API_KEY = "api"
        os.environ["STANDINGS_KEY"] = "editor"
        ts.Handler.store = ts.Store()
        ts.Handler.standings_path = os.path.join(tmp, "standings.json")
        server = ThreadingHTTPServer(("127.0.0.1", 0), ts.Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = "http://127.0.0.1:%d/standings" % server.server_address[1]
        try:
            def call(method, headers, body=None):
                req = urllib.request.Request(base, data=body, method=method, headers=headers)
                try:
                    with urllib.request.urlopen(req) as r:
                        return r.status, json.load(r)
                except urllib.error.HTTPError as e:
                    return e.code, None
            self.assertEqual(call("GET", {"X-Api-Key": "api"}), (200, {"standings": None}))
            body = json.dumps({"name": "test", "startDate": "2026-10-11", "endDate": "2026-10-18"}).encode()
            self.assertEqual(call("PUT", {"X-Api-Key": "api", "X-Editor-Key": "wrong"}, body)[0], 403)
            self.assertEqual(call("PUT", {"X-Api-Key": "x", "X-Editor-Key": "editor"}, body)[0], 401)
            status, saved = call("PUT", {"X-Api-Key": "api", "X-Editor-Key": "editor"}, body)
            self.assertEqual(status, 200)
            self.assertIn("updatedAt", saved["standings"])
            status, got = call("GET", {"X-Api-Key": "api"})
            self.assertEqual(got["standings"]["name"], "test")
            # Without an editor key set, any app may save.
            del os.environ["STANDINGS_KEY"]
            ts._editor_key.update(value="", at=time.time())
            self.assertEqual(call("PUT", {"X-Api-Key": "api"}, body)[0], 200)
        finally:
            server.shutdown()
            os.environ.pop("STANDINGS_KEY", None)


class LapTimingTest(unittest.TestCase):
    """The ported lap timing, on a made-up flight round course ② (妻沼-千代田-高林給水塔-妻沼)."""

    @staticmethod
    def offset(origin, distance_m, bearing_deg):
        lat = origin[0] + distance_m * math.cos(math.radians(bearing_deg)) / 110574.0
        lon = origin[1] + distance_m * math.sin(math.radians(bearing_deg)) / (111320.0 * math.cos(math.radians(origin[0])))
        return lat, lon

    def flight(self, chiyoda_alt=700.0, goal=True):
        names = ts.COURSES[1][2]
        mid_goal = ((ts.GOAL_A[0] + ts.GOAL_B[0]) / 2, (ts.GOAL_A[1] + ts.GOAL_B[1]) / 2)
        pts = [(0, *self.offset(ts.START_POINT, 200, 135), 30.0), (10, *self.offset(ts.START_POINT, 200, 315), 150.0)]
        pts.append((600, *self.offset(ts.TURNPOINTS["千代田"], 1000, ts.sector_bearing(names, "千代田")), chiyoda_alt))
        pts.append((1200, *self.offset(ts.TURNPOINTS["高林給水塔"], 1000, ts.sector_bearing(names, "高林給水塔")), 700.0))
        if goal:
            pts += [(1760, *self.offset(mid_goal, 300, 315), 400.0), (1780, *self.offset(mid_goal, 300, 135), 400.0)]
        return pts

    def test_full_lap(self):
        lap = ts.evaluate_lap(self.flight(), ts.COURSES[1][2])
        self.assertAlmostEqual(lap["start"], 5, delta=0.5)
        self.assertEqual([n for n, _ in lap["passed"]], ["千代田", "高林給水塔"])
        self.assertAlmostEqual(lap["finish"], 1770, delta=0.5)

    def test_low_turnpoint_and_partial_results(self):
        lap = ts.evaluate_lap(self.flight(chiyoda_alt=500.0), ts.COURSES[1][2])  # below the 630 m guideline there
        self.assertEqual(lap["passed"], [])
        self.assertIsNone(lap["finish"])
        results = ts.day_results({"g1": [self.flight(goal=False)]}, now=1e9)
        partial = results[ts.COURSES[1][0]]
        self.assertEqual(partial, [{"id": "g1", "start": partial[0]["start"], "passed": ["千代田", "高林給水塔"], "distanceKm": 23.6}])
        finished = ts.day_results({"g1": [self.flight()]}, now=1e9)[ts.COURSES[1][0]][0]
        self.assertAlmostEqual(finished["lapSeconds"], 1765, delta=1)

