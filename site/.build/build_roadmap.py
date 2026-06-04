#!/usr/bin/env python3
# Builds roadmap.html

NAV = '''<nav>
  <a href="index.html" class="nav-logo"><img src="static/phantom-logo.jpg" alt=""><span>PHANTOM</span></a>
  <button class="menu-btn" aria-label="Menu"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12h18M3 6h18M3 18h18"/></svg></button>
  <div class="nav-links">
    <a href="index.html" data-lang-en>Home</a><a href="index.html" data-lang-ru>Главная</a>
    <a href="about.html" data-lang-en>About</a><a href="about.html" data-lang-ru>О проекте</a>
    <a href="roadmap.html" class="active" data-lang-en>Roadmap</a><a href="roadmap.html" class="active" data-lang-ru>Дорожная карта</a>
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
      <a href="https://codeberg.org/VladislavLiudvig/Phantom">Codeberg</a>
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
<title data-en="Roadmap — PHANTOM" data-ru="Дорожная карта — PHANTOM">Roadmap — PHANTOM</title>
<meta name="description" content="What is shipped, in progress, and planned in PHANTOM. We tag releases only when claims are tested on real devices.">
<meta name="robots" content="index,follow">
<link rel="icon" type="image/jpeg" href="static/phantom-logo.jpg">
<link rel="stylesheet" href="styles.css">
<script src="site.js"></script>
</head>'''

PAGE_CSS = '''<style>
.rm-hero{position:relative;z-index:2;padding:150px 0 10px;text-align:center}
.rm-hero h1{font-family:var(--geist);font-weight:500;font-size:clamp(1.9rem,4.6vw,3rem);letter-spacing:-0.02em;margin-bottom:14px}
.rm-hero p{color:var(--ts);max-width:600px;margin:0 auto;font-size:0.98rem;line-height:1.6}
.rm-note{position:relative;z-index:2;max-width:760px;margin:26px auto 0;padding:0 40px}
.rm-note .box{border:1px solid var(--border-sub);border-radius:11px;background:rgba(0,212,255,0.03);padding:16px 20px;display:flex;gap:12px;align-items:flex-start}
.rm-note .box svg{color:var(--cyan);flex-shrink:0;margin-top:2px}
.rm-note .box p{color:var(--ts);font-size:0.84rem;line-height:1.55;margin:0}
.rm-wrap{position:relative;z-index:2;max-width:1180px;margin:0 auto;padding:54px 40px 30px}
.rm-cols{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}
@media(max-width:900px){.rm-cols{grid-template-columns:1fr;gap:16px}}
.rm-col{border:1px solid var(--border-sub);border-radius:14px;background:var(--surface-deep);overflow:hidden}
.rm-col-head{padding:20px 22px;border-bottom:1px solid var(--border-sub);display:flex;align-items:center;gap:10px}
.rm-col-head .dot{width:9px;height:9px;border-radius:50%;flex-shrink:0}
.rm-col-head h3{font-family:var(--geist);font-weight:500;font-size:1.02rem}
.rm-col-head .ct{font-family:var(--mono);font-size:0.65rem;color:var(--tt);margin-left:auto}
.rm-col.done .dot{background:var(--success);box-shadow:0 0 8px var(--success)}
.rm-col.prog .dot{background:var(--cyan);box-shadow:0 0 8px var(--cyan)}
.rm-col.plan .dot{background:var(--tt)}
.rm-list{padding:10px 0}
.rm-item{padding:13px 22px;border-bottom:1px solid var(--border-sub);display:flex;gap:11px;align-items:flex-start}
.rm-item:last-child{border-bottom:none}
.rm-item .ic{flex-shrink:0;margin-top:1px;color:var(--tt)}
.rm-col.done .rm-item .ic{color:var(--success)}
.rm-col.prog .rm-item .ic{color:var(--cyan)}
.rm-item .txt h4{font-family:var(--inter);font-weight:600;font-size:0.86rem;margin-bottom:3px;color:var(--tp)}
.rm-item .txt p{font-family:var(--inter);font-size:0.76rem;line-height:1.5;color:var(--ts)}
.rm-blocker{position:relative;z-index:2;max-width:1180px;margin:0 auto;padding:10px 40px 30px}
.rm-blocker .box{border:1px solid var(--border-sub);border-radius:12px;background:rgba(0,212,255,0.03);padding:22px 24px}
.rm-blocker .box .label{margin-bottom:12px}
.rm-blocker .box p{color:var(--ts);font-size:0.88rem;line-height:1.6;max-width:780px}
.rm-blocker .box p strong{color:var(--tp);font-weight:500}
.rm-cta{text-align:center;padding:20px 0 10px}
@media(max-width:680px){.rm-wrap,.rm-blocker{padding-left:22px;padding-right:22px}.rm-note{padding:0 22px}}
</style>'''

