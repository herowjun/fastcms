<#-- 联系区块 / default 变体：浅灰底卡片，信息项两列排布 -->
<section class="twx-section twx-section-alt">
  <div class="twx-container">
    <h2 class="twx-h2 twx-head-center">${(comp.title)!''}</h2>
    <div class="twx-grid twx-cols-2">
      <#if (comp.address)?? && comp.address?has_content>
        <div class="twx-card twx-contact__item">
          <span class="twx-contact__icon" aria-hidden="true">📍</span>
          <div>
            <p class="twx-contact__label">地址</p>
            <p class="twx-contact__value">${comp.address}</p>
          </div>
        </div>
      </#if>
      <#if (comp.phone)?? && comp.phone?has_content>
        <div class="twx-card twx-contact__item">
          <span class="twx-contact__icon" aria-hidden="true">📞</span>
          <div>
            <p class="twx-contact__label">电话</p>
            <p class="twx-contact__value">${comp.phone}</p>
          </div>
        </div>
      </#if>
      <#if (comp.email)?? && comp.email?has_content>
        <div class="twx-card twx-contact__item">
          <span class="twx-contact__icon" aria-hidden="true">✉️</span>
          <div>
            <p class="twx-contact__label">邮箱</p>
            <p class="twx-contact__value">${comp.email}</p>
          </div>
        </div>
      </#if>
      <#if (comp.hours)?? && comp.hours?has_content>
        <div class="twx-card twx-contact__item">
          <span class="twx-contact__icon" aria-hidden="true">🕒</span>
          <div>
            <p class="twx-contact__label">服务时间</p>
            <p class="twx-contact__value">${comp.hours}</p>
          </div>
        </div>
      </#if>
    </div>
  </div>
</section>
