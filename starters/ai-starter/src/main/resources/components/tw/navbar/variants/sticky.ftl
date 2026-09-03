<#-- 导航栏 / sticky 变体：品牌名 + CMS 菜单，吸顶悬浮 -->
<section class="sticky top-0 z-50 border-b border-slate-200/80 bg-white/80 backdrop-blur">
  <div class="mx-auto flex h-16 max-w-6xl items-center justify-between px-4">
    <a href="/" class="text-lg font-bold tracking-tight text-slate-900">${(comp.brand)!''}</a>
    <nav aria-label="主导航">
      <ul class="hidden items-center gap-8 md:flex">
        <@menuTag>
          <#if data??>
            <#list data as item>
              <#if item.menuName?? && item.menuName?has_content>
                <li>
                  <a href="${item.url!''}" target="${item.target!'_self'}"
                     class="text-sm text-slate-600 transition-colors hover:text-primary-600">${item.menuName}</a>
                </li>
              </#if>
            </#list>
          </#if>
        </@menuTag>
      </ul>
    </nav>
  </div>
</section>