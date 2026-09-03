<#-- 页脚 / simple 变体：深色简洁横栏 -->
<footer class="bg-slate-900 text-slate-400">
  <div class="mx-auto max-w-6xl px-4 py-12">
    <div class="flex flex-col items-center justify-between gap-6 md:flex-row">
      <a href="/" class="text-lg font-bold text-white">${(comp.brand)!''}</a>
      <nav aria-label="页脚导航">
        <ul class="flex flex-wrap items-center justify-center gap-6">
          <@menuTag>
            <#if data??>
              <#list data as item>
                <#if item.menuName?? && item.menuName?has_content>
                  <li>
                    <a href="${item.url!''}" target="${item.target!'_self'}"
                       class="text-sm transition-colors hover:text-white">${item.menuName}</a>
                  </li>
                </#if>
              </#list>
            </#if>
          </@menuTag>
        </ul>
      </nav>
    </div>
    <#assign copyright = (comp.copyright)!''>
    <#if !copyright?has_content>
      <#assign copyright = ((comp.brand)!'FastCMS') + ' © ' + .now?string("yyyy")>
    </#if>
    <div class="mt-8 border-t border-slate-800 pt-6 text-center text-sm">${copyright}</div>
  </div>
</footer>