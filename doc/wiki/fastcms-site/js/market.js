(function(){
  "use strict";
  var hints = {
    ai: '技能包与组件包：让 AI 懂你的行业与审美',
    wx: '公众号 / 小程序 / 微信支付：把站点接进微信生态',
    money: '付费下载 / 拼团 / 佣金：让内容产生收益',
    content: '文章与页面的增强能力：让内容站持续生长',
    infra: '多站点 / 对象存储 / 登录与安全：站点地基'
  };
  var tabBtns = document.querySelectorAll('.tab-btn');
  if(!tabBtns.length) return;
  var hint = document.getElementById('tabHint');
  function activate(btn, pushHash){
    var key = btn.getAttribute('data-tab');
    tabBtns.forEach(function(b){ b.classList.toggle('active', b === btn); });
    document.querySelectorAll('.mkt-panel').forEach(function(p){
      p.classList.toggle('active', p.getAttribute('data-panel') === key);
    });
    if(hint) hint.textContent = hints[key] || '';
    if(pushHash && history.replaceState) history.replaceState(null, '', '#' + key);
    applyFilter();
  }
  tabBtns.forEach(function(btn){
    btn.addEventListener('click', function(){ activate(btn, true); });
  });
  /* 搜索过滤（仅 plugins.html 有输入框） */
  var search = document.getElementById('mktSearch'), count = document.getElementById('mktCount');
  function applyFilter(){
    if(!search) return;
    var q = search.value.trim().toLowerCase();
    var panel = document.querySelector('.mkt-panel.active');
    if(!panel) return;
    var n = 0;
    panel.querySelectorAll('.plugin').forEach(function(c){
      var hit = !q || c.textContent.toLowerCase().indexOf(q) !== -1;
      c.style.display = hit ? '' : 'none';
      if(hit) n++;
    });
    if(count) count.textContent = q ? n + ' 个结果' : '';
  }
  if(search) search.addEventListener('input', applyFilter);
  /* 初始 hash 定位（如 plugins.html#wx） */
  var h = (location.hash || '').slice(1);
  if(h && hints.hasOwnProperty(h)){
    var btn = document.querySelector('.tab-btn[data-tab="' + h + '"]');
    if(btn) activate(btn, false);
  }
})();
