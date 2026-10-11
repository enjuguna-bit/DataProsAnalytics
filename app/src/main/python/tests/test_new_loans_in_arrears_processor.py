import os
import sys
import tempfile
import textwrap
import unittest

import pandas as pd

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from processors import new_loans_in_arrears_processor


class NewLoansInArrearsProcessorTests(unittest.TestCase):
    def test_run_generates_report(self):
        disb_csv = textwrap.dedent(
            """
            LoanId,OLB,Product,Disbursement Date,FieldOfficer,FullNames,Branch
            1001,7500,5 WEEKS CREDIT EASE,2026-07-01,Officer A,John Doe,Nairobi
            1002,15000,SME loan,2026-07-02,Officer B,Jane Smith,Mombasa
            """
        ).strip()

        arr_csv = textwrap.dedent(
            """
            LoanId,LoanBalance,ProductName,DaysInArrears,SalesRep,FullNames,Branch,ArrearsAmount,Phone
            1001,5000,5 WEEKS CREDIT EASE,5,Officer A,John Doe,Nairobi,2000,254722000000
            """
        ).strip()

        with tempfile.TemporaryDirectory() as temp_dir:
            disb_path = os.path.join(temp_dir, "disbursement.csv")
            arr_path = os.path.join(temp_dir, "arrears.csv")
            output_path = os.path.join(temp_dir, "output.xlsx")

            with open(disb_path, "w", encoding="utf-8", newline="") as f:
                f.write(disb_csv)
            with open(arr_path, "w", encoding="utf-8", newline="") as f:
                f.write(arr_csv)

            result = new_loans_in_arrears_processor.run(
                disb_path, arr_path, output_path, window_days=30
            )

            self.assertTrue(result["success"])
            self.assertTrue(os.path.isfile(output_path))
            self.assertEqual(result["stats"]["total_disbursed"], 2)
            self.assertEqual(result["stats"]["matched"], 1)
            self.assertEqual(result["stats"]["max_days_in_arrears"], 5.0)
            self.assertEqual(result["stats"]["total_arrears_amount"], 5000.0)


if __name__ == "__main__":
    unittest.main()
