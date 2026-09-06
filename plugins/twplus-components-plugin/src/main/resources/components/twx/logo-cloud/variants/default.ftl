<#-- 品牌客户云 / default 变体：浅灰底横条，客户名胶囊 -->
<section class="twx-logocloud">
  <div class="twx-container">
    <#if (comp.title)?? && comp.title?has_content>
      <p class="twx-logocloud__title">${comp.title}</p>
    </#if>
    <div class="twx-logocloud__row">
      <#if (comp.items)?? && comp.items?is_sequence>
        <#list comp.items as item>
          <span class="twx-logocloud__pill">${item}</span>
        </#list>
      </#if>
    </div>
  </div>
</section>
