"""
SOD CR Portfolio Processor
Combines an officer-level SOD CR report with account-level loans-due data.
"""

from __future__ import annotations

import os
import re
from datetime import datetime
from typing import Dict, List

import matplotlib
import pandas as pd
from openpyxl import Workbook
from openpyxl.drawing.image import Image as XLImage
from openpyxl.formatting.rule import FormulaRule
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
    file_ext = os.path.splitext(path)[1].lower()
    csv_encodings = ("utf-8-sig", "utf-8", "latin1", "cp1252", "iso-8859-1")
    read_order = ("csv", "excel") if file_ext == ".csv" else ("excel", "csv")
    last_error: Exception | None = None
    for reader in read_order:
        if reader == "excel":
            try:
                return pd.read_excel(path, dtype=str)
            except Exception as exc:
                last_error = exc
        else:
            for enc in csv_encodings:
                try:
                    return pd.read_csv(path, dtype=str, encoding=enc)
                except UnicodeDecodeError as exc:
                    last_error = exc
                except Exception as exc:
                    last_error = exc
                    break
    raise ValueError(f"Unable to read input file as CSV or Excel: {last_error}")


def _normalize_header(name: str) -> str:
    clean = str(name or "").strip().lower().replace("%", "pct")
    return re.sub(r"[^a-z0-9]+", "", clean)


def _to_number(series: pd.Series) -> pd.Series:
    return (
        series.fillna("").astype(str).str.replace(",", "", regex=False)
        .str.replace(r"[^\d\.\-]", "", regex=True).replace("", "0")
        .pipe(pd.to_numeric, errors="coerce").fillna(0.0)
    )


def _pick_column(columns: List[str], candidates: List[str]) -> str:
    lookup = {_normalize_header(c): c for c in columns}
    for candidate in candidates:
        if _normalize_header(candidate) in lookup:
            return lookup[_normalize_header(candidate)]
    return ""


def _clean_name(series: pd.Series) -> pd.Series:
    return series.fillna("").astype(str).str.strip().str.replace(r"\s+", " ", regex=True).str.upper()


def _prepare_dataframe(df: pd.DataFrame) -> pd.DataFrame:
    """Normalize File 1; it remains authoritative for officer-level figures."""
    if df.empty:
        raise ValueError("Officer-level input file is empty")
    df = df.copy()
    df.columns = [str(c).strip() for c in df.columns]
    cols = list(df.columns)
    id_col = _pick_column(cols, ["#ID", "ID", "OfficerID"])
    name_col = _pick_column(cols, ["Full Name", "FullName", "Name", "SalesRep", "Officer"])
    total_col = _pick_column(cols, ["Total Amount", "TotalAmount", "AmountDue", "Amount Due"])
    paid_col = _pick_column(cols, ["Paid Amount", "PaidAmount", "AmountPaid", "Amount Paid"])
    outstanding_col = _pick_column(cols, ["Outstanding Amount", "OutstandingAmount", "Outstanding", "Arrears", "Balance"])
    cr_col = _pick_column(cols, ["Collection Rate (%)", "CollectionRate", "CR%", "CR"])
    branch_col = _pick_column(cols, ["Branch Name", "Branch", "Office"])
    if not name_col or not total_col or not paid_col:
        raise ValueError("Officer-level file needs Full Name, Total Amount and Paid Amount columns")

    out = pd.DataFrame(index=df.index)
    out["Full Name"] = _clean_name(df[name_col])
    out = out[out["Full Name"] != ""].copy()
    if out.empty:
        raise ValueError("No valid officer rows found after cleaning Full Name")
    base = df.loc[out.index]
    total_amount = _to_number(base[total_col]).round(2)
    paid_amount = _to_number(base[paid_col]).round(2)
    outstanding_amount = _to_number(base[outstanding_col]).round(2) if outstanding_col else (total_amount - paid_amount).clip(lower=0).round(2)
    collection_rate = _to_number(base[cr_col]).clip(lower=0, upper=100).round(2) if cr_col else ((paid_amount / total_amount.replace(0, pd.NA)) * 100).fillna(0).clip(lower=0, upper=100).round(2)
    target_to_80 = (total_amount * 0.8 - paid_amount).clip(lower=0).round(0)

    ids = _to_number(base[id_col]).astype(int) if id_col else pd.Series([0] * len(base), index=base.index, dtype=int)
    resolved_ids: List[int] = []
    next_fallback = 1
    for idx, name in out["Full Name"].items():
        existing_id = int(ids.loc[idx]) if idx in ids.index else 0
        if existing_id > 0:
            resolved_ids.append(existing_id)
        elif name in KNOWN_OFFICER_IDS:
            resolved_ids.append(KNOWN_OFFICER_IDS[name])
        else:
            resolved_ids.append(next_fallback)
            next_fallback += 1
    branches = base[branch_col].fillna("").astype(str).str.strip().reindex(out.index).replace("", "MOLO") if branch_col else pd.Series(["MOLO"] * len(out), index=out.index)

    out.insert(0, "#ID", resolved_ids)
    out["Total Amount"] = total_amount.reindex(out.index)
    out["Paid Amount"] = paid_amount.reindex(out.index)
    out["Outstanding Amount"] = outstanding_amount.reindex(out.index)
    out["Collection Rate (%)"] = collection_rate.reindex(out.index)
    out["Target To 80%"] = target_to_80.reindex(out.index).astype(int)
    out["Branch Name"] = branches.reindex(out.index)
    out["Status"] = out["Target To 80%"].apply(lambda v: "At/Above 80%" if float(v) <= 0 else "Gap to 80%")
    return out.sort_values(by=["Collection Rate (%)", "Outstanding Amount"], ascending=[False, False]).reset_index(drop=True)


