// PHANTOM site — nav + interactions
//
// Reference copy of the JS inlined into each of the 8 HTML pages
// (/ /about.html /roadmap.html /donate.html + /ru/ mirror).
//
// This file is NOT loaded at runtime — production reads the inlined <script>
// block inside each HTML page. This copy is kept as a readable single-source
// for maintenance: when you change interaction logic, edit here first, then
// copy-paste into each of the 8 HTML pages' inline <script> block.
//
// Language switching lives in real <a> links (see .lang in nav), not JS.
// The whole "auto-detect + localStorage + toggle" model was removed on
// 2026-07-15 when the site switched to a /ru/ URL tree (Phase 3 SEO work).

(function(){
  document.addEventListener('DOMContentLoaded', function(){
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
      // safety net: force-reveal anything still hidden after 2s
      setTimeout(function(){
        revealEls.forEach(function(el){ if(!el.classList.contains('in')) el.classList.add('in'); });
      }, 2000);
    }

    // copy-to-clipboard for donation addresses
    var lang = document.documentElement.lang || 'en';
    document.querySelectorAll('[data-copy]').forEach(function(btn){
      var original = btn.textContent;
      btn.addEventListener('click', function(){
        var val = btn.getAttribute('data-copy');
        var done = function(){
          btn.classList.add('copied');
          btn.textContent = (lang === 'ru') ? 'Скопировано ✓' : 'Copied ✓';
          setTimeout(function(){
            btn.classList.remove('copied');
            btn.textContent = original;
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
