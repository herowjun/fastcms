<#-- 单页列表 / grid 变体：卡片网格（CMS 单页数据绑定） -->
<section class="bg-slate-50 py-20">
  <div class="mx-auto max-w-6xl px-4">
    <#if (comp.title)?? && comp.title?has_content>
      <div class="mx-auto max-w-2xl text-center">
        <h2 class="text-3xl font-bold tracking-tight text-slate-900">${comp.title}</h2>
      </div>
    </#if>
    <div class="mt-14 grid gap-6 sm:grid-cols-2 md:grid-cols-3">
      <@singlePageList>
        <#if data?? && (data?size > 0)>
          <#list data as item>
            <a href="${item.url!''}"
               class="group rounded-xl border border-slate-200 bg-white p-6 transition hover:border-primary-300 hover:shadow-lg">
              <h3 class="font-semibold text-slate-900 transition-colors group-hover:text-primary-600">${item.title!''}</h3>
              <span class="mt-3 inline-block text-sm text-slate-400">查看详情 →</span>
            </a>
          </#list>
        </#if>
      </@singlePageList>
    </div>
  </div>
</section>