HERO = '''<header class="rm-hero">
  <div class="wrap">
    <h1 data-lang-en>Roadmap</h1><h1 data-lang-ru>Дорожная карта</h1>
    <p data-lang-en>An honest view of what works today, what's being built, and what's ahead. We tag a release only when the claim has been tested on real devices.</p>
    <p data-lang-ru>Честная картина того, что работает сегодня, что в разработке и что впереди. Мы выпускаем релиз только когда заявленное протестировано на реальных устройствах.</p>
  </div>
</header>
<div class="rm-note">
  <div class="box">
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>
    <p data-lang-en>PHANTOM is in active <strong style="color:var(--tp)">Alpha 2</strong>, Android. This page reflects the real state of the code — not a marketing wishlist. Features are labelled by what actually works.</p>
    <p data-lang-ru>PHANTOM находится в активной стадии <strong style="color:var(--tp)">Alpha 2</strong>, Android. Эта страница отражает реальное состояние кода, а не маркетинговые обещания. Возможности отмечены по тому, что действительно работает.</p>
  </div>
</div>'''

# helper for items
def item(h_en, p_en, h_ru, p_ru, icon='check'):
    icons = {
      'check':'<path d="M20 6L9 17l-5-5"/>',
      'dot':'<circle cx="12" cy="12" r="4"/>',
      'arrow':'<path d="M5 12h14M13 6l6 6-6 6"/>',
    }
    sv = icons.get(icon, icons['check'])
    return f'''<div class="rm-item">
      <div class="ic"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">{sv}</svg></div>
      <div class="txt">
        <h4 data-lang-en>{h_en}</h4><h4 data-lang-ru>{h_ru}</h4>
        <p data-lang-en>{p_en}</p><p data-lang-ru>{p_ru}</p>
      </div>
    </div>'''

DONE = ''.join([
  item("End-to-end encryption","Double Ratchet over libsodium, ED25519 identity keys, X3DH session setup.",
       "Сквозное шифрование","Double Ratchet поверх libsodium, ключи ED25519, установка сессии X3DH."),
  item("Sealed sender","Every message and read receipt hides the sender from the relay.",
       "Sealed sender","Каждое сообщение и отметка о прочтении скрывают отправителя от сервера."),
  item("Text messaging (1:1)","Reliable across Direct WSS, REALITY and REST fallback. Idempotent delivery.",
       "Текстовые сообщения (1:1)","Надёжно через Direct WSS, REALITY и REST fallback. Защита от дублей."),
  item("REALITY transport","VLESS+REALITY validated through Russia's TSPU in production.",
       "Транспорт REALITY","VLESS+REALITY проверен против российской TSPU в production."),
  item("Voice notes","Encrypted media upload with chunked delivery.",
       "Голосовые сообщения","Шифрованная загрузка медиа с разбивкой на части."),
  item("Contact verification","Mutual ED25519 fingerprint check, with QR exchange.",
       "Проверка контактов","Взаимная сверка отпечатка ED25519, обмен по QR."),
  item("Production relay","Rust relay live in Helsinki, with Tor onion + WebTunnel bridge.",
       "Relay в production","Rust-relay работает в Хельсинки, с Tor onion и WebTunnel-мостом."),
  item("Security hardening","Multiple P1 cryptographic findings resolved: prekey wrap, ratchet state wrap, signed-challenge authentication.",
       "Усиление безопасности","Закрыты P1-находки по криптографии: оборачивание prekey, оборачивание состояния ratchet, подписанная аутентификация."),
])