def _prepare_detail_dataframe(df: pd.DataFrame) -> pd.DataFrame:
    """Normalize File 2 account by account and mark Arrears == 0 as clean."""
    if df.empty:
        raise ValueError("Detailed loans-due input file is empty")
    df = df.copy()
    df.columns = [str(c).strip() for c in df.columns]
    cols = list(df.columns)
    client_col = _pick_column(cols, ["Client Name", "FullNames", "Full Name", "CustomerName", "Customer Name"])
    officer_col = _pick_column(cols, ["Officer", "FieldOfficer", "Field Officer", "SalesRep"])
    due_col = _pick_column(cols, ["Amount Due", "AmountDue", "Due Amount"])
    paid_col = _pick_column(cols, ["Amount Paid", "AmountPaid", "Paid Amount", "Paid"])
    arrears_col = _pick_column(cols, ["Arrears", "Arrears Amount", "ArrearsAmount"])
    missing = [label for label, col in (("Client Name", client_col), ("Officer", officer_col), ("Amount Due", due_col), ("Amount Paid", paid_col), ("Arrears", arrears_col)) if not col]
    if missing:
        raise ValueError(f"Detailed loans-due file is missing required columns: {', '.join(missing)}")

    out = pd.DataFrame()
    out["Client Name"] = df[client_col].fillna("").astype(str).str.strip()
    out["Officer"] = _clean_name(df[officer_col])
    out["Amount Due"] = _to_number(df[due_col]).round(2)
    out["Amount Paid"] = _to_number(df[paid_col]).round(2)
    out["Arrears"] = _to_number(df[arrears_col]).round(2)
    out["Amount Clean"] = out["Amount Due"].where(out["Arrears"].eq(0), 0.0)
    out["Clean Amount Still Collectible"] = (out["Amount Due"] - out["Amount Paid"]).clip(lower=0).where(out["Arrears"].eq(0), 0.0).round(2)
    out = out[(out["Client Name"] != "") & (out["Officer"] != "")].reset_index(drop=True)
    if out.empty:
        raise ValueError("No valid account rows found in detailed loans-due file")
    return out


