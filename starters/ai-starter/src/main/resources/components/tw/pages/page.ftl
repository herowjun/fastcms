<#-- 单页正文骨架（内容页内置模板，渲染引擎组装进 page.html） -->
<article class="bg-white py-12">
  <div class="mx-auto max-w-3xl px-4">
    <#-- 面包屑：首页 / 本页标题 -->
    <nav class="page-breadcrumb" aria-label="面包屑">
      <a class="page-breadcrumb__link" href="/">首页</a>
      <span class="page-breadcrumb__sep" aria-hidden="true">/</span>
      <span class="page-breadcrumb__current">${(singlePage.title)!'单页'}</span>
    </nav>
    <header class="border-b border-slate-200 pb-8">
      <h1 class="text-3xl font-bold tracking-tight text-slate-900">${(singlePage.title)!'单页'}</h1>
      <p class="mt-2 text-sm text-slate-500">${seoTag("website_sub_title")!''}</p>
    </header>
    <div class="article-content mt-8">${(singlePage.contentHtml)!''}</div>
  </div>
</article>
