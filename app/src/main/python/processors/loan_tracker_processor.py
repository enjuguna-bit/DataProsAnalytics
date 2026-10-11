"""
Loan week-tracking processor.

This module compares a disbursement report against the built-in product guide
logic to
calculate:
- initial OLB from the product logic
- daily installment using a 6-day repayment week
- expected repayment days as of today
- current loan week
- expected balance, missed days, and catch-up amount
"""

from __future__ import annotations

import os
import re
from typing import Iterable, Optional, Tuple

import numpy as np
import pandas as pd

from processors.tabular_io import read_tabular_file


REPAYMENT_DAYS_PER_WEEK = 6
REPAYMENT_WEEKMASK = "1111110"  # Monday-Saturday are repayment days.

DISBURSED_AMOUNT_ALIASES = (
    "disbursed_amount",
    "disbursed amount",
    "amount disbursed",
    "amountdisbursed",
    "loan amount",
    "amount",
)
DISBURSEMENT_DATE_ALIASES = (
    "disbursement_date",
    "disbursement date",
    "borrow date",
    "borrowdate",
    "loan date",
)
CURRENT_OLB_ALIASES = (
    "current_olb",
    "current olb",
    "olb",
    "outstanding loan balance",
    "loan balance",
)
CLIENT_NAME_ALIASES = (
    "fullnames",
    "full names",
    "client name",
    "customer name",
    "name",
)
ACCOUNT_ALIASES = (
    "accountno",
    "account no",
    "account number",
    "phone number",
    "phonenumber",
    "phone",
)
FIELD_OFFICER_ALIASES = (
    "fieldofficer",
    "field officer",
    "salesrep",
    "sales rep",
    "officer",
)
PRODUCT_ALIASES = (
    "product",
    "product name",
    "loan product",
)
CLEAR_DATE_ALIASES = (
    "clear date",
    "clear_date",
    "maturity date",
    "due date",
)

SUPPORTED_TERM_WEEKS = (5, 7, 10, 12)
MIN_LOAN_SIZE = 3000
MAX_LOAN_SIZE = 49000
LOAN_SIZE_STEP = 1000


def _normalize_label(value: object) -> str:
    text = "" if value is None else str(value)
    return re.sub(r"[^a-z0-9]+", "", text.strip().lower())


def _parse_amount(value: object) -> float:
    if value is None:
        return np.nan
    if isinstance(value, (int, float, np.integer, np.floating)):
        return np.nan if pd.isna(value) else float(value)

    text = str(value).strip()
    if not text:
        return np.nan

    cleaned = re.sub(r"[^0-9.\-]", "", text)
    if cleaned in {"", "-", ".", "-."}:
        return np.nan

    try:
        return float(cleaned)
    except ValueError:
        return np.nan


def _parse_date(value: object) -> pd.Timestamp:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return pd.NaT

    if isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value.strip()):
        return pd.Timestamp(value).normalize()

    parsed = pd.to_datetime(value, errors="coerce", dayfirst=True)
    if pd.isna(parsed):
        return pd.NaT
    return pd.Timestamp(parsed).normalize()


def _extract_term_weeks(value: object) -> Optional[int]:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None

    match = re.search(r"(\d+)\s*weeks?", str(value), flags=re.IGNORECASE)
    if not match:
        return None

    try:
        return int(match.group(1))
    except ValueError:
        return None


def _find_column(columns: Iterable[object], aliases: Iterable[str]) -> Optional[str]:
    alias_keys = {_normalize_label(alias) for alias in aliases}
    for column in columns:
        if _normalize_label(column) in alias_keys:
            return str(column)
    return None


def _amount_key(value: float) -> Optional[int]:
    if pd.isna(value):
        return None
    return int(round(float(value) * 100))


def _count_repayment_days(disbursement_date: pd.Timestamp, as_of_date: pd.Timestamp) -> int:
    if pd.isna(disbursement_date) or pd.isna(as_of_date) or as_of_date <= disbursement_date:
        return 0

    start = np.datetime64((disbursement_date + pd.Timedelta(days=1)).date(), "D")
    end = np.datetime64((as_of_date + pd.Timedelta(days=1)).date(), "D")
    return int(np.busday_count(start, end, weekmask=REPAYMENT_WEEKMASK))


