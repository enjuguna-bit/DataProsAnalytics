"""
DataPros Analytics - Python Bridge
Called by Kotlin via Chaquopy. Each public function takes file paths
and returns a JSON-serialisable dict.

Design notes
------------
* Every public function is called from Kotlin with str arguments.
* All return values are JSON strings so Kotlin can use JSONObject/JSONArray directly.
* _wrap() guarantees a structured error dict is returned instead of propagating
  exceptions, which would surface as an unformatted Chaquopy exception on the
  Kotlin side.
* _assert_file() is called at the top of every processor entry-point to give
  users a clear "file not found" message rather than a cryptic pandas error.
"""

import json
import os
import traceback
from processors import (
    arrears_processor,
    dues_processor,
    collections_processor,
    dormant_processor,
    branch_comparison,
    disbursement_processor,
    loan_tracker_processor,
    mtd_unpaid_dues,
    mtd_pivot_analyzer,
    ro_performance,
    sod_cr_portfolio,
    mtd_cr_portfolio,
    mtd_income_analysis,
    new_loans_in_arrears_processor,
    dcr_trend_analyser_processor,
)


DEBUG_BRIDGE = os.getenv("DATAPROS_DEBUG_BRIDGE", "").strip().lower() in {"1", "true", "yes", "on"}

# --------------------------------------------------------------------------- #
# Internal helpers                                                             #
# --------------------------------------------------------------------------- #

def _wrap(fn, *args, **kwargs) -> dict:
    """Execute a processor function and guarantee a JSON-safe dict return."""
    try:
        return fn(*args, **kwargs)
    except Exception as exc:
        payload = {"success": False, "error": str(exc) or "Processor failed"}
        if DEBUG_BRIDGE:
            payload["trace"] = traceback.format_exc()
        return payload


def _assert_file(*paths: str) -> None:
    """
    Raise FileNotFoundError with a descriptive message when any of the supplied
    paths does not exist on-disk.  This produces a clean error message on the
    Kotlin side rather than a confusing pandas / openpyxl traceback.
    """
    for path in paths:
        if path and not os.path.isfile(path):
            raise FileNotFoundError(
                f"Input file not found: {os.path.basename(path)!r}. "
                "Re-select the file and try again."
            )


def _to_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


# --------------------------------------------------------------------------- #
# Health-check                                                                 #
# --------------------------------------------------------------------------- #

def ping() -> str:
    """
    Lightweight round-trip to verify the Python bridge is alive.
    Called by PythonRunner.ping() during app start-up diagnostics.
    Returns {"success": true, "pong": true}.
    """
    return json.dumps({"success": True, "pong": True})


# --------------------------------------------------------------------------- #
# Public API (called from Kotlin)                                              #
# --------------------------------------------------------------------------- #

def run_arrears(input_path: str, output_dir: str) -> str:
    _assert_file(input_path)
    result = _wrap(arrears_processor.run, input_path, output_dir)
    return json.dumps(result)


def run_loan_tracker(report_path: str, output_dir: str) -> str:
    _assert_file(report_path)
    result = _wrap(loan_tracker_processor.run, report_path, output_dir)
    return json.dumps(result)


def run_dues(input_path: str, output_dir: str) -> str:
    _assert_file(input_path)
    result = _wrap(dues_processor.run, input_path, output_dir)
    return json.dumps(result)


def get_collection_officers(sod_path: str) -> str:
    try:
        _assert_file(sod_path)
        officers = collections_processor.get_officers(sod_path)
        return json.dumps(officers)
    except Exception:
        return json.dumps([])


def run_collections(
    sod_path: str,
    current_path: str,
    output_dir: str,
    targets_input: str = "",
) -> str:
    _assert_file(sod_path, current_path)
    result = _wrap(collections_processor.run, sod_path, current_path, output_dir, targets_input)
    return json.dumps(result)


def get_dormant_branches(input_path: str) -> str:
    try:
        _assert_file(input_path)
        branches = dormant_processor.get_branches(input_path)
        return json.dumps(branches)
    except Exception:
        return json.dumps([])


def run_dormant(
    input_path: str,
    output_dir: str,
    process_all_branches: str = "true",
    selected_branch: str = "",
    remove_duplicates: str = "true",
    fill_na: str = "true",
    add_formatting: str = "true",
) -> str:
    _assert_file(input_path)
    result = _wrap(
        dormant_processor.run,
        input_path,
        output_dir,
        _to_bool(process_all_branches),
        selected_branch,
        _to_bool(remove_duplicates),
        _to_bool(fill_na),
        _to_bool(add_formatting),
    )
    return json.dumps(result)


