<#-- 文章详情页正文骨架（内容页内置模板，渲染引擎组装进 article.html） -->
<article class="bg-white py-12">
  <div class="mx-auto max-w-3xl px-4">
    <header class="border-b border-slate-200 pb-8 text-center">
      <h1 class="text-3xl font-bold leading-tight tracking-tight text-slate-900">${(article.title)!''}</h1>
      <div class="mt-4 flex items-center justify-center gap-6 text-sm text-slate-500">
        <time><@formatTime value=(article.created)! format="yyyy-MM-dd"/></time>
        <span>${(article.viewCount)!0} 次阅读</span>
      </div>
    </header>
    <#if (article.thumbnail)?? && article.thumbnail != "">
      <figure class="article-cover mt-8">
        <img src="${article.thumbnail}" alt="${(article.title)!''}" loading="lazy" class="article-cover__img">
      </figure>
    </#if>
    <div class="article-content mt-8">${(article.contentHtml)!''}</div>

    <#-- 上一篇 / 下一篇 -->
    <nav class="article-pager mt-12" aria-label="文章导航">
      <@prevArticleTag articleId=(article.id)!>
        <#if data?? && (data.url)?? && ((data.url)!'')?has_content>
          <a class="article-pager__link" href="${data.url}">
            <span class="article-pager__label">上一篇</span>
            <span class="article-pager__title">${(data.title)!''}</span>
          </a>
        <#else>
          <span class="article-pager__link article-pager__link--empty">
            <span class="article-pager__label">上一篇</span>
            <span class="article-pager__title">已经是最早一篇</span>
          </span>
        </#if>
      </@prevArticleTag>
      <@nextArticleTag articleId=(article.id)!>
        <#if data?? && (data.url)?? && ((data.url)!'')?has_content>
          <a class="article-pager__link article-pager__link--end" href="${data.url}">
            <span class="article-pager__label">下一篇</span>
            <span class="article-pager__title">${(data.title)!''}</span>
          </a>
        <#else>
          <span class="article-pager__link article-pager__link--empty article-pager__link--end">
            <span class="article-pager__label">下一篇</span>
            <span class="article-pager__title">已经是最新一篇</span>
          </span>
        </#if>
      </@nextArticleTag>
    </nav>

    <#-- 相关推荐 -->
    <@relatedArticleList articleId=(article.id)! count=3>
      <#if data?? && (data?size > 0)>
        <section class="article-related mt-12" aria-label="相关推荐">
          <h2 class="article-related__title">相关推荐</h2>
          <div class="article-related__grid">
            <#list data as item>
              <a class="article-related__card" href="${item.url!''}">
                <#if (item.thumbnail)?? && item.thumbnail != "">
                  <span class="article-related__thumb"><img src="${item.thumbnail}" alt="${item.title!''}" loading="lazy"></span>
                </#if>
                <span class="article-related__name">${item.title!''}</span>
                <time class="article-related__time"><@formatTime value=(item.created)! format="yyyy-MM-dd"/></time>
              </a>
            </#list>
          </div>
        </section>
      </#if>
    </@relatedArticleList>
  </div>
</article>
