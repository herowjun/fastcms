<#-- 特性网格 / three-col 变体：三列图标卡片 -->
<section class="bg-white py-20">
  <div class="mx-auto max-w-6xl px-4">
    <div class="mx-auto max-w-2xl text-center">
      <h2 class="text-3xl font-bold tracking-tight text-slate-900">${(comp.title)!''}</h2>
      <#if (comp.subtitle)?? && comp.subtitle?has_content>
        <p class="mt-4 text-lg text-slate-600">${comp.subtitle}</p>
      </#if>
    </div>
    <div class="mt-14 grid gap-8 md:grid-cols-3">
      <#list (comp.items)![] as item>
        <div class="rounded-xl border border-slate-200 bg-white p-8 transition hover:border-primary-300 hover:shadow-lg">
          <#if (item.icon)?? && item.icon?has_content>
            <div class="flex h-12 w-12 items-center justify-center rounded-lg bg-primary-50 text-2xl">${item.icon}</div>
          </#if>
          <h3 class="mt-5 text-lg font-semibold text-slate-900">${(item.title)!''}</h3>
          <#if (item.desc)?? && item.desc?has_content>
            <p class="mt-2 text-sm leading-relaxed text-slate-600">${item.desc}</p>
          </#if>
        </div>
      </#list>
    </div>
  </div>
</section>