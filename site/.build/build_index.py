#!/usr/bin/env python3
# Builds index.html

NAV = '''<nav>
  <a href="index.html" class="nav-logo"><img src="static/phantom-logo.jpg" alt=""><span>PHANTOM</span></a>
  <button class="menu-btn" aria-label="Menu"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12h18M3 6h18M3 18h18"/></svg></button>
  <div class="nav-links">
    <a href="index.html" class="active" data-lang-en>Home</a><a href="index.html" class="active" data-lang-ru>Главная</a>
    <a href="about.html" data-lang-en>About</a><a href="about.html" data-lang-ru>О проекте</a>
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
      <a href="https://codeberg.org/VladislavLiudvig/Phantom">Codeberg</a>
      <a href="donate.html" data-lang-en>Donate</a><a href="donate.html" data-lang-ru>Поддержать</a>
      <a href="about.html" data-lang-en>About</a><a href="about.html" data-lang-ru>О проекте</a>
    </div>
  </div>
</footer>'''

HEAD = '''<!DOCTYPE html>
<html data-lang="en" lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title data-en="PHANTOM — Encrypted messenger that survives state-level surveillance" data-ru="PHANTOM — Зашифрованный мессенджер против государственной слежки">PHANTOM</title>
<meta name="description" content="Open-source, end-to-end encrypted Android messenger built to work on hostile mobile networks. Production-validated through Russia's TSPU.">
<meta name="robots" content="index,follow">
<link rel="icon" type="image/jpeg" href="static/phantom-logo.jpg">
<link rel="stylesheet" href="styles.css">
<script src="site.js"></script>
</head>
<body>
<div class="atmos"></div>
<div class="grid-bg"></div>
'''

# ---- HERO ----
HERO = '''<header class="hero-main">
  <div class="wrap">
    <div class="hero-mark">
      <span class="hr-ring"></span><span class="hr-ring"></span><span class="hr-ring"></span>
      <img src="static/phantom-logo.jpg" alt="PHANTOM logo" class="hr-logo">
    </div>
    <div class="hero-word">PHANTOM</div>
    <div class="badge-row">
      <span class="pill"><span style="color:var(--success)">●</span> Alpha 2 · Android</span>
      <span class="pill">AGPL-3.0</span>
      <span class="pill" data-lang-en>No phone number</span><span class="pill" data-lang-ru>Без номера телефона</span>
      <span class="pill" data-lang-en>Validated vs TSPU DPI</span><span class="pill" data-lang-ru>Проверен против TSPU</span>
    </div>
    <h1 data-lang-en>An end-to-end encrypted messenger built to <span style="color:var(--cyan)">survive state-level surveillance</span> and network censorship.</h1>
    <h1 data-lang-ru>Мессенджер со сквозным шифрованием, который <span style="color:var(--cyan)">продолжает работать под государственной слежкой</span> и сетевой цензурой.</h1>
    <p data-lang-en class="hero-sub">Open-source. No phone number, no email, no metadata harvesting. Engineered to keep working on hostile mobile networks where other messengers are restricted.</p>
    <p data-lang-ru class="hero-sub">Открытый код. Без номера телефона, без email, без сбора метаданных. Спроектирован, чтобы работать во враждебных мобильных сетях, где другие мессенджеры ограничены.</p>
    <div class="hero-cta">
      <a href="donate.html" class="btn btn-primary" data-lang-en>Support the project</a><a href="donate.html" class="btn btn-primary" data-lang-ru>Поддержать проект</a>
      <a href="https://github.com/LiudvigVladislav/Phantom" class="btn btn-ghost" data-lang-en>View source ↗</a><a href="https://github.com/LiudvigVladislav/Phantom" class="btn btn-ghost" data-lang-ru>Исходный код ↗</a>
    </div>
  </div>
</header>'''