def _infer_term_weeks_from_dates(
    disbursement_date: pd.Timestamp,
    clear_date: pd.Timestamp,
) -> Optional[int]:
    if pd.isna(disbursement_date) or pd.isna(clear_date) or clear_date <= disbursement_date:
        return None

    repayment_days = _count_repayment_days(disbursement_date, clear_date)
    if repayment_days <= 0 or repayment_days % REPAYMENT_DAYS_PER_WEEK != 0:
        return None

    return repayment_days // REPAYMENT_DAYS_PER_WEEK


def _normalize_supported_loan_size(disbursed_amount: float) -> Tuple[Optional[int], Optional[str]]:
    if pd.isna(disbursed_amount):
        return None, "Missing or invalid disbursed amount."

    rounded_amount = int(round(float(disbursed_amount)))
    if abs(float(disbursed_amount) - rounded_amount) > 0.01:
        return None, f"Unsupported loan size {disbursed_amount:,.2f}. Expected whole-thousand values."

    if rounded_amount < MIN_LOAN_SIZE or rounded_amount > MAX_LOAN_SIZE:
        return (
            None,
            f"Unsupported loan size {rounded_amount:,.0f}. Supported range is "
            f"{MIN_LOAN_SIZE:,.0f} to {MAX_LOAN_SIZE:,.0f}.",
        )

    if rounded_amount % LOAN_SIZE_STEP != 0:
        return (
            None,
            f"Unsupported loan size {rounded_amount:,.0f}. Supported values increase in "
            f"{LOAN_SIZE_STEP:,.0f} steps.",
        )

    return rounded_amount, None


