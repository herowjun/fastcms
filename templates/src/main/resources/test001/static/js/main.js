(function() {
 'use strict';

 //预览模式下修正首页链接
 var previewMatch = window.location.pathname.match(/^(\/template\/preview\/[^/]+)\/?/);
 if (previewMatch) {
 var baseUrl = previewMatch[1];
 document.querySelectorAll('a.home-link').forEach(function(link) {
 link.setAttribute('href', baseUrl + '/index.html');
 });
 }

 var navToggle = document.querySelector('.nav-toggle');
 var navMenu = document.querySelector('.nav-menu');

 if (navToggle && navMenu) {
 navToggle.addEventListener('click', function() {
 navMenu.classList.toggle('active');
 this.classList.toggle('active');
 this.setAttribute('aria-expanded', this.classList.contains('active') ? 'true' : 'false');
 });
 }

 document.querySelectorAll('.nav-menu a').forEach(function(link) {
 link.addEventListener('click', function() {
 if (navMenu.classList.contains('active')) {
 navMenu.classList.remove('active');
 if (navToggle) {
 navToggle.classList.remove('active');
 navToggle.setAttribute('aria-expanded', 'false');
 }
 }
 });
 });

 var backTop = document.createElement('button');
 backTop.className = 'back-to-top';
 backTop.setAttribute('aria-label', '回到顶部');
 backTop.innerHTML = '<svg width="20" height="20" viewBox="002424" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1815129615"></polyline></svg>';
 document.body.appendChild(backTop);

 var ticking = false;

 window.addEventListener('scroll', function() {
 if (!ticking) {
 window.requestAnimationFrame(function() {
 if (window.scrollY >400) {
 backTop.classList.add('visible');
 } else {
 backTop.classList.remove('visible');
 }
 ticking = false;
 });
 ticking = true;
 }
 }, { passive: true });

 backTop.addEventListener('click', function() {
 window.scrollTo({ top:0, behavior: 'smooth' });
 });

 var currentPath = window.location.pathname;
 document.querySelectorAll('.nav-menu a').forEach(function(link) {
 var href = link.getAttribute('href');
 if (href === currentPath || (currentPath.length >1 && href === '/')) {
 link.classList.add('active');
 } else if (href !== '/' && currentPath.indexOf(href) ===0) {
 link.classList.add('active');
 }
 });

 var articles = document.querySelectorAll('.article-item, .article-card');
 if (articles.length >1) {
 var observer = new IntersectionObserver(function(entries) {
 entries.forEach(function(entry) {
 if (entry.isIntersecting) {
 entry.target.classList.add('fade-in');
 }
 });
 }, { threshold:0.1 });

 articles.forEach(function(article) {
 article.style.opacity = '0';
 article.style.transform = 'translateY(20px)';
 article.style.transition = 'opacity0.5s ease, transform0.5s ease';
 observer.observe(article);
 });
 }
})();