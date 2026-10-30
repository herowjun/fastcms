/**
 * Copyright (c) 广州小橘灯信息科技有限公司 2016-2017, wjun_java@163.com.
 * <p>
 * Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * http://www.xjd2020.com
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fastcms.plugin;

import com.fastcms.common.utils.FastcmsInstallState;
import com.fastcms.plugin.compat.FastcmsPluginDescriptorFinder;
import com.fastcms.plugin.compat.PluginCompatibility;
import com.fastcms.plugin.extension.FastcmsSpringExtensionFactory;
import com.fastcms.plugin.register.CompoundPluginRegister;
import org.pf4j.DefaultPluginManager;
import org.pf4j.ExtensionFactory;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * fastcms插件管理器
 * @author： wjun_java@163.com
 * @date： 2021/4/21
 * @description：
 * @modifiedBy：
 * @version: 1.0
 */
public class FastcmsPluginManager extends DefaultPluginManager implements PluginManagerService, ApplicationContextAware, InitializingBean, ApplicationListener<ApplicationStartedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FastcmsPluginManager.class);

    private ApplicationContext applicationContext;

    private PluginRegister pluginRegister;

    public FastcmsPluginManager(Path... pluginsRoots) {
        super(pluginsRoots);
    }

//    @Override
//    protected ExtensionFinder createExtensionFinder() {
//        FastcmsExtensionFinder extensionFinder = new FastcmsExtensionFinder(this);
//        addPluginStateListener(extensionFinder);
//
//        return extensionFinder;
//    }

    @Override
    protected ExtensionFactory createExtensionFactory() {
        return new FastcmsSpringExtensionFactory(this);
    }

    /**
     * 使用 fastcms 自有描述符查找器：额外读取 {@code Plugin-Fastcms-Version} 兼容声明。
     *
     * <p>pf4j 默认的 {@link org.pf4j.ManifestPluginDescriptorFinder} 只填硬编码的
     * 标准字段，无法携带任意 manifest 键。</p>
     */
    @Override
    protected PluginDescriptorFinder createPluginDescriptorFinder() {
        return new FastcmsPluginDescriptorFinder();
    }

    /**
     * 插件有效性判定：叠加 fastcms 版本兼容检查。
     *
     * <p><b>为什么必须在这里拦</b>：pf4j 自带的 {@code isPluginValid} 只比对
     * {@code systemVersion}，而它默认为 {@code "0.0.0"} → 整个校验被短路绕过
     * （fastcms 也从未调用 {@code setSystemVersion}）。结果是<b>主程序与插件版本错配
     * 在运行时完全无法被检测</b>——插件照常加载，只是技能正文可能比主程序期望的旧，
     * 产出质量无声下降。</p>
     *
     * <p>pf4j 在 {@code loadPlugin} 与 {@code enablePlugin} 两条路径上都会调用本方法，
     * 因此不兼容的插件在<b>加载阶段</b>就被拦下（置为 DISABLED），不会进入
     * {@code startPlugins()}，其扩展（含 SkillProvider）也不会被注册——
     * 杜绝「加载了但用不了」的半吊子状态。</p>
     */
    @Override
    protected boolean isPluginValid(PluginWrapper pluginWrapper) {
        PluginDescriptor descriptor = pluginWrapper.getDescriptor();
        PluginCompatibility.Result result = PluginCompatibility.check(descriptor, PluginCompatibility.currentFastcmsVersion());
        if (!result.compatible()) {
            log.error("【插件版本不兼容】插件 [{}] 已被拒绝加载：{}", descriptor.getPluginId(), result.reason());
            return false;
        }
        String required = PluginCompatibility.requiredVersion(descriptor);
        if (required != null && PluginCompatibility.currentFastcmsVersion() == null) {
            log.warn("【插件版本未校验】插件 [{}] 声明要求 fastcms >= {}，"
                            + "但当前主程序版本无法读取（fastcms-version.txt 缺失或未过滤），"
                            + "本次未做兼容性判定——请确认插件与主程序版本匹配",
                    descriptor.getPluginId(), required);
        }
        return super.isPluginValid(pluginWrapper);
    }

    @Override
    public void installPlugin(Path path) throws Exception {
        final String pluginId = loadPlugin(path);
        startPlugin(pluginId);
        pluginRegister.registry(pluginId);
    }

    @Override
    public void unInstallPlugin(String pluginId) throws Exception {
        try {
            pluginRegister.unRegistry(pluginId);
            deletePlugin(pluginId);
        } catch (Exception e) {
            throw new Exception(e.getMessage());
        }
    }

    @Override
    public void initPlugins() throws Exception {
        loadPlugins();
        startPlugins();

        for (PluginWrapper item : getStartedPlugins()) {
            pluginRegister.registry(item.getPluginId());
        }

    }

    @Override
    public PluginResult getPluginList(int pageNum, int pageSize, String pluginId, String provider) {
        List<PluginWrapper> plugins = getPlugins();
        if(plugins == null || plugins.size() <=0) {
            return new PluginResult(0, new ArrayList<>());
        }

        // 记录总数
        final int count = plugins.size();
        // 总页数
        int pageCount;
        if (count % pageSize == 0) {
            pageCount = count / pageSize;
        } else {
            pageCount = count / pageSize + 1;
        }

        // 开始索引
        int fromIndex;
        // 结束索引
        int toIndex;

        if (pageNum < pageCount) {
            fromIndex = (pageNum - 1) * pageSize;
            toIndex = fromIndex + pageSize;
        } else {
            fromIndex = (pageNum - 1) * pageSize;
            toIndex = count;
        }

        List<PluginWrapper> wrapperList = plugins.subList(fromIndex, toIndex);

        List<PluginVo> pluginVoList = wrapperList.stream().map(item -> new PluginVo(item)).collect(Collectors.toList());
        return new PluginResult(count, pluginVoList);
    }

    @Override
    public FastcmsPluginManager getPluginManager() {
        return this;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        pluginRegister = new CompoundPluginRegister(this);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        try {
            // 未安装模式（哑数据源启动）下跳过插件加载：插件注册链可能触碰数据库，待安装完成重启后再加载
            if (FastcmsInstallState.isInstallMode()) {
                log.warn("系统处于未安装模式，跳过插件加载，请先完成安装向导并重启应用");
                return;
            }
            // 收集各模块（如 ai-starter 的 AI 工具注册器）通过 Spring 容器贡献的 PluginRegister，
            // 追加到注册链末尾后再初始化插件：此时容器已刷新完毕，bean 依赖可正常解析
            if (applicationContext != null && pluginRegister instanceof CompoundPluginRegister compoundRegister) {
                applicationContext.getBeansOfType(PluginRegister.class)
                        .forEach((name, register) -> compoundRegister.addRegister(register));
            }
            initPlugins();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

}
