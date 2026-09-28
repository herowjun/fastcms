<#-- 单页列表 / links 变体：竖向链接（CMS 单页数据绑定） -->
<section class="bg-white py-14">
  <div class="mx-auto max-w-6xl px-4">
    <#if (comp.title)?? && comp.title?has_content>
      <h2 class="text-2xl font-bold tracking-tight text-slate-900">${comp.title}</h2>
    </#if>
    <ul class="mt-6 space-y-3">
      <@singlePageList>
        <#if data?? && (data?size > 0)>
          <#list data as item>
            <li>
              <a href="${item.url!''}"
                 class="text-sm font-medium text-slate-600 transition hover:text-primary-600">${item.title!''}</a>
            </li>
          </#list>
        </#if>
      </@singlePageList>
    </ul>
  </div>
</section>
