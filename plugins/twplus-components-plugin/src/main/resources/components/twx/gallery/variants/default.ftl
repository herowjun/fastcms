<#-- 图库展示 / default 变体：三列网格，渐变色块封面 + 底部说明 -->
<section class="twx-section">
  <div class="twx-container">
    <h2 class="twx-h2 twx-head-center">${(comp.title)!''}</h2>
    <div class="twx-grid twx-cols-3">
      <#if (comp.items)?? && comp.items?is_sequence>
        <#list comp.items as item>
          <figure class="twx-gallery__tile twx-gallery__tile--${(item.tone)!'primary'}">
            <span class="twx-gallery__icon" aria-hidden="true">${(item.icon)!'🖼️'}</span>
            <figcaption class="twx-gallery__caption">${(item.caption)!''}</figcaption>
          </figure>
        </#list>
      </#if>
    </div>
  </div>
</section>
