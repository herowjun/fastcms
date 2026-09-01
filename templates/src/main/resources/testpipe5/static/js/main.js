(function () {
 'use strict';
 var header = document.querySelector('.site-header');
 var toggle = document.querySelector('.nav-toggle');
 var nav = document.querySelector('.site-nav');
 var navList = document.querySelector('.nav-list');

 function closeMenu() {
 if (!nav) return;
 nav.classList.remove('is-open');
 document.body.classList.remove('nav-open');
 if (toggle) {
 toggle.setAttribute('aria-expanded', 'false');
 toggle.setAttribute('aria-label', '打开菜单');
 }
 }

 function openMenu() {
 if (!nav) return;
 nav.classList.add('is-open');
 document.body.classList.add('nav-open');
 if (toggle) {
 toggle.setAttribute('aria-expanded', 'true');
 toggle.setAttribute('aria-label', '关闭菜单');
 }
 }

 if (toggle && nav) {
 toggle.addEventListener('click', function () {
 if (nav.classList.contains('is-open')) {
 closeMenu();
 } else {
 openMenu();
 }
 });
 }

 document.addEventListener('click', function (event) {
 if (!nav || !nav.classList.contains('is-open')) return;
 var isInsideNav = nav.contains(event.target);
 var isToggle = toggle && toggle.contains(event.target);
 if (!isInsideNav && !isToggle) {
 closeMenu();
 }
 });

 document.addEventListener('keydown', function (event) {
 if (event.key === 'Escape') {
 closeMenu();
 }
 });

 if (navList) {
 navList.addEventListener('click', function (event) {
 if (event.target.closest('a')) {
 closeMenu();
 }
 });
 }

 function smoothScrollTo(top) {
 if ('scrollBehavior' in document.documentElement.style) {
 window.scrollTo({ top: top, behavior: 'smooth' });
 } else {
 window.scrollTo(0, top);
 }
 }

 document.addEventListener('click', function (event) {
 var link = event.target.closest("a[href^='#']");
 if (!link) return;
 var id = link.getAttribute('href').slice(1);
 var target = id ? document.getElementById(id) : null;
 if (!target) return;
 event.preventDefault();
 var top = target.getBoundingClientRect().top + window.scrollY -72;
 smoothScrollTo(top);
 closeMenu();
 });

 var sections = Array.prototype.slice.call(document.querySelectorAll('section[id]'));
 var navLinks = Array.prototype.slice.call(document.querySelectorAll(".nav-list a[href^='#']"));

 function setActiveLink(hash) {
 navLinks.forEach(function (link) {
 link.classList.toggle('is-active', link.getAttribute('href') === hash);
 });
 }

 function updateActive() {
 var current = '';
 sections.forEach(function (section) {
 if (section.getBoundingClientRect().top <=120) {
 current = '#' + section.id;
 }
 });
 setActiveLink(current);
 }

 if (sections.length) {
 window.addEventListener('scroll', updateActive, { passive: true });
 updateActive();
 }

 var style = document.createElement('style');
 style.textContent = '.back-to-top{position:fixed;right:20px;bottom:20px;z-index:999;display:flex;align-items:center;justify-content:center;width:42px;height:42px;border:0;border-radius:50%;background:#0f172a;color:#fff;box-shadow:08px20px rgba(15,23,42,.18);cursor:pointer;opacity:0;visibility:hidden;transform:translateY(10px);transition:opacity .2s,transform .2s,visibility .2s}.back-to-top.is-visible{opacity:1;visibility:visible;transform:translateY(0)}body.nav-open{overflow:hidden}';
 document.head.appendChild(style);

 var topButton = document.createElement('button');
 topButton.type = 'button';
 topButton.className = 'back-to-top';
 topButton.setAttribute('aria-label', '回到顶部');
 topButton.innerHTML = `<svg viewBox='002424' width='18' height='18' aria-hidden='true'><path d='M126l66h-4v6h-4v-6H6l6-6z'/></svg>`;
 document.body.appendChild(topButton);

 function toggleTopButton() {
 if (window.scrollY >320) {
 topButton.classList.add('is-visible');
 } else {
 topButton.classList.remove('is-visible');
 }
 }

 topButton.addEventListener('click', function () {
 smoothScrollTo(0);
 });

 window.addEventListener('scroll', toggleTopButton, { passive: true });
 toggleTopButton();

 if (header) {
 function toggleHeaderState() {
 if (window.scrollY >8) {
 header.classList.add('is-scrolled');
 } else {
 header.classList.remove('is-scrolled');
 }
 }
 window.addEventListener('scroll', toggleHeaderState, { passive: true });
 toggleHeaderState();
 }
})();
