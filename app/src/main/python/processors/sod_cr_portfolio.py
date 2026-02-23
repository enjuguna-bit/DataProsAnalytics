"""
SOD CR Portfolio Processor
Converts raw SOD CR portfolio data into a processed Excel workbook.
"""

from __future__ import annotations

import os
import re
from datetime import datetime
from typing import Dict, List

import matplotlib
import pandas as pd
from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

matplotlib.use("Agg")
import matplotlib.pyplot as plt


KNOWN_OFFICER_IDS: Dict[str, int] = {
    "ESTHER MUMBI": 92,
    "BRIAN WANJALA": 66,
    "JOASH MUTISYA": 144,
    "ALEXANDER MWANGI": 30,
    "BENSON KIRUI": 84,
}


def _read_input(path: str) -> pd.DataFrame:
    if path.lower().endswith(".csv"):
        last_error = None
        for enc in ("utf-8-sig", "utf-8", "latin1", "cp1252", "iso-8859-1"):
            try:
                return pd.read_csv(path, dtype=str, encoding=enc)
            except Exception as exc:
                last_error = exc
        raise ValueError(f"Unable to read CSV file: {last_error}")
    if path.lower().endswith(".xlsx") or path.lower().endswith(".xls"):
        return pd.read_excel(path, dtype=str)
    raise ValueError("Unsupported file type. Please upload CSV or Excel data.")


def _normalize_header(name: str) -> str:
    clean = str(name or "").strip().lower()
    clean = clean.replace("%", "pct")
    clean = re.sub(r"[^a-z0-9]+", "", clean)
    return clean


def _to_number(series: pd.Series) -> pd.Series:
    return (
        series.fillna("")
        .astype(str)
        .str.replace(",", "", regex=False)
        .str.replace(r"[^\d\.\-]", "", regex=True)
        .replace("", "0")
        .pipe(pd.to_numeric, errors="coerce")
        .fillna(0.0)
    )


def _pick_column(columns: List[str], candidates: List[str]) -> str:
    lookup = {_normalize_header(c): c for c in columns}
    for candidate in candidates:
        key = _normalize_header(candidate)
        if key in lookup:
            return lookup[key]
    return ""


