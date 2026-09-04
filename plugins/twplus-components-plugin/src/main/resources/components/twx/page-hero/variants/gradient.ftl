<#-- 栏目横幅 / gradient 变体：主色浅渐变底，居中标题 + 面包屑 -->
<section class="twx-pagehero">
  <div class="twx-container twx-pagehero__inner">
    <#if (comp.breadcrumb)?? && comp.breadcrumb?has_content>
      <p class="twx-pagehero__crumb">${comp.breadcrumb}</p>
    </#if>
    <h1 class="twx-pagehero__title">${(comp.title)!''}</h1>
    <#if (comp.subtitle)?? && comp.subtitle?has_content>
      <p class="twx-pagehero__sub">${comp.subtitle}</p>
    </#if>
  </div>
</section>
