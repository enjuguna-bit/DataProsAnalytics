"""
Shared helpers for loading uploaded tabular files.
"""

from __future__ import annotations

import os
from typing import Any

import pandas as pd


CSV_ENCODINGS = ("utf-8-sig", "utf-8", "latin1", "cp1252", "iso-8859-1")


def read_tabular_file(
    path: str,
    *,
    dtype: Any = None,
    prefer_csv: bool | None = None,
) -> pd.DataFrame:
    """
    Read a CSV or Excel file while tolerating missing or misleading extensions.

    The reader tries the most likely format first, but will fall back between CSV
    and Excel automatically. This makes the upload flows resilient to downloads
    that arrive with generic or inconsistent names.
    """
    if not os.path.isfile(path):
        raise FileNotFoundError(f"Input file not found: {path}")

    ext = os.path.splitext(path)[1].lower()
    if prefer_csv is True:
        read_order = ("csv", "excel")
    elif prefer_csv is False:
        read_order = ("excel", "csv")
    elif ext == ".csv":
        read_order = ("csv", "excel")
    elif ext in {".xls", ".xlsx"}:
        read_order = ("excel", "csv")
    else:
        read_order = ("csv", "excel")

    last_error: Exception | None = None
    for reader in read_order:
        if reader == "csv":
            for encoding in CSV_ENCODINGS:
                try:
                    return pd.read_csv(path, dtype=dtype, encoding=encoding)
                except UnicodeDecodeError as exc:
                    last_error = exc
                    continue
                except Exception as exc:
                    last_error = exc
                    break
        else:
            try:
                return pd.read_excel(path, dtype=dtype)
            except Exception as exc:
                last_error = exc

    raise ValueError(f"Unable to read input file as CSV or Excel: {last_error}")