def _add_clean_opportunity(officer_df: pd.DataFrame, detail_df: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    clean = detail_df[detail_df["Arrears"].eq(0)].copy()
    grouped = clean.groupby("Officer", as_index=False).agg({"Amount Clean": "sum", "Clean Amount Still Collectible": "sum"})
    merged = officer_df.merge(grouped, left_on="Full Name", right_on="Officer", how="left").drop(columns=["Officer"], errors="ignore")
    merged["Amount Clean"] = merged["Amount Clean"].fillna(0).round(2)
    merged["Clean Amount Still Collectible"] = merged["Clean Amount Still Collectible"].fillna(0).round(2)
    merged["Potential EOD CR (%)"] = ((merged["Paid Amount"] + merged["Amount Clean"]) / merged["Total Amount"].replace(0, pd.NA) * 100).fillna(0).clip(lower=0, upper=100).round(2)
    return merged, clean


def _reconcile(officer_df: pd.DataFrame, detail_df: pd.DataFrame) -> pd.DataFrame:
    detail_totals = detail_df.groupby("Officer", as_index=False).agg({"Amount Due": "sum", "Amount Paid": "sum"})
    rec = officer_df[["Full Name", "Total Amount", "Paid Amount", "Collection Rate (%)"]].merge(detail_totals, left_on="Full Name", right_on="Officer", how="outer")
    rec["Officer"] = rec["Full Name"].fillna(rec["Officer"])
    rec["File 1 Total Amount"] = rec["Total Amount"].fillna(0).round(2)
    rec["File 2 Total Amount"] = rec["Amount Due"].fillna(0).round(2)
    rec["Total Difference"] = (rec["File 1 Total Amount"] - rec["File 2 Total Amount"]).round(2)
    rec["File 1 Paid Amount"] = rec["Paid Amount"].fillna(0).round(2)
    rec["File 2 Paid Amount"] = rec["Amount Paid"].fillna(0).round(2)
    rec["Paid Difference"] = (rec["File 1 Paid Amount"] - rec["File 2 Paid Amount"]).round(2)
    rec["File 1 Current CR (%)"] = rec["Collection Rate (%)"].fillna(0).round(2)
    rec["File 2 Calculated CR (%)"] = (rec["File 2 Paid Amount"] / rec["File 2 Total Amount"].replace(0, pd.NA) * 100).fillna(0).clip(0, 100).round(2)
    rec["CR Difference"] = (rec["File 1 Current CR (%)"] - rec["File 2 Calculated CR (%)"]).round(2)
    rec["Status"] = rec.apply(lambda r: "Match" if abs(r["Total Difference"]) < 0.01 and abs(r["Paid Difference"]) < 0.01 else "Discrepancy - File 1 authoritative", axis=1)
    return rec[["Officer", "File 1 Total Amount", "File 2 Total Amount", "Total Difference", "File 1 Paid Amount", "File 2 Paid Amount", "Paid Difference", "File 1 Current CR (%)", "File 2 Calculated CR (%)", "CR Difference", "Status"]]


def _build_preview_chart(df: pd.DataFrame, chart_path: str) -> str:
    try:
        chart_df = df.sort_values("Collection Rate (%)", ascending=False)
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


def _write_table(ws, rows, headers, border, hdr_fill, hdr_font, center, right, left, numeric_headers=None, percent_headers=None):
    numeric_headers, percent_headers = set(numeric_headers or []), set(percent_headers or [])
    ws.row_dimensions[1].height = 28
    for col_idx, header in enumerate(headers, start=1):
        cell = ws.cell(row=1, column=col_idx, value=header)
        cell.fill, cell.font, cell.alignment, cell.border = hdr_fill, hdr_font, center, border
    for row_idx, row in enumerate(rows, start=2):
        for col_idx, value in enumerate(row, start=1):
            cell = ws.cell(row=row_idx, column=col_idx, value=value)
            cell.border = border
            header = headers[col_idx - 1]
            if header in numeric_headers:
                cell.number_format, cell.alignment = "#,##0.00", right
            elif header in percent_headers:
                cell.number_format, cell.alignment = '0.0"%"', right
            else:
                cell.alignment = left
    report_widths = {
        "A": 30,
        "B": 18,
        "C": 18,
        "D": 18,
        "E": 12,
        "F": 18,
        "G": 18,
        "H": 24,
    }
    for col_idx in range(1, ws.max_column + 1):
        col_letter = get_column_letter(col_idx)
        max_len = max((len(str(ws.cell(row=r, column=col_idx).value or "")) for r in range(1, ws.max_row + 1)), default=10)
        ws.column_dimensions[col_letter].width = max(report_widths.get(col_letter, 12), min(max_len + 3, 42))
    ws.freeze_panes, ws.auto_filter.ref = "A2", ws.dimensions


def _write_workbook(df: pd.DataFrame, clean_df: pd.DataFrame, rec_df: pd.DataFrame, output_file: str, chart_path: str, branch_stats: dict):
    wb = Workbook()
    ws = wb.active
    ws.title = "Processed Data"
    thin = Side(style="thin", color="D0D0D0")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    hdr_fill, hdr_font = PatternFill("solid", fgColor="0D47A1"), Font(bold=True, color="FFFFFF")
    center, right, left = Alignment(horizontal="center", vertical="center"), Alignment(horizontal="right", vertical="center"), Alignment(horizontal="left", vertical="center")

    headers = ["Full Name", "Total Amount", "Paid Amount", "Outstanding", "CR", "Target To 80%", "Amount Clean", "Potential EOD CR (%)"]
    for col_idx, header in enumerate(headers, start=1):
        cell = ws.cell(row=1, column=col_idx, value=header)
        cell.fill, cell.font, cell.alignment, cell.border = hdr_fill, hdr_font, center, border
    red_fill = PatternFill("solid", fgColor="FFC7CE")
    yellow_fill = PatternFill("solid", fgColor="FFF2CC")
    green_fill = PatternFill("solid", fgColor="C6EFCE")
    yellow_font = Font(color="FFFF00")
    light_green_font = Font(color="70AD47")
    red_font = Font(color="C00000")
    for row_idx, row in enumerate(df.itertuples(index=False), start=2):
        row_fill = PatternFill("solid", fgColor="E8F5E9" if str(row.Status) == "At/Above 80%" else "FFF3E0")
        values = [row[1], row[2], row[3], row[4], row[5], row[6], row[9], row[11]]
        ws.row_dimensions[row_idx].height = 24
        for col_idx, value in enumerate(values, start=1):
            cell = ws.cell(row=row_idx, column=col_idx, value=value)
            cell.border, cell.fill, cell.font = border, row_fill, Font(size=12, bold=True, color="000000")
            if headers[col_idx - 1] in ("Total Amount", "Paid Amount", "Outstanding", "Target To 80%", "Amount Clean"):
                cell.number_format, cell.alignment = "#,##0.00", right
            elif headers[col_idx - 1] in ("CR", "Potential EOD CR (%)"):
                cell.number_format, cell.alignment = '0.0"%"', right
            else:
                cell.alignment = left
            if headers[col_idx - 1] == "CR":
                cr_value = float(value)
                if cr_value < 20:
                    cell.fill = red_fill
                elif cr_value < 40:
                    cell.fill = yellow_fill
                elif cr_value <= 50:
                    cell.font = Font(size=12, bold=True, color="FFFF00")
                elif cr_value < 80:
                    cell.font = Font(size=12, bold=True, color="70AD47")
                else:
                    cell.fill = green_fill
            elif headers[col_idx - 1] == "Potential EOD CR (%)" and float(value) < 80:
                cell.font = Font(size=12, bold=True, color="C00000")
    average_row = len(df) + 2
    average_values = [
        "AVERAGE BRANCH PERFORMANCE",
        round(float(df["Total Amount"].mean()), 2),
        round(float(df["Paid Amount"].mean()), 2),
        round(float(df["Outstanding Amount"].mean()), 2),
        round(float(df["Collection Rate (%)"].mean()), 2),
        round(float(df["Target To 80%"].mean()), 2),
        round(float(df["Amount Clean"].mean()), 2),
        round(float(df["Potential EOD CR (%)"].mean()), 2),
    ]
    average_fill = PatternFill("solid", fgColor="D9EAF7")
    ws.row_dimensions[average_row].height = 26
    for col_idx, value in enumerate(average_values, start=1):
        cell = ws.cell(row=average_row, column=col_idx, value=value)
        cell.border, cell.fill, cell.font = border, average_fill, Font(size=12, bold=True, color="000000")
        if headers[col_idx - 1] in ("Total Amount", "Paid Amount", "Outstanding", "Target To 80%", "Amount Clean"):
            cell.number_format, cell.alignment = "#,##0.00", right
        elif headers[col_idx - 1] in ("CR", "Potential EOD CR (%)"):
            cell.number_format, cell.alignment = '0.0"%"', right
        else:
            cell.alignment = left
        if headers[col_idx - 1] == "CR":
            cr_value = float(value)
            if cr_value < 20:
                cell.fill = red_fill
            elif cr_value < 40:
                cell.fill = yellow_fill
            elif cr_value <= 50:
                cell.font = Font(bold=True, color="FFFF00")
            elif cr_value < 80:
                cell.font = Font(bold=True, color="70AD47")
            else:
                cell.fill = green_fill
        elif headers[col_idx - 1] == "Potential EOD CR (%)" and float(value) < 80:
            cell.font = Font(bold=True, color="C00000")
    cr_range = f"F2:F{average_row}"
    ws.conditional_formatting.add(cr_range, FormulaRule(formula=["E2<20"], fill=red_fill))
    ws.conditional_formatting.add(cr_range, FormulaRule(formula=["AND(E2>=20,E2<40)"], fill=yellow_fill))
    ws.conditional_formatting.add(cr_range, FormulaRule(formula=["AND(E2>=40,E2<=50)"], font=yellow_font))
    ws.conditional_formatting.add(cr_range, FormulaRule(formula=["AND(E2>=51,E2<80)"], font=light_green_font))
    ws.conditional_formatting.add(cr_range, FormulaRule(formula=["E2>=80"], fill=green_fill))
    ws.conditional_formatting.add(f"H2:H{average_row}", FormulaRule(formula=["H2<80"], font=red_font))
    for col_idx in range(1, ws.max_column + 1):
        max_len = max((len(str(ws.cell(row=r, column=col_idx).value or "")) for r in range(1, ws.max_row + 1)), default=10)
        ws.column_dimensions[get_column_letter(col_idx)].width = min(max(max_len + 3, 12), 42)
    ws.freeze_panes, ws.auto_filter.ref = "A2", ws.dimensions

    summary = wb.create_sheet("Summary")
    summary_rows = [("Metric", "Value"), ("Total Portfolios", int(len(df))), ("Total Amount", branch_stats["total_amount"]), ("Total Paid", branch_stats["total_paid"]), ("Total Outstanding", float(df["Outstanding Amount"].sum())), ("Current Branch CR (%)", branch_stats["current_branch_cr"]), ("Total Clean Amount", branch_stats["total_clean_amount"]), ("Total Clean Amount Still Collectible", branch_stats["total_clean_still_collectible"]), ("Potential Branch EOD CR (%)", branch_stats["potential_branch_eod_cr"]), ("Clean Accounts", int(len(clean_df))), ("Detailed Total Amount (reconciliation)", branch_stats["detail_total_amount"]), ("Detailed Paid Amount (reconciliation)", branch_stats["detail_paid_amount"]), ("Total Difference vs File 1", branch_stats["detail_total_amount"] - branch_stats["total_amount"]), ("Paid Difference vs File 1", branch_stats["detail_paid_amount"] - branch_stats["total_paid"]), ("Officer-level authority", "File 1 - existing officer-level CR/performance report")]
    for row_idx, (metric, value) in enumerate(summary_rows, start=1):
        summary.cell(row=row_idx, column=1, value=metric)
        summary.cell(row=row_idx, column=2, value=value)
        for c in summary[row_idx]:
            c.border = border
        if row_idx == 1:
            for c in summary[row_idx]:
                c.fill, c.font, c.alignment = hdr_fill, hdr_font, center
        else:
            summary.cell(row=row_idx, column=1).font = Font(bold=True)
            summary.cell(row=row_idx, column=1).alignment = left
            summary.cell(row=row_idx, column=2).alignment = right if isinstance(value, (int, float)) else left
            if "CR (%)" in metric:
                summary.cell(row=row_idx, column=2).number_format = '0.0"%"'
            elif isinstance(value, (int, float)):
                summary.cell(row=row_idx, column=2).number_format = "#,##0.00"
    summary.column_dimensions["A"].width, summary.column_dimensions["B"].width = 44, 48

    clean_ws = wb.create_sheet("Clean Accounts")
    clean_headers = ["Client Name", "Officer", "Amount Due", "Amount Paid", "Arrears", "Amount Clean", "Clean Amount Still Collectible"]
    _write_table(clean_ws, clean_df[clean_headers].itertuples(index=False, name=None), clean_headers, border, hdr_fill, hdr_font, center, right, left, numeric_headers={"Amount Due", "Amount Paid", "Arrears", "Amount Clean", "Clean Amount Still Collectible"})

    rec_ws = wb.create_sheet("Reconciliation")
    rec_headers = list(rec_df.columns)
    _write_table(rec_ws, rec_df.itertuples(index=False, name=None), rec_headers, border, hdr_fill, hdr_font, center, right, left, numeric_headers={"File 1 Total Amount", "File 2 Total Amount", "Total Difference", "File 1 Paid Amount", "File 2 Paid Amount", "Paid Difference"}, percent_headers={"File 1 Current CR (%)", "File 2 Calculated CR (%)", "CR Difference"})

    if chart_path and os.path.exists(chart_path):
        chart_ws = wb.create_sheet("Chart")
        chart_ws.add_image(XLImage(chart_path), "A1")
    wb.save(output_file)


def run(officer_input_path: str, detail_input_path: str, output_dir: str) -> dict:
    os.makedirs(output_dir, exist_ok=True)
    officer_df = _prepare_dataframe(_read_input(officer_input_path))
    detail_df = _prepare_detail_dataframe(_read_input(detail_input_path))
    processed, clean_df = _add_clean_opportunity(officer_df, detail_df)
    rec_df = _reconcile(officer_df, detail_df)

    total_amount, total_paid = float(processed["Total Amount"].sum()), float(processed["Paid Amount"].sum())
    total_clean_amount, total_clean_still_collectible = float(clean_df["Amount Clean"].sum()), float(clean_df["Clean Amount Still Collectible"].sum())
    current_branch_cr = (total_paid / total_amount * 100) if total_amount else 0.0
    potential_branch_eod_cr = min(100.0, ((total_paid + total_clean_amount) / total_amount * 100) if total_amount else 0.0)
    branch_stats = {"total_amount": round(total_amount, 2), "total_paid": round(total_paid, 2), "total_clean_amount": round(total_clean_amount, 2), "total_clean_still_collectible": round(total_clean_still_collectible, 2), "current_branch_cr": round(current_branch_cr, 2), "potential_branch_eod_cr": round(potential_branch_eod_cr, 2), "detail_total_amount": round(float(detail_df["Amount Due"].sum()), 2), "detail_paid_amount": round(float(detail_df["Amount Paid"].sum()), 2)}

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_file = os.path.join(output_dir, f"SOD_CR_Per_Portfolio_{timestamp}.xlsx")
    chart_file = _build_preview_chart(processed, os.path.join(output_dir, "sod_cr_portfolio_chart.png"))
    _write_workbook(processed, clean_df, rec_df, output_file, chart_file, branch_stats)
    return {"success": True, "output_file": output_file, "chart_file": chart_file, "stats": {"total_portfolios": int(len(processed)), "total_amount": branch_stats["total_amount"], "total_paid": branch_stats["total_paid"], "total_outstanding": round(float(processed["Outstanding Amount"].sum()), 2), "avg_collection_rate": round(float(processed["Collection Rate (%)"].mean()) if len(processed) else 0.0, 2), "total_target_to_80": round(float(processed["Target To 80%"].sum()), 2), "below_80_count": int((processed["Collection Rate (%)"] < 80).sum()), "total_clean_amount": branch_stats["total_clean_amount"], "total_clean_still_collectible": branch_stats["total_clean_still_collectible"], "current_branch_cr": branch_stats["current_branch_cr"], "potential_branch_eod_cr": branch_stats["potential_branch_eod_cr"], "clean_account_count": int(len(clean_df)), "officer_reconciliation_rows": int(len(rec_df))}}
