# PHANTOM — phntm.pro site

Static site for **phntm.pro**. Four pages, bilingual (EN/RU, auto-detect
browser language + manual switcher with localStorage persistence),
no build tooling required at runtime — pure HTML/CSS/JS.

Each `*.html` is fully self-contained: CSS and JS are inlined into
the `<style>` and `<script>` blocks so the page renders without any
extra HTTP requests beyond CDN fonts (Inter, JetBrains Mono via
Google Fonts; Geist via jsdelivr). The standalone `styles.css` and
`site.js` files are kept as **source-of-truth** for maintenance —
edit them, then regenerate the HTML via `.build/build_*.py`.

## Layout

```
site/
├── index.html              Home (hero, features, privacy modes, transports, status)
├── about.html              About (mission, how it's built, who builds it)
├── roadmap.html            Roadmap (Shipped / In progress / Planned)
├── donate.html             Support (donation channels + crypto copy-buttons)
├── styles.css              Shared design system (source-of-truth; inlined in HTML)
├── site.js                 Lang switcher + mobile menu + scroll-reveal + clipboard
├── static/                 Static assets — DO NOT rename to "assets/"
│   └── phantom-logo.jpg    Logo (also embedded as base64 in HTML for inline render)
├── .build/                 Python regen scripts (dot-prefix; Caddy file_server hides)
│   ├── build_index.py
│   ├── build_about.py
│   ├── build_roadmap.py
│   └── build_donate.py
└── README.md               This file
```

### Why `static/` and not `assets/`

Caddy on phntm.pro has a dedicated route `handle_path /assets/*` that
proxies to `/srv/legal/assets/` (for the legal pages `/terms` and
`/privacy`). If this site used `assets/` for its own files, any
`<img src="assets/...">` reference would silently 404 because Caddy
would intercept it. `static/` avoids that namespace collision.

This is currently invisible because every HTML embeds the logo as a
base64 data URI rather than loading it via `<img src="static/...">`.
But the build scripts already write `static/phantom-logo.jpg` so any
future build that decides to use external images instead of base64
will work without surprise.

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

### Direct HTML edit

Each page is self-contained, so you can edit `index.html` / `about.html`
/ `roadmap.html` / `donate.html` directly with any text editor and the
change is live after the next deploy. This is the path you want for
small fixes — typos, wording, link updates.

### Regenerate from source (`.build/build_*.py`)

If you change the shared design system (`styles.css`) or the shared
JavaScript (`site.js`), the inlined copies inside each HTML page go
stale. Regenerate them:

```bash
cd site
python3 .build/build_index.py    > index.html
python3 .build/build_about.py    > about.html
python3 .build/build_roadmap.py  > roadmap.html
python3 .build/build_donate.py   > donate.html
```

(Each `build_*.py` reads `styles.css` + `site.js` + its own page-specific
body markup + base64-embeds `static/phantom-logo.jpg`, then prints a
single HTML file. The Python scripts have zero external dependencies.)

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
