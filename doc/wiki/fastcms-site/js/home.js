(function(){
  "use strict";
  if(!document.getElementById('chatBody')) return;
  var steps = [
    { who:'user', html:'帮我生成一个科技公司官网，蓝白风格，要有产品、新闻和联系我们' },
    { who:'ai', html:'<span class="tick">✓</span>已生成整套站点模板：首页 · 产品列表 · 新闻详情 · 关于我们 · 联系我们，含响应式布局与 SEO 字段。' },
    { who:'ai', html:'<span class="tick">✓</span>已同步生成 FreeMarker 模板与静态化规则。' , site:true },
    { who:'ai', html:'<span class="dim">预览地址已就绪，可在「模板 → 在线预览」中查看效果，随时可回退到上一版。</span>' }
  ];
  var body = document.getElementById('chatBody'), playing = false;
  function buildSite(){
    var d = document.createElement('div');
    d.className = 'mini-site';
    d.innerHTML = '<div class="ms-top"><span class="ms-dot"></span><span class="ms-dot"></span><span class="ms-dot"></span></div>' +
      '<div class="ms-body"><div class="ms-line w40"></div><div class="ms-line w85"></div><div class="ms-line brand"></div></div>' +
      '<div class="ms-actions"><span class="ms-btn p">在线预览</span><span class="ms-btn o">回退上一版</span></div>';
    return d;
  }
  function play(){
    if(playing) return; playing = true;
    body.innerHTML = '';
    var i = 0;
    (function next(){
      if(i >= steps.length){ playing = false; return; }
      var s = steps[i], m = document.createElement('div');
      m.className = 'msg ' + s.who;
      m.innerHTML = s.html;
      if(s.site){ m.appendChild(buildSite()); }
      body.appendChild(m);
      requestAnimationFrame(function(){ m.classList.add('show'); });
      i++;
      setTimeout(next, 1500);
    })();
  }
  document.getElementById('replay').addEventListener('click', play);
  var started = false;
  new IntersectionObserver(function(es, obs){
    if(es[0].isIntersecting && !started){ started = true; play(); obs.disconnect(); }
  }, {threshold:.4}).observe(document.querySelector('.chat'));
})();
