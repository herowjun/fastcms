<#-- 团队展示 / default 变体：三列居中卡片，头像为图标占位 -->
<section class="twx-section">
  <div class="twx-container">
    <h2 class="twx-h2 twx-head-center">${(comp.title)!''}</h2>
    <div class="twx-grid twx-cols-3">
      <#if (comp.items)?? && comp.items?is_sequence>
        <#list comp.items as item>
          <div class="twx-card twx-team">
            <div class="twx-team__avatar" aria-hidden="true">
              <#if (item.name)?? && item.name?has_content>${item.name?substring(0, 1)}<#else>👤</#if>
            </div>
            <h3 class="twx-team__name">${(item.name)!''}</h3>
            <#if (item.role)?? && item.role?has_content>
              <p class="twx-team__role">${item.role}</p>
            </#if>
            <#if (item.bio)?? && item.bio?has_content>
              <p class="twx-team__bio">${item.bio}</p>
            </#if>
          </div>
        </#list>
      </#if>
    </div>
  </div>
</section>
