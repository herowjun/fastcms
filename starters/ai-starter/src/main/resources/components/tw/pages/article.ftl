<#-- 文章详情页正文骨架（内容页内置模板，渲染引擎组装进 article.html） -->
<article class="bg-white py-12">
  <div class="mx-auto max-w-3xl px-4">
    <header class="border-b border-slate-200 pb-8 text-center">
      <h1 class="text-3xl font-bold leading-tight tracking-tight text-slate-900">${(article.title)!''}</h1>
      <div class="mt-4 flex items-center justify-center gap-6 text-sm text-slate-500">
        <time><@formatTime value=(article.created)! format="yyyy-MM-dd"/></time>
        <span>${(article.viewCount)!0} 次阅读</span>
      </div>
    </header>
    <div class="article-content mt-8">${(article.contentHtml)!''}</div>
  </div>
</article>