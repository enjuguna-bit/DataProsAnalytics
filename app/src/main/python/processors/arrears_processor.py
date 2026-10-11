"""
Arrears Processor
Script-parity implementation of arrange_arrears.py for Android/Chaquopy.
"""

import os
import pandas as pd
import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


def _get_bucket(days):
    if days < 1:
        return "Current"
    if 1 <= days <= 3:
        return "1-3 Days"
    if 4 <= days <= 5:
        return "4-5 Days"
    if 6 <= days <= 9:
        return "6-9 Days"
    if 10 <= days <= 30:
        return "10-30 Days"
    return "31+ Days"


def _get_bucket_id(days):
    if days < 1:
        return 0
    if 1 <= days <= 3:
        return 1
    if 4 <= days <= 5:
        return 2
    if 6 <= days <= 9:
        return 3
    if 10 <= days <= 30:
        return 4
    return 5


def _clean_phone(value):
    try:
        return str(int(float(value)))
    except Exception:
        return str(value)


def _build_preview_chart(pivot_rep: pd.DataFrame, chart_path: str) -> str:
    """Generate a lightweight PNG preview for the Android card UI."""
    try:
        fig, ax = plt.subplots(figsize=(8, 5))
        if pivot_rep.empty:
            ax.text(0.5, 0.5, "No data", ha="center", va="center")
            ax.axis("off")
        else:
            top10 = pivot_rep.head(10).sort_values("Total_Arrears", ascending=True)
            ax.barh(top10["SalesRep"], top10["Total_Arrears"], color="#4472C4")
            ax.set_xlabel("Arrears Amount (KES)")
            ax.set_title("Total Arrears by Sales Rep", fontweight="bold")
        fig.tight_layout()
        fig.savefig(chart_path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        return chart_path
    except Exception:
        plt.close("all")
        return ""


def _read_tabular_file(input_path: str) -> pd.DataFrame:
    file_ext = os.path.splitext(input_path)[1].lower()
    csv_encodings = ("utf-8-sig", "utf-8", "latin1", "cp1252", "iso-8859-1")
    read_order = ("csv", "excel") if file_ext == ".csv" else ("excel", "csv")
    last_error: Exception | None = None

    for reader in read_order:
        if reader == "excel":
            try:
                return pd.read_excel(input_path)
            except Exception as exc:
                last_error = exc
        else:
            for encoding in csv_encodings:
                try:
                    return pd.read_csv(input_path, encoding=encoding)
                except UnicodeDecodeError as exc:
                    last_error = exc
                    continue
                except Exception as exc:
                    last_error = exc
                    break

    raise ValueError(f"Unable to read input file as CSV or Excel: {last_error}")


def run(input_path: str, output_dir: str) -> dict:
    """Process arrears data and generate executive Excel report."""
    os.makedirs(output_dir, exist_ok=True)

    df = _read_tabular_file(input_path)

    required_cols = ["FullNames", "PhoneNumber", "Arrears Amount", "DaysInArrears", "LoanBalance", "SalesRep"]
    df.columns = [str(c).strip() for c in df.columns]

    for col in required_cols:
        if col not in df.columns:
            df[col] = 0 if ("Amount" in col or "Days" in col) else "Unknown"

    df_clean = df[required_cols].copy()

    for col in ["Arrears Amount", "LoanBalance", "DaysInArrears"]:
        df_clean[col] = pd.to_numeric(df_clean[col], errors="coerce").fillna(0)

    df_clean["SalesRep"] = df_clean["SalesRep"].fillna("Unassigned").astype(str)
    df_clean["FullNames"] = df_clean["FullNames"].fillna("Unknown Client").astype(str)
    df_clean["PhoneNumber"] = df_clean["PhoneNumber"].apply(_clean_phone)

    df_clean["Bucket"] = df_clean["DaysInArrears"].apply(_get_bucket)
    df_clean["BucketID"] = df_clean["DaysInArrears"].apply(_get_bucket_id)
    df_clean.sort_values(by=["SalesRep", "DaysInArrears"], ascending=[True, True], inplace=True)

    total_clients = len(df_clean)
    total_arrears = df_clean["Arrears Amount"].sum()
    total_balance = df_clean["LoanBalance"].sum()
    unique_reps = df_clean["SalesRep"].nunique()

    pivot_rep = df_clean.groupby("SalesRep").agg(
        Total_Arrears=("Arrears Amount", "sum"),
        Total_Portfolio=("LoanBalance", "sum"),
        Client_Count=("FullNames", "count"),
    ).reset_index()
    pivot_rep["Risk_Pct"] = (pivot_rep["Total_Arrears"] / pivot_rep["Total_Portfolio"]).replace([np.inf, -np.inf], 0).fillna(0)
    pivot_rep = pivot_rep.sort_values("Total_Arrears", ascending=False)

    pivot_bucket = df_clean.groupby("Bucket").agg(
        Total_Arrears=("Arrears Amount", "sum")
    ).reset_index()

    chart_path = os.path.join(output_dir, "arrears_chart.png")
    chart_file = _build_preview_chart(pivot_rep, chart_path)

    filename = os.path.basename(input_path)
    name_no_ext = os.path.splitext(filename)[0]
    timestamp = pd.Timestamp.now().strftime("%Y-%m-%d_%H-%M")
    output_file = os.path.join(output_dir, f"EXECUTIVE_REPORT_{name_no_ext}_{timestamp}.xlsx")

    writer = pd.ExcelWriter(output_file, engine="xlsxwriter")
    workbook = writer.book

    font_name = "Segoe UI"

    fmt_currency = workbook.add_format({
        "num_format": "[$KES] #,##0.00",
        "border": 1,
        "align": "right",
        "font_name": font_name,
        "valign": "vcenter",
    })
    fmt_number = workbook.add_format({"border": 1, "align": "center", "font_name": font_name, "valign": "vcenter"})
    fmt_text = workbook.add_format({"border": 1, "align": "left", "font_name": font_name, "valign": "vcenter"})
    fmt_center = workbook.add_format({"border": 1, "align": "center", "font_name": font_name, "valign": "vcenter"})
    fmt_percent = workbook.add_format({
        "num_format": "0.0%",
        "border": 1,
        "align": "center",
        "font_name": font_name,
        "valign": "vcenter",
    })

    fmt_header_yellow = workbook.add_format({
        "bold": True,
        "bg_color": "#FFD966",
        "font_color": "#000000",
        "border": 1,
        "align": "center",
        "valign": "vcenter",
        "font_name": font_name,
        "font_size": 11,
    })
    fmt_header_dark = workbook.add_format({
        "bold": True,
        "bg_color": "#203764",
        "font_color": "#FFFFFF",
        "border": 1,
        "align": "center",
        "valign": "vcenter",
        "font_name": font_name,
        "font_size": 12,
    })

    fmt_total_row = workbook.add_format({
        "bold": True,
        "border": 1,
        "bg_color": "#D9D9D9",
        "num_format": "[$KES] #,##0.00",
        "align": "right",
    })
    fmt_total_label = workbook.add_format({"bold": True, "border": 1, "bg_color": "#D9D9D9", "align": "left"})
    fmt_bucket_sum = workbook.add_format({
        "bold": True,
        "border": 1,
        "bg_color": "#F2F2F2",
        "align": "center",
        "valign": "vcenter",
        "font_name": font_name,
        "text_wrap": True,
    })

    bucket_colors = {
        0: "#FFFFFF",
        1: "#E2EFDA",
        2: "#FFF2CC",
        3: "#FCE4D6",
        4: "#F8CBAD",
        5: "#FF9999",
    }

    ws_dash = workbook.add_worksheet("Executive Dashboard")
    ws_dash.hide_gridlines(2)
    ws_dash.set_tab_color("#FF0000")

    pivot_rep_chart = pivot_rep[["SalesRep", "Total_Arrears"]].copy()
    if pivot_rep_chart.empty:
        pivot_rep_chart = pd.DataFrame({"SalesRep": ["No Data"], "Total_Arrears": [0.0]})

    pivot_bucket_chart = pivot_bucket[["Bucket", "Total_Arrears"]].copy()
    if pivot_bucket_chart.empty:
        pivot_bucket_chart = pd.DataFrame({"Bucket": ["No Data"], "Total_Arrears": [0.0]})

    ws_dash.write_row("AA1", ["Rep", "Arrears"], fmt_header_dark)
    ws_dash.write_column("AA2", pivot_rep_chart["SalesRep"], fmt_text)
    ws_dash.write_column("AB2", pivot_rep_chart["Total_Arrears"], fmt_currency)

    ws_dash.write_row("AD1", ["Bucket", "Arrears"], fmt_header_dark)
    ws_dash.write_column("AD2", pivot_bucket_chart["Bucket"], fmt_text)
    ws_dash.write_column("AE2", pivot_bucket_chart["Total_Arrears"], fmt_currency)

    chart_rep = workbook.add_chart({"type": "column"})
    chart_rep.add_series({
        "name": "Arrears Amount",
        "categories": f"='Executive Dashboard'!$AA$2:$AA${len(pivot_rep_chart) + 1}",
        "values": f"='Executive Dashboard'!$AB$2:$AB${len(pivot_rep_chart) + 1}",
        "fill": {"color": "#4472C4"},
        "data_labels": {"value": False},
    })
    chart_rep.set_title({"name": "Total Arrears by Sales Rep"})

    chart_aging = workbook.add_chart({"type": "doughnut"})
    chart_aging.add_series({
        "name": "Aging",
        "categories": f"='Executive Dashboard'!$AD$2:$AD${len(pivot_bucket_chart) + 1}",
        "values": f"='Executive Dashboard'!$AE$2:$AE${len(pivot_bucket_chart) + 1}",
        "data_labels": {"percentage": True},
    })
    chart_aging.set_title({"name": "Portfolio Risk Distribution"})

    ws_dash.insert_chart("B2", chart_rep, {"x_scale": 1.8, "y_scale": 1.2})
    ws_dash.insert_chart("B18", chart_aging, {"x_scale": 1.8, "y_scale": 1.2})

    table_start_row = 34
    dash_headers = ["Rank", "Sales Rep", "Client Count", "Total Arrears", "Total Portfolio", "Collection Risk %"]

    ws_dash.merge_range(
        table_start_row - 2, 1, table_start_row - 2, 6, "EXECUTIVE ARREARS SCORECARD",
        workbook.add_format({
            "bold": True,
            "font_size": 14,
            "align": "center",
            "bg_color": "#F2F2F2",
            "border": 1,
        }),
    )

    for col, header in enumerate(dash_headers):
        ws_dash.write(table_start_row, col + 1, header, fmt_header_dark)

    for i, row in enumerate(pivot_rep.itertuples(index=False), table_start_row + 1):
        ws_dash.write(i, 1, i - table_start_row, fmt_number)
        ws_dash.write(i, 2, row.SalesRep, fmt_text)
        ws_dash.write(i, 3, row.Client_Count, fmt_number)
        ws_dash.write(i, 4, row.Total_Arrears, fmt_currency)
        ws_dash.write(i, 5, row.Total_Portfolio, fmt_currency)
        ws_dash.write(i, 6, row.Risk_Pct, fmt_percent)

    if not pivot_rep.empty:
        ws_dash.conditional_format(table_start_row + 1, 6, table_start_row + len(pivot_rep), 6, {
            "type": "3_color_scale",
            "min_color": "#63BE7B",
            "mid_color": "#FFEB84",
            "max_color": "#F8696B",
        })

    ws_dash.set_column("B:B", 8)
    ws_dash.set_column("C:C", 25)
    ws_dash.set_column("D:D", 12)
    ws_dash.set_column("E:F", 18)
    ws_dash.set_column("G:G", 15)

    ws = workbook.add_worksheet("Collection Details")
    ws.set_tab_color("#FFC000")
    ws.freeze_panes(1, 2)

    headers = ["Sales Rep", "Client Name", "Phone", "Arrears Amount", "Days Late", "Risk Category Total", "Loan Balance"]
    for col, header in enumerate(headers):
        ws.write(0, col, header, fmt_header_yellow)

    fmt_curr_plain = workbook.add_format({"num_format": "#,##0.00", "border": 1})
    fmt_text_plain = workbook.add_format({"border": 1})
    fmt_cent_plain = workbook.add_format({"border": 1, "align": "center"})
    fmt_days_default = workbook.add_format({"border": 1, "align": "center", "bold": True})
    fmt_days_by_bucket = {
        bid: workbook.add_format({"border": 1, "align": "center", "bg_color": color, "bold": True})
        for bid, color in bucket_colors.items()
    }

    row_num = 1
    col_widths = [len(h) for h in headers]

    for rep_name, group in df_clean.groupby("SalesRep", sort=False):
        bucket_sums = group.groupby("Bucket")["Arrears Amount"].sum()

        bucket_ranges = []
        current_b_id = -1
        current_b_name = ""
        start_r = row_num
        count = 0

        group_rows = list(group.itertuples(index=False))
        for i, row in enumerate(group_rows):
            b_id = row.BucketID
            b_name = row.Bucket

            if b_id != current_b_id and count > 0:
                b_total = bucket_sums.get(current_b_name, 0)
                bucket_ranges.append((start_r, count, b_total, current_b_name))
                start_r = row_num + i
                count = 1
                current_b_id = b_id
                current_b_name = b_name
            elif count == 0:
                current_b_id = b_id
                current_b_name = b_name
                start_r = row_num + i
                count = 1
            else:
                count += 1

        if count > 0:
            b_total = bucket_sums.get(current_b_name, 0)
            bucket_ranges.append((start_r, count, b_total, current_b_name))

        for row in group_rows:
            b_id = int(getattr(row, "BucketID", 0))
            f_days = fmt_days_by_bucket.get(b_id, fmt_days_default)
            arrears_val = getattr(row, "_2", row[2])

            ws.write(row_num, 0, row.SalesRep, fmt_text_plain)
            ws.write(row_num, 1, row.FullNames, fmt_text_plain)
            ws.write(row_num, 2, row.PhoneNumber, fmt_cent_plain)
            ws.write(row_num, 3, arrears_val, fmt_curr_plain)
            ws.write(row_num, 4, row.DaysInArrears, f_days)
            ws.write(row_num, 5, "", fmt_curr_plain)
            ws.write(row_num, 6, row.LoanBalance, fmt_curr_plain)

            vals_for_width = [
                row.SalesRep,
                row.FullNames,
                row.PhoneNumber,
                arrears_val,
                row.DaysInArrears,
                15000,
                row.LoanBalance,
            ]
            for c_idx, value in enumerate(vals_for_width):
                if c_idx < len(col_widths):
                    str_len = len(str(value))
                    if str_len > col_widths[c_idx]:
                        col_widths[c_idx] = str_len

            row_num += 1

        for start_r_idx, count_rows, total_val, bucket_name in bucket_ranges:
            formatted_val = f"{bucket_name}\nKES {total_val:,.2f}"
            if count_rows > 1:
                ws.merge_range(start_r_idx, 5, start_r_idx + count_rows - 1, 5, formatted_val, fmt_bucket_sum)
            else:
                ws.write(start_r_idx, 5, formatted_val, fmt_bucket_sum)

        total_arrears_rep = group["Arrears Amount"].sum()
        total_balance_rep = group["LoanBalance"].sum()

        ws.write(row_num, 0, f"TOTAL: {rep_name}", fmt_total_label)
        ws.write(row_num, 1, "", fmt_total_row)
        ws.write(row_num, 2, "", fmt_total_row)
        ws.write(row_num, 3, total_arrears_rep, fmt_total_row)
        ws.write(row_num, 4, "", fmt_total_row)
        ws.write(row_num, 5, "", fmt_total_row)
        ws.write(row_num, 6, total_balance_rep, fmt_total_row)
        row_num += 2

    for idx, width in enumerate(col_widths):
        ws.set_column(idx, idx, min(width + 3, 50))
    ws.set_column(5, 5, 25)

    if row_num > 1:
        ws.conditional_format(1, 3, row_num, 3, {"type": "data_bar", "bar_color": "#63C384"})
        ws.autofilter(0, 0, row_num, len(headers) - 1)

    writer.close()

    return {
        "success": True,
        "output_file": output_file,
        "chart_file": chart_file,
        "stats": {
            "total_clients": int(total_clients),
            "total_arrears": round(float(total_arrears), 2),
            "total_loan_balance": round(float(total_balance), 2),
            "unique_sales_reps": int(unique_reps),
        },
    }
