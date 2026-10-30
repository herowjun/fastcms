# FastCMS 官网 · 多页站点原型

`doc/wiki/fastcms-site/` 是把此前两个**单文件**原型（`fastcms-landing-v2.html` 落地页、`fastcms-market-detail.html` 详情页+购买）重构成的**多页静态站点**：多个 HTML 页面、可点击的菜单相互跳转，CSS / JS 分别独立放在 `css/`、`js/` 目录。纯前端、零依赖、无框架、无 CDN，浏览器直接打开 `index.html` 即可。

单文件原型保留原样作对照，本目录不修改任何源码。

## 目录结构

```
fastcms-site/
├─ index.html           首页：Hero(AI对话演示) / 核心能力 / AI建站 / 插件市场预览 / 定价 / 技术架构 / CTA
├─ plugins.html         插件市场：搜索框 + 5 分类页签 + 全部 21 张插件卡
├─ plugin-detail.html   插件详情 + 购买抽屉（4 状态）·「微信支付」示例
├─ css/
│   ├─ site.css         全局：设计令牌 / 基础 / 按钮 / 导航 / 页脚
│   └─ pages.css        页面级：首页各区块 / 市场 / 面包屑 / 详情 / 购买卡 / 购买抽屉
├─ js/
│   ├─ site.js          公共：移动端菜单
│   ├─ home.js          首页：AI 对话演示动画
│   ├─ market.js        市场：页签切换 + hash 定位 + 搜索过滤
│   └─ detail.js        详情：购买抽屉状态机（确认/支付/成功/超时）
└─ README.md            本文件
```

## 页面跳转关系

- **顶部导航**（三页共用，`js/site.js` 负责移动端折叠）：
  - 核心能力 / AI 建站 / 定价 / 技术架构 → `index.html#锚点`
  - 插件市场 → `plugins.html`（在 `plugins.html` 与 `plugin-detail.html` 上高亮 active）
  - 源码下载 → Gitee；免费开始使用 → `index.html#cta`
- **首页**：Hero「逛逛插件市场 →」→ `plugins.html`；市场预览卡 / 「进入插件市场 →」→ 详情页或 `plugins.html`
- **插件市场**：卡片点击 → `plugin-detail.html`（原型阶段所有卡都指向「微信支付」示例详情）；页签可 hash 直达（如 `plugins.html#wx`）
- **详情**：面包屑 → `plugins.html` / `plugins.html#wx`；「去后台安装」→ `index.html#cta`

## 购买抽屉四状态（`js/detail.js`）

一个抽屉、四种状态，与 `fastcms-market-detail.html` 单文件版完全一致：

| 状态 | 内容 | 底按钮 |
|---|---|---|
| confirm 确认 | 商品摘要 + 授权站点 + 优惠券 + 应付金额 | 微信支付 ¥X |
| paying 支付 | 订单号 + 二维码 + 15:00 倒计时 | —（模拟支付成功/超时） |
| done 成功 | 订单号 + 授权码（可复制） | 去后台安装 |
| timeout 超时 | 订单已关闭 | 重新下单 |

金额联动（已实测）：1 年 ¥499 · 3 年 ¥1,299（省 15%）· 演示券码 `fastcms` 抵 ¥50 → 1年+券 ¥449、3年+券 ¥1,249；右卡价、订单总额、底按钮金额三处实时同步。

## 诚实标注（原型须知）

1. **定价为示例**（1年 ¥499 / 3年 ¥1,299 / 订阅 月/季/年），以插件市场正式开放时为准。
2. **详情页目前只有「微信支付」一个示例**，其余插件共用同一套模板；免费插件的购买卡会换成「下载安装」。
3. **购买抽屉是演示状态机**：二维码为占位图、支付成功/超时为模拟按钮。正式环境对接 `wechat-pay` 插件 Native 支付二维码 + 订单轮询 + `IPayBackProcessOrderService` 发授权码。
4. **更新日志为占位**，上架后取真实版本记录。
5. **搜索为纯前端过滤**（按名称/能力关键词在当前分类内过滤），未接后端索引。
6. 插件市场当前是**架构预留**（无远程索引），页面已标注「示例 / 内测」。

## 后端对接（见 `fastcms-site-design.md` §5.6 / §5.7）

- `order` / `order_item` / `payment_record` 表已存在，`product_type` 区分插件 / 订阅；
- 授权码签发走 `IPayBackProcessOrderService` 扩展点（AI 订阅已有同链路）；
- 待补：远程插件索引、商品主数据、插件授权校验、付费 jar 分发。

## 验证记录

- 静态自检：内部链接齐全、标签配平（index 28.6KB / plugins 15.9KB / detail 13.3KB / site.css 4.1KB / pages.css 24.2KB / 4 个 JS）。
- 浏览器实测：首页 21 卡 + 对话演示；市场页签切换 + hash 直达 + 搜索过滤（「公众号」3 命中）；详情面包屑 + 四状态流转 + 金额三处同步 + 授权码生成 + 超时重下单，全部通过。
