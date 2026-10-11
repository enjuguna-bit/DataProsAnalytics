"""
Disbursement processor for uploaded CSV/XLSX files.

The output Excel report adds:
- split name columns
- normalized Kenyan phone numbers
- weekly installment (OLB / product duration in weeks)
- daily installment (weekly installment / 6)
"""

import os
import re
from typing import Any, Dict, Optional, Tuple

import pandas as pd
from openpyxl.utils import get_column_letter

from processors.tabular_io import read_tabular_file


NAME_COLUMNS = ["First Name", "2nd Name", "3rd Name"]
PHONE_OUTPUT_COLUMN = "Normalized Phone Number"
INSTALLMENT_COLUMNS = ["Weekly Installment", "Daily Installment"]


def _parse_amount(value: Any) -> float:
    """Convert Ksh-formatted text or numeric values to a float."""
    if pd.isna(value):
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)

    cleaned = re.sub(r"[^0-9.\-]", "", str(value))
    if cleaned in {"", "-", ".", "-."}:
        return 0.0

    try:
        return float(cleaned)
    except ValueError:
        return 0.0


def _extract_weeks(product: Any) -> int:
    """Extract the product duration in weeks, defaulting to 5 when unknown."""
    if pd.isna(product):
        return 5

    match = re.search(r"(\d+)\s*week", str(product), re.IGNORECASE)
    if not match:
        return 5

    weeks = int(match.group(1))
    return weeks if weeks > 0 else 5


def _normalize_phone(phone: Any) -> str:
    """
    Normalize Kenyan mobile numbers to 254XXXXXXXXX.

    Accepted shapes include:
    - 07XXXXXXXX
    - 01XXXXXXXX
    - 7XXXXXXXX
    - 1XXXXXXXX
    - 2547XXXXXXX
    - 2541XXXXXXX
    - +2547XXXXXXX
    """
    if pd.isna(phone):
        return ""

    digits = re.sub(r"\D", "", str(phone).strip())
    if not digits:
        return ""

    if digits.startswith("2540") and len(digits) == 13:
        digits = "254" + digits[4:]
    elif digits.startswith("0") and len(digits) == 10:
        digits = "254" + digits[1:]
    elif len(digits) == 9 and digits[:1] in {"7", "1"}:
        digits = "254" + digits

    if re.fullmatch(r"254(?:7|1)\d{8}", digits):
        return digits
    return ""


def _split_names(full_name: Any) -> Tuple[str, str, str]:
    """Split a full name into first, second, and third-name buckets."""
    if pd.isna(full_name):
        return "", "", ""

    cleaned = re.sub(r"\s+", " ", str(full_name).strip())
    if not cleaned:
        return "", "", ""

    parts = [part.title() for part in cleaned.split(" ") if part]
    first = parts[0] if len(parts) >= 1 else ""
    second = parts[1] if len(parts) >= 2 else ""
    third = " ".join(parts[2:]) if len(parts) >= 3 else ""
    return first, second, third


def _normalize_column_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def _find_column(
    df: pd.DataFrame,
    exact_candidates: Tuple[str, ...],
    contains_tokens: Tuple[str, ...] = (),
) -> Optional[str]:
    normalized = {_normalize_column_name(col): col for col in df.columns}

    for candidate in exact_candidates:
        match = normalized.get(_normalize_column_name(candidate))
        if match:
            return match

    if contains_tokens:
        for col in df.columns:
            normalized_col = _normalize_column_name(col)
            if any(token in normalized_col for token in contains_tokens):
                return col

    return None


def _looks_like_phone_series(series: pd.Series) -> bool:
    sample = series.dropna().astype(str).head(25)
    if sample.empty:
        return False

    normalized = sample.map(_normalize_phone)
    return (normalized != "").mean() >= 0.6


def _find_phone_column(df: pd.DataFrame) -> Optional[str]:
    phone_col = _find_column(
        df,
        exact_candidates=("Phone", "Phone Number", "Mobile", "Mobile Number", "Tel", "Telephone", "MSISDN"),
        contains_tokens=("phone", "mobile", "tel", "msisdn"),
    )
    if phone_col and _looks_like_phone_series(df[phone_col]):
        return phone_col

    for candidate in ("AccountNo", "Account Number", "Account"):
        account_col = _find_column(df, exact_candidates=(candidate,))
        if account_col and _looks_like_phone_series(df[account_col]):
            return account_col

    for col in df.columns:
        if _looks_like_phone_series(df[col]):
            return col

    return None


def _load_frame(input_path: str) -> pd.DataFrame:
    return read_tabular_file(input_path, dtype=str)


