<#-- custom-html 兜底模板：正常渲染时渲染器会为每个 custom-html section 物化专属文件
     _components/cap__{sectionId}.ftl（snippet 展开或 data.html 原文，含 FTL 插值求值与
     hasPlugin 门控），不经过本文件。本文件仅在渲染分支缺失时兜底输出槽位原文。 -->
<section class="py-8">
    ${(comp.html)!''}
</section>
