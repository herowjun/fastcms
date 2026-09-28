<#-- 分类导航 / chips 变体：横向胶囊条（CMS 分类数据绑定） -->
<section class="bg-white py-14">
  <div class="mx-auto max-w-6xl px-4">
    <#if (comp.title)?? && comp.title?has_content>
      <h2 class="text-2xl font-bold tracking-tight text-slate-900">${comp.title}</h2>
    </#if>
    <div class="mt-6 flex flex-wrap gap-3">
      <@categoryList>
        <#if data?? && (data?size > 0)>
          <#list data as item>
            <a href="${item.url!''}"
               class="rounded-full border border-slate-200 bg-slate-50 px-4 py-2 text-sm font-medium text-slate-700 transition hover:border-primary-300 hover:text-primary-600">${item.title!''}</a>
          </#list>
        </#if>
      </@categoryList>
    </div>
  </div>
</section>