PROG = ''.join([
  item("Android stabilization","Session-repair, chat-list lifecycle, reconnect reliability on hostile OEMs.",
       "Стабилизация Android","Восстановление сессий, жизненный цикл списка чатов, надёжность переподключения.", 'dot'),
  item("Group chats","Core group messaging through Double Ratchet + Sealed Sender. Hardening pending.",
       "Групповые чаты","Базовый групповой обмен через Double Ratchet + Sealed Sender. Усиление впереди.", 'dot'),
])

PLAN = ''.join([
  item("Voice calls","Experimental today; reliability work for restrictive mobile networks.",
       "Голосовые звонки","Сейчас экспериментальны; работа над надёжностью в ограничивающих сетях.", 'arrow'),
  item("iOS port","Shared core is iOS-ready — the remaining work is the native Swift shell.",
       "Порт на iOS","Общее ядро готово к iOS — остаётся нативная Swift-оболочка.", 'arrow'),
  item("Attachments","Photos and files via an encrypted media store.",
       "Вложения","Фото и файлы через шифрованное медиа-хранилище.", 'arrow'),
  item("Adaptive transport","Runtime transport selection and multi-server fan-out.",
       "Адаптивный транспорт","Выбор транспорта в реальном времени и распределение по серверам.", 'arrow'),
  item("Username directory","Discoverable usernames via the relay namespace.",
       "Каталог имён","Поиск по именам пользователей через пространство имён relay.", 'arrow'),
  item("Offline mesh","Peer-to-peer transport without infrastructure. Research stage.",
       "Офлайн-меш","P2P-транспорт без инфраструктуры. Стадия исследования.", 'arrow'),
])

COLS = f'''<div class="rm-wrap">
  <div class="rm-cols">
    <div class="rm-col done">
      <div class="rm-col-head"><span class="dot"></span><h3 data-lang-en>Shipped</h3><h3 data-lang-ru>Готово</h3></div>
      <div class="rm-list">{DONE}</div>
    </div>
    <div class="rm-col prog">
      <div class="rm-col-head"><span class="dot"></span><h3 data-lang-en>In progress</h3><h3 data-lang-ru>В работе</h3></div>
      <div class="rm-list">{PROG}</div>
    </div>
    <div class="rm-col plan">
      <div class="rm-col-head"><span class="dot"></span><h3 data-lang-en>Planned</h3><h3 data-lang-ru>В планах</h3></div>
      <div class="rm-list">{PLAN}</div>
    </div>
  </div>
</div>'''

BLOCKER = '''<div class="rm-blocker">
  <div class="box">
    <div class="label" data-lang-en>Why iOS comes next</div><div class="label" data-lang-ru>Почему iOS — следующий шаг</div>
    <p data-lang-en>The shared core was deliberately built for cross-platform reuse — one Double Ratchet, one storage schema, one transport layer. That means the <strong>iOS port is largely a matter of building the native Swift shell</strong> on top of a core that already works, rather than rewriting the cryptography. It's a priority for the next stage of development — and the kind of milestone your support helps reach sooner.</p>
    <p data-lang-ru>Общее ядро специально создано для повторного использования на разных платформах — один Double Ratchet, одна схема хранения, один транспортный слой. Это значит, что <strong>порт на iOS — во многом вопрос сборки нативной Swift-оболочки</strong> поверх уже работающего ядра, а не переписывания криптографии. Это приоритет следующего этапа разработки и та веха, которую ваша поддержка помогает достичь быстрее.</p>
  </div>
</div>
<div class="rm-cta">
  <a href="donate.html" class="btn btn-primary" data-lang-en>Support the project</a><a href="donate.html" class="btn btn-primary" data-lang-ru>Поддержать проект</a>
</div>'''

html = HEAD + PAGE_CSS + '\n<body>\n<div class="atmos"></div>\n<div class="grid-bg"></div>\n' + NAV + HERO + COLS + BLOCKER + FOOTER + '\n</body>\n</html>'
def add_reveal(h):
    for t in ['class="rm-col done"','class="rm-col prog"','class="rm-col plan"']:
        h = h.replace(t, t[:-1] + ' reveal"')
    # blocker box
    h = h.replace('<div class="box">\n    <div class="label" data-lang-en>Why iOS', '<div class="box reveal">\n    <div class="label" data-lang-en>Why iOS')
    return h
html = add_reveal(html)
with open('/home/claude/site_build/roadmap.html','w') as f:
    f.write(html)
print('roadmap.html written:', len(html), 'bytes')
