<#-- 单页正文骨架（内容页内置模板，渲染引擎组装进 page.html） -->
<article class="bg-white py-12">
  <div class="mx-auto max-w-3xl px-4">
    <header class="border-b border-slate-200 pb-8">
      <h1 class="text-3xl font-bold tracking-tight text-slate-900">${(singlePage.title)!'单页'}</h1>
      <p class="mt-2 text-sm text-slate-500">${seoTag("website_sub_title")!''}</p>
    </header>
    <div class="article-content mt-8">${(singlePage.contentHtml)!''}</div>
  </div>
</article>