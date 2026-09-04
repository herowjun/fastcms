<#-- 常见问题 / accordion 变体：纯 CSS details/summary 手风琴，默认第一项展开 -->
<section class="twx-section twx-section-alt">
  <div class="twx-container twx-faq">
    <h2 class="twx-h2 twx-head-center">${(comp.title)!''}</h2>
    <div class="twx-faq__list">
      <#if (comp.items)?? && comp.items?is_sequence>
        <#list comp.items as item>
          <details class="twx-faq__item"<#if item?index == 0> open</#if>>
            <summary class="twx-faq__q">
              <span class="twx-faq__qtext">${(item.q)!''}</span>
              <span class="twx-faq__marker" aria-hidden="true"></span>
            </summary>
            <div class="twx-faq__a">${(item.a)!''}</div>
          </details>
        </#list>
      </#if>
    </div>
  </div>
</section>
