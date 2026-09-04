#!/usr/bin/env python3
"""Fetch full Prime-listed universe from JPX (東京証券取引所 上場会社情報).

Zero deps: stdlib urllib only.
Output: data/prime.csv — one line per stock: `code,name,industry` (UTF-8).
JPX page declares shift_jis in its meta tag but actually serves UTF-8.
"""
import http.cookiejar
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://www2.jpx.co.jp/tseHpFront/JJK010010Action.do"
PAGE_BASE = "https://www2.jpx.co.jp/tseHpFront/JJK010030Action.do"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
PAGE_SIZE = 200


class JPXSession:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar)
        )
        self.opener.addheaders = [("User-Agent", UA), ("Accept-Language", "ja")]

    def _request(self, fields, url=BASE, referer=None):
        req = urllib.request.Request(
            url,
            data=urllib.parse.urlencode(fields).encode(),
            headers={"Referer": referer or url},
        )
        with self.opener.open(req, timeout=30) as r:
            return r.read().decode("utf-8", "replace")

    def get_landing(self):
        req = urllib.request.Request(BASE + "?Show=Show", headers={"User-Agent": UA})
        with self.opener.open(req, timeout=30) as r:
            return r.read().decode("utf-8", "replace")

    def search(self, page=1):
        fields = {
            "ListShow": "ListShow",
            "dspSsuPd": str(PAGE_SIZE),
            "szkbuChkbx": "011",  # プライム
        }
        if page == 1:
            return self._request(fields)
        fields = {
            "Transition": "Transition",
            "lstDspPg": str(page),
            "dspGs": str(PAGE_SIZE),
            "szkbuChkbx": "011",
        }
        return self._request(fields, url=PAGE_BASE, referer=BASE)


def _cell(td):
    txt = re.sub(r"<[^>]+>", " ", td)
    return re.sub(r"\s+", " ", txt).strip()


def parse_rows(html):
    """Return {(code): (name, industry)} for Prime-ordinary rows only.

    Row cells: [0]=code [1]=name [2]=market [3]=industry ...  Filtering on
    market=='プライム' drops ETFs/REITs/foreign shares that share the page.
    """
    out = {}
    for row in re.findall(r"<tr[^>]*>(.*?)</tr>", html, re.S):
        tds = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)
        if len(tds) < 4:
            continue
        code, market, industry = _cell(tds[0]), _cell(tds[2]), _cell(tds[3])
        if code.isdigit() and market == "プライム":
            out[code] = (_cell(tds[1]), industry)
    return out


def parse_total(html):
    m = re.search(r"(\d+)件を表示／(\d+)件中", html)
    return int(m.group(2)) if m else None


def fetch_prime():
    s = JPXSession()
    s.get_landing()
    first = s.search(1)
    rows = parse_rows(first)
    total = parse_total(first) or len(rows)
    pages = (total + PAGE_SIZE - 1) // PAGE_SIZE
    print(f"total={total} pages={pages} page1={len(rows)}", file=sys.stderr)
    for page in range(2, pages + 1):
        rows.update(parse_rows(s.search(page)))
        print(f"page={page} cumulative={len(rows)}", file=sys.stderr)
        time.sleep(0.3)
    return rows


def main():
    rows = fetch_prime()
    out = Path(__file__).resolve().parent.parent / "data" / "prime.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"{code},{name},{industry}" for code, (name, industry) in sorted(rows.items())]
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {len(lines)} rows -> {out}")


if __name__ == "__main__":
    main()
