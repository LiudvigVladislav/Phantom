#!/usr/bin/env python3
# Builds about.html

NAV = '''<nav>
  <a href="index.html" class="nav-logo"><img src="static/phantom-logo.jpg" alt=""><span>PHANTOM</span></a>
  <button class="menu-btn" aria-label="Menu"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12h18M3 6h18M3 18h18"/></svg></button>
  <div class="nav-links">
    <a href="index.html" data-lang-en>Home</a><a href="index.html" data-lang-ru>Главная</a>
    <a href="about.html" class="active" data-lang-en>About</a><a href="about.html" class="active" data-lang-ru>О проекте</a>
    <a href="roadmap.html" data-lang-en>Roadmap</a><a href="roadmap.html" data-lang-ru>Дорожная карта</a>
    <a href="donate.html" data-lang-en>Support</a><a href="donate.html" data-lang-ru>Поддержать</a>
    <div class="lang"><button data-set-lang="en">EN</button><button data-set-lang="ru">RU</button></div>
  </div>
</nav>'''

FOOTER = '''<footer>
  <div class="foot-inner">
    <div>
      <div class="foot-brand">PHANTOM</div>
      <div class="foot-meta">
        <span data-lang-en>Maintained by Willen LLC · Wyoming, USA<br>AGPL-3.0-or-later · No metadata, no compromise.</span>
        <span data-lang-ru>Проект Willen LLC · Вайоминг, США<br>AGPL-3.0-or-later · Без метаданных, без компромиссов.</span>
      </div>
    </div>
    <div class="foot-links">
      <a href="https://github.com/LiudvigVladislav/Phantom">GitHub</a>
      <a href="https://codeberg.org/LiudvigVladislav/Phantom">Codeberg</a>
      <a href="donate.html" data-lang-en>Donate</a><a href="donate.html" data-lang-ru>Поддержать</a>
      <a href="index.html" data-lang-en>Home</a><a href="index.html" data-lang-ru>Главная</a>
    </div>
  </div>
</footer>'''

HEAD = '''<!DOCTYPE html>
<html data-lang="en" lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title data-en="About — PHANTOM" data-ru="О проекте — PHANTOM">About — PHANTOM</title>
<meta name="description" content="Why PHANTOM is being built: private, censorship-resistant communication that should answer to no one but its users.">
<meta name="robots" content="index,follow">
<link rel="icon" type="image/jpeg" href="static/phantom-logo.jpg">
<link rel="stylesheet" href="styles.css">
<script src="site.js"></script>
</head>'''

PAGE_CSS = '''<style>
.about-hero{position:relative;z-index:2;padding:150px 0 20px;text-align:center}
.about-hero .mark{position:relative;width:120px;height:120px;margin:0 auto 26px;display:flex;align-items:center;justify-content:center}
.about-hero .mark img{width:78px;height:78px;border-radius:19px;position:relative;z-index:3;box-shadow:0 0 40px rgba(123,107,240,0.38)}
.about-hero .mark .r{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);border:1px solid rgba(0,212,255,0.35);border-radius:50%;animation:hr-pulse 3.4s ease-out infinite}
.about-hero .mark .r:nth-child(1){width:86px;height:86px;animation-delay:0s}
.about-hero .mark .r:nth-child(2){width:108px;height:108px;animation-delay:.6s}
.about-hero .mark .r:nth-child(3){width:130px;height:130px;animation-delay:1.2s}
@keyframes hr-pulse{0%{opacity:0.5;transform:translate(-50%,-50%) scale(0.8)}70%{opacity:0}100%{opacity:0;transform:translate(-50%,-50%) scale(1.25)}}
.about-hero h1{font-family:var(--geist);font-weight:500;font-size:clamp(1.9rem,4.6vw,3rem);letter-spacing:-0.02em;margin-bottom:14px}
.about-hero .tag{color:var(--ts);font-family:var(--mono);font-size:0.8rem;letter-spacing:0.12em}
.about-body{position:relative;z-index:2;max-width:760px;margin:0 auto;padding:70px 40px 30px}
.about-block{margin-bottom:64px}
.about-block .label{margin-bottom:18px}
.about-block h2{font-family:var(--geist);font-weight:500;font-size:clamp(1.4rem,2.8vw,1.9rem);letter-spacing:-0.01em;margin-bottom:20px;line-height:1.2}
.about-block p{color:var(--ts);font-size:1rem;line-height:1.75;margin-bottom:16px}
.about-block p strong{color:var(--tp);font-weight:500}
.lede p{font-size:1.12rem;line-height:1.7;color:var(--tp)}
.principles{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:14px;margin-top:30px}
.principle{border:1px solid var(--border-sub);border-radius:11px;padding:22px;background:var(--surface-deep)}
.principle .pi{color:var(--cyan);margin-bottom:12px}
.principle h4{font-family:var(--geist);font-weight:500;font-size:0.92rem;margin-bottom:6px}
.principle p{font-size:0.82rem;line-height:1.55;margin:0}
.about-cta{text-align:center;padding:30px 0 20px}
@media(max-width:680px){.about-body{padding:54px 22px 20px}}
@media(prefers-reduced-motion:reduce){.about-hero .mark .r{animation:none;opacity:0.3}}
</style>'''

