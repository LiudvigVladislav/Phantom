#!/usr/bin/env python3
"""
Render PHANTOM legal Markdown documents to themed HTML.

Usage:
    python3 legal/render.py

Reads every `*_EN.md` (and `*_RU.md` later) under `legal/` and emits a
matching `*.html` next to it, wrapped in a PHANTOM-branded HTML
template. The template colours and typography track
PHANTOM_Design_Brief_v2.pdf §03 / §04 (Surface Deep, Cyan Accent,
Inter-fallback). No external dependencies — uses Python's stdlib `re`
to handle the small Markdown subset our legal documents use:

    # / ## headings
    **bold**
    - bullet lists
    [text](url) links
    | tables |
    paragraphs separated by blank lines

The output is committed alongside the Markdown so the repo carries both
the source-of-truth (`.md`) and the public-facing rendered page
(`.html`). Caddy serves the HTML at https://phntm.pro/terms and
https://phntm.pro/privacy.

Re-run this script whenever the Markdown is edited.
"""

from __future__ import annotations

import html
import re
from pathlib import Path

LEGAL_DIR = Path(__file__).resolve().parent

# Map source filenames to (output filename, page title) for the public
# /terms and /privacy URLs Caddy will serve.
TARGETS = {
    "TERMS_OF_SERVICE_EN.md": ("terms.html", "Terms of Service — PHANTOM"),
    "PRIVACY_POLICY_EN.md":   ("privacy.html", "Privacy Policy — PHANTOM"),
}

