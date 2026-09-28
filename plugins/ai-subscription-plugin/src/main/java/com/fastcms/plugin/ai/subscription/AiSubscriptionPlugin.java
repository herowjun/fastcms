package com.fastcms.plugin.ai.subscription;

import com.fastcms.plugin.PluginBase;
import com.fastcms.utils.PluginUtils;
import org.pf4j.PluginWrapper;

/**
 * AI 模型订阅插件。
 *
 * <p><b>一个插件，两种角色</b>：官网实例与客户实例安装的是同一个插件包，靠配置区分角色，
 * 这样签发方与使用方的授权码格式、校验逻辑天然一致，不存在两侧实现漂移的风险。</p>
 *
 * <ul>
 *   <li><b>官网侧（签发 + 中转）</b>：把"AI 模型订阅"作为商城商品上架（月度/季度/年度三档价格），
 *       用户下单支付成功后由 {@code IPayBackProcessOrderService} 扩展点签发授权码；
 *       同时提供 OpenAI 兼容的 AI 中转端点，是客户实例 AI 请求的实际出口（真 Key 只留在官网）。</li>
 *   <li><b>客户侧（激活 + 调用）</b>：后台粘贴授权码，本地用内置公钥验签（官网不可达也能判断
 *       "付没付费"，不会因官网抖动误锁功能），激活后写入 {@code ai_model_config}
 *       （{@code provider=fastcms}、{@code base_url} 指向官网中转端点），
 *       AI 模板编辑 / 文章编写 / 生图随即可用。</li>
 * </ul>
 *
 * <p><b>功能门禁为什么不需要在核内插检查点</b>：AI 请求必须经官网中转，授权过期时中转端点直接
 * 返回 401，客户侧 AI 调用自然失败并走既有的失败提示链路。这就实现了"只挡 AI 功能、
 * 网站前台与后台其他功能照常运行"，且对 fastcms 核心零侵入。</p>
 *
 * @author wjun_java@163.com
 */
public class AiSubscriptionPlugin extends PluginBase {

    public AiSubscriptionPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getConfigUrl() {
        return PluginUtils.getConfigUrlHost() + "/ai/subscription/config";
    }

    @Override
    public void start() {
        runSqlFile("ai_subscription_init.sql");
    }

}
