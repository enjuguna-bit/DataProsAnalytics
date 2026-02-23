"""
DataPros Analytics - Python Bridge
Called by Kotlin via Chaquopy. Each public function takes file paths
and returns a JSON-serialisable dict.
"""

import json, traceback
from processors import (
    arrears_processor,
    dues_processor,
    collections_processor,
    dormant_processor,
    branch_comparison,
    mtd_unpaid_dues,
    mtd_pivot_analyzer,
    ro_performance,
    sod_cr_portfolio,
    mtd_cr_portfolio,
    mtd_income_analysis,
)


def _wrap(fn, *args, **kwargs):
    """Execute a processor function and guarantee a JSON-safe dict return."""
    try:
        return fn(*args, **kwargs)
    except Exception as exc:
        return {"success": False, "error": str(exc), "trace": traceback.format_exc()}


# ── Public API (called from Kotlin) ─────────────────────────────────────────

def run_arrears(input_path: str, output_dir: str) -> str:
    result = _wrap(arrears_processor.run, input_path, output_dir)
    return json.dumps(result)


def run_dues(input_path: str, output_dir: str) -> str:
    result = _wrap(dues_processor.run, input_path, output_dir)
    return json.dumps(result)


def get_collection_officers(sod_path: str) -> str:
    try:
        officers = collections_processor.get_officers(sod_path)
        return json.dumps(officers)
    except Exception:
        return json.dumps([])


def run_collections(sod_path: str, current_path: str, output_dir: str, targets_input: str = "") -> str:
    result = _wrap(collections_processor.run, sod_path, current_path, output_dir, targets_input)
    return json.dumps(result)


def _to_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def get_dormant_branches(input_path: str) -> str:
    try:
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
    result = _wrap(branch_comparison.run, income_path, cr_path, disb_path, output_dir)
    return json.dumps(result)


def run_mtd(input_path: str, output_dir: str) -> str:
    result = _wrap(mtd_unpaid_dues.run, input_path, output_dir)
    return json.dumps(result)


def get_mtd_pivot_officers(input_path: str) -> str:
    try:
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
    result = _wrap(ro_performance.run, income_path, collection_rate_path, disbursement_path, output_dir)
    return json.dumps(result)


def run_sod_cr_portfolio(input_path: str, output_dir: str) -> str:
    result = _wrap(sod_cr_portfolio.run, input_path, output_dir)
    return json.dumps(result)


def get_mtd_cr_officers(input_path: str) -> str:
    try:
        officers = mtd_cr_portfolio.get_officers(input_path)
        return json.dumps(officers)
    except Exception:
        return json.dumps([])


def run_mtd_cr_portfolio(input_path: str, output_dir: str, targets_input: str = "") -> str:
    result = _wrap(mtd_cr_portfolio.run, input_path, output_dir, targets_input)
    return json.dumps(result)


def get_mtd_income_portfolios(input_path: str) -> str:
    try:
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
    result = _wrap(mtd_income_analysis.run, input_path, output_dir, day_input, daily_targets_input)
    return json.dumps(result)
