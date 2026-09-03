<#-- 首屏 / split 变体：左文右图分栏 -->
<section class="bg-gradient-to-b from-primary-50 via-white to-white">
  <div class="mx-auto grid max-w-6xl items-center gap-12 px-4 py-20 md:grid-cols-2 md:py-28">
    <div>
      <h1 class="text-4xl font-bold leading-tight tracking-tight text-slate-900 md:text-5xl">${(comp.title)!''}</h1>
      <#if (comp.subtitle)?? && comp.subtitle?has_content>
        <p class="mt-6 text-lg leading-relaxed text-slate-600">${comp.subtitle}</p>
      </#if>
      <#if ((comp.ctaLabel)?? && comp.ctaLabel?has_content) || ((comp.ctaSecondaryLabel)?? && comp.ctaSecondaryLabel?has_content)>
        <div class="mt-10 flex flex-col gap-4 sm:flex-row">
          <#if (comp.ctaLabel)?? && comp.ctaLabel?has_content>
            <a href="${(comp.ctaHref)!'#'}"
               class="rounded-lg bg-primary-600 px-8 py-3 text-center text-sm font-medium text-white transition-colors hover:bg-primary-700">${comp.ctaLabel}</a>
          </#if>
          <#if (comp.ctaSecondaryLabel)?? && comp.ctaSecondaryLabel?has_content>
            <a href="${(comp.ctaSecondaryHref)!'#'}"
               class="rounded-lg border border-slate-300 px-8 py-3 text-center text-sm font-medium text-slate-700 transition-colors hover:border-primary-600 hover:text-primary-600">${comp.ctaSecondaryLabel}</a>
          </#if>
        </div>
      </#if>
    </div>
    <#if (comp.image)?? && comp.image?has_content>
      <div class="overflow-hidden rounded-2xl shadow-xl">
        <img src="${comp.image}" alt="${(comp.title)!''}" class="aspect-[4/3] w-full object-cover">
      </div>
    <#else>
      <div class="flex aspect-[4/3] items-center justify-center rounded-2xl bg-gradient-to-br from-primary-100 to-primary-200">
        <span class="text-6xl" aria-hidden="true">🚀</span>
      </div>
    </#if>
  </div>
</section>