import os
import sys
import tempfile
import textwrap
import unittest

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from processors import dues_processor


class DuesProcessorTests(unittest.TestCase):
    def test_clean_and_prepare_data_uses_unit_title_for_branch_and_preserves_due_date(self):
        csv_text = textwrap.dedent(
            """
            LoanId,FullNames,PhoneNumber,InstallmentNo,Amount Due,Arrears,AmountPaid,LoanBalance,FieldOfficer,UnitTitle,Due Date
            1048353,ZIPPORAH WANGARI MWANGI,254111445824,2,1942.72,0,1000.14,16657.00,Alexander Mwangi,MOLO,6/3/2026 12:00:00 AM
            1016327,Wilfred kipkemoi Langat,254726512027,4,290.00,870,0.00,1160.00,Benson Kirui,MOLO,6/3/2026 12:00:00 AM
            """
        ).strip()

        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = os.path.join(temp_dir, "dues.csv")

            with open(input_path, "w", encoding="utf-8", newline="") as handle:
                handle.write(csv_text)

            generator = dues_processor.PremiumLoanReportGenerator()
            self.assertTrue(generator.load_data(input_path))
            self.assertTrue(generator.clean_and_prepare_data())

            self.assertEqual(generator.df.loc[0, "Branch"], "MOLO")
            self.assertEqual(generator.df.loc[0, "Due Date"], "2026-06-03")
            self.assertEqual(generator.df["Branch"].nunique(), 1)

    def test_run_supports_sod_cr_style_export(self):
        csv_text = textwrap.dedent(
            """
            #ID,Full Name,Total Amount,Paid Amount,Outstanding Amount,Collection Rate (%),Branch Name
            25,ALEXANDER MWANGI,38519,10013,28506,26,MOLO
            54,BRIAN WANJALA,34771,21566,13205,62,MOLO
            """
        ).strip()

        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = os.path.join(temp_dir, "sod_cr.csv")
            output_dir = os.path.join(temp_dir, "out")

            with open(input_path, "w", encoding="utf-8", newline="") as handle:
                handle.write(csv_text)

            result = dues_processor.run(input_path, output_dir)

            self.assertTrue(result["success"], result)
            self.assertTrue(os.path.isfile(result["output_file"]))

            stats = result["stats"]
            self.assertEqual(stats["total_clients"], 2)
            self.assertEqual(stats["total_groups"], 2)
            self.assertEqual(stats["num_branches"], 1)
            self.assertEqual(stats["total_due"], 73290.0)
            self.assertEqual(stats["total_arrears"], 41711.0)
            self.assertEqual(stats["total_amount_paid"], 31579.0)
            self.assertEqual(stats["total_loan_balance"], 41711.0)


if __name__ == "__main__":
    unittest.main()