def _prepare_dataframe(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        raise ValueError("Input file is empty")

    df = df.copy()
    df.columns = [str(c).strip() for c in df.columns]
    cols = list(df.columns)

    id_col = _pick_column(cols, ["#ID", "ID", "OfficerID"])
    name_col = _pick_column(cols, ["Full Name", "FullName", "Name", "SalesRep", "Officer"])
    total_col = _pick_column(cols, ["Total Amount", "TotalAmount", "AmountDue", "Amount Due"])
    paid_col = _pick_column(cols, ["Paid Amount", "PaidAmount", "AmountPaid", "Amount Paid"])
    outstanding_col = _pick_column(
        cols, ["Outstanding Amount", "OutstandingAmount", "Outstanding", "Arrears", "Balance"]
    )
    cr_col = _pick_column(cols, ["Collection Rate (%)", "CollectionRate", "CR%", "CR"])
    branch_col = _pick_column(cols, ["Branch Name", "Branch", "Office"])

    if not name_col or not total_col or not paid_col:
        raise ValueError(
            "Missing required columns. Need at least: Full Name, Total Amount, Paid Amount."
        )

    out = pd.DataFrame()

    names = df[name_col].fillna("").astype(str).str.strip()
    out["Full Name"] = names.str.replace(r"\s+", " ", regex=True).str.upper()
    out = out[out["Full Name"] != ""].copy()
    if out.empty:
        raise ValueError("No valid rows found after cleaning Full Name")

    base = df.loc[out.index]
    total_amount = _to_number(base[total_col]).round(2)
    paid_amount = _to_number(base[paid_col]).round(2)

    if outstanding_col:
        outstanding_amount = _to_number(base[outstanding_col]).round(2)
    else:
        outstanding_amount = (total_amount - paid_amount).clip(lower=0).round(2)

    if cr_col:
        collection_rate = _to_number(base[cr_col]).clip(lower=0, upper=100).round(2)
    else:
        collection_rate = (
            (paid_amount / total_amount.replace(0, pd.NA)) * 100
        ).fillna(0).clip(lower=0, upper=100).round(2)

    target_to_80 = (total_amount * 0.8 - paid_amount).clip(lower=0).round(0)

    if id_col:
        ids = _to_number(base[id_col]).astype(int)
    else:
        ids = pd.Series([0] * len(base), index=base.index, dtype=int)

    resolved_ids: List[int] = []
    next_fallback = 1
    for idx, name in out["Full Name"].items():
        existing_id = int(ids.loc[idx]) if idx in ids.index else 0
        if existing_id > 0:
            resolved_ids.append(existing_id)
            continue
        if name in KNOWN_OFFICER_IDS:
            resolved_ids.append(KNOWN_OFFICER_IDS[name])
            continue
        resolved_ids.append(next_fallback)
        next_fallback += 1

    if branch_col:
        branches = base[branch_col].fillna("").astype(str).str.strip()
        branches = branches.reindex(out.index).replace("", "MOLO")
    else:
        branches = pd.Series(["MOLO"] * len(out), index=out.index)

    out.insert(0, "#ID", resolved_ids)
    out["Total Amount"] = total_amount.reindex(out.index)
    out["Paid Amount"] = paid_amount.reindex(out.index)
    out["Outstanding Amount"] = outstanding_amount.reindex(out.index)
    out["Collection Rate (%)"] = collection_rate.reindex(out.index)
    out["Target To 80%"] = target_to_80.reindex(out.index).astype(int)
    out["Branch Name"] = branches.reindex(out.index)
    out["Status"] = out["Target To 80%"].apply(lambda v: "At/Above 80%" if float(v) <= 0 else "Gap to 80%")

    out = out.sort_values(by=["Collection Rate (%)", "Outstanding Amount"], ascending=[True, False])
    out = out.reset_index(drop=True)
    return out


def _build_preview_chart(df: pd.DataFrame, chart_path: str) -> str:
    try:
        chart_df = df.copy()
        chart_df = chart_df.sort_values("Collection Rate (%)", ascending=True)

        fig, ax = plt.subplots(figsize=(8.5, 5))
        colors = ["#2E7D32" if v >= 80 else "#C62828" for v in chart_df["Collection Rate (%)"]]
        ax.barh(chart_df["Full Name"], chart_df["Collection Rate (%)"], color=colors)
        ax.axvline(x=80, color="#1565C0", linestyle="--", linewidth=1.5)
        ax.set_xlim(0, 100)
        ax.set_xlabel("Collection Rate (%)")
        ax.set_title("SOD CR Per Portfolio - Collection Rate", fontweight="bold")
        fig.tight_layout()
        fig.savefig(chart_path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        return chart_path
    except Exception:
        plt.close("all")
        return ""


def _write_workbook(df: pd.DataFrame, output_file: str, chart_path: str):
    wb = Workbook()
    ws = wb.active
    ws.title = "Processed Data"

    thin = Side(style="thin", color="D0D0D0")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    hdr_fill = PatternFill("solid", fgColor="0D47A1")
    hdr_font = Font(bold=True, color="FFFFFF")
    center = Alignment(horizontal="center", vertical="center")
    right = Alignment(horizontal="right", vertical="center")
    left = Alignment(horizontal="left", vertical="center")

    headers = list(df.columns)
    for col_idx, header in enumerate(headers, start=1):
        cell = ws.cell(row=1, column=col_idx, value=header)
        cell.fill = hdr_fill
        cell.font = hdr_font
        cell.alignment = center
        cell.border = border

    for row_idx, row in enumerate(df.itertuples(index=False), start=2):
        status_value = str(getattr(row, "Status"))
        row_fill = PatternFill(
            "solid",
            fgColor="E8F5E9" if status_value == "At/Above 80%" else "FFF3E0",
        )
        for col_idx, value in enumerate(row, start=1):
            cell = ws.cell(row=row_idx, column=col_idx, value=value)
            cell.border = border
            cell.fill = row_fill

            header = headers[col_idx - 1]
            if header in ("Total Amount", "Paid Amount", "Outstanding Amount", "Target To 80%"):
                cell.number_format = "#,##0"
                cell.alignment = right
            elif header == "Collection Rate (%)":
                cell.number_format = '0.0"%"'
                cell.alignment = right
            elif header == "#ID":
                cell.alignment = center
            else:
                cell.alignment = left

    for col_idx in range(1, ws.max_column + 1):
        max_len = max(
            (len(str(ws.cell(row=r, column=col_idx).value or "")) for r in range(1, ws.max_row + 1)),
            default=10,
        )
        ws.column_dimensions[get_column_letter(col_idx)].width = min(max_len + 3, 42)

    ws.freeze_panes = "A2"
    ws.auto_filter.ref = ws.dimensions

    summary = wb.create_sheet("Summary")
    summary_rows = [
        ("Metric", "Value"),
        ("Total Portfolios", int(len(df))),
        ("Total Amount", float(df["Total Amount"].sum())),
        ("Total Paid", float(df["Paid Amount"].sum())),
        ("Total Outstanding", float(df["Outstanding Amount"].sum())),
        ("Average Collection Rate (%)", float(df["Collection Rate (%)"].mean())),
        ("Total Target To 80%", float(df["Target To 80%"].sum())),
        ("Count Below 80%", int((df["Collection Rate (%)"] < 80).sum())),
    ]

    for row_idx, (metric, value) in enumerate(summary_rows, start=1):
        summary.cell(row=row_idx, column=1, value=metric)
        summary.cell(row=row_idx, column=2, value=value)

        left_cell = summary.cell(row=row_idx, column=1)
        right_cell = summary.cell(row=row_idx, column=2)
        left_cell.border = border
        right_cell.border = border

        if row_idx == 1:
            left_cell.fill = hdr_fill
            right_cell.fill = hdr_fill
            left_cell.font = hdr_font
            right_cell.font = hdr_font
            left_cell.alignment = center
            right_cell.alignment = center
            continue

        left_cell.font = Font(bold=True)
        left_cell.alignment = left
        right_cell.alignment = right
        if isinstance(value, (int, float)):
            if "Rate" in metric:
                right_cell.number_format = '0.0"%"'
            elif "Count" in metric or "Portfolios" in metric:
                right_cell.number_format = "#,##0"
            else:
                right_cell.number_format = "#,##0"

    summary.column_dimensions["A"].width = 34
    summary.column_dimensions["B"].width = 22

    if chart_path and os.path.exists(chart_path):
        from openpyxl.drawing.image import Image as XLImage

        chart_ws = wb.create_sheet("Chart")
        chart_ws.add_image(XLImage(chart_path), "A1")

    wb.save(output_file)


def run(input_path: str, output_dir: str) -> dict:
    os.makedirs(output_dir, exist_ok=True)
    df = _read_input(input_path)
    processed = _prepare_dataframe(df)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_file = os.path.join(output_dir, f"SOD_CR_Per_Portfolio_{timestamp}.xlsx")
    chart_file = _build_preview_chart(processed, os.path.join(output_dir, "sod_cr_portfolio_chart.png"))
    _write_workbook(processed, output_file, chart_file)

    total_amount = float(processed["Total Amount"].sum())
    total_paid = float(processed["Paid Amount"].sum())
    total_outstanding = float(processed["Outstanding Amount"].sum())
    avg_rate = float(processed["Collection Rate (%)"].mean()) if len(processed) else 0.0
    total_target = float(processed["Target To 80%"].sum())

    return {
        "success": True,
        "output_file": output_file,
        "chart_file": chart_file,
        "stats": {
            "total_portfolios": int(len(processed)),
            "total_amount": round(total_amount, 2),
            "total_paid": round(total_paid, 2),
            "total_outstanding": round(total_outstanding, 2),
            "avg_collection_rate": round(avg_rate, 2),
            "total_target_to_80": round(total_target, 2),
            "below_80_count": int((processed["Collection Rate (%)"] < 80).sum()),
        },
    }
