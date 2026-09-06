<#-- 客户评价 / cards 变体：三列引述卡片 -->
<section class="twx-section">
  <div class="twx-container">
    <h2 class="twx-h2 twx-head-center">${(comp.title)!''}</h2>
    <div class="twx-grid twx-cols-3 twx-quote-grid">
      <#if (comp.items)?? && comp.items?is_sequence>
        <#list comp.items as item>
          <figure class="twx-card twx-quote">
            <blockquote class="twx-quote__text">
              <span class="twx-quote__mark" aria-hidden="true">“</span>${(item.quote)!''}
            </blockquote>
            <figcaption class="twx-quote__meta">
              <span class="twx-avatar" aria-hidden="true"><#if (item.author)?? && item.author?has_content>${item.author?substring(0, 1)?upper_case}<#else>👤</#if></span>
              <span>
                <span class="twx-quote__author">${(item.author)!''}</span>
                <#if (item.role)?? && item.role?has_content>
                  <span class="twx-quote__role">${item.role}</span>
                </#if>
              </span>
            </figcaption>
          </figure>
        </#list>
      </#if>
    </div>
  </div>
</section>
