(function(){
  "use strict";
  /* 移动端菜单（三页共用） */
  var nav = document.getElementById('nav'), tog = document.getElementById('navToggle');
  if(!nav || !tog) return;
  tog.addEventListener('click', function(){ nav.classList.toggle('open'); });
  nav.querySelectorAll('.nav-links a').forEach(function(a){
    a.addEventListener('click', function(){ nav.classList.remove('open'); });
  });
})();
