#!/usr/bin/env python3
# Builds donate.html

NAV = '''<nav>
  <a href="index.html" class="nav-logo"><img src="static/phantom-logo.jpg" alt=""><span>PHANTOM</span></a>
  <button class="menu-btn" aria-label="Menu"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12h18M3 6h18M3 18h18"/></svg></button>
  <div class="nav-links">
    <a href="index.html" data-lang-en>Home</a><a href="index.html" data-lang-ru>Главная</a>
    <a href="about.html" data-lang-en>About</a><a href="about.html" data-lang-ru>О проекте</a>
    <a href="roadmap.html" data-lang-en>Roadmap</a><a href="roadmap.html" data-lang-ru>Дорожная карта</a>
    <a href="donate.html" class="active" data-lang-en>Support</a><a href="donate.html" class="active" data-lang-ru>Поддержать</a>
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
      <a href="about.html" data-lang-en>About</a><a href="about.html" data-lang-ru>О проекте</a>
      <a href="index.html" data-lang-en>Home</a><a href="index.html" data-lang-ru>Главная</a>
    </div>
  </div>
</footer>'''

HEAD = '''<!DOCTYPE html>
<html data-lang="en" lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title data-en="Support — PHANTOM" data-ru="Поддержать — PHANTOM">Support — PHANTOM</title>
<meta name="description" content="Support PHANTOM's development. Recurring or one-time donations via Liberapay, Buy Me a Coffee, or cryptocurrency.">
<meta name="robots" content="index,follow">
<link rel="icon" type="image/jpeg" href="static/phantom-logo.jpg">
<link rel="stylesheet" href="styles.css">
<script src="site.js"></script>
</head>'''

PAGE_CSS = '''<style>
.dn-hero{position:relative;z-index:2;padding:150px 0 10px;text-align:center}
.dn-hero h1{font-family:var(--geist);font-weight:500;font-size:clamp(1.9rem,4.6vw,3rem);letter-spacing:-0.02em;margin-bottom:14px}
.dn-hero p{color:var(--ts);max-width:560px;margin:0 auto;font-size:0.98rem;line-height:1.6}
.dn-wrap{position:relative;z-index:2;max-width:920px;margin:0 auto;padding:50px 40px 20px}
/* where funds go */
.funds-head{text-align:center;margin-bottom:26px}
.funds-sub{color:var(--ts);font-size:0.92rem;max-width:520px;margin:0 auto}
.funds-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-bottom:54px}
@media(max-width:740px){.funds-grid{grid-template-columns:1fr}}
.fund-card{position:relative;border:1px solid var(--border-sub);border-radius:14px;background:linear-gradient(180deg,var(--elev),var(--surface-deep));padding:28px 24px;overflow:hidden}
.fund-card::before{content:'';position:absolute;top:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,rgba(0,212,255,0.5),transparent);opacity:0.6}
.fund-card .fc-ic{width:50px;height:50px;border-radius:13px;display:flex;align-items:center;justify-content:center;color:var(--cyan);background:rgba(0,212,255,0.07);border:1px solid rgba(0,212,255,0.2);margin-bottom:18px;box-shadow:0 0 22px rgba(0,212,255,0.08)}
.fund-card h4{font-family:var(--geist);font-weight:500;font-size:1.04rem;margin-bottom:8px}
.fund-card p{color:var(--ts);font-size:0.84rem;line-height:1.6}
/* methods */
.methods-label{font-family:var(--geist);font-weight:500;font-size:1.3rem;margin-bottom:6px;text-align:center}
.methods-sub{color:var(--ts);font-size:0.9rem;text-align:center;margin-bottom:34px}
.dn-cards{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:18px}
@media(max-width:640px){.dn-cards{grid-template-columns:1fr}}
.donate-card{border:1px solid var(--border-sub);border-radius:14px;background:var(--elev);padding:26px 24px;display:flex;flex-direction:column}
.dc-head{display:flex;align-items:center;gap:12px;margin-bottom:8px}
.dc-icon{width:42px;height:42px;border-radius:10px;display:flex;align-items:center;justify-content:center;flex-shrink:0}
.dc-icon.lp{background:#f6c915}.dc-icon.bmc{background:#ffdd00}
.dc-icon.btc{background:rgba(247,147,26,0.12);border:1px solid rgba(247,147,26,0.4)}
.dc-icon.xmr{background:rgba(255,102,0,0.1);border:1px solid rgba(255,102,0,0.4)}
.dc-icon.eth{background:rgba(98,126,234,0.12);border:1px solid rgba(98,126,234,0.4)}
.dc-title{font-family:var(--geist);font-weight:500;font-size:1.02rem}
.dc-meta{font-family:var(--mono);font-size:0.66rem;color:var(--tt);letter-spacing:0.04em}
.dc-desc{color:var(--ts);font-size:0.82rem;line-height:1.5;margin-bottom:18px;flex:1}
.dc-btn{font-family:var(--mono);font-weight:500;font-size:0.8rem;padding:12px 20px;border-radius:8px;text-decoration:none;text-align:center;background:var(--cyan);color:#04141a;border:1px solid var(--cyan);transition:all .25s;cursor:pointer}
.dc-btn:hover{background:var(--cyan-hover);box-shadow:0 0 24px rgba(0,212,255,0.25)}
.addr-row{display:flex;align-items:center;gap:8px;background:var(--surface-deep);border:1px solid var(--border-sub);border-radius:8px;padding:10px 12px;margin-top:auto}
.addr-row code{font-family:var(--mono);font-size:0.68rem;color:var(--ts);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
.copy-btn{font-family:var(--mono);font-size:0.68rem;color:var(--cyan);background:rgba(0,212,255,0.08);border:1px solid rgba(0,212,255,0.25);border-radius:6px;padding:6px 11px;cursor:pointer;flex-shrink:0;transition:all .2s}
.copy-btn:hover{background:rgba(0,212,255,0.16)}
.copy-btn.copied{background:var(--success);color:#04140a;border-color:var(--success)}
.crypto-note{text-align:center;color:var(--tt);font-size:0.78rem;line-height:1.5;max-width:560px;margin:24px auto 0}
.big-wire{border:1px solid var(--border-sub);border-radius:12px;background:var(--surface-deep);padding:22px 24px;margin-top:30px;text-align:center}
.big-wire p{color:var(--ts);font-size:0.84rem;line-height:1.6}
.big-wire a{color:var(--cyan);text-decoration:none}
@media(max-width:680px){.dn-wrap{padding:44px 22px 20px}.funds{padding:28px 22px}}
</style>'''

