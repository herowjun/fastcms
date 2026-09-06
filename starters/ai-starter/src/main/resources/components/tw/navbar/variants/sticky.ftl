<#-- 导航栏 / sticky 变体：品牌名 + CMS 菜单，吸顶悬浮
     菜单高亮：正式环境由框架注入 request 变量，预览环境由预览渲染器注入 mock request
     （requestURI = 预览页地址），两端统一按 requestURI 前缀匹配输出 active。
     移动端适配：读取布局注入的 (mobileAdaptive!true)，开启时输出汉堡菜单 + 竖向展开菜单。 -->
<#assign cp = (request.contextPath)!''>
<#assign currentUri = cp + ((request.requestURI)!((request.url)!))>
<#-- 菜单项是否选中：URL 前缀匹配（子页面命中时父级菜单同样高亮） -->
<#function navIsActive url>
  <#if !(url??) || url == '' || url == '#' || url == '/' || !(currentUri??) || currentUri == ''>
    <#return false>
  </#if>
  <#return currentUri?starts_with(url)>
</#function>
<#-- 首页是否选中：URI 等于根路径（生产）或以 /index.html 结尾（预览）才算，
     避免 starts_with('/') 恒真 -->
<#function navIsHome>
  <#return currentUri == (cp + '/') || currentUri == cp || currentUri?ends_with('/index.html')>
</#function>
<#-- 菜单项渲染条件：CMS 菜单数据中常含"首页"（url=/），与本组件硬编码的首页链接重复，跳过 -->
<#function navShowItem item>
  <#local u = (item.url!'')>
  <#return item.menuName?? && item.menuName?has_content && u != '' && u != '/'>
</#function>
<#assign mobile = (mobileAdaptive!true)>
<section class="sticky top-0 z-50 border-b border-slate-200/80 bg-white/80 backdrop-blur">
  <div class="mx-auto flex max-w-6xl flex-wrap items-center justify-between px-4">
    <a href="/" class="text-lg font-bold tracking-tight text-slate-900">${(comp.brand)!''}</a>
    <#if mobile>
    <#-- 移动端汉堡按钮（纯 CSS：checkbox + :checked 兄弟选择器，不依赖 JS） -->
    <input type="checkbox" id="ai-nav-toggle" class="ai-nav-toggle" aria-label="展开导航菜单">
    <label for="ai-nav-toggle" class="ai-nav-toggle-label" aria-label="菜单">
      <svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none"
           stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <line x1="3" y1="6" x2="21" y2="6"></line>
        <line x1="3" y1="12" x2="21" y2="12"></line>
        <line x1="3" y1="18" x2="21" y2="18"></line>
      </svg>
    </label>
    </#if>
    <nav aria-label="主导航">
      <ul class="ai-nav-menu hidden items-center gap-8 md:flex">
        <li>
          <a href="/" class="text-sm transition-colors hover:text-primary-600<#if navIsHome()> text-primary-600 font-semibold<#else> text-slate-600</#if>">首页</a>
        </li>
        <@menuTag>
          <#if data??>
            <#list data as item>
              <#if navShowItem(item)>
                <li>
                  <a href="${item.url!''}" target="${item.target!'_self'}"
                     class="text-sm transition-colors hover:text-primary-600<#if navIsActive(item.url!'')> text-primary-600 font-semibold<#else> text-slate-600</#if>">${item.menuName}</a>
                </li>
              </#if>
            </#list>
          </#if>
        </@menuTag>
      </ul>
    </nav>
    <#if mobile>
    <#-- 移动端竖向展开菜单（与桌面菜单同一数据源、同一高亮口径） -->
    <div class="ai-nav-mobile">
      <ul class="ai-nav-mobile-list">
        <li>
          <a href="/" class="block border-t border-slate-100 px-4 py-3 text-sm<#if navIsHome()> text-primary-600 font-semibold<#else> text-slate-700</#if>">首页</a>
        </li>
        <@menuTag>
          <#if data??>
            <#list data as item>
              <#if navShowItem(item)>
                <li>
                  <a href="${item.url!''}" target="${item.target!'_self'}"
                     class="block border-t border-slate-100 px-4 py-3 text-sm<#if navIsActive(item.url!'')> text-primary-600 font-semibold<#else> text-slate-700</#if>">${item.menuName}</a>
                </li>
              </#if>
            </#list>
          </#if>
        </@menuTag>
      </ul>
    </div>
    </#if>
  </div>
  <#if mobile>
  <style>
    /* 移动端汉堡菜单（≤768px 生效；桌面菜单由 hidden md:flex 控制） */
    .ai-nav-toggle { display: none; }
    .ai-nav-toggle-label {
      display: none; cursor: pointer; color: #334155; padding: .25rem;
    }
    .ai-nav-mobile { display: none; }
    @media (max-width: 47.99rem) {
      .ai-nav-toggle-label { display: inline-flex; }
      .ai-nav-menu { display: none !important; }
      .ai-nav-mobile { display: block; width: 100%; }
      .ai-nav-mobile-list { display: none; }
      .ai-nav-toggle:checked ~ .ai-nav-mobile .ai-nav-mobile-list {
        display: block;
        background: #ffffff;
        border-top: 1px solid #e2e8f0;
      }
      .ai-nav-toggle:checked ~ .ai-nav-toggle-label { color: #2563eb; }
    }
  </style>
  </#if>
</section>
