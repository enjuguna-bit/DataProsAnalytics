"""
MTD Income Analysis Processor
Builds a day-based income analysis report per portfolio.
"""

from __future__ import annotations

import os
import re
from datetime import datetime
from typing import Dict, List, Tuple

import matplotlib
import pandas as pd
from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

matplotlib.use("Agg")
import matplotlib.pyplot as plt


DEFAULT_TARGET_CR = 93

TARGET_CR_BY_ID: Dict[int, int] = {
    288: 95,
    120: 92,
    155: 91,
    69: 93,
    145: 94,
}

TARGET_CR_BY_NAME: Dict[str, int] = {
    "JOASH MUTISYA": 95,
    "BRIAN WANJALA": 92,
    "ESTHER MUMBI": 91,
    "ALEXANDER MWANGI": 93,
    "BENSON KIRUI": 94,
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


def _pick_column(columns: List[str], candidates: List[str]) -> str:
    lookup = {_normalize_header(c): c for c in columns}
    for candidate in candidates:
        key = _normalize_header(candidate)
        if key in lookup:
            return lookup[key]
    return ""


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


def _parse_day(day_input: str) -> int:
    text = str(day_input or "").strip()
    if text == "":
        return 1
    digits = re.sub(r"[^0-9]", "", text)
    if digits == "":
        return 1
    value = int(digits)
    if value < 1:
        return 1
    if value > 31:
        return 31
    return value


def _parse_daily_targets(daily_targets_input: str, names: List[str]) -> Dict[str, float]:
    targets = {n: 0.0 for n in names}
    if not daily_targets_input or not str(daily_targets_input).strip():
        return targets

    default_target = 0.0
    lookup = {n.strip().upper(): n for n in names}

    for raw_line in str(daily_targets_input).splitlines():
        line = raw_line.strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip().upper()
        try:
            amount = max(0.0, float(str(value).strip().replace(",", "")))
        except Exception:
            continue

        if key in {"DEFAULT", "ALL", "*"}:
            default_target = amount
            continue
        if key in lookup:
            targets[lookup[key]] = amount

    for name in names:
        if targets[name] <= 0 and default_target > 0:
            targets[name] = default_target

    return targets


def _resolve_target_cr(ids: pd.Series, names: pd.Series, target_cr_input: pd.Series | None) -> pd.Series:
    resolved: List[int] = []
    for idx, name in names.items():
        if target_cr_input is not None and idx in target_cr_input.index:
            val = int(max(0, min(100, round(float(target_cr_input.loc[idx])))))
            if val > 0:
                resolved.append(val)
                continue

        officer_id = int(ids.loc[idx]) if idx in ids.index else 0
        if officer_id in TARGET_CR_BY_ID:
            resolved.append(TARGET_CR_BY_ID[officer_id])
            continue

        officer_name = str(name).strip().upper()
        if officer_name in TARGET_CR_BY_NAME:
            resolved.append(TARGET_CR_BY_NAME[officer_name])
            continue

        resolved.append(DEFAULT_TARGET_CR)

    return pd.Series(resolved, index=names.index, dtype=int)


def get_portfolios(input_path: str) -> List[str]:
    try:
        df = _read_input(input_path)
        if df is None or df.empty:
            return []
        df.columns = [str(c).strip() for c in df.columns]
        name_col = _pick_column(list(df.columns), ["Full Name", "FullName", "Name", "SalesRep", "Officer"])
        if not name_col:
            return []
        names = (
            df[name_col]
            .fillna("")
            .astype(str)
            .str.strip()
            .str.replace(r"\s+", " ", regex=True)
            .str.upper()
        )
        return sorted(names[names != ""].drop_duplicates().tolist())
    except Exception:
        return []


def _build_dataframe(df: pd.DataFrame, day_input: str, daily_targets_input: str) -> Tuple[pd.DataFrame, Dict[str, float]]:
    if df is None or df.empty:
        raise ValueError("Input file is empty")

    day_number = _parse_day(day_input)

    df = df.copy()
    df.columns = [str(c).strip() for c in df.columns]
    cols = list(df.columns)

    id_col = _pick_column(cols, ["#ID", "ID", "OfficerID"])
    name_col = _pick_column(cols, ["Full Name", "FullName", "Name", "SalesRep", "Officer"])
    total_col = _pick_column(cols, ["Total Amount", "TotalAmount", "AmountDue", "Amount Due"])
    paid_col = _pick_column(cols, ["Paid Amount", "PaidAmount", "AmountPaid", "Amount Paid"])
    outstanding_col = _pick_column(cols, ["Outstanding Amount", "OutstandingAmount", "Outstanding", "Arrears", "Balance"])
    cr_col = _pick_column(cols, ["Collection Rate (%)", "CollectionRate", "CR%", "CR"])
    target_cr_col = _pick_column(cols, ["Target CR", "TargetCR", "Target (%)", "CR Target", "Target"])

    if not name_col or not total_col:
        raise ValueError("Missing required columns. Need at least Full Name and Total Amount.")

    names = (
        df[name_col]
        .fillna("")
        .astype(str)
        .str.strip()
        .str.replace(r"\s+", " ", regex=True)
        .str.upper()
    )
    mask = names != ""
    if not mask.any():
        raise ValueError("No valid portfolio names found")

    base = df.loc[mask].copy()
    names = names.loc[mask]

    total_amount = _to_number(base[total_col]).round(2)
    # Some raw income files only provide Total Amount. In that case, treat it as actual paid income.
    if paid_col:
        paid_amount = _to_number(base[paid_col]).round(2)
    else:
        paid_amount = total_amount.copy()

    if outstanding_col:
        outstanding = _to_number(base[outstanding_col]).round(2)
    else:
        outstanding = (total_amount - paid_amount).clip(lower=0).round(2)

    if cr_col:
        current_cr = _to_number(base[cr_col]).clip(lower=0, upper=100).round(2)
    else:
        current_cr = ((paid_amount / total_amount.replace(0, pd.NA)) * 100).fillna(0).clip(lower=0, upper=100).round(2)

    if id_col:
        ids = _to_number(base[id_col]).astype(int)
    else:
        ids = pd.Series([0] * len(base), index=base.index, dtype=int)

    target_cr_input = _to_number(base[target_cr_col]).round(0) if target_cr_col else None
    target_cr = _resolve_target_cr(ids, names, target_cr_input)

    target_amount = (total_amount * (target_cr / 100.0) - paid_amount).clip(lower=0).round(0)
    daily_targets = _parse_daily_targets(daily_targets_input, names.tolist())
    daily_target = names.apply(lambda n: float(daily_targets.get(n, 0.0)))
    expected_to_day = (daily_target * day_number).round(2)
    income_gap = (paid_amount - expected_to_day).round(2)
    today_required = (expected_to_day - paid_amount).clip(lower=0).round(2)
    achievement = ((paid_amount / expected_to_day.replace(0, pd.NA)) * 100).fillna(0).round(1)
    status = income_gap.apply(lambda v: "On Track" if float(v) >= 0 else "Behind")

    out = pd.DataFrame()
    out["Full Name"] = names.values
    out["Total Amount"] = total_amount.values
    out["Paid Amount"] = paid_amount.values
    out["Outstanding"] = outstanding.values
    out["CR%"] = [f"{float(a):.2f}/{int(b)}" for a, b in zip(current_cr.values, target_cr.values)]
    out["TargetAmount"] = target_amount.values
    out["Day"] = [day_number] * len(out)
    out["Daily Target"] = daily_target.values
    out["Expected To Day"] = expected_to_day.values
    out["Income Gap"] = income_gap.values
    out["Today Required"] = today_required.values
    out["Achievement %"] = achievement.values
    out["Status"] = status.values

    out = out.sort_values(by=["Income Gap", "Outstanding"], ascending=[True, False]).reset_index(drop=True)

    total_amount_sum = float(out["Total Amount"].sum())
    total_paid_sum = float(out["Paid Amount"].sum())
    total_outstanding_sum = float(out["Outstanding"].sum())
    total_target_amount = float(out["TargetAmount"].sum())
    total_daily_target = float(out["Daily Target"].sum())
    total_expected = float(out["Expected To Day"].sum())
    total_gap = float(out["Income Gap"].sum())
    total_today_required = float(out["Today Required"].sum())
    weighted_cr = (total_paid_sum / total_amount_sum * 100.0) if total_amount_sum > 0 else 0.0
    weighted_target_cr = (
        (out["Total Amount"] * target_cr.values).sum() / total_amount_sum if total_amount_sum > 0 else DEFAULT_TARGET_CR
    )

    total_row = pd.DataFrame(
        [
            {
                "Full Name": "TOTAL",
                "Total Amount": total_amount_sum,
                "Paid Amount": total_paid_sum,
                "Outstanding": total_outstanding_sum,
                "CR%": f"{weighted_cr:.2f}/{int(round(weighted_target_cr))}",
                "TargetAmount": total_target_amount,
                "Day": day_number,
                "Daily Target": total_daily_target,
                "Expected To Day": total_expected,
                "Income Gap": total_gap,
                "Today Required": total_today_required,
                "Achievement %": (total_paid_sum / total_expected * 100.0) if total_expected > 0 else 0.0,
                "Status": "TOTAL",
            }
        ]
    )

    final_df = pd.concat([out, total_row], ignore_index=True)

    on_track_count = int((out["Status"] == "On Track").sum())
    behind_count = int((out["Status"] == "Behind").sum())

    stats = {
        "day_number": int(day_number),
        "total_portfolios": int(len(out)),
        "total_actual_income": round(total_paid_sum, 2),
        "total_expected_income": round(total_expected, 2),
        "total_income_gap": round(total_gap, 2),
        "total_today_required": round(total_today_required, 2),
        "total_outstanding": round(total_outstanding_sum, 2),
        "on_track_count": on_track_count,
        "behind_count": behind_count,
    }
    return final_df, stats


def _build_chart(df: pd.DataFrame, chart_path: str) -> str:
    try:
        body = df[df["Status"] != "TOTAL"].copy()
        if body.empty:
            return ""
        body = body.sort_values("Income Gap", ascending=True)

        x = list(range(len(body)))
        fig, ax = plt.subplots(figsize=(max(8, len(body) * 0.9), 5))
        ax.bar([i - 0.2 for i in x], body["Paid Amount"], width=0.4, label="Actual Income", color="#1E88E5")
        ax.bar([i + 0.2 for i in x], body["Expected To Day"], width=0.4, label="Expected To Day", color="#FB8C00")
        ax.set_xticks(x)
        ax.set_xticklabels(body["Full Name"], rotation=45, ha="right", fontsize=8)
        ax.set_ylabel("Amount")
        ax.set_title("MTD Income Analysis: Actual vs Expected", fontweight="bold")
        ax.legend()
        plt.tight_layout()
        fig.savefig(chart_path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        return chart_path
    except Exception:
        plt.close("all")
        return ""


def _write_excel(df: pd.DataFrame, output_file: str, chart_path: str):
    wb = Workbook()
    ws = wb.active
    ws.title = "Income Analysis"

    display_columns = [
        "Full Name",
        "Total Amount",
        "Paid Amount",
        "Outstanding",
        "CR%",
        "TargetAmount",
        "Day",
        "Daily Target",
        "Expected To Day",
        "Income Gap",
        "Today Required",
        "Achievement %",
    ]

    thin = Side(style="thin", color="D0D0D0")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    center = Alignment(horizontal="center", vertical="center")
    left = Alignment(horizontal="left", vertical="center")
    right = Alignment(horizontal="right", vertical="center")

    header_fill = PatternFill("solid", fgColor="0D47A1")
    header_font = Font(bold=True, color="FFFFFF")
    red_font = Font(color="C62828", bold=True)
    total_font = Font(bold=True)

    for col_idx, header in enumerate(display_columns, start=1):
        cell = ws.cell(row=1, column=col_idx, value=header)
        cell.fill = header_fill
        cell.font = header_font
        cell.alignment = center
        cell.border = border

    for row_idx, (_, row) in enumerate(df.iterrows(), start=2):
        is_total = str(row.get("Status", "")) == "TOTAL"
        row_fill = PatternFill("solid", fgColor="F5F5F5" if is_total else "FFFFFF")

        for col_idx, header in enumerate(display_columns, start=1):
            value = row.get(header, "")
            cell = ws.cell(row=row_idx, column=col_idx, value=value)
            cell.border = border
            cell.fill = row_fill

            if header in {"Full Name", "CR%"}:
                cell.alignment = left if header == "Full Name" else center
            elif header == "Day":
                cell.alignment = center
            else:
                cell.alignment = right

            if header in {
                "Total Amount",
                "Paid Amount",
                "Outstanding",
                "TargetAmount",
                "Daily Target",
                "Expected To Day",
                "Income Gap",
                "Today Required",
            }:
                cell.number_format = "#,##0"
            elif header == "Achievement %":
                cell.number_format = '0.0"%"'

            if is_total:
                cell.font = total_font
            else:
                if str(row.get("Status", "")) == "Behind" and header in {
                    "Full Name",
                    "Total Amount",
                    "Paid Amount",
                    "Outstanding",
                    "CR%",
                    "TargetAmount",
                    "Daily Target",
                    "Expected To Day",
                    "Income Gap",
                    "Today Required",
                    "Achievement %",
                }:
                    cell.font = red_font

    for col_idx in range(1, ws.max_column + 1):
        max_len = max(
            (len(str(ws.cell(row=r, column=col_idx).value or "")) for r in range(1, ws.max_row + 1)),
            default=10,
        )
        ws.column_dimensions[get_column_letter(col_idx)].width = min(max_len + 3, 35)

    ws.freeze_panes = "A2"
    ws.auto_filter.ref = f"A1:{get_column_letter(ws.max_column)}{ws.max_row}"

    if chart_path and os.path.exists(chart_path):
        from openpyxl.drawing.image import Image as XLImage

        chart_ws = wb.create_sheet("Chart")
        chart_ws.add_image(XLImage(chart_path), "A1")

    wb.save(output_file)


def run(input_path: str, output_dir: str, day_input: str = "1", daily_targets_input: str = "") -> dict:
    os.makedirs(output_dir, exist_ok=True)
    source_df = _read_input(input_path)
    final_df, stats = _build_dataframe(source_df, day_input=day_input, daily_targets_input=daily_targets_input)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_file = os.path.join(output_dir, f"MTD_Income_Analysis_{timestamp}.xlsx")
    chart_file = _build_chart(final_df, os.path.join(output_dir, "mtd_income_analysis_chart.png"))
    _write_excel(final_df, output_file, chart_file)

    return {
        "success": True,
        "output_file": output_file,
        "chart_file": chart_file,
        "stats": stats,
    }
