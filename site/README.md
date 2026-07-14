# PHANTOM — phntm.pro site

Static site for **phntm.pro**. Four pages, mirrored in two languages
(English at the tree root, Russian at `/ru/`), each page single-language,
no build tooling — pure HTML/CSS/JS.

Language switching is done by real `<a>` links between the two trees
(see `.lang` in the nav). There is no JavaScript language auto-detect,
no `localStorage` toggle, no client-side content swap — a page has
exactly one language and its URL declares which. This structure was
introduced on 2026-07-15 in the Phase 3 SEO work to make hreflang
targeting effective for both Google and Yandex.

Each `*.html` embeds its CSS and JS directly in the `<style>` and
`<script>` blocks. The brand logo is served as a small image from
`/static/favicon.png` (~57 KB, same-origin, cached across pages and
reused for the browser tab icon — so the `<img>` reference costs zero
new network requests). CDN fonts (Inter and JetBrains Mono via Google
Fonts, Geist via jsdelivr) are the only external requests.

Earlier revisions of these HTML files embedded the brand logo as a
base64 data URI directly inside every `<img>` tag — six ~82 KB copies
of the same JPEG across the four pages, pushing ~660 KB of duplicated
bytes into the HTML that had to travel before first render. That was
removed on 2026-06-02 (index / about ~83–89% smaller; roadmap / donate
~77% smaller). The logo is now fetched once and cached.

**Source of truth: the eight `*.html` files themselves.** Edit them
directly with any text editor and the change is live after the next
deploy.

**Two-tree maintenance model.** The EN tree (`site/*.html`) and the RU
tree (`site/ru/*.html`) are maintained independently — a content edit
that should appear in both languages must be applied to both files.
There is no generator; the initial RU tree was produced once by a
throwaway Python script at split time and both trees are hand-edited
from that point on. Rationale: PR #377 already deleted a class of stale
build tooling that had drifted from the HTML it was supposed to
generate; a live generator would just re-introduce the same drift risk.
Four pages × 2 languages is small enough to maintain by hand.

Each EN/RU pair carries a reciprocal `hreflang` cluster
(`en` / `ru` / `x-default`) plus a self-referencing `canonical`, so a
search engine that crawls one variant discovers the other and
associates them correctly. `sitemap.xml` lists all 8 URLs with the
same alternate annotations. Do not break the pairing: if a page is
renamed, both files must be renamed and both hreflang blocks + the
sitemap entry must be updated in the same commit.

