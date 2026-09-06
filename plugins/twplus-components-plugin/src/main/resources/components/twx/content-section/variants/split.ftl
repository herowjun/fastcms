<#-- 图文内容区 / split 变体：左文右图分栏，image 为空时以图标色块占位 -->
<section class="twx-section">
  <div class="twx-container twx-cs-grid">
    <div>
      <h2 class="twx-h2 twx-cs-title">${(comp.title)!''}</h2>
      <#if (comp.paragraphs)?? && comp.paragraphs?is_sequence>
        <#list comp.paragraphs as para>
          <p class="twx-cs-para">${para}</p>
        </#list>
      </#if>
    </div>
    <#if (comp.image)?? && comp.image?has_content>
      <div class="twx-cs-figure">
        <img src="${comp.image}" alt="${(comp.title)!''}" loading="lazy" class="twx-cs-img">
      </div>
    <#else>
      <div class="twx-cs-figure twx-cs-figure--placeholder">
        <span class="twx-cs-placeholder-icon" aria-hidden="true">${(comp.icon)!'✨'}</span>
      </div>
    </#if>
  </div>
</section>
