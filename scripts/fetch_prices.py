#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Holt die Day-Ahead-Strompreise für Österreich (15-Minuten-Auflösung) und
schreibt sie als statische JSON-Dateien in docs/ (GitHub Pages).
Quellen: ENTSO-E (Token) → Energy-Charts (Fallback, CC BY 4.0 / SMARD)."""

import datetime as dt
import json
import os
import sys
from xml.etree import ElementTree as ET

import requests

UTC = dt.timezone.utc
DOCS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs")
KEEP_DAYS = 400


def res_minutes(text):
    t = (text or "").upper()
    for token, minutes in (("PT15M", 15), ("PT30M", 30), ("PT60M", 60), ("PT1H", 60)):
        if token in t:
            return minutes
    return 60


def parse_entsoe(root):
    """Jede <Period> einzeln; innere und End-Lücken werden aufgefüllt."""
    by_res = {}
    for period in root.iter():
        if not period.tag.endswith("Period"):
            continue
        start_txt = end_txt = res_txt = None
        for child in period:
            if child.tag.endswith("timeInterval"):
                for sub in child:
                    if sub.tag.endswith("start"):
                        start_txt = sub.text
                    elif sub.tag.endswith("end"):
                        end_txt = sub.text
            elif child.tag.endswith("resolution"):
                res_txt = child.text
        if not start_txt:
            continue
        try:
            p0 = dt.datetime.fromisoformat(start_txt.replace("Z", "+00:00"))
        except ValueError:
            continue
        res = res_minutes(res_txt)
        bucket = by_res.setdefault(res, {})
        last_pos, last_price = 0, None
        for point in period:
            if not point.tag.endswith("Point"):
                continue
            pos = next((c.text for c in point if c.tag.endswith("position")), None)
            price = next((c.text for c in point if c.tag.endswith("price.amount")), None)
            try:
                pos, price = int(pos), float(price)
            except (TypeError, ValueError):
                continue
            if last_price is not None:
                for gap in range(last_pos + 1, pos):
                    bucket[p0 + dt.timedelta(minutes=res * (gap - 1))] = last_price
            bucket[p0 + dt.timedelta(minutes=res * (pos - 1))] = price
            last_pos, last_price = pos, price
        if end_txt and last_price is not None:
            try:
                p_end = dt.datetime.fromisoformat(end_txt.replace("Z", "+00:00"))
                n_soll = int((p_end - p0).total_seconds() // (60 * res))
                for gap in range(last_pos + 1, n_soll + 1):
                    bucket[p0 + dt.timedelta(minutes=res * (gap - 1))] = last_price
            except ValueError:
                pass
    if not by_res:
        return None
    span = {r: len(v) * r for r, v in by_res.items()}
    best = max(span, key=lambda r: (span[r], -r))
    best = next(r for r in sorted(by_res) if span[r] >= span[best] * 0.95)
    return sorted(({"start": s, "epex": round(mwh / 10.0, 3), "res": best}
                   for s, mwh in by_res[best].items()), key=lambda p: p["start"])


def fetch_entsoe(token):
    day0 = dt.datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    r = requests.get("https://web-api.tp.entsoe.eu/api", timeout=30, params={
        "securityToken": token, "documentType": "A44",
        "in_Domain": "10YAT-APG------L", "out_Domain": "10YAT-APG------L",
        "periodStart": (day0 - dt.timedelta(days=1)).strftime("%Y%m%d%H%M"),
        "periodEnd": (day0 + dt.timedelta(days=2)).strftime("%Y%m%d%H%M")})
    r.raise_for_status()
    root = ET.fromstring(r.text)
    if "Acknowledgement" in root.tag:
        return None
    return parse_entsoe(root)


def fetch_energy_charts():
    day0 = dt.datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    r = requests.get("https://api.energy-charts.info/price", timeout=30, params={
        "bzn": "AT",
        "start": int((day0 - dt.timedelta(days=1)).timestamp()),
        "end": int((day0 + dt.timedelta(days=2)).timestamp())})
    r.raise_for_status()
    d = r.json()
    ts, pr = d.get("unix_seconds", []), d.get("price", [])
    if len(ts) < 2:
        return None
    res = max(1, int((ts[1] - ts[0]) / 60))
    return [{"start": dt.datetime.fromtimestamp(t, tz=UTC), "epex": round(p / 10.0, 3),
             "res": res} for t, p in zip(ts, pr) if p is not None]


def main():
    token = os.environ.get("ENTSOE_TOKEN", "")
    prices, source = None, None
    if token:
        try:
            prices = fetch_entsoe(token)
            source = "ENTSO-E"
        except Exception as e:
            # bewusst nur der Fehlertyp — nie die URL (enthält den Schlüssel)
            print(f"ENTSO-E nicht verfügbar ({type(e).__name__})")
    if not prices:
        prices = fetch_energy_charts()
        source = "SMARD/Energy-Charts"
    if not prices:
        print("Keine Daten von beiden Quellen — Abbruch ohne Änderung.")
        sys.exit(0)                        # alte Dateien behalten

    os.makedirs(DOCS, exist_ok=True)
    with open(os.path.join(DOCS, "prices.json"), "w", encoding="utf-8") as f:
        json.dump({
            "generated": dt.datetime.now(UTC).isoformat(timespec="seconds"),
            "source": source,
            "attribution": "Day-Ahead-Preise: ENTSO-E Transparency / "
                           "Bundesnetzagentur SMARD.de via Energy-Charts (CC BY 4.0)",
            "zone": "AT",
            "prices": [{"start": p["start"].isoformat(), "epex": p["epex"],
                        "res": p["res"]} for p in prices]}, f)

    # Historie: volle Roh-Tagesreihen, rollierend
    hist_path = os.path.join(DOCS, "history.json")
    days = {}
    try:
        with open(hist_path, "r", encoding="utf-8") as f:
            days = json.load(f).get("days", {})
    except Exception:
        pass
    by_day = {}
    for p in prices:
        local = p["start"].astimezone(dt.timezone(dt.timedelta(hours=2)))  # grob CEST
        by_day.setdefault(local.date().isoformat(), []).append(p)
    today = dt.datetime.now(UTC).date().isoformat()
    for day, slots in by_day.items():
        if day >= today:
            continue                       # nur abgeschlossene Tage archivieren
        if sum(s["res"] for s in slots) >= 18 * 60:
            days[day] = {"res": slots[0]["res"],
                         "epex": [s["epex"] for s in sorted(slots, key=lambda x: x["start"])]}
    for day in sorted(days)[:-KEEP_DAYS] if len(days) > KEEP_DAYS else []:
        del days[day]
    with open(hist_path, "w", encoding="utf-8") as f:
        json.dump({"days": days}, f)
    print(f"OK: {len(prices)} Slots ({source}), Historie {len(days)} Tage")


if __name__ == "__main__":
    main()
