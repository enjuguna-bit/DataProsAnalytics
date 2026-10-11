import os
import sys
import tempfile
import textwrap
import unittest

import pandas as pd

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from processors import disbursement_processor


class DisbursementProcessorTests(unittest.TestCase):
    def test_run_adds_name_phone_and_installment_columns(self):
        csv_text = textwrap.dedent(
            """
            FullNames,AccountNo,LoanId,OLB,Product
            "MARY JANE DOE",0722123456,1001,"Ksh 7,750.00","5 WEEKS CREDIT EASE"
            """
        ).strip()

        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = os.path.join(temp_dir, "input.csv")
            output_dir = os.path.join(temp_dir, "out")

            with open(input_path, "w", encoding="utf-8", newline="") as handle:
                handle.write(csv_text)

            result = disbursement_processor.run(input_path, output_dir)

            self.assertTrue(result["success"], result)
            self.assertTrue(os.path.isfile(result["output_file"]))
            self.assertEqual(result["rows_processed"], 1)

            df = pd.read_excel(result["output_file"])
            self.assertEqual(df.loc[0, "First Name"], "Mary")
            self.assertEqual(df.loc[0, "2nd Name"], "Jane")
            self.assertEqual(df.loc[0, "3rd Name"], "Doe")
            self.assertEqual(str(df.loc[0, "Normalized Phone Number"]), "254722123456")
            self.assertAlmostEqual(float(df.loc[0, "Weekly Installment"]), 1550.0)
            self.assertAlmostEqual(float(df.loc[0, "Daily Installment"]), 258.33)


if __name__ == "__main__":
    unittest.main()
