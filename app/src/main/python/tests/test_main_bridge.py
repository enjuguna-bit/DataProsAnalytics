import json
import os
import sys
import unittest

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

import main


class MainBridgeTests(unittest.TestCase):
    def test_wrap_success_passthrough(self):
        def _ok():
            return {"success": True, "value": 42}

        result = main._wrap(_ok)
        self.assertEqual(result["success"], True)
        self.assertEqual(result["value"], 42)

    def test_wrap_failure_sanitized(self):
        def _boom():
            raise ValueError("bad input")

        result = main._wrap(_boom)
        self.assertEqual(result["success"], False)
        self.assertIn("bad input", result["error"])
        if not main.DEBUG_BRIDGE:
            self.assertNotIn("trace", result)

    def test_run_disbursement_entry_exists(self):
        self.assertTrue(callable(main.run_disbursement))

    def test_run_loan_tracker_entry_exists(self):
        self.assertTrue(callable(main.run_loan_tracker))


if __name__ == "__main__":
    unittest.main()
