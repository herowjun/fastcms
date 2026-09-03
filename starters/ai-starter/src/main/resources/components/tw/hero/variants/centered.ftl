<#-- 首屏 / centered 变体：居中大标题 + 行动按钮 -->
<section class="bg-gradient-to-b from-primary-50 via-white to-white">
  <div class="mx-auto max-w-6xl px-4 py-24 text-center md:py-32">
    <h1 class="mx-auto max-w-3xl text-4xl font-bold tracking-tight text-slate-900 md:text-5xl md:leading-tight">${(comp.title)!''}</h1>
    <#if (comp.subtitle)?? && comp.subtitle?has_content>
      <p class="mx-auto mt-6 max-w-2xl text-lg leading-relaxed text-slate-600">${comp.subtitle}</p>
    </#if>
    <#if ((comp.ctaLabel)?? && comp.ctaLabel?has_content) || ((comp.ctaSecondaryLabel)?? && comp.ctaSecondaryLabel?has_content)>
      <div class="mt-10 flex flex-col items-center justify-center gap-4 sm:flex-row">
        <#if (comp.ctaLabel)?? && comp.ctaLabel?has_content>
          <a href="${(comp.ctaHref)!'#'}"
             class="w-full rounded-lg bg-primary-600 px-8 py-3 text-sm font-medium text-white transition-colors hover:bg-primary-700 sm:w-auto">${comp.ctaLabel}</a>
        </#if>
        <#if (comp.ctaSecondaryLabel)?? && comp.ctaSecondaryLabel?has_content>
          <a href="${(comp.ctaSecondaryHref)!'#'}"
             class="w-full rounded-lg border border-slate-300 px-8 py-3 text-sm font-medium text-slate-700 transition-colors hover:border-primary-600 hover:text-primary-600 sm:w-auto">${comp.ctaSecondaryLabel}</a>
        </#if>
      </div>
    </#if>
  </div>
</section>