import os
import sys
import unittest

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from processors import mtd_income_analysis


class MtdIncomeAnalysisTests(unittest.TestCase):
    def test_parse_day_bounds(self):
        self.assertEqual(mtd_income_analysis._parse_day(""), 1)
        self.assertEqual(mtd_income_analysis._parse_day("D0"), 1)
        self.assertEqual(mtd_income_analysis._parse_day("D19"), 19)
        self.assertEqual(mtd_income_analysis._parse_day("99"), 31)

    def test_parse_daily_targets_default_fill(self):
        names = ["JOASH MUTISYA", "BRIAN WANJALA"]
        targets = mtd_income_analysis._parse_daily_targets(
            "DEFAULT=5000\nJOASH MUTISYA=7000",
            names,
        )
        self.assertEqual(targets["JOASH MUTISYA"], 7000.0)
        self.assertEqual(targets["BRIAN WANJALA"], 5000.0)


if __name__ == "__main__":
    unittest.main()
