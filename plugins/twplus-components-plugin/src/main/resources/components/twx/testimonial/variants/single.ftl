<#-- 客户评价 / single 变体：居中大引述，取第一则 -->
<section class="twx-section twx-section-alt">
  <div class="twx-container twx-quote-single">
    <h2 class="twx-h2 twx-head-center">${(comp.title)!''}</h2>
    <#if (comp.items)?? && comp.items?is_sequence && comp.items?size > 0>
      <#assign t = comp.items[0]>
      <blockquote class="twx-quote-single__text">
        <span class="twx-quote-single__mark" aria-hidden="true">“</span>${(t.quote)!''}<span class="twx-quote-single__mark" aria-hidden="true">”</span>
      </blockquote>
      <figcaption class="twx-quote-single__meta">
        <#if (t.author)?? && t.author?has_content>
          <span class="twx-quote-single__author">${t.author}</span>
        </#if>
        <#if (t.role)?? && t.role?has_content>
          <span class="twx-quote__role">${t.role}</span>
        </#if>
      </figcaption>
    </#if>
  </div>
</section>
