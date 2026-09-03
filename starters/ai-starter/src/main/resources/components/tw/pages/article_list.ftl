<#-- 文章列表页正文骨架（内容页内置模板，渲染引擎组装进 article_list.html） -->
<section class="bg-white py-12">
  <div class="mx-auto max-w-6xl px-4">
    <header class="border-b border-slate-200 pb-8">
      <h1 class="text-3xl font-bold tracking-tight text-slate-900">${(category.title)!'文章列表'}</h1>
      <p class="mt-2 text-sm text-slate-500">${seoTag("website_sub_title")!''}</p>
    </header>
    <div class="mt-10 grid gap-8 md:grid-cols-3">
      <#if articleVoPage?? && articleVoPage.records??>
        <#list articleVoPage.records as item>
          <article class="group flex flex-col overflow-hidden rounded-xl border border-slate-200 bg-white transition hover:border-primary-300 hover:shadow-lg">
            <#if item.thumbnail?? && item.thumbnail != "">
              <a href="${item.url!''}" class="block aspect-video overflow-hidden bg-slate-100">
                <img src="${item.thumbnail}" alt="${item.title!''}" loading="lazy"
                     class="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105">
              </a>
            </#if>
            <div class="flex flex-1 flex-col p-6">
              <h2 class="font-semibold leading-snug text-slate-900">
                <a href="${item.url!''}" class="transition-colors hover:text-primary-600">${item.title!''}</a>
              </h2>
              <#if (item.summary)?? && item.summary?has_content>
                <p class="mt-2 flex-1 text-sm leading-relaxed text-slate-600">${item.summary}</p>
              </#if>
              <div class="mt-4 flex items-center justify-between border-t border-slate-100 pt-4 text-xs text-slate-400">
                <time><@formatTime value=(item.created)! format="yyyy-MM-dd"/></time>
                <span>${(item.viewCount)!0} 次阅读</span>
              </div>
            </div>
          </article>
        </#list>
      </#if>
    </div>
    <@articlePageTag>
      <#if data??>
        <nav class="mt-12 flex flex-wrap items-center justify-center gap-2" aria-label="文章分页">
          <#if data.prev?? && (data.prev.url)?? && ((data.prev.url)!'')?has_content>
            <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
               href="${data.prev.url}">${(data.prev.text)!'上一页'}</a>
          </#if>
          <#if data.list?? && data.list?is_sequence>
            <#list data.list as page>
              <#if page?? && page?is_hash>
                <#if (page.url)?? && ((page.url)!'')?has_content>
                  <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
                     href="${page.url}">${(page.text)!}</a>
                <#else>
                  <span class="rounded-lg border border-primary-600 bg-primary-600 px-4 py-2 text-sm text-white"
                        aria-current="page">${(page.text)!}</span>
                </#if>
              </#if>
            </#list>
          </#if>
          <#if data.next?? && (data.next.url)?? && ((data.next.url)!'')?has_content>
            <a class="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 transition-colors hover:border-primary-600 hover:text-primary-600"
               href="${data.next.url}">${(data.next.text)!'下一页'}</a>
          </#if>
        </nav>
      </#if>
    </@articlePageTag>
  </div>
</section>