HERO = '''<header class="dn-hero">
  <div class="wrap">
    <h1 data-lang-en>Support PHANTOM</h1><h1 data-lang-ru>Поддержать PHANTOM</h1>
    <p data-lang-en>PHANTOM takes no venture capital and sells no user data. If you'd like to help the project grow, any contribution helps it keep moving forward.</p>
    <p data-lang-ru>PHANTOM не берёт венчурных денег и не продаёт данные пользователей. Если вы хотели бы помочь проекту расти, любая поддержка помогает ему двигаться дальше.</p>
  </div>
</header>'''

FUNDS = '''<div class="dn-wrap">
  <div class="funds-head reveal">
    <div class="label ctr" data-lang-en>Where support goes</div><div class="label ctr" data-lang-ru>Куда идёт поддержка</div>
    <p class="funds-sub" data-lang-en>Transparently — into the things that move the project forward.</p>
    <p class="funds-sub" data-lang-ru>Прозрачно — на то, что двигает проект вперёд.</p>
  </div>
  <div class="funds-grid">
    <div class="fund-card reveal">
      <div class="fc-ic"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="5" y="2" width="14" height="20" rx="2.5"/><path d="M10 18h4"/></svg></div>
      <h4 data-lang-en>iOS development</h4><h4 data-lang-ru>Разработка iOS</h4>
      <p data-lang-en>Bringing PHANTOM to iPhone — the next major platform on the roadmap.</p>
      <p data-lang-ru>PHANTOM на iPhone — следующая крупная платформа в дорожной карте.</p>
    </div>
    <div class="fund-card reveal" data-delay="100">
      <div class="fc-ic"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2" y="4" width="20" height="7" rx="2"/><rect x="2" y="14" width="20" height="6" rx="2"/><path d="M6 7.5h.01M6 17h.01"/></svg></div>
      <h4 data-lang-en>Infrastructure</h4><h4 data-lang-ru>Инфраструктура</h4>
      <p data-lang-en>Production relay, domain, TLS certificates, and censorship-resistant bridge nodes.</p>
      <p data-lang-ru>Production-relay, домен, TLS-сертификаты и мост-узлы для обхода цензуры.</p>
    </div>
    <div class="fund-card reveal" data-delay="200">
      <div class="fc-ic"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 2L3 6v6c0 5 3.5 8 9 10 5.5-2 9-5 9-10V6z"/><path d="M9 12l2 2 4-4"/></svg></div>
      <h4 data-lang-en>Security audit</h4><h4 data-lang-ru>Аудит безопасности</h4>
      <p data-lang-en>An independent external review of the cryptography before the v1.0 release.</p>
      <p data-lang-ru>Независимая внешняя проверка криптографии перед релизом v1.0.</p>
    </div>
  </div>

  <div class="methods-label reveal" data-lang-en>Ways to contribute</div><div class="methods-label reveal" data-lang-ru>Способы поддержки</div>
  <div class="methods-sub reveal" data-lang-en>Recurring, one-time, or cryptocurrency — whatever suits you.</div>
  <div class="methods-sub reveal" data-lang-ru>Регулярно, разово или криптовалютой — как вам удобнее.</div>

  <div class="dn-cards">
    <div class="donate-card reveal">
      <div class="dc-head">
        <div class="dc-icon lp"><svg width="22" height="22" viewBox="0 0 24 24" fill="#1a1a00"><path d="M8 4h5.5a4.5 4.5 0 0 1 0 9H11v7H8zm3 6h2.2a1.7 1.7 0 0 0 0-3.4H11z"/></svg></div>
        <div><div class="dc-title">Liberapay</div><div class="dc-meta" data-lang-en>RECURRING · 0% FEE</div><div class="dc-meta" data-lang-ru>РЕГУЛЯРНО · 0% КОМИССИЯ</div></div>
      </div>
      <p class="dc-desc" data-lang-en>Non-profit, open-source platform for recurring weekly or monthly support. Card or PayPal. Donor stays anonymous by default.</p>
      <p class="dc-desc" data-lang-ru>Некоммерческая платформа с открытым кодом для регулярной еженедельной или ежемесячной поддержки. Карта или PayPal. Донор по умолчанию анонимен.</p>
      <a href="https://liberapay.com/Phantom-messenger/" class="dc-btn" data-lang-en>Donate on Liberapay ↗</a><a href="https://liberapay.com/Phantom-messenger/" class="dc-btn" data-lang-ru>Поддержать на Liberapay ↗</a>
    </div>
    <div class="donate-card reveal" data-delay="80">
      <div class="dc-head">
        <div class="dc-icon bmc"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#7a5c00" stroke-width="1.8"><path d="M5 8h13l-1 11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2zM5 8l-.5-3M18 11h2a2 2 0 0 1 0 4h-1"/></svg></div>
        <div><div class="dc-title">Buy Me a Coffee</div><div class="dc-meta" data-lang-en>ONE-TIME · CARD</div><div class="dc-meta" data-lang-ru>РАЗОВО · КАРТА</div></div>
      </div>
      <p class="dc-desc" data-lang-en>Quick one-time support by card or PayPal. The simplest way to chip in if you don't want a recurring commitment.</p>
      <p class="dc-desc" data-lang-ru>Быстрая разовая поддержка картой или через PayPal. Самый простой способ помочь без регулярных обязательств.</p>
      <a href="https://buymeacoffee.com/phantompro" class="dc-btn" data-lang-en>Buy a coffee ↗</a><a href="https://buymeacoffee.com/phantompro" class="dc-btn" data-lang-ru>Купить кофе ↗</a>
    </div>
  </div>

  <div class="dn-cards">
    <div class="donate-card reveal">
      <div class="dc-head">
        <div class="dc-icon btc"><svg width="20" height="20" viewBox="0 0 24 24" fill="#f7931a"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm3.5 8.4c-.2 1-.9 1.4-1.9 1.5.9.3 1.4 1 1.2 2.1-.3 1.5-1.5 1.8-3.2 1.7l-.3 1.6-1-.2.3-1.5-.8-.2-.3 1.6-1-.2.3-1.6-2-.4.5-1.1s.7.2.7.1c.3.1.4-.1.5-.3l.9-3.6c0-.2 0-.4-.4-.5 0 0-.7-.1-.7-.1l.2-1 2 .4.3-1.5 1 .2-.3 1.5.8.2.3-1.5 1 .2-.3 1.5c1.4.3 2.4.7 2.2 2zm-1.9 2.9c.2-1.1-1.5-1.2-2.1-1.4l-.4 1.9c.6.1 2.2.5 2.5-.5zm.1-2.8c.2-1-1.2-1.1-1.7-1.2l-.4 1.7c.5.1 1.9.4 2.1-.5z"/></svg></div>
        <div><div class="dc-title">Bitcoin</div><div class="dc-meta">BTC · NATIVE SEGWIT</div></div>
      </div>
      <p class="dc-desc" data-lang-en>Native SegWit address. Self-custody wallet held by the maintainer.</p>
      <p class="dc-desc" data-lang-ru>Адрес Native SegWit. Кошелёк под самостоятельным управлением.</p>
      <div class="addr-row">
        <code>bc1qrkufy97gv88cz80fqak9f5q7vcjpvr547aru6j</code>
        <button class="copy-btn" data-copy="bc1qrkufy97gv88cz80fqak9f5q7vcjpvr547aru6j" data-label-en="Copy" data-label-ru="Копировать" data-lang-en>Copy</button>
        <button class="copy-btn" data-copy="bc1qrkufy97gv88cz80fqak9f5q7vcjpvr547aru6j" data-label-en="Copy" data-label-ru="Копировать" data-lang-ru>Копировать</button>
      </div>
    </div>
    <div class="donate-card reveal" data-delay="80">
      <div class="dc-head">
        <div class="dc-icon xmr"><svg width="20" height="20" viewBox="0 0 24 24" fill="#ff6600"><path d="M12 2a10 10 0 0 0-10 10c0 .7.1 1.4.2 2h3.3V7.4l4.5 4.5 4.5-4.5V16h3.3c.1-.6.2-1.3.2-2A10 10 0 0 0 12 2zM9.2 16H5.9l-.9 1.6A10 10 0 0 0 9 21V16zm5.6 0v5a10 10 0 0 0 4-3.4l-.9-1.6h-3.1z"/></svg></div>
        <div><div class="dc-title">Monero</div><div class="dc-meta" data-lang-en>XMR · PRIVATE</div><div class="dc-meta" data-lang-ru>XMR · ПРИВАТНО</div></div>
      </div>
      <p class="dc-desc" data-lang-en>For privacy-aligned support. Subaddress for public donations.</p>
      <p class="dc-desc" data-lang-ru>Для поддержки с упором на приватность. Субадрес для публичных донатов.</p>
      <div class="addr-row">
        <code>832MyHXv8KpZxL1Z…r9N7AzaU</code>
        <button class="copy-btn" data-copy="832MyHXv8KpZxL1ZAwkfTUEPc8ghnJbCxH1YHaXLy4Hw6oG1wfF9xa91XbpMyy7nKmfpzduv1FBie3JL5cZ7qz6r9N7AzaU" data-label-en="Copy" data-label-ru="Копировать" data-lang-en>Copy</button>
        <button class="copy-btn" data-copy="832MyHXv8KpZxL1ZAwkfTUEPc8ghnJbCxH1YHaXLy4Hw6oG1wfF9xa91XbpMyy7nKmfpzduv1FBie3JL5cZ7qz6r9N7AzaU" data-label-en="Copy" data-label-ru="Копировать" data-lang-ru>Копировать</button>
      </div>
    </div>
  </div>

  <div class="dn-cards" style="grid-template-columns:1fr;max-width:448px;margin:0 auto 18px">
    <div class="donate-card reveal">
      <div class="dc-head">
        <div class="dc-icon eth"><svg width="20" height="20" viewBox="0 0 24 24" fill="#627eea"><path d="M12 2L5 12.3l7 4 7-4zM5 13.6l7 8.4 7-8.4-7 4z"/></svg></div>
        <div><div class="dc-title">Ethereum</div><div class="dc-meta" data-lang-en>ETH · EVM NETWORKS</div><div class="dc-meta" data-lang-ru>ETH · СЕТИ EVM</div></div>
      </div>
      <p class="dc-desc" data-lang-en>Native ETH. Address is valid across EVM-compatible networks.</p>
      <p class="dc-desc" data-lang-ru>Нативный ETH. Адрес действителен во всех EVM-совместимых сетях.</p>
      <div class="addr-row">
        <code>0x9De87C1955a5772c2411D86504962eb581d852D5</code>
        <button class="copy-btn" data-copy="0x9De87C1955a5772c2411D86504962eb581d852D5" data-label-en="Copy" data-label-ru="Копировать" data-lang-en>Copy</button>
        <button class="copy-btn" data-copy="0x9De87C1955a5772c2411D86504962eb581d852D5" data-label-en="Copy" data-label-ru="Копировать" data-lang-ru>Копировать</button>
      </div>
    </div>
  </div>

  <p class="crypto-note" data-lang-en>Always verify the address after pasting. Cryptocurrency transactions cannot be reversed.</p>
  <p class="crypto-note" data-lang-ru>Всегда проверяйте адрес после вставки. Криптовалютные транзакции необратимы.</p>

  <div class="big-wire reveal">
    <p data-lang-en>For larger gifts or grant-related support, you can coordinate a bank transfer via <a href="mailto:[email protected]">[email protected]</a>.</p>
    <p data-lang-ru>Для крупных пожертвований или поддержки в рамках грантов можно согласовать банковский перевод через <a href="mailto:[email protected]">[email protected]</a>.</p>
  </div>
</div>'''

html = HEAD + PAGE_CSS + '\n<body>\n<div class="atmos"></div>\n<div class="grid-bg"></div>\n' + NAV + HERO + FUNDS + FOOTER + '\n</body>\n</html>'
with open('/home/claude/site_build/donate.html','w') as f:
    f.write(html)
print('donate.html written:', len(html), 'bytes')