# ---- WHAT IT IS (phone + features) ----
WHAT = '''<section id="what">
  <div class="wrap">
    <div class="label" data-lang-en>What it is</div><div class="label" data-lang-ru>Что это</div>
    <h2 class="s-title" data-lang-en>Privacy that holds under pressure.</h2>
    <h2 class="s-title" data-lang-ru>Приватность, которая держится под давлением.</h2>
    <p class="s-intro" data-lang-en>PHANTOM is not another chat app with an encryption sticker. Every design decision assumes a hostile network and an adversary with state-level resources.</p>
    <p class="s-intro" data-lang-ru>PHANTOM — не очередной мессенджер с наклейкой «шифрование». Каждое решение здесь исходит из того, что сеть враждебна, а противник располагает ресурсами государства.</p>
    <div class="split">
      <div class="phone">
        <div class="phone-preview-bar">DESIGN PREVIEW</div>
        <div class="statusbar"><span class="t">9:41</span><span class="t">●●●</span></div>
        <div class="chat-head">
          <div class="av" style="background:#4853C2">MH</div>
          <div><div class="nm">Maya Hertzog</div><div class="st" style="display:block">● online</div></div>
        </div>
        <div class="enc-strip">🔒<span>End-to-end encrypted · ED25519 · @maya</span></div>
        <div class="thread">
          <div class="bub in" style="display:flex;flex-direction:column">Can you look over the contract draft? There's a clause on p.4 I'd like your read on.<span class="tm">14:18</span></div>
          <div class="bub out" style="display:flex;flex-direction:column">Section 4.2 — the liability clause — reads too broad. I'd flag it before signing.<span class="tm">14:20 ✓✓</span></div>
          <div class="bub in" style="display:flex;flex-direction:column">That's exactly what I was unsure about. Ask legal to revise?<span class="tm">14:21</span></div>
          <div class="bub out" style="display:flex;flex-direction:column">Yes. I'll draft a note this afternoon.<span class="tm">14:22 ✓✓</span></div>
        </div>
        <div class="composer"><div class="field" style="display:flex">Message</div><div class="send"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#04141a" stroke-width="2"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg></div></div>
      </div>
      <div class="flist">
        <div class="fitem">
          <div class="ic"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg></div>
          <div>
            <h3 data-lang-en>End-to-end encrypted</h3><h3 data-lang-ru>Сквозное шифрование</h3>
            <p data-lang-en>A custom Double Ratchet implementation over libsodium, with ED25519 identity keys generated on your device. The relay never sees plaintext.</p>
            <p data-lang-ru>Собственная реализация Double Ratchet поверх libsodium, ключи личности ED25519 генерируются на устройстве. Сервер-ретранслятор никогда не видит открытый текст.</p>
          </div>
        </div>
        <div class="fitem">
          <div class="ic"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="4"/><circle cx="12" cy="12" r="9" opacity="0.5"/></svg></div>
          <div>
            <h3 data-lang-en>Censorship-resistant</h3><h3 data-lang-ru>Устойчивость к цензуре</h3>
            <p data-lang-en>Multiple transport paths reach the network, including traffic that blends in with ordinary HTTPS. When one route is blocked, another takes over.</p>
            <p data-lang-ru>Несколько транспортных путей до сети, включая трафик, неотличимый от обычного HTTPS. Когда один маршрут блокируется, его подхватывает другой.</p>
          </div>
        </div>
        <div class="fitem">
          <div class="ic"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 2L3 6v6c0 5 3.5 8 9 10 5.5-2 9-5 9-10V6z"/></svg></div>
          <div>
            <h3 data-lang-en>No identity required</h3><h3 data-lang-ru>Без привязки к личности</h3>
            <p data-lang-en>No phone number, no email, no real name. Sealed-sender routing means the relay cannot build a graph of who talks to whom.</p>
            <p data-lang-ru>Без номера телефона, email и настоящего имени. Технология sealed sender не даёт серверу построить граф того, кто с кем общается.</p>
          </div>
        </div>
        <div class="fitem">
          <div class="ic"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M16 18l6-6-6-6M8 6l-6 6 6 6"/></svg></div>
          <div>
            <h3 data-lang-en>Fully open-source</h3><h3 data-lang-ru>Полностью открытый код</h3>
            <p data-lang-en>Licensed AGPL-3.0. Every line is auditable — no closed components, no hidden telemetry, no trust-us black boxes.</p>
            <p data-lang-ru>Лицензия AGPL-3.0. Каждую строку можно проверить — никаких закрытых компонентов, скрытой телеметрии и «чёрных ящиков».</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</section>'''

