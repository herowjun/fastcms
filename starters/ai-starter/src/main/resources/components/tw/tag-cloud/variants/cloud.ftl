<#-- 标签云 / cloud 变体（CMS 标签数据绑定） -->
<section class="bg-slate-50 py-14">
  <div class="mx-auto max-w-6xl px-4">
    <#if (comp.title)?? && comp.title?has_content>
      <h2 class="text-2xl font-bold tracking-tight text-slate-900">${comp.title}</h2>
    </#if>
    <div class="mt-6 flex flex-wrap gap-2">
      <@tagList>
        <#if data?? && (data?size > 0)>
          <#list data as item>
            <a href="${item.url!''}"
               class="rounded bg-white px-3 py-1 text-sm text-slate-600 shadow-sm transition hover:text-primary-600">#${item.name!''}</a>
          </#list>
        </#if>
      </@tagList>
    </div>
  </div>
</section>
