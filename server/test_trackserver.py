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
        pts = [(now - 600, 0, 0, 30), (now - 500, 0, 0, 200), (now - 400, 0, 0, 40),
               (now - 300, 0, 0, 150), (now - 200, 0, 0, 400), (now - 10, 0, 0, 500)]
        seg = ts.current_flight(pts, 60, now)
        self.assertEqual([p[3] for p in seg], [150, 400, 500])

    def test_landed_or_stale_glider_has_no_current_flight(self):
        now = 10_000
        self.assertIsNone(ts.current_flight([(now - 20, 0, 0, 300), (now - 5, 0, 0, 40)], 60, now))
        self.assertIsNone(ts.current_flight([(now - 3600, 0, 0, 300)], 60, now))

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