HERO = '''<header class="about-hero">
  <div class="wrap">
    <div class="mark"><span class="r"></span><span class="r"></span><span class="r"></span><img src="static/phantom-logo.jpg" alt="PHANTOM"></div>
    <h1 data-lang-en>About PHANTOM</h1><h1 data-lang-ru>О проекте PHANTOM</h1>
    <div class="tag" data-lang-en>The right to communicate, by design</div>
    <div class="tag" data-lang-ru>Право на общение — заложено в основу</div>
  </div>
</header>'''

BODY = '''<div class="about-body">
  <div class="about-block lede">
    <div class="label" data-lang-en>Mission</div><div class="label" data-lang-ru>Миссия</div>
    <p data-lang-en>Communication has become constant and essential — and at the same time increasingly restricted, filtered, and watched. PHANTOM is being built so that people can stay in contact and speak freely regardless of circumstances, limitations, or borders. Access to private communication should never depend on permission.</p>
    <p data-lang-ru>Общение стало постоянным и необходимым — и одновременно всё более ограниченным, фильтруемым и поднадзорным. PHANTOM создаётся для того, чтобы люди могли оставаться на связи и свободно общаться независимо от обстоятельств, ограничений и границ. Доступ к приватному общению не должен зависеть от чьего-либо разрешения.</p>
  </div>

  <div class="about-block">
    <div class="label" data-lang-en>How it's built</div><div class="label" data-lang-ru>Как это устроено</div>
    <h2 data-lang-en>Built assuming the network is hostile.</h2>
    <h2 data-lang-ru>Построен из допущения, что сеть враждебна.</h2>
    <p data-lang-en>Messages are end-to-end encrypted with a custom Double Ratchet implementation over libsodium, using <strong>ED25519 identity keys generated on your device</strong>. A sealed-sender design keeps the relay from learning who is talking to whom.</p>
    <p data-lang-ru>Сообщения защищены сквозным шифрованием — собственной реализацией Double Ratchet поверх libsodium, с <strong>ключами личности ED25519, которые генерируются на устройстве</strong>. Технология sealed sender не даёт серверу-ретранслятору узнать, кто с кем общается.</p>
    <p data-lang-en>To stay reachable under censorship, the app routes through several transports — including traffic that is indistinguishable from ordinary HTTPS — and falls back automatically when a route is blocked. Everything is open-source under <strong>AGPL-3.0</strong>.</p>
    <p data-lang-ru>Чтобы оставаться доступным под цензурой, приложение использует несколько транспортов — включая трафик, неотличимый от обычного HTTPS, — и автоматически переключается, когда маршрут блокируется. Весь код открыт под лицензией <strong>AGPL-3.0</strong>.</p>
    <div class="principles">
      <div class="principle">
        <div class="pi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg></div>
        <h4 data-lang-en>On-device keys</h4><h4 data-lang-ru>Ключи на устройстве</h4>
        <p data-lang-en>Identity keys never leave your phone.</p><p data-lang-ru>Ключи личности не покидают телефон.</p>
      </div>
      <div class="principle">
        <div class="pi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 2L3 6v6c0 5 3.5 8 9 10 5.5-2 9-5 9-10V6z"/></svg></div>
        <h4 data-lang-en>No metadata graph</h4><h4 data-lang-ru>Без графа метаданных</h4>
        <p data-lang-en>Sealed sender hides who talks to whom.</p><p data-lang-ru>Sealed sender скрывает, кто с кем общается.</p>
      </div>
      <div class="principle">
        <div class="pi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="4"/><circle cx="12" cy="12" r="9" opacity="0.5"/></svg></div>
        <h4 data-lang-en>Routes around blocks</h4><h4 data-lang-ru>Обходит блокировки</h4>
        <p data-lang-en>Multiple transports, automatic fallback.</p><p data-lang-ru>Несколько транспортов, авто-переключение.</p>
      </div>
      <div class="principle">
        <div class="pi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M16 18l6-6-6-6M8 6l-6 6 6 6"/></svg></div>
        <h4 data-lang-en>Open & auditable</h4><h4 data-lang-ru>Открыт и проверяем</h4>
        <p data-lang-en>AGPL-3.0. No hidden components.</p><p data-lang-ru>AGPL-3.0. Без скрытых компонентов.</p>
      </div>
    </div>
  </div>

  <div class="about-block">
    <div class="label" data-lang-en>Who builds it</div><div class="label" data-lang-ru>Кто его делает</div>
    <h2 data-lang-en>One developer. No investors. No compromise.</h2>
    <h2 data-lang-ru>Один разработчик. Без инвесторов. Без компромиссов.</h2>
    <p data-lang-en>PHANTOM is built by a single independent developer, operating through <strong>Willen LLC (Wyoming, USA)</strong>. It is not a startup chasing growth and takes no venture funding.</p>
    <p data-lang-ru>PHANTOM делает один независимый разработчик под <strong>Willen LLC (Вайоминг, США)</strong>. Это не стартап в погоне за ростом, и проект не привлекает венчурных денег.</p>
    <p data-lang-en>The project is driven by a simple conviction: that private, censorship-resistant communication is infrastructure people deserve to have — and that it should be open, auditable, and answerable to no one but its users.</p>
    <p data-lang-ru>Им движет простое убеждение: приватное, устойчивое к цензуре общение — это инфраструктура, которую люди заслуживают иметь, и она должна быть открытой, проверяемой и подотчётной только своим пользователям.</p>
  </div>

  <div class="about-cta">
    <a href="roadmap.html" class="btn btn-ghost" data-lang-en>See the roadmap →</a><a href="roadmap.html" class="btn btn-ghost" data-lang-ru>Дорожная карта →</a>
    <a href="donate.html" class="btn btn-primary" style="margin-left:10px" data-lang-en>Support the project</a><a href="donate.html" class="btn btn-primary" style="margin-left:10px" data-lang-ru>Поддержать проект</a>
  </div>
</div>'''

html = HEAD + PAGE_CSS + '\n<body>\n<div class="atmos"></div>\n<div class="grid-bg"></div>\n' + NAV + HERO + BODY + FOOTER + '\n</body>\n</html>'
def add_reveal(h):
    for t in ['class="about-block lede"','class="about-block"','class="principle"']:
        h = h.replace(t, t[:-1] + ' reveal"')
    return h
html = add_reveal(html)
with open('/home/claude/site_build/about.html','w') as f:
    f.write(html)
print('about.html written:', len(html), 'bytes')