def _build_column_order(
    original_columns: Tuple[str, ...],
    name_col: str,
    phone_col: Optional[str],
    product_col: str,
) -> list[str]:
    ordered: list[str] = []

    for col in original_columns:
        ordered.append(col)
        if col == name_col:
            ordered.extend(NAME_COLUMNS)
        if phone_col and col == phone_col:
            ordered.append(PHONE_OUTPUT_COLUMN)
        if col == product_col:
            ordered.extend(INSTALLMENT_COLUMNS)

    for col in NAME_COLUMNS + [PHONE_OUTPUT_COLUMN] + INSTALLMENT_COLUMNS:
        if col not in ordered:
            ordered.append(col)

    return ordered


def _format_worksheet(worksheet, df: pd.DataFrame) -> None:
    worksheet.freeze_panes = "A2"
    worksheet.auto_filter.ref = worksheet.dimensions

    header_positions = {column: index for index, column in enumerate(df.columns, start=1)}

    for column_name in INSTALLMENT_COLUMNS:
        column_index = header_positions.get(column_name)
        if not column_index:
            continue
        column_letter = get_column_letter(column_index)
        for cell in worksheet[column_letter][1:]:
            cell.number_format = "#,##0.00"

    phone_index = header_positions.get(PHONE_OUTPUT_COLUMN)
    if phone_index:
        phone_letter = get_column_letter(phone_index)
        for cell in worksheet[phone_letter][1:]:
            cell.number_format = "@"

    for index, column in enumerate(df.columns, start=1):
        values = [column] + df[column].fillna("").astype(str).tolist()
        max_length = max(len(value) for value in values) + 2
        worksheet.column_dimensions[get_column_letter(index)].width = min(max_length, 32)


def run(input_path: str, output_dir: str) -> Dict[str, Any]:
    try:
        if not os.path.isfile(input_path):
            return {"success": False, "error": f"Input file not found: {input_path}"}

        os.makedirs(output_dir, exist_ok=True)

        df = _load_frame(input_path)
        if df.empty:
            return {"success": False, "error": "The uploaded file is empty."}

        name_col = _find_column(
            df,
            exact_candidates=("FullNames", "Full Name", "Customer Name", "Name"),
            contains_tokens=("fullname", "customername", "borrowername"),
        )
        olb_col = _find_column(df, exact_candidates=("OLB", "Outstanding Loan Balance"))
        product_col = _find_column(df, exact_candidates=("Product", "Loan Type", "Loantype"))
        phone_col = _find_phone_column(df)

        missing = []
        if not name_col:
            missing.append("name column")
        if not olb_col:
            missing.append("OLB column")
        if not product_col:
            missing.append("product column")
        if missing:
            return {
                "success": False,
                "error": "Missing required columns: " + ", ".join(missing),
            }

        output = df.copy()

        split_names = output[name_col].apply(_split_names)
        output["First Name"] = split_names.str[0]
        output["2nd Name"] = split_names.str[1]
        output["3rd Name"] = split_names.str[2]

        if phone_col:
            output[PHONE_OUTPUT_COLUMN] = output[phone_col].apply(_normalize_phone)
        else:
            output[PHONE_OUTPUT_COLUMN] = ""

        olb_amounts = output[olb_col].apply(_parse_amount)
        product_weeks = output[product_col].apply(_extract_weeks).replace(0, 5)

        output["Weekly Installment"] = (olb_amounts / product_weeks).round(2)
        output["Daily Installment"] = (output["Weekly Installment"] / 6).round(2)

        ordered_columns = _build_column_order(tuple(df.columns), name_col, phone_col, product_col)
        output = output[ordered_columns]

        input_name = os.path.splitext(os.path.basename(input_path))[0]
        output_path = os.path.join(output_dir, f"{input_name}_processed.xlsx")

        with pd.ExcelWriter(output_path, engine="openpyxl") as writer:
            output.to_excel(writer, index=False, sheet_name="Disbursement")
            _format_worksheet(writer.sheets["Disbursement"], output)

        return {
            "success": True,
            "output_file": output_path,
            "rows_processed": int(len(output)),
            "columns_added": NAME_COLUMNS + [PHONE_OUTPUT_COLUMN] + INSTALLMENT_COLUMNS,
            "stats": {
                "total_loans": int(len(output)),
                "total_olb": round(float(olb_amounts.sum()), 2),
                "total_weekly_installment": round(float(output["Weekly Installment"].sum()), 2),
                "total_daily_installment": round(float(output["Daily Installment"].sum()), 2),
            },
        }
    except Exception as exc:
        return {"success": False, "error": str(exc)}