# ---- PRIVACY MODES (THREE — honest) ----
MODES = '''<section id="modes">
  <div class="wrap">
    <div class="label" data-lang-en>Privacy modes</div><div class="label" data-lang-ru>Режимы приватности</div>
    <h2 class="s-title" data-lang-en>Three levels of exposure. You choose.</h2>
    <h2 class="s-title" data-lang-ru>Три уровня приватности. Выбираешь ты.</h2>
    <p class="s-intro" data-lang-en>Each mode picks a different chain of transports to reach the network — trading latency for resistance. Ghost never silently downgrades: if it can't go through Tor, it fails visibly rather than exposing you.</p>
    <p class="s-intro" data-lang-ru>Каждый режим выбирает свою цепочку транспортов до сети — баланс между скоростью и устойчивостью. Ghost никогда не понижает уровень незаметно: если соединение через Tor невозможно, он явно сообщит об этом, а не раскроет вас.</p>
    <div class="modes">
      <div class="mode">
        <div class="mic"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></svg></div>
        <h4>Standard</h4>
        <p data-lang-en>Direct-first chain. Lowest latency for everyday use on open networks.</p>
        <p data-lang-ru>Цепочка Direct-first. Наименьшая задержка для повседневного использования в открытых сетях.</p>
      </div>
      <div class="mode">
        <div class="mic"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg></div>
        <h4 data-lang-en>Private</h4><h4 data-lang-ru>Private</h4>
        <p data-lang-en>Reality-first chain. Hides your source IP from the relay behind cover traffic.</p>
        <p data-lang-ru>Цепочка Reality-first. Скрывает ваш IP от сервера за маскирующим трафиком.</p>
      </div>
      <div class="mode">
        <div class="mic"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M6 21V10a6 6 0 0 1 12 0v11l-2-1.5-2 1.5-2-1.5-2 1.5-2-1.5z"/><circle cx="9.5" cy="11" r="0.8" fill="currentColor"/><circle cx="14.5" cy="11" r="0.8" fill="currentColor"/></svg></div>
        <h4>Ghost</h4>
        <p data-lang-en>Tor-only chain. Never silently downgrades — fails visibly if Tor can't bootstrap.</p>
        <p data-lang-ru>Только через Tor. Никогда не понижается незаметно — явно сообщит, если Tor не запустился.</p>
      </div>
    </div>
  </div>
</section>'''

# ---- TRANSPORT ----
TRANSPORT = '''<section id="transport" style="padding-top:10px">
  <div class="wrap">
    <div class="transport">
      <div class="transport-head">
        <h3 data-lang-en>Transport diversity</h3><h3 data-lang-ru>Разнообразие транспортов</h3>
        <span data-lang-en>SEVERAL WAYS TO REACH THE NETWORK</span><span data-lang-ru>НЕСКОЛЬКО ПУТЕЙ ДО СЕТИ</span>
      </div>
      <div class="paths">
        <div class="path"><div class="dot"></div><h4>Direct WSS</h4>
          <p data-lang-en>Encrypted WebSocket over TLS for normal conditions.</p>
          <p data-lang-ru>Шифрованный WebSocket поверх TLS для обычных условий.</p></div>
        <div class="path"><div class="dot"></div><h4>REALITY / Xray</h4>
          <p data-lang-en>Traffic indistinguishable from ordinary TLS. Validated through Russia's TSPU in production.</p>
          <p data-lang-ru>Трафик, неотличимый от обычного TLS. Проверен против российской TSPU в production.</p></div>
        <div class="path"><div class="dot" style="background:var(--warn);box-shadow:0 0 8px var(--warn)"></div><h4>Tor (v3 onion)</h4>
          <p data-lang-en>Text-only emergency fallback for hostile networks. Does not carry calls.</p>
          <p data-lang-ru>Аварийный резерв только для текста во враждебных сетях. Звонки не передаёт.</p></div>
        <div class="path"><div class="dot"></div><h4>REST fallback</h4>
          <p data-lang-en>Short-poll delivery when WebSocket frames are silently dropped.</p>
          <p data-lang-ru>Доставка через short-poll, когда WebSocket-кадры молча отбрасываются.</p></div>
        <div class="path future"><div class="dot"></div><h4 data-lang-en>Offline mesh</h4><h4 data-lang-ru>Офлайн-меш</h4>
          <p data-lang-en>Peer-to-peer transport without infrastructure. Planned — not yet built.</p>
          <p data-lang-ru>P2P-транспорт без инфраструктуры. В планах — пока не реализован.</p></div>
      </div>
    </div>
  </div>
</section>'''

