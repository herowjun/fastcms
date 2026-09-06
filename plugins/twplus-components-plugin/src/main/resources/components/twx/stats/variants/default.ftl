<#-- 数据统计 / default 变体：主色实底横条，大数字指标 -->
<section class="twx-stats">
  <div class="twx-container">
    <#if (comp.title)?? && comp.title?has_content>
      <h2 class="twx-stats__title">${comp.title}</h2>
    </#if>
    <div class="twx-stats__grid">
      <#if (comp.items)?? && comp.items?is_sequence>
        <#list comp.items as item>
          <div class="twx-stats__item">
            <p class="twx-stats__value">${(item.value)!''}</p>
            <p class="twx-stats__label">${(item.label)!''}</p>
          </div>
        </#list>
      </#if>
    </div>
  </div>
</section>