# ── HTML template ──────────────────────────────────────────────────────────────
# Colours and spacing track PHANTOM_Design_Brief_v2.pdf §03/§04/§05 (Surfaces,
# Cyan accent #00D4FF, 8pt grid). No external font CDNs — the messenger is a
# privacy-first project, so the marketing pages must not leak font requests to
# Google or any other third party. We use the OS sans/mono stack instead.
TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>{title}</title>
<meta name="theme-color" content="#08090C">
<meta name="description" content="PHANTOM — privacy-focused, end-to-end encrypted messenger. {description}">
<link rel="icon" type="image/png" href="/assets/phantom-logo.png">
<link rel="apple-touch-icon" href="/assets/phantom-logo.png">
<style>
  /* ─── Design tokens (Brief §03) ─────────────────────────────────────── */
  :root {{
    --surface-deep:     #08090C;
    --surface:          #0E1014;
    --surface-elevated: #161A20;
    --border-subtle:    #1F242C;
    --border:           #2A2F38;
    --text-primary:     #F5F7FA;
    --text-secondary:   #A4ACBA;
    --text-tertiary:    #6B7385;
    --accent-cyan:      #00D4FF;

    /* Type stacks. PP Neue Montreal / Inter / JetBrains Mono are licensed,
       so we use OS-native equivalents that match the design feel:
        * Sans:    San Francisco / Segoe UI Variable / Roboto. Modern,
                   high-quality, ship with every supported platform.
        * Mono:    SF Mono / Cascadia Code / Roboto Mono. Used only for
                   the brand wordmark and overline labels per Brief §04. */
    --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI Variable Display",
                 "Segoe UI", Roboto, "Helvetica Neue", system-ui, sans-serif;
    --font-mono: ui-monospace, "SF Mono", "Cascadia Code", "Roboto Mono",
                 "Liberation Mono", Menlo, Consolas, monospace;
  }}

  /* ─── Reset ─────────────────────────────────────────────────────────── */
  *, *::before, *::after {{ box-sizing: border-box; }}
  html, body {{ margin: 0; padding: 0; }}
  body {{
    background: var(--surface-deep);
    color: var(--text-primary);
    font-family: var(--font-sans);
    /* Body 15/22 per Brief §04 type scale. */
    font-size: 15px;
    line-height: 1.6;
    font-feature-settings: "tnum" 1, "ss01" 1;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
    text-rendering: optimizeLegibility;
  }}

  /* ─── Header ────────────────────────────────────────────────────────── */
  header {{
    border-bottom: 1px solid var(--border-subtle);
    background: var(--surface-deep);
    position: sticky;
    top: 0;
    z-index: 10;
    backdrop-filter: saturate(160%) blur(8px);
    -webkit-backdrop-filter: saturate(160%) blur(8px);
    background-color: rgba(8, 9, 12, 0.85);
  }}
  header .inner {{
    max-width: 768px;
    margin: 0 auto;
    padding: 16px 24px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;
  }}
  header .brand {{
    display: inline-flex;
    align-items: center;
    gap: 12px;
    color: var(--text-primary);
    text-decoration: none;
  }}
  header .brand img {{
    width: 28px;
    height: 28px;
    border-radius: 6px;
    display: block;
  }}
  header .brand .word {{
    font-family: var(--font-mono);
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 5px;
    color: var(--text-primary);
  }}
  header nav {{
    display: flex;
    gap: 24px;
    align-items: center;
  }}
  header nav a {{
    color: var(--text-secondary);
    font-size: 13px;
    font-weight: 500;
    text-decoration: none;
    transition: color 150ms ease-out;
  }}
  header nav a:hover {{ color: var(--accent-cyan); }}
  header nav a[aria-current="page"] {{ color: var(--text-primary); }}

  /* ─── Main / article ────────────────────────────────────────────────── */
  main {{
    max-width: 720px;
    margin: 0 auto;
    padding: 64px 24px 96px;
  }}
  article h1 {{
    /* Display 32/38 Medium per Brief §04. */
    font-size: 32px;
    line-height: 1.18;
    font-weight: 500;
    letter-spacing: -0.012em;
    margin: 0;
    color: var(--text-primary);
    text-wrap: balance;
  }}
  article .meta {{
    /* Overline 11/14 Mono uppercase +8% per Brief §04. */
    color: var(--text-tertiary);
    font-family: var(--font-mono);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 1.2px;
    margin: 12px 0 48px;
    padding-bottom: 32px;
    border-bottom: 1px solid var(--border-subtle);
  }}
  article .meta span {{ color: var(--text-secondary); }}

  article h2 {{
    /* Headline 20/28 Medium. */
    font-size: 20px;
    line-height: 1.4;
    font-weight: 500;
    letter-spacing: -0.005em;
    margin: 48px 0 14px;
    color: var(--text-primary);
  }}
  article h3 {{
    /* Title 17/24 Semibold. */
    font-size: 17px;
    line-height: 1.4;
    font-weight: 600;
    margin: 32px 0 8px;
    color: var(--text-primary);
  }}
  article p {{
    margin: 14px 0;
    color: var(--text-secondary);
  }}
  article ul, article ol {{
    padding-left: 24px;
    margin: 14px 0;
  }}
  article li {{
    margin: 8px 0;
    color: var(--text-secondary);
  }}
  article li::marker {{ color: var(--text-tertiary); }}
  article strong {{ color: var(--text-primary); font-weight: 500; }}
  article em {{ font-style: italic; }}

  /* Accent for inline links — single underline so the page stays calm
     (Brief §02: "Animation communicates, doesn't decorate"). */
  article a {{
    color: var(--accent-cyan);
    text-decoration: none;
    border-bottom: 1px solid rgba(0, 212, 255, 0.28);
    transition: border-color 150ms ease-out;
  }}
  article a:hover {{ border-bottom-color: var(--accent-cyan); }}

  article code {{
    font-family: var(--font-mono);
    background: var(--surface-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: 6px;
    padding: 1px 6px;
    font-size: 13px;
    color: var(--text-primary);
  }}

  /* Table — Brief radius Medium 12pt, Surface Elevated for header. */
  article table {{
    width: 100%;
    border-collapse: separate;
    border-spacing: 0;
    margin: 24px 0;
    font-size: 14px;
    background: var(--surface);
    border: 1px solid var(--border-subtle);
    border-radius: 12px;
    overflow: hidden;
  }}
  article th, article td {{
    padding: 12px 16px;
    text-align: left;
    border-bottom: 1px solid var(--border-subtle);
    vertical-align: top;
  }}
  article tr:last-child td {{ border-bottom: 0; }}
  article th {{
    background: var(--surface-elevated);
    color: var(--text-primary);
    font-weight: 500;
    font-size: 13px;
    letter-spacing: 0.2px;
  }}
  article td {{ color: var(--text-secondary); }}

  /* ─── Footer ────────────────────────────────────────────────────────── */
  footer {{
    border-top: 1px solid var(--border-subtle);
    padding: 40px 24px 64px;
    text-align: center;
  }}
  footer .inner {{ max-width: 720px; margin: 0 auto; }}
  footer .brand-mark {{
    display: inline-flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 24px;
    color: var(--text-secondary);
  }}
  footer .brand-mark img {{
    width: 24px;
    height: 24px;
    border-radius: 5px;
    opacity: 0.9;
    display: block;
  }}
  footer .brand-mark .word {{
    font-family: var(--font-mono);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 4px;
    color: var(--text-secondary);
  }}
  footer nav {{ margin-bottom: 18px; display: flex; justify-content: center; gap: 28px; flex-wrap: wrap; }}
  footer nav a {{
    color: var(--text-secondary);
    text-decoration: none;
    font-size: 13px;
    transition: color 150ms ease-out;
  }}
  footer nav a:hover {{ color: var(--accent-cyan); }}
  footer .legal {{
    color: var(--text-tertiary);
    font-size: 11px;
    font-family: var(--font-mono);
    letter-spacing: 0.6px;
    text-transform: uppercase;
  }}

  /* ─── Mobile (Brief §05: 16pt margin) ───────────────────────────────── */
  @media (max-width: 600px) {{
    main {{ padding: 48px 18px 64px; }}
    article h1 {{ font-size: 26px; }}
    article h2 {{ font-size: 18px; margin-top: 36px; }}
    article .meta {{ margin-bottom: 36px; padding-bottom: 24px; }}
    header .inner {{ padding: 14px 18px; gap: 12px; }}
    header nav {{ gap: 16px; }}
    footer nav {{ gap: 18px; }}
  }}

  /* ─── Reduced motion ─────────────────────────────────────────────────── */
  @media (prefers-reduced-motion: reduce) {{
    * {{ transition: none !important; animation: none !important; }}
  }}
</style>
</head>
<body>
<header>
  <div class="inner">
    <a class="brand" href="https://phntm.pro/" aria-label="PHANTOM home">
      <img src="/assets/phantom-logo.png" alt="" width="28" height="28">
      <span class="word">PHANTOM</span>
    </a>
    <nav aria-label="Legal navigation">
      <a href="/terms"{terms_current}>Terms</a>
      <a href="/privacy"{privacy_current}>Privacy</a>
    </nav>
  </div>
</header>
<main>
  <article>
{body}
  </article>
</main>
<footer>
  <div class="inner">
    <div class="brand-mark">
      <img src="/assets/phantom-logo.png" alt="" width="24" height="24">
      <span class="word">PHANTOM</span>
    </div>
    <nav aria-label="Footer navigation">
      <a href="/terms">Terms of Service</a>
      <a href="/privacy">Privacy Policy</a>
      <a href="https://github.com/WladislaWLE/Phantom">GitHub</a>
    </nav>
    <div class="legal">© 2026 Willen LLC · Wyoming, United States</div>
  </div>
</footer>
</body>
</html>
"""


def render_inline(text: str) -> str:
    """Render inline Markdown to HTML — bold, italic, links, code."""
    # Escape HTML first, then re-introduce our tags.
    text = html.escape(text)
    # `code`
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    # **bold**
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    # *italic* (single asterisk; our docs barely use it but keep complete)
    text = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<em>\1</em>", text)
    # [text](url)
    text = re.sub(
        r"\[([^\]]+)\]\(([^)]+)\)",
        lambda m: f'<a href="{html.escape(m.group(2))}">{m.group(1)}</a>',
        text,
    )
    return text


def render_table(lines: list[str]) -> str:
    """Render a Markdown pipe-table to <table>."""
    # First line: header. Second: separator (---). Rest: rows.
    rows = [
        [cell.strip() for cell in re.split(r"\s*\|\s*", line.strip().strip("|"))]
        for line in lines
    ]
    if len(rows) < 2:
        return ""
    head_cells = rows[0]
    body_rows = rows[2:]
    html_out = ["<table><thead><tr>"]
    for cell in head_cells:
        html_out.append(f"<th>{render_inline(cell)}</th>")
    html_out.append("</tr></thead><tbody>")
    for row in body_rows:
        html_out.append("<tr>")
        for cell in row:
            html_out.append(f"<td>{render_inline(cell)}</td>")
        html_out.append("</tr>")
    html_out.append("</tbody></table>")
    return "".join(html_out)


def render_markdown(md: str) -> tuple[str, str, str]:
    """Render the document body. Returns (html_body, h1_title, meta_html)."""
    lines = md.splitlines()
    html_parts: list[str] = []
    h1_title = ""
    meta_html = ""

    i = 0
    in_list = False
    para_buf: list[str] = []
    meta_pending: list[str] = []

    def flush_para():
        nonlocal para_buf
        if para_buf:
            text = " ".join(para_buf).strip()
            if text:
                html_parts.append(f"<p>{render_inline(text)}</p>")
            para_buf = []

    def close_list():
        nonlocal in_list
        if in_list:
            html_parts.append("</ul>")
            in_list = False

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        # Skip blank lines but flush any open paragraph/list
        if not stripped:
            flush_para()
            close_list()
            i += 1
            continue

        # # heading → h1 (only the first one becomes the page title)
        if stripped.startswith("# ") and not stripped.startswith("## "):
            flush_para()
            close_list()
            text = stripped[2:].strip()
            if not h1_title:
                h1_title = text
            html_parts.append(f"<h1>{render_inline(text)}</h1>")
            i += 1
            # Look ahead for **Effective:** / **Last updated:** meta lines
            meta_lines: list[str] = []
            while i < len(lines):
                nxt = lines[i].strip()
                if not nxt:
                    i += 1
                    continue
                if re.match(r"\*\*(Effective|Last updated|Дата|Обновлено|Действует)", nxt):
                    meta_lines.append(nxt)
                    i += 1
                    continue
                break
            if meta_lines:
                rendered = " · ".join(render_inline(line) for line in meta_lines)
                # Remove the <strong> tags inside meta for a cleaner look —
                # they are bold by structure already.
                rendered = rendered.replace("<strong>", "").replace("</strong>", "<span> </span>")
                meta_html = f'<p class="meta">{rendered}</p>'
            continue

        # ## heading → h2
        if stripped.startswith("## "):
            flush_para()
            close_list()
            text = stripped[3:].strip()
            html_parts.append(f"<h2>{render_inline(text)}</h2>")
            i += 1
            continue

        # ### heading → h3
        if stripped.startswith("### "):
            flush_para()
            close_list()
            text = stripped[4:].strip()
            html_parts.append(f"<h3>{render_inline(text)}</h3>")
            i += 1
            continue

        # Pipe table start
        if stripped.startswith("|") and stripped.endswith("|"):
            flush_para()
            close_list()
            tbl_lines = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                tbl_lines.append(lines[i])
                i += 1
            html_parts.append(render_table(tbl_lines))
            continue

        # - bullet
        if stripped.startswith("- "):
            flush_para()
            if not in_list:
                html_parts.append("<ul>")
                in_list = True
            text = stripped[2:].strip()
            html_parts.append(f"<li>{render_inline(text)}</li>")
            i += 1
            continue

        # Paragraph continuation — accumulate
        close_list()
        para_buf.append(stripped)
        i += 1

    flush_para()
    close_list()

    return "\n".join(html_parts), h1_title, meta_html


def render_file(md_path: Path, html_path: Path, page_title: str) -> None:
    md = md_path.read_text(encoding="utf-8")
    body, h1, meta = render_markdown(md)
    if meta:
        body = body.replace(
            f"<h1>{render_inline(h1)}</h1>",
            f"<h1>{render_inline(h1)}</h1>\n{meta}",
            1,
        )
    # Highlight the active nav item — aria-current="page" + style hook.
    is_terms = "terms" in html_path.stem.lower()
    is_privacy = "privacy" in html_path.stem.lower()
    description = h1.lower()
    out = TEMPLATE.format(
        title=page_title,
        description=description,
        body=body,
        terms_current=' aria-current="page"' if is_terms else "",
        privacy_current=' aria-current="page"' if is_privacy else "",
    )
    html_path.write_text(out, encoding="utf-8")
    print(f"Rendered: {md_path.name} -> {html_path.name}  ({len(out):,} bytes)")


def main() -> None:
    for src_name, (out_name, title) in TARGETS.items():
        src = LEGAL_DIR / src_name
        if not src.exists():
            print(f"Skip {src_name}: not found")
            continue
        out = LEGAL_DIR / out_name
        render_file(src, out, title)


if __name__ == "__main__":
    main()