# ---- HONEST STATUS NOTE ----
STATUS = '''<section id="status" style="padding-top:10px">
  <div class="wrap">
    <div class="status-box">
      <div class="label" data-lang-en>Where the project stands</div><div class="label" data-lang-ru>На какой стадии проект</div>
      <p data-lang-en style="color:var(--ts);font-size:0.92rem;max-width:760px;line-height:1.7">PHANTOM is in active development (<strong style="color:var(--tp)">Alpha 2</strong>), Android for now. Text messaging is reliable across the multi-transport stack, and the censorship-circumvention transport has been validated against TSPU in production. Voice notes work; voice calls are experimental. Group chats are partial. iOS, channels, and offline mesh are in the next stage of development. We ship a release only when the claim has been tested on real devices.</p>
      <p data-lang-ru style="color:var(--ts);font-size:0.92rem;max-width:760px;line-height:1.7">PHANTOM находится в активной стадии разработки (<strong style="color:var(--tp)">Alpha 2</strong>). Пока проект реализован на платформе Android. Текстовые сообщения работают надёжно через мульти-транспортный стек, а транспорт обхода цензуры проверен против TSPU в production. Голосовые сообщения работают; голосовые звонки — в экспериментальной версии. Групповые чаты разработаны частично. iOS, каналы и офлайн-меш находятся в следующей стадии разработки. Мы выпускаем релиз только когда заявленное протестировано на реальных устройствах.</p>
      <a href="roadmap.html" class="btn btn-ghost" data-lang-en>See the full roadmap →</a>
      <a href="roadmap.html" class="btn btn-ghost" data-lang-ru>Полная дорожная карта →</a>
    </div>
  </div>
</section>'''

# ---- SUPPORT CTA ----
SUPPORT = '''<section class="cta-band" id="support">
  <div class="wrap">
    <div class="label ctr" data-lang-en>Support</div><div class="label ctr" data-lang-ru>Поддержка</div>
    <h2 class="s-title" style="text-align:center" data-lang-en>If you'd like to help PHANTOM grow.</h2>
    <h2 class="s-title" style="text-align:center" data-lang-ru>Если вы хотите помочь проекту расти.</h2>
    <p data-lang-en style="color:var(--ts);max-width:540px;margin:0 auto 32px;text-align:center;font-size:0.94rem">PHANTOM takes no venture capital and sells no user data — it's built by one developer. If the project is something you'd like to support, any contribution helps it keep moving forward.</p>
    <p data-lang-ru style="color:var(--ts);max-width:540px;margin:0 auto 32px;text-align:center;font-size:0.94rem">PHANTOM не берёт венчурных денег и не продаёт данные пользователей — его делает один разработчик. Если вы хотели бы поддержать проект, любая помощь помогает ему двигаться дальше.</p>
    <div style="display:flex;gap:13px;flex-wrap:wrap;justify-content:center">
      <a href="donate.html" class="btn btn-primary" data-lang-en>Ways to donate</a><a href="donate.html" class="btn btn-primary" data-lang-ru>Способы поддержать</a>
      <a href="https://github.com/LiudvigVladislav/Phantom" class="btn btn-ghost" data-lang-en>Star on GitHub ↗</a><a href="https://github.com/LiudvigVladislav/Phantom" class="btn btn-ghost" data-lang-ru>Звезда на GitHub ↗</a>
    </div>
  </div>
</section>'''

