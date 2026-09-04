<#-- 栏目横幅 / minimal 变体：白底极简，标题居中带下划分隔 -->
<section class="twx-section twx-pagehero-min">
  <div class="twx-container twx-pagehero-min__inner">
    <h1 class="twx-pagehero-min__title">${(comp.title)!''}</h1>
    <div class="twx-pagehero-min__bar" aria-hidden="true"></div>
    <#if (comp.subtitle)?? && comp.subtitle?has_content>
      <p class="twx-pagehero-min__sub">${comp.subtitle}</p>
    </#if>
    <#if (comp.breadcrumb)?? && comp.breadcrumb?has_content>
      <p class="twx-pagehero-min__crumb">${comp.breadcrumb}</p>
    </#if>
  </div>
</section>
