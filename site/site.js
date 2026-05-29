// PHANTOM site — language + nav
(function(){
  // Determine language: saved choice > browser language > default 'en'
  function pickLang(){
    try{
      var saved = localStorage.getItem('phantom_lang');
      if(saved === 'en' || saved === 'ru') return saved;
    }catch(e){}
    var nav = (navigator.language || navigator.userLanguage || 'en').toLowerCase();
    return nav.indexOf('ru') === 0 ? 'ru' : 'en';
  }
  function applyLang(lang){
    document.documentElement.setAttribute('data-lang', lang);
    document.documentElement.setAttribute('lang', lang);
    try{ localStorage.setItem('phantom_lang', lang); }catch(e){}
    // update toggle buttons
    document.querySelectorAll('.lang button').forEach(function(b){
      b.classList.toggle('active', b.getAttribute('data-set-lang') === lang);
    });
    // update <title> if alt provided
    var t = document.querySelector('title');
    if(t){
      var alt = t.getAttribute('data-'+lang);
      if(alt) document.title = alt;
    }
  }
  // expose
  window.setLang = function(lang){ applyLang(lang); };
  // init ASAP
  applyLang(pickLang());
  document.addEventListener('DOMContentLoaded', function(){
    applyLang(document.documentElement.getAttribute('data-lang') || pickLang());
    // wire toggle
    document.querySelectorAll('.lang button').forEach(function(b){
      b.addEventListener('click', function(){ applyLang(b.getAttribute('data-set-lang')); });
    });
    // mobile menu
    var mb = document.querySelector('.menu-btn');
    var links = document.querySelector('.nav-links');
    if(mb && links){
      mb.addEventListener('click', function(){ links.classList.toggle('open'); });
      links.querySelectorAll('a').forEach(function(a){
        a.addEventListener('click', function(){ links.classList.remove('open'); });
      });
    }

    // scroll reveal — respects reduced-motion
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var revealEls = document.querySelectorAll('.reveal');
    if(reduce || !('IntersectionObserver' in window)){
      revealEls.forEach(function(el){ el.classList.add('in'); });
    } else {
      var io = new IntersectionObserver(function(entries){
        entries.forEach(function(e){
          if(e.isIntersecting){
            var d = e.target.getAttribute('data-delay');
            if(d){ e.target.style.transitionDelay = d + 'ms'; }
            e.target.classList.add('in');
            io.unobserve(e.target);
          }
        });
      }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });
      revealEls.forEach(function(el){ io.observe(el); });
      // safety net: force-reveal anything still hidden after 2s (covers edge cases)
      setTimeout(function(){
        revealEls.forEach(function(el){ if(!el.classList.contains('in')) el.classList.add('in'); });
      }, 2000);
    }

    // copy-to-clipboard for donation addresses
    document.querySelectorAll('[data-copy]').forEach(function(btn){
      btn.addEventListener('click', function(){
        var val = btn.getAttribute('data-copy');
        var done = function(){
          var lang = document.documentElement.getAttribute('data-lang') || 'en';
          var prev = btn.getAttribute('data-label-' + lang) || btn.textContent;
          btn.classList.add('copied');
          btn.textContent = (lang === 'ru') ? 'Скопировано ✓' : 'Copied ✓';
          setTimeout(function(){
            btn.classList.remove('copied');
            btn.textContent = btn.getAttribute('data-label-' + (document.documentElement.getAttribute('data-lang')||'en')) || prev;
          }, 1600);
        };
        if(navigator.clipboard && navigator.clipboard.writeText){
          navigator.clipboard.writeText(val).then(done).catch(function(){ fallbackCopy(val, done); });
        } else { fallbackCopy(val, done); }
      });
    });
    function fallbackCopy(text, cb){
      var ta = document.createElement('textarea');
      ta.value = text; ta.style.position='fixed'; ta.style.opacity='0';
      document.body.appendChild(ta); ta.select();
      try{ document.execCommand('copy'); }catch(e){}
      document.body.removeChild(ta); if(cb) cb();
    }
  });
})();