PAGE_CSS = '''<style>
.pill{font-family:var(--mono);font-size:0.68rem;letter-spacing:0.05em;color:var(--ts);border:1px solid var(--border);border-radius:100px;padding:6px 14px;background:rgba(0,212,255,0.02)}
.split{display:grid;grid-template-columns:1fr 1fr;gap:56px;align-items:center;margin-top:10px}
@media(max-width:900px){.split{grid-template-columns:1fr;gap:44px}}
.phone{width:300px;max-width:100%;margin:0 auto;border-radius:38px;border:1px solid var(--border);background:var(--surface-deep);box-shadow:0 0 0 9px #05060a,0 28px 70px rgba(0,0,0,0.6),0 0 55px rgba(0,212,255,0.06);overflow:hidden;position:relative}
.phone-preview-bar{text-align:center;font-family:var(--mono);font-size:0.55rem;letter-spacing:0.18em;color:var(--cyan);background:rgba(0,212,255,0.08);border-bottom:1px solid rgba(0,212,255,0.18);padding:5px 0}
.statusbar{height:36px;display:flex;align-items:center;justify-content:space-between;padding:0 18px}
.statusbar .t{font-family:var(--mono);font-size:0.68rem;color:var(--tt)}
.chat-head{display:flex;align-items:center;gap:10px;padding:11px 16px;border-bottom:1px solid var(--border-sub)}
.av{width:36px;height:36px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-family:var(--inter);font-size:0.8rem;font-weight:600;color:#fff;flex-shrink:0}
.chat-head .nm{font-family:var(--inter);font-size:0.85rem;font-weight:600}
.chat-head .st{font-family:var(--mono);font-size:0.58rem;color:var(--success)}
.enc-strip{height:29px;display:flex;align-items:center;justify-content:center;gap:6px;border-bottom:1px solid var(--border-sub)}
.enc-strip span{font-family:var(--mono);font-size:0.56rem;color:var(--tt);opacity:0.75}
.thread{padding:13px 13px 0;height:312px;overflow:hidden;display:flex;flex-direction:column;gap:5px;background:var(--surface)}
.bub{max-width:80%;padding:8px 12px;font-family:var(--inter);font-size:0.76rem;line-height:1.4}
.bub .tm{display:block;font-family:var(--mono);font-size:0.52rem;margin-top:3px;opacity:0.5}
.bub.in{align-self:flex-start;background:var(--elev);border:1px solid var(--border-sub);border-radius:12px 12px 12px 2px;color:var(--tp)}
.bub.in .tm{color:var(--tt)}
.bub.out{align-self:flex-end;background:var(--cyan);border-radius:12px 12px 2px 12px;color:#04141a}
.bub.out .tm{color:#04141a;opacity:0.6}
.composer{display:flex;align-items:center;gap:10px;padding:11px 13px;border-top:1px solid var(--border-sub);background:var(--elev)}
.composer .field{flex:1;height:32px;border-radius:18px;background:var(--surface);border:1px solid var(--border-sub);display:flex;align-items:center;padding:0 14px;font-family:var(--inter);font-size:0.72rem;color:var(--tt)}
.composer .send{width:32px;height:32px;border-radius:50%;background:var(--cyan);display:flex;align-items:center;justify-content:center;flex-shrink:0;box-shadow:0 0 14px rgba(0,212,255,0.3)}
.flist{display:flex;flex-direction:column;gap:24px}
.fitem{display:flex;gap:15px}
.fitem .ic{width:40px;height:40px;border-radius:10px;border:1px solid var(--border);display:flex;align-items:center;justify-content:center;color:var(--cyan);flex-shrink:0;background:rgba(0,212,255,0.03)}
.fitem h3{font-family:var(--geist);font-weight:500;font-size:1rem;margin-bottom:5px}
.fitem p{color:var(--ts);font-size:0.85rem;line-height:1.55}
.modes{display:grid;grid-template-columns:repeat(3,1fr);gap:1px;background:var(--border-sub);border:1px solid var(--border-sub);border-radius:14px;overflow:hidden}
@media(max-width:760px){.modes{grid-template-columns:1fr}}
.mode{background:var(--elev);padding:30px 24px;transition:background .25s}
.mode:hover{background:var(--hover)}
.mode .mic{width:44px;height:44px;border-radius:11px;border:1px solid var(--border);display:flex;align-items:center;justify-content:center;color:var(--cyan);margin-bottom:18px}
.mode h4{font-family:var(--geist);font-weight:500;font-size:1rem;margin-bottom:8px}
.mode p{color:var(--ts);font-size:0.84rem;line-height:1.5}
.transport{border:1px solid var(--border-sub);border-radius:14px;background:var(--surface-deep);padding:42px 36px}
.transport-head{display:flex;align-items:baseline;justify-content:space-between;flex-wrap:wrap;gap:14px;margin-bottom:28px}
.transport-head h3{font-family:var(--geist);font-weight:500;font-size:1.2rem}
.transport-head span{font-family:var(--mono);color:var(--tt);font-size:0.7rem;letter-spacing:0.08em}
.paths{display:grid;grid-template-columns:repeat(auto-fit,minmax(165px,1fr));gap:15px}
.path{border:1px solid var(--border-sub);border-radius:10px;padding:18px;background:var(--surface)}
.path .dot{width:7px;height:7px;border-radius:50%;background:var(--success);box-shadow:0 0 8px var(--success);margin-bottom:11px}
.path h4{font-family:var(--geist);font-weight:500;font-size:0.87rem;margin-bottom:5px}
.path p{color:var(--ts);font-size:0.73rem;line-height:1.5}
.path.future{opacity:0.5}.path.future .dot{background:var(--tt);box-shadow:none}
.status-box{border:1px solid var(--border-sub);border-radius:14px;background:linear-gradient(180deg,var(--surface-deep),var(--surface));padding:42px 36px}
.cta-band{background:radial-gradient(ellipse 55% 75% at 50% 50%,rgba(0,212,255,0.06),transparent 70%)}
/* HERO main */
.hero-main{position:relative;z-index:2;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:130px 0 80px}
.hero-main .wrap{display:flex;flex-direction:column;align-items:center}
.hero-mark{position:relative;width:180px;height:180px;display:flex;align-items:center;justify-content:center;margin-bottom:8px;animation:fade 1s ease both}
.hero-mark .hr-logo{width:108px;height:108px;border-radius:26px;position:relative;z-index:3;box-shadow:0 0 50px rgba(123,107,240,0.4)}
.hr-ring{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);border:1px solid rgba(0,212,255,0.4);border-radius:50%;animation:hr-pulse 3.4s ease-out infinite}
.hr-ring:nth-child(1){width:120px;height:120px;animation-delay:0s}
.hr-ring:nth-child(2){width:150px;height:150px;animation-delay:0.5s}
.hr-ring:nth-child(3){width:180px;height:180px;animation-delay:1s}
@keyframes hr-pulse{0%{opacity:0.55;transform:translate(-50%,-50%) scale(0.8)}70%{opacity:0}100%{opacity:0;transform:translate(-50%,-50%) scale(1.25)}}
.hero-word{font-family:var(--geist);font-weight:500;letter-spacing:0.46em;text-indent:0.46em;font-size:clamp(1.8rem,4.6vw,2.9rem);color:var(--tp);margin-bottom:8px;animation:fade 1s ease .1s both;text-shadow:0 0 40px rgba(0,212,255,0.15)}
.hero-word::after{content:'';display:block;width:60px;height:1px;margin:18px auto 0;background:linear-gradient(90deg,transparent,var(--cyan),transparent);opacity:0.7}
.badge-row{display:flex;gap:9px;justify-content:center;margin:22px 0 26px;flex-wrap:wrap;animation:fade 1s ease .2s both}
.hero-main h1{font-family:var(--geist);font-weight:500;font-size:clamp(1.4rem,3.2vw,2.3rem);letter-spacing:-0.01em;line-height:1.24;max-width:780px;margin-bottom:20px;animation:fade 1s ease .3s both}
.hero-sub{color:var(--ts);font-size:0.96rem;max-width:560px;margin-bottom:38px;animation:fade 1s ease .4s both}
.hero-cta{display:flex;gap:13px;flex-wrap:wrap;justify-content:center;animation:fade 1s ease .5s both}
@keyframes fade{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:translateY(0)}}
/* status box buttons — avoid overlap */
.status-box .btn{margin-top:24px;display:none}
html[data-lang="en"] .status-box a.btn[data-lang-en]{display:inline-flex}
html[data-lang="ru"] .status-box a.btn[data-lang-ru]{display:inline-flex}
@media(prefers-reduced-motion:reduce){.hr-ring{animation:none;opacity:0.3}.hero-mark,.hero-word,.badge-row,.hero-main h1,.hero-sub,.hero-cta{animation:none}}
</style>'''

html = HEAD + PAGE_CSS + '</head><body>'.join([''])  # placeholder
# assemble
html = HEAD.replace('</head>','') + PAGE_CSS + '\n</head>\n<body>\n' + \
  '<div class="atmos"></div>\n<div class="grid-bg"></div>\n' + \
  NAV + HERO + WHAT + MODES + TRANSPORT + STATUS + SUPPORT + FOOTER + \
  '\n</body>\n</html>'

# bake in scroll-reveal classes on content blocks
def add_reveal(h):
    for t in ['class="feature"','class="mode"','class="path "','class="path"','class="fitem"','class="transport"','class="status-box"']:
        if t.endswith('"'):
            h = h.replace(t, t[:-1] + ' reveal"')
        else:
            h = h.replace(t, t.replace('class="', 'class="reveal '))
    return h
html = add_reveal(html)

with open('/home/claude/site_build/index.html','w') as f:
    f.write(html)
print('index.html written:', len(html), 'bytes')