def run_branch(income_path: str, cr_path: str, disb_path: str, output_dir: str) -> str:
    _assert_file(income_path, cr_path, disb_path)
    result = _wrap(branch_comparison.run, income_path, cr_path, disb_path, output_dir)
    return json.dumps(result)


def run_mtd(input_path: str, output_dir: str) -> str:
    _assert_file(input_path)
    result = _wrap(mtd_unpaid_dues.run, input_path, output_dir)
    return json.dumps(result)


def get_mtd_pivot_officers(input_path: str) -> str:
    try:
        _assert_file(input_path)
        officers = mtd_pivot_analyzer.get_officers(input_path)
        return json.dumps(officers)
    except Exception:
        return json.dumps([])


def run_mtd_pivot(
    input_path: str,
    output_dir: str,
    day_header: str = "D19",
    new_targets_input: str = "",
    mtd_targets_input: str = "",
    daily_targets_input: str = "",
) -> str:
    _assert_file(input_path)
    result = _wrap(
        mtd_pivot_analyzer.run,
        input_path,
        output_dir,
        day_header,
        new_targets_input,
        mtd_targets_input,
        daily_targets_input,
    )
    return json.dumps(result)


def run_ro(income_path: str, collection_rate_path: str, disbursement_path: str, output_dir: str) -> str:
    _assert_file(income_path, collection_rate_path, disbursement_path)
    result = _wrap(ro_performance.run, income_path, collection_rate_path, disbursement_path, output_dir)
    return json.dumps(result)


def run_sod_cr_portfolio(officer_input_path: str, detail_input_path: str, output_dir: str) -> str:
    _assert_file(officer_input_path, detail_input_path)
    result = _wrap(sod_cr_portfolio.run, officer_input_path, detail_input_path, output_dir)
    return json.dumps(result)


def get_mtd_cr_officers(input_path: str) -> str:
    try:
        _assert_file(input_path)
        officers = mtd_cr_portfolio.get_officers(input_path)
        return json.dumps(officers)
    except Exception:
        return json.dumps([])


def run_mtd_cr_portfolio(input_path: str, output_dir: str, targets_input: str = "") -> str:
    _assert_file(input_path)
    result = _wrap(mtd_cr_portfolio.run, input_path, output_dir, targets_input)
    return json.dumps(result)


def get_mtd_income_portfolios(input_path: str) -> str:
    try:
        _assert_file(input_path)
        items = mtd_income_analysis.get_portfolios(input_path)
        return json.dumps(items)
    except Exception:
        return json.dumps([])


def run_mtd_income_analysis(
    input_path: str,
    output_dir: str,
    day_input: str = "1",
    daily_targets_input: str = "",
) -> str:
    _assert_file(input_path)
    result = _wrap(mtd_income_analysis.run, input_path, output_dir, day_input, daily_targets_input)
    return json.dumps(result)


def run_disbursement(input_path: str, output_dir: str) -> str:
    """
    Process Cemes disbursement file with enhanced transformations:
    - Split FullNames into First Name, 2nd Name, 3rd Name
    - Normalize phone numbers to 254XXXXXXXXX format
    - Calculate Weekly Installment (OLB / Product Duration in weeks)
    - Calculate Daily Installment (Weekly Installment / 7)
    """
    _assert_file(input_path)
    result = _wrap(disbursement_processor.run, input_path, output_dir)
    return json.dumps(result)


def run_new_loans_in_arrears(
    disbursement_path: str,
    arrears_path: str,
    output_dir: str,
) -> str:
    """
    Cross-reference this month's disbursements against the current arrears list.

    Identifies loans disbursed this month that are already appearing in arrears —
    a flag for immediate default risk, repeat-borrower issues, or data problems.

    Produces a 4-sheet Excel report:
      Sheet 1 – Full match list (sorted worst days first, colour-coded)
      Sheet 2 – Breakdown by Sales Rep
      Sheet 3 – Maturity status breakdown
      Sheet 4 – Top 10 worst cases by DaysInArrears

    Returns JSON with:
      success          bool
      output_file      str | null
      stats.total_disbursed
      stats.total_in_arrears_list
      stats.matched
      stats.match_pct
      stats.total_arrears_amount
      stats.max_days_in_arrears
    """
    _assert_file(disbursement_path, arrears_path)
    result = _wrap(
        new_loans_in_arrears_processor.run,
        disbursement_path,
        arrears_path,
        output_dir,
    )
    return json.dumps(result)


def run_dcr_trend_analyser(
    morning_path: str,
    current_path: str,
    output_dir: str,
) -> str:
    _assert_file(morning_path, current_path)
    result = _wrap(
        dcr_trend_analyser_processor.run,
        morning_path,
        current_path,
        output_dir,
    )
    return json.dumps(result)