def _weekly_interest(loan_size: int) -> float:
    # Derived from the guide: 3,000 => 200, then +50 for each extra 1,000.
    return float(((loan_size // LOAN_SIZE_STEP) + 1) * 50)


def _resolve_product_logic(
    disbursed_amount: float,
    term_weeks: Optional[int],
) -> Tuple[Optional[int], Optional[float], Optional[str]]:
    loan_size, amount_error = _normalize_supported_loan_size(disbursed_amount)
    if amount_error:
        return None, None, amount_error

    if term_weeks is None:
        return None, None, "Could not determine loan term from Product/Clear Date."

    if term_weeks not in SUPPORTED_TERM_WEEKS:
        supported_terms = ", ".join(str(weeks) for weeks in SUPPORTED_TERM_WEEKS)
        return (
            None,
            None,
            f"Unsupported term {term_weeks} weeks. Supported terms: {supported_terms}.",
        )

    initial_olb = round(float(loan_size) + (_weekly_interest(loan_size) * term_weeks), 2)
    return term_weeks, initial_olb, None


def _read_report(path: str) -> pd.DataFrame:
    return read_tabular_file(path, dtype=str)


def _format_identifier(value: object) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return ""

    if isinstance(value, (int, np.integer)):
        return str(int(value))

    if isinstance(value, (float, np.floating)):
        if pd.isna(value):
            return ""
        if float(value).is_integer():
            return str(int(value))
        return str(value)

    text = str(value).strip()
    if not text:
        return ""

    if re.fullmatch(r"\d+\.0+", text):
        return text.split(".", 1)[0]
    return text


def _split_client_name(value: object) -> Tuple[str, str]:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return "", ""

    parts = [part for part in re.split(r"\s+", str(value).strip()) if part]
    if not parts:
        return "", ""

    normalized_parts = [part.title() for part in parts]
    first_name = normalized_parts[0]
    other_names = " ".join(normalized_parts[1:])
    return first_name, other_names


def _shorten_product_name(value: object) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return ""

    text = re.sub(r"\s+", " ", str(value).strip())
    if not text:
        return ""

    text = re.sub(r"\bWEEKS?\b", "WKS", text, flags=re.IGNORECASE)
    text = re.sub(r"\bCREDIT\s+EASE\b", "CREDIT", text, flags=re.IGNORECASE)
    text = re.sub(r"\bINSTAL+MENT\b", "INST", text, flags=re.IGNORECASE)
    text = text.title()
    text = text.replace("Wks", "WKS").replace("Olb", "OLB")
    return text


def _format_whole_amount(value: object) -> str:
    number = pd.to_numeric(pd.Series([value]), errors="coerce").iloc[0]
    if pd.isna(number):
        return ""
    return f"{int(round(float(number))):,}"


def _build_presentable_output(
    raw_output: pd.DataFrame,
    client_name_column: Optional[str],
    account_column: Optional[str],
    product_column: Optional[str],
    field_officer_column: Optional[str],
) -> pd.DataFrame:
    presentable = pd.DataFrame(index=raw_output.index)

    first_names = []
    other_names = []
    source_names = (
        raw_output[client_name_column]
        if client_name_column
        else pd.Series([""] * len(raw_output), index=raw_output.index)
    )
    for value in source_names:
        first_name, other_names_value = _split_client_name(value)
        first_names.append(first_name)
        other_names.append(other_names_value)

    presentable["First Name"] = first_names
    presentable["Other Names"] = other_names

    if client_name_column:
        presentable["__client_name"] = raw_output[client_name_column].fillna("").astype(str)

    presentable["Account No"] = (
        raw_output[account_column].apply(_format_identifier)
        if account_column
        else ""
    )
    if field_officer_column:
        presentable["Field Officer"] = raw_output[field_officer_column].fillna("").astype(str)
    else:
        presentable["Field Officer"] = ""

    presentable["Disbursement Date"] = raw_output["Disbursement_Date_Parsed"]
    presentable["Disbursed Amount"] = raw_output["Disbursed_Amount_Parsed"]
    presentable["Initial / Current OLB"] = [
        f"{_format_whole_amount(initial)} / {_format_whole_amount(current)}".strip(" /")
        for initial, current in zip(raw_output["Initial_OLB"], raw_output["Current_OLB_Parsed"])
    ]
    presentable["Expected Repayment Days"] = raw_output["Expected_Repayment_Days"]
    presentable["Current Week"] = raw_output["Current_Week"]
    presentable["Expected Balance"] = raw_output["Expected_Balance"]
    presentable["Missed Days"] = raw_output["Missed_Days"]
    presentable["Required Catch-up Amount"] = raw_output["Required_Catchup_Amount"]
    presentable["__review_note"] = raw_output["Processing_Note"].fillna("").astype(str)
    presentable["__loan_status"] = raw_output["Repayment_Status"].fillna("").astype(str)
    presentable["__sort_date"] = raw_output["Disbursement_Date_Parsed"]
    presentable["__field_officer_sort"] = presentable["Field Officer"].fillna("").astype(str).str.lower()
    presentable["__first_name_sort"] = presentable["First Name"].fillna("").astype(str).str.lower()
    presentable["__other_names_sort"] = presentable["Other Names"].fillna("").astype(str).str.lower()
    presentable["__initial_olb_num"] = pd.to_numeric(raw_output["Initial_OLB"], errors="coerce")
    presentable["__current_olb_num"] = pd.to_numeric(raw_output["Current_OLB_Parsed"], errors="coerce")
    presentable["__expected_days_num"] = pd.to_numeric(raw_output["Expected_Repayment_Days"], errors="coerce").fillna(0)
    presentable["__catchup_num"] = pd.to_numeric(raw_output["Required_Catchup_Amount"], errors="coerce").fillna(0)
    presentable["__no_payments_made"] = (
        (presentable["__expected_days_num"] > 0)
        & (presentable["__current_olb_num"] >= (presentable["__initial_olb_num"] - 0.01))
    )
    if product_column:
        presentable["__product_short"] = raw_output[product_column].apply(_shorten_product_name)

    return presentable


def _current_week_label(expected_repayment_days: int, term_weeks: int) -> str:
    if term_weeks <= 0:
        return ""

    if expected_repayment_days <= 0:
        week_number = 1
    else:
        week_number = ((expected_repayment_days - 1) // REPAYMENT_DAYS_PER_WEEK) + 1

    week_number = min(max(week_number, 1), term_weeks)
    return f"Week {week_number}"


def _repayment_status(
    current_olb: float,
    expected_balance: float,
    required_catchup_amount: float,
) -> str:
    if pd.isna(current_olb):
        return "Data Error"
    if current_olb <= 0.01:
        return "Cleared"
    if required_catchup_amount > 0.01:
        return "In Arrears"
    if (expected_balance - current_olb) > 0.01:
        return "Ahead"
    return "On Track"


def _autosize_sheet(worksheet, dataframe: pd.DataFrame) -> None:
    for idx, column_name in enumerate(dataframe.columns):
        sample_lengths = [len(str(column_name))]
        sample_lengths.extend(len(str(value)) for value in dataframe[column_name].head(50))
        worksheet.set_column(idx, idx, min(max(sample_lengths) + 2, 28))


def _write_output(
    dataframe: pd.DataFrame,
    output_file: str,
    as_of_date: pd.Timestamp,
    stats: dict,
) -> None:
    visible_columns = [
        "First Name",
        "Other Names",
        "Account No",
        "Field Officer",
        "Disbursement Date",
        "Disbursed Amount",
        "Initial / Current OLB",
        "Expected Repayment Days",
        "Current Week",
        "Expected Balance",
        "Missed Days",
        "Required Catch-up Amount",
    ]

    summary_df = pd.DataFrame(
        [
            {"Metric": "As Of Date", "Value": as_of_date.strftime("%d/%m/%Y")},
            {"Metric": "Loans Processed", "Value": stats["loans_processed"]},
            {"Metric": "Loans In Arrears", "Value": stats["loans_in_arrears"]},
            {"Metric": "Total Catch-up Amount", "Value": stats["total_catchup_amount"]},
            {"Metric": "Rows With Errors", "Value": stats["rows_with_errors"]},
            {"Metric": "Average Missed Days", "Value": stats["average_missed_days"]},
        ]
    )

    sorted_df = dataframe.sort_values(
        by=["__sort_date", "__field_officer_sort", "__first_name_sort", "__other_names_sort"],
        ascending=[False, True, True, True],
        na_position="last",
    ).reset_index(drop=True)

    error_df = sorted_df[sorted_df["__review_note"].astype(str).str.strip() != ""].copy()
    if not error_df.empty:
        error_df = error_df[visible_columns + ["__review_note"]].rename(
            columns={"__review_note": "Review Note"}
        )

    with pd.ExcelWriter(
        output_file,
        engine="xlsxwriter",
        datetime_format="dd/mm/yyyy",
    ) as writer:
        summary_df.to_excel(writer, sheet_name="Summary", index=False)
        if not error_df.empty:
            error_df.to_excel(writer, sheet_name="Errors", index=False)

        workbook = writer.book
        title_format = workbook.add_format({
            "bold": True,
            "font_size": 14,
            "align": "center",
            "valign": "vcenter",
            "bg_color": "#203764",
            "font_color": "#FFFFFF",
        })
        as_of_format = workbook.add_format({
            "bold": True,
            "align": "left",
            "valign": "vcenter",
            "bg_color": "#D9E2F3",
            "font_color": "#203764",
        })
        bucket_format = workbook.add_format({
            "bold": True,
            "align": "left",
            "valign": "vcenter",
            "bg_color": "#E2F0D9",
            "font_color": "#1F4E78",
        })
        header_format = workbook.add_format({
            "bold": True,
            "align": "center",
            "valign": "vcenter",
            "bg_color": "#1F4E78",
            "font_color": "#FFFFFF",
            "border": 1,
            "text_wrap": True,
        })
        text_format = workbook.add_format({"border": 1, "align": "left", "valign": "vcenter"})
        center_format = workbook.add_format({"border": 1, "align": "center", "valign": "vcenter"})
        integer_center_format = workbook.add_format({
            "border": 1,
            "align": "center",
            "valign": "vcenter",
            "num_format": "0",
        })
        date_format = workbook.add_format({"border": 1, "align": "center", "num_format": "dd/mm/yyyy"})
        whole_money_format = workbook.add_format({"border": 1, "align": "right", "num_format": "#,##0"})
        missed_days_format = workbook.add_format({"border": 1, "align": "center", "num_format": "0.##"})
        catchup_green_format = workbook.add_format({
            "border": 1,
            "align": "right",
            "num_format": "#,##0",
            "bold": True,
            "bg_color": "#C6E0B4",
            "font_color": "#006100",
        })
        catchup_red_format = workbook.add_format({
            "border": 1,
            "align": "right",
            "num_format": "#,##0",
            "bold": True,
            "bg_color": "#C00000",
            "font_color": "#FFFFFF",
        })
        catchup_amber_format = workbook.add_format({
            "border": 1,
            "align": "right",
            "num_format": "#,##0",
            "bold": True,
            "bg_color": "#F4B183",
            "font_color": "#7F0000",
        })
        plain_date_format = workbook.add_format({"num_format": "dd/mm/yyyy"})
        plain_integer_format = workbook.add_format({"num_format": "0"})

        summary_ws = writer.sheets["Summary"]
        summary_ws.freeze_panes(1, 0)
        _autosize_sheet(summary_ws, summary_df)
        summary_ws.set_column(0, 0, 28)
        summary_ws.set_column(1, 1, 20)

        if not error_df.empty and "Errors" in writer.sheets:
            error_ws = writer.sheets["Errors"]
            error_ws.freeze_panes(1, 0)
            _autosize_sheet(error_ws, error_df)
            error_ws.set_column(4, 4, 14, plain_date_format)
            error_ws.set_column(7, 7, 16, plain_integer_format)

        tracking_ws = workbook.add_worksheet("Loan Tracking")
        writer.sheets["Loan Tracking"] = tracking_ws

        last_col = len(visible_columns) - 1
        tracking_ws.merge_range(0, 0, 0, last_col, "LOAN TRACKER REPORT", title_format)
        tracking_ws.merge_range(
            1,
            0,
            1,
            last_col,
            f"As Of Date: {as_of_date.strftime('%d/%m/%Y')}",
            as_of_format,
        )
        tracking_ws.freeze_panes(2, 0)

        column_widths = {
            "First Name": 16,
            "Other Names": 24,
            "Account No": 16,
            "Field Officer": 20,
            "Disbursement Date": 14,
            "Disbursed Amount": 16,
            "Initial / Current OLB": 20,
            "Expected Repayment Days": 16,
            "Current Week": 12,
            "Expected Balance": 16,
            "Missed Days": 12,
            "Required Catch-up Amount": 18,
        }

        for column_index, column_name in enumerate(visible_columns):
            tracking_ws.set_column(
                column_index,
                column_index,
                column_widths.get(column_name, max(len(column_name) + 2, 14)),
            )

        current_row = 2
        grouped_dates = list(sorted_df.groupby("__sort_date", dropna=False, sort=False))
        for group_index, (disbursement_date, group) in enumerate(grouped_dates):
            if group_index > 0:
                current_row += 1

            date_label = (
                pd.Timestamp(disbursement_date).strftime("%d/%m/%Y")
                if not pd.isna(disbursement_date)
                else "Unknown Date"
            )
            tracking_ws.merge_range(
                current_row,
                0,
                current_row,
                last_col,
                f"Disbursement Date: {date_label}",
                bucket_format,
            )
            current_row += 1

            for column_index, column_name in enumerate(visible_columns):
                tracking_ws.write(current_row, column_index, column_name, header_format)
            current_row += 1

            for _, row in group.iterrows():
                for column_index, column_name in enumerate(visible_columns):
                    value = row[column_name]

                    if column_name == "Disbursement Date":
                        if pd.isna(value):
                            tracking_ws.write_blank(current_row, column_index, None, center_format)
                        else:
                            tracking_ws.write_datetime(current_row, column_index, pd.Timestamp(value), date_format)
                    elif column_name == "Expected Repayment Days":
                        if pd.isna(value):
                            tracking_ws.write_blank(current_row, column_index, None, integer_center_format)
                        else:
                            tracking_ws.write_number(
                                current_row,
                                column_index,
                                float(value),
                                integer_center_format,
                            )
                    elif column_name in {"Disbursed Amount", "Expected Balance"}:
                        if pd.isna(value):
                            tracking_ws.write_blank(current_row, column_index, None, whole_money_format)
                        else:
                            tracking_ws.write_number(current_row, column_index, float(value), whole_money_format)
                    elif column_name == "Missed Days":
                        if pd.isna(value):
                            tracking_ws.write_blank(current_row, column_index, None, missed_days_format)
                        else:
                            tracking_ws.write_number(current_row, column_index, float(value), missed_days_format)
                    elif column_name == "Required Catch-up Amount":
                        catchup_value = row["__catchup_num"]
                        status = str(row["__loan_status"]).strip().lower()
                        if status in {"ahead", "on track", "cleared"}:
                            catchup_format = catchup_green_format
                        elif bool(row["__no_payments_made"]):
                            catchup_format = catchup_red_format
                        else:
                            catchup_format = catchup_amber_format

                        tracking_ws.write_number(current_row, column_index, float(catchup_value), catchup_format)
                    elif column_name == "Current Week":
                        if pd.isna(value):
                            tracking_ws.write_blank(current_row, column_index, None, center_format)
                        else:
                            tracking_ws.write(current_row, column_index, value, center_format)
                    else:
                        if pd.isna(value):
                            tracking_ws.write_blank(current_row, column_index, None, text_format)
                        else:
                            tracking_ws.write(current_row, column_index, value, text_format)

                current_row += 1


def run(
    report_path: str,
    output_dir: str,
    as_of_date: Optional[object] = None,
) -> dict:
    """Generate a loan tracking report from the disbursement file and built-in logic."""
    os.makedirs(output_dir, exist_ok=True)

    report_df = _read_report(report_path)

    report_df = report_df.copy()
    report_df.columns = [str(column).strip() for column in report_df.columns]

    disbursed_amount_column = _find_column(report_df.columns, DISBURSED_AMOUNT_ALIASES)
    disbursement_date_column = _find_column(report_df.columns, DISBURSEMENT_DATE_ALIASES)
    current_olb_column = _find_column(report_df.columns, CURRENT_OLB_ALIASES)
    client_name_column = _find_column(report_df.columns, CLIENT_NAME_ALIASES)
    account_column = _find_column(report_df.columns, ACCOUNT_ALIASES)
    field_officer_column = _find_column(report_df.columns, FIELD_OFFICER_ALIASES)
    product_column = _find_column(report_df.columns, PRODUCT_ALIASES)
    clear_date_column = _find_column(report_df.columns, CLEAR_DATE_ALIASES)

    missing_columns = []
    if disbursed_amount_column is None:
        missing_columns.append("Disbursed Amount")
    if disbursement_date_column is None:
        missing_columns.append("Disbursement Date")
    if current_olb_column is None:
        missing_columns.append("Current OLB")
    if missing_columns:
        raise ValueError(
            "Disbursement report is missing required columns: " + ", ".join(missing_columns)
        )

    if as_of_date is None:
        as_of_timestamp = pd.Timestamp.now().normalize()
    else:
        as_of_timestamp = _parse_date(as_of_date)
        if pd.isna(as_of_timestamp):
            raise ValueError("Invalid as-of date supplied to loan tracker processor.")

    output = report_df.copy()

    derived_columns = [
        "As_Of_Date",
        "Disbursed_Amount_Parsed",
        "Current_OLB_Parsed",
        "Disbursement_Date_Parsed",
        "Term_Weeks",
        "Initial_OLB",
        "Daily_Installment",
        "Expected_Repayment_Days",
        "Current_Week",
        "Expected_Balance",
        "Missed_Days",
        "Required_Catchup_Amount",
        "Amount_to_be_Up_to_Date",
        "Repayment_Status",
        "Processing_Status",
        "Processing_Note",
    ]
    for column_name in derived_columns:
        output[column_name] = pd.NA

    for row_index, row in output.iterrows():
        notes = []

        disbursed_amount = _parse_amount(row.get(disbursed_amount_column))
        current_olb = _parse_amount(row.get(current_olb_column))
        disbursement_date = _parse_date(row.get(disbursement_date_column))
        clear_date = _parse_date(row.get(clear_date_column)) if clear_date_column else pd.NaT

        term_weeks = None
        if product_column:
            term_weeks = _extract_term_weeks(row.get(product_column))
        if term_weeks is None:
            term_weeks = _infer_term_weeks_from_dates(disbursement_date, clear_date)

        output.at[row_index, "As_Of_Date"] = as_of_timestamp
        output.at[row_index, "Disbursed_Amount_Parsed"] = (
            round(float(disbursed_amount), 2) if not pd.isna(disbursed_amount) else pd.NA
        )
        output.at[row_index, "Current_OLB_Parsed"] = (
            round(float(current_olb), 2) if not pd.isna(current_olb) else pd.NA
        )
        output.at[row_index, "Disbursement_Date_Parsed"] = (
            disbursement_date if not pd.isna(disbursement_date) else pd.NaT
        )
        output.at[row_index, "Term_Weeks"] = term_weeks if term_weeks is not None else pd.NA

        if pd.isna(disbursed_amount):
            notes.append("Invalid disbursed amount.")
        if pd.isna(current_olb):
            notes.append("Invalid current OLB.")
        if pd.isna(disbursement_date):
            notes.append("Invalid disbursement date.")
        if term_weeks is None:
            notes.append("Could not determine loan term from Product/Clear Date.")

        initial_olb = None
        if not notes:
            resolved_weeks, initial_olb, logic_error = _resolve_product_logic(
                disbursed_amount,
                term_weeks,
            )
            if logic_error:
                notes.append(logic_error)
            else:
                term_weeks = resolved_weeks
                output.at[row_index, "Term_Weeks"] = term_weeks

        if notes or initial_olb is None or term_weeks is None:
            output.at[row_index, "Processing_Status"] = "Error"
            output.at[row_index, "Processing_Note"] = " ".join(notes).strip()
            output.at[row_index, "Repayment_Status"] = "Logic Error"
            continue

        total_term_days = term_weeks * REPAYMENT_DAYS_PER_WEEK
        expected_repayment_days = min(
            _count_repayment_days(disbursement_date, as_of_timestamp),
            total_term_days,
        )
        daily_installment = round(float(initial_olb) / total_term_days, 2)
        expected_balance = round(
            max(float(initial_olb) - (expected_repayment_days * daily_installment), 0.0),
            2,
        )
        required_catchup_amount = round(max(float(current_olb) - expected_balance, 0.0), 2)
        missed_days = round(
            required_catchup_amount / daily_installment if daily_installment > 0 else 0.0,
            2,
        )

        output.at[row_index, "Initial_OLB"] = round(float(initial_olb), 2)
        output.at[row_index, "Daily_Installment"] = daily_installment
        output.at[row_index, "Expected_Repayment_Days"] = expected_repayment_days
        output.at[row_index, "Current_Week"] = _current_week_label(
            expected_repayment_days,
            term_weeks,
        )
        output.at[row_index, "Expected_Balance"] = expected_balance
        output.at[row_index, "Missed_Days"] = missed_days
        output.at[row_index, "Required_Catchup_Amount"] = required_catchup_amount
        output.at[row_index, "Amount_to_be_Up_to_Date"] = required_catchup_amount
        output.at[row_index, "Repayment_Status"] = _repayment_status(
            float(current_olb),
            expected_balance,
            required_catchup_amount,
        )
        output.at[row_index, "Processing_Status"] = "Processed"
        output.at[row_index, "Processing_Note"] = ""

    filename = os.path.splitext(os.path.basename(report_path))[0]
    timestamp = pd.Timestamp.now().strftime("%Y-%m-%d_%H-%M")
    output_file = os.path.join(output_dir, f"LOAN_TRACKER_{filename}_{timestamp}.xlsx")

    numeric_catchup = pd.to_numeric(output["Required_Catchup_Amount"], errors="coerce").fillna(0)
    numeric_missed_days = pd.to_numeric(output["Missed_Days"], errors="coerce").fillna(0)
    processed_mask = output["Processing_Status"] == "Processed"
    error_mask = output["Processing_Status"] == "Error"

    stats = {
        "as_of_date": as_of_timestamp.strftime("%Y-%m-%d"),
        "loans_processed": int(processed_mask.sum()),
        "loans_in_arrears": int((numeric_catchup > 0).sum()),
        "total_catchup_amount": round(float(numeric_catchup.sum()), 2),
        "rows_with_errors": int(error_mask.sum()),
        "average_missed_days": round(
            float(numeric_missed_days[processed_mask].mean()) if processed_mask.any() else 0.0,
            2,
        ),
    }

    presentable_output = _build_presentable_output(
        output,
        client_name_column,
        account_column,
        product_column,
        field_officer_column,
    )

    _write_output(presentable_output, output_file, as_of_timestamp, stats)

    return {
        "success": True,
        "output_file": output_file,
        "rows_processed": int(len(output)),
        "stats": stats,
    }
