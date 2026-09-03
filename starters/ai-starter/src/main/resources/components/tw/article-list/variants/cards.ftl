<#-- 文章列表 / cards 变体：三列图文卡片（CMS 数据绑定） -->
<section class="bg-slate-50 py-20">
  <div class="mx-auto max-w-6xl px-4">
    <div class="mx-auto max-w-2xl text-center">
      <h2 class="text-3xl font-bold tracking-tight text-slate-900">${(comp.title)!''}</h2>
    </div>
    <div class="mt-14 grid gap-8 md:grid-cols-3">
      <@articleListTag orderBy="created" count=(comp.count)!6>
        <#if data?? && (data?size > 0)>
          <#list data as item>
            <article class="group flex flex-col overflow-hidden rounded-xl border border-slate-200 bg-white transition hover:border-primary-300 hover:shadow-lg">
              <#if item.thumbnail?? && item.thumbnail != "">
                <a href="${item.url!''}" class="block aspect-video overflow-hidden bg-slate-100">
                  <img src="${item.thumbnail}" alt="${item.title!''}" loading="lazy"
                       class="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105">
                </a>
              </#if>
              <div class="flex flex-1 flex-col p-6">
                <h3 class="font-semibold leading-snug text-slate-900">
                  <a href="${item.url!''}" class="transition-colors hover:text-primary-600">${item.title!''}</a>
                </h3>
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
      </@articleListTag>
    </div>
  </div>
</section>