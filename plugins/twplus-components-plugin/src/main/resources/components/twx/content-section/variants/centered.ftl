<#-- 图文内容区 / centered 变体：居中窄栏段落 -->
<section class="twx-section twx-section-alt">
  <div class="twx-container twx-cs-centered">
    <h2 class="twx-h2">${(comp.title)!''}</h2>
    <#if (comp.paragraphs)?? && comp.paragraphs?is_sequence>
      <#list comp.paragraphs as para>
        <p class="twx-cs-centered__para">${para}</p>
      </#list>
    </#if>
  </div>
</section>
