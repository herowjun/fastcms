(function(){
  "use strict";
  /* 档位选择（同步右卡与订单） */
  var plan = {price:499, per:"1 年"};
  var coupon = 0;
  document.querySelectorAll('#optList .buy-opt').forEach(function(opt){
    var radio = opt.querySelector('input');
    radio.addEventListener('change', function(){
      document.querySelectorAll('#optList .buy-opt').forEach(function(o){o.classList.remove('checked');});
      opt.classList.add('checked');
      plan = {price:+radio.dataset.price, per:radio.dataset.per};
      var strike = document.getElementById('priceStrike');
      if(radio.dataset.strike){ strike.textContent = radio.dataset.strike; strike.style.display='inline'; }
      else { strike.style.display='none'; }
      document.getElementById('priceNum').textContent = '¥'+plan.price.toLocaleString();
      document.getElementById('pricePer').textContent = '/ '+plan.per+' · 每站点';
      if(drawerOpen) renderConfirm();
    });
  });
  document.getElementById('btnCoupon').addEventListener('click', function(){
    var v = document.getElementById('fCoupon').value.trim().toLowerCase();
    coupon = (v === 'fastcms') ? 50 : 0;
    document.getElementById('couponOk').style.display = coupon ? 'block' : 'none';
    renderConfirm();
  });
  function total(){ return Math.max(0, plan.price - coupon); }
  function renderConfirm(){
    document.getElementById('ordSpec').textContent = plan.per+' · 每站点 · '+document.getElementById('fSite').value;
    document.getElementById('ordTotal').textContent = '¥'+total().toLocaleString();
    document.getElementById('ordSave').textContent = coupon ? '（已优惠 ¥'+coupon+'）' : '';
    var pb = document.getElementById('payBtnAmt');
    if(pb) pb.textContent = '¥'+total().toLocaleString();
  }
  document.getElementById('fSite').addEventListener('change', function(){ if(drawerOpen) renderConfirm(); });

  /* 抽屉开合 + 状态机 */
  var drawer = document.getElementById('drawer'), mask = document.getElementById('mask'),
      foot = document.getElementById('dwFoot'), title = document.getElementById('dwTitle'),
      drawerOpen = false, timerId = null, leftSec = 0;
  function setState(s, t){
    document.querySelectorAll('.dw-state').forEach(function(el){ el.classList.toggle('on', el.dataset.state===s); });
    title.textContent = t;
    if(s==='confirm'){ foot.style.display='grid'; }
    else if(s==='paying'){ foot.style.display='none'; startTimer(); drawQR(); }
    else if(s==='done'){ foot.style.display='grid'; }
    else { foot.style.display='grid'; }
  }
  function openDrawer(){ drawerOpen=true; mask.classList.add('show'); drawer.classList.add('show'); renderConfirm(); setState('confirm','确认订单'); }
  function closeDrawer(){ drawerOpen=false; mask.classList.remove('show'); drawer.classList.remove('show'); stopTimer(); }
  document.getElementById('btnOrder').addEventListener('click', openDrawer);
  document.getElementById('dwClose').addEventListener('click', closeDrawer);
  mask.addEventListener('click', closeDrawer);
  document.addEventListener('keydown', function(e){ if(e.key==='Escape' && drawerOpen) closeDrawer(); });

  /* 支付 */
  function genOrderNo(){ return 'FC'+Date.now().toString().slice(-10)+Math.floor(Math.random()*90+10); }
  var curOrd = genOrderNo();
  function payNow(){
    curOrd = genOrderNo();
    document.getElementById('ordNo').textContent = curOrd;
    document.getElementById('payAmt').textContent = '¥'+total().toLocaleString();
    setState('paying','微信支付');
  }
  /* 倒计时 */
  function startTimer(){ stopTimer(); leftSec = 15*60; tick(); timerId = setInterval(tick, 1000); }
  function stopTimer(){ if(timerId){ clearInterval(timerId); timerId=null; } }
  function tick(){
    var m = Math.floor(leftSec/60), s = leftSec%60;
    document.getElementById('payTimer').textContent = (m<10?'0':'')+m+':'+(s<10?'0':'')+s;
    if(--leftSec <= 0){ stopTimer(); setState('timeout','订单已关闭');
      document.getElementById('btnReorder').textContent='重新下单'; }
  }
  /* 模拟支付成功 */
  document.getElementById('btnPaid').addEventListener('click', function(){
    stopTimer();
    document.getElementById('doneOrd').textContent = curOrd;
    var seg = function(){ return Math.random().toString(36).slice(2,6).toUpperCase(); };
    document.getElementById('licCode').textContent = 'FC-'+seg()+seg()+seg()+seg();
    setState('done','支付成功');
  });
  document.getElementById('btnTimeout').addEventListener('click', function(){ stopTimer(); leftSec=0; tick(); });
  document.getElementById('btnCopy').addEventListener('click', function(){
    var t = document.getElementById('licCode').textContent, b=this;
    (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).then(function(){
      b.textContent='已复制 ✓'; setTimeout(function(){ b.textContent='复制授权码'; },1500);
    }).catch(function(){ b.textContent=t; });
  });
  /* 支付成功页底按钮 → 去后台；超时页底按钮 → 重新下单 */
  function footButton(){
    var s = document.querySelector('.dw-state.on').dataset.state;
    var b = document.createElement('button');
    b.className = 'btn btn-primary btn-lg btn-block';
    if(s==='done'){ b.textContent='去后台安装'; b.id='btnGoAdmin';
      b.addEventListener('click', function(){ window.location.href='index.html#cta'; }); }
    else if(s==='timeout'){ b.textContent='重新下单'; b.id='btnReorder';
      b.addEventListener('click', function(){ renderConfirm(); setState('confirm','确认订单'); }); }
    else { b.id='btnPay'; b.innerHTML='微信支付 <span id="payBtnAmt">¥'+total().toLocaleString()+'</span>';
      b.addEventListener('click', payNow); }
    foot.innerHTML=''; foot.appendChild(b);
    if(s==='confirm'){ var p=document.createElement('p'); p.className='pay-line';
      p.textContent='支付即同意《授权协议》· 一个授权码 = 一个站点'; foot.appendChild(p); }
  }
  /* 覆写 setState 的 footer 渲染 */
  var _set = setState;
  setState = function(s,t){ _set(s,t); footButton(); };
  /* 假二维码（演示占位，正式环境为 wechat-pay 插件 Native 支付二维码） */
  function drawQR(){
    var box = document.getElementById('qr'); box.innerHTML='';
    var seed = 20261015;
    function rnd(){ seed = (seed*1103515245+12345) & 0x7fffffff; return seed/0x7fffffff; }
    var N=21, finder=function(r,c){ return (r<7&&c<7)||(r<7&&c>=N-7)||(r>=N-7&&c<7); };
    for(var r=0;r<N;r++) for(var c=0;c<N;c++){
      var i=document.createElement('i');
      var on;
      if(finder(r,c)){ var lr=r<7?r:r-(N-7), lc=c<7?c:c-(N-7);
        on = (lr===0||lr===6||lc===0||lc===6||(lr>=2&&lr<=4&&lc>=2&&lc<=4)); }
      else on = rnd() > .5;
      if(on) i.className='on';
      box.appendChild(i);
    }
  }
  renderConfirm();
})();