The old Python generator scripts under `.build/build_*.py` were
removed on 2026-06-02 because their template had drifted about two
months behind the actual HTML (missing `<link rel="canonical">`,
Open Graph tags, Twitter Card tags, JSON-LD structured data, and
the inline `<style>` / `<script>` blocks) and their output path was
hardcoded to a Claude Desktop sandbox directory (`/home/claude/site_build/`).
They were never wired into any build or deploy pipeline, and
re-running them would have produced broken HTML that also broke
the SEO markup landed in PR-SEO-PASS-1 (#267).

The standalone `styles.css` and `site.js` files at the root of this
directory are retained as readable reference copies of the current
design system — useful as a workspace when developing larger visual
changes. They are NOT executed at runtime — production reads only
the inlined copies in each HTML page.

## Layout

```
site/
├── index.html              Home — EN     (URL: /)
├── about.html              About — EN    (URL: /about.html)
├── roadmap.html            Roadmap — EN  (URL: /roadmap.html)
├── donate.html             Support — EN  (URL: /donate.html)
├── ru/
│   ├── index.html          Home — RU     (URL: /ru/)
│   ├── about.html          About — RU    (URL: /ru/about.html)
│   ├── roadmap.html        Roadmap — RU  (URL: /ru/roadmap.html)
│   └── donate.html         Support — RU  (URL: /ru/donate.html)
├── sitemap.xml             SEO sitemap (8 URLs with xhtml:link alternate annotations)
├── robots.txt              Crawler rules (allow search + AI grounding, deny training)
├── styles.css              Reference copy of design tokens + layout (NOT executed at runtime)
├── site.js                 Reference copy of lang switcher + scroll-reveal (NOT executed at runtime)
├── static/
│   ├── favicon.png         512×512, browser tab / bookmark / home-screen
│   │                       / JSON-LD org logo / on-page brand logo (nav + hero)
│   └── og-image.png        1200×630, social share preview (og:image + twitter:image)
└── README.md               This file
```

### Why `static/` and not `assets/`

Caddy on phntm.pro has a dedicated route `handle_path /assets/*` that
proxies to `/srv/legal/assets/` (for the legal pages `/terms` and
`/privacy`). If this site used `assets/` for its own files, any
`<img src="assets/...">` reference would silently 404 because Caddy
would intercept it. `static/` avoids that namespace collision.

## Deploy

The site is served by the `caddy` container in `/home/phantom/Phantom/deploy/`
via a bind-mount: the Caddyfile's `phntm.pro` vhost serves files from
`/srv/landing/` inside the container, and `deploy/docker-compose.yml`
maps `../site` (this directory) into `/srv/landing` read-only.

### Standard release deploy

```bash
# 1. Pull latest into the VPS-side checkout.
cd /home/phantom/Phantom && git pull

# 2. Force-recreate caddy.
#
#    `--force-recreate` is REQUIRED. Caddy's bind-mount for funding.json
#    is a single-file mount, which means the container holds the original
#    inode at start-time; `git pull` overwrites the file on disk but the
#    running container still serves the old inode. Without
#    `--force-recreate`, requests serve stale content.
#
#    The site/ directory bind-mount (`../site:/srv/landing:ro`) is less
#    fragile because it maps a whole directory, not a single file —
#    but recreating caddy is still the simplest single-command sweep
#    that covers both.
cd deploy && docker compose up -d --force-recreate caddy

# 3. Cloudflare Dashboard → phntm.pro → Caching → Configuration →
#    Purge Everything.
#    (Required because phntm.pro is Proxied through Cloudflare with a
#     1-hour cache header on funding.json and default caching on the
#     HTML pages.)
```

### Post-deploy verification

Four `curl` checks confirm the deploy landed cleanly:

```bash
# 1. New site is served (not the old stub).
#    Old stub had: <meta name="robots" content="noindex,nofollow">
#    New pages have: <meta name="robots" content="index,follow">
curl -sL https://phntm.pro/ | grep '<meta name="robots"'
# Expect: <meta name="robots" content="index,follow">

# 2. Old "noindex" must be GONE.
curl -sL https://phntm.pro/ | grep -i 'noindex'
# Expect: empty

# 3. funding.json donation URLs are correct (PR #245 fix deployed).
curl -s https://phntm.pro/funding.json | grep -E 'liberapay\.com|buymeacoffee\.com'
# Expect: https://liberapay.com/Phantom-messenger (CAPITAL P)
#         https://www.buymeacoffee.com/phantompro  (NOT phantommessenger)

# 4. Inner pages reachable (no .html-routing tricks needed; full path serves).
curl -sI https://phntm.pro/about.html | head -1
curl -sI https://phntm.pro/roadmap.html | head -1
curl -sI https://phntm.pro/donate.html | head -1
# Expect: HTTP/2 200 on all three
```

### Rollback

The old stub landing is preserved at `deploy/landing/` (no longer
bind-mounted but kept in git history). To roll back, revert the
docker-compose line and `--force-recreate caddy`:

```yaml
# In deploy/docker-compose.yml, revert:
- ../site:/srv/landing:ro
# back to:
- ./landing:/srv/landing:ro
```

Then `cd /home/phantom/Phantom/deploy && docker compose up -d --force-recreate caddy`
+ Cloudflare purge.

## Editing the site

Each page is self-contained, so you can edit `index.html` / `about.html`
/ `roadmap.html` / `donate.html` directly with any text editor and the
change is live after the next deploy. This is the whole workflow —
there is no build step and no code generator.

For larger visual changes you may prefer to iterate first on the reference
`styles.css` / `site.js` files (they are cleaner to edit than the inlined
copies), then copy the updated content back into each HTML page's
`<style>` and `<script>` blocks. Take care to keep all four HTML pages in
sync when doing this — there is no automation.

## What NOT to lose on the next deploy

When swapping web roots, these MUST be preserved:

- `funding.json` at repo root — already separately bind-mounted at
  `../funding.json:/srv/funding/funding.json:ro` and served at
  `https://phntm.pro/funding.json` by a dedicated Caddyfile `handle`.
  NOT inside `site/`. Do not duplicate.
- `.well-known/funding-manifest-urls` at repo root — exists for the
  FLOSS/fund wellKnown proof on GitHub
  (`https://github.com/LiudvigVladislav/Phantom/blob/master/.well-known/funding-manifest-urls`).
  NOT served via phntm.pro and NOT needed inside `site/`.
- `deploy/well-known/assetlinks.json` — Android App Links manifest,
  separately bind-mounted to `/srv/well-known` and served via
  `handle_path /.well-known/*`. NOT inside `site/`.
- Caddy's auto-managed `.well-known/acme-challenge/` — never put a
  file there. Caddy creates it transiently for HTTP-01 challenges.

The `caddy:2.8-alpine` image and `caddy_data` named volume hold
issued certificates; those are also untouched by site/ replacement.

## Bilingual content (`data-lang-en` / `data-lang-ru`)

Both languages live inline in every HTML page and are toggled by a
CSS rule on `<html data-lang="…">`. `site.js`:

1. Picks language: `localStorage` saved choice > browser language > `en`.
2. Sets `<html data-lang="…">` and `<html lang="…">`.
3. Updates `<title>` from `data-en` / `data-ru` attributes.
4. Wires the EN / RU buttons in the top nav.

If you add new content, put both languages side-by-side:

```html
<h2 data-lang-en>Built assuming the network is hostile.</h2>
<h2 data-lang-ru>Построен из допущения, что сеть враждебна.</h2>
```

The CSS in `styles.css` (and inlined in each HTML) controls which
language is visible.

## CDN dependencies (intentional)

- `https://fonts.googleapis.com/css2?family=Inter…&family=JetBrains+Mono…` — Google Fonts.
- `https://cdn.jsdelivr.net/npm/geist@1/dist/fonts/geist-sans/style.css` — Geist (jsdelivr).

The site renders without these (fallback fonts inside the family
stack: `'Geist','Inter',sans-serif` etc.), but the typography is
intentional. To bundle the fonts locally for a fully self-hosted
deploy, download the WOFF2 files, add them to `static/fonts/`,
and replace the two `@import` lines in `styles.css` with `@font-face`
declarations.
