import os
import sys
import tempfile
import textwrap
import unittest

import pandas as pd

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from processors import dcr_trend_analyser_processor


class DcrTrendAnalyserProcessorTests(unittest.TestCase):
    def test_run_builds_trend_workbook_and_stats(self):
        morning_csv = textwrap.dedent(
            """
            Full Name,Total Amount,Paid Amount,Outstanding Amount,Collection Rate (%),Branch Name
            ALICE KIPTOO,10000,5000,5000,50,MOLO
            BOB KIPYEGON,8000,4000,4000,50,MOLO
            """
        ).strip()

        current_csv = textwrap.dedent(
            """
            Full Name,Total Amount,Paid Amount,Outstanding Amount,Collection Rate (%),Branch Name
            ALICE KIPTOO,10000,6500,3500,65,MOLO
            BOB KIPYEGON,8000,3600,4400,45,MOLO
            """
        ).strip()

        with tempfile.TemporaryDirectory() as temp_dir:
            morning_path = os.path.join(temp_dir, "morning.csv")
            current_path = os.path.join(temp_dir, "current.csv")
            output_dir = os.path.join(temp_dir, "out")

            with open(morning_path, "w", encoding="utf-8", newline="") as handle:
                handle.write(morning_csv)
            with open(current_path, "w", encoding="utf-8", newline="") as handle:
                handle.write(current_csv)

            result = dcr_trend_analyser_processor.run(morning_path, current_path, output_dir)

            self.assertTrue(result["success"], result)
            self.assertTrue(os.path.isfile(result["output_file"]))
            self.assertTrue(os.path.isfile(result["chart_file"]))

            stats = result["stats"]
            self.assertEqual(stats["total_portfolios"], 2)
            self.assertEqual(stats["improved_count"], 1)
            self.assertEqual(stats["declined_count"], 1)
            self.assertEqual(stats["unchanged_count"], 0)
            self.assertEqual(stats["avg_change_pct"], 5.0)

            workbook = pd.read_excel(result["output_file"], sheet_name="Trend Analysis")
            self.assertListEqual(
                list(workbook.columns),
                [
                    "Full Name",
                    "Branch Name",
                    "Morning CR (%)",
                    "Current CR (%)",
                    "CR Change (%)",
                    "Morning Paid",
                    "Current Paid",
                    "Morning Outstanding",
                    "Current Outstanding",
                    "Trend Status",
                    "Trend Comment",
                ],
            )
            self.assertEqual(workbook.loc[0, "Trend Status"], "Improved")
            self.assertEqual(workbook.loc[1, "Trend Status"], "Declined")


if __name__ == "__main__":
    unittest.main()
