<#-- 转化行动横幅 / solid 变体：主色实底 + 白色按钮 -->
<section class="twx-section">
  <div class="twx-container">
    <div class="twx-cta">
      <h2 class="twx-cta__title">${(comp.title)!''}</h2>
      <#if (comp.subtitle)?? && comp.subtitle?has_content>
        <p class="twx-cta__sub">${comp.subtitle}</p>
      </#if>
      <a href="${(comp.buttonHref)!'#'}" class="twx-btn twx-btn--light twx-cta__btn">${(comp.buttonLabel)!''}</a>
    </div>
  </div>
</section>
