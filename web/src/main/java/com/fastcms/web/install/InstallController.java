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
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.fastcms.web.install;

import com.fastcms.common.constants.FastcmsConstants;
import com.fastcms.common.model.RestResult;
import com.fastcms.common.model.RestResultUtils;
import com.fastcms.common.utils.FastcmsInstallState;
import com.fastcms.web.Fastcms;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 安装向导接口：仅在未安装模式（哑数据源启动）下有效，
 * 路径已在 fastcms.security.ignore.urls 中放行，由 InstallGuardFilter 保证已安装后不可访问
 */
@RestController
@RequestMapping(FastcmsConstants.INSTALL_MAPPING)
public class InstallController {

    /**
     * 安装成功后的自动重启触发器：守护线程，仅负责延迟触发一次重启信号，
     * 触发后由 Fastcms.main 的循环执行"关旧上下文 → 重跑"（此时触发线程早已结束，不受上下文关闭影响）
     */
    private static final ScheduledExecutorService RESTART_TRIGGER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "fastcms-install-restart");
        thread.setDaemon(true);
        return thread;
    });

    /** 响应提交后到触发重启的等待毫秒数，确保安装结果已送达浏览器 */
    private static final long RESTART_DELAY_MILLIS = 1500;

    @Autowired
    private InstallService installService;

    /**
     * 安装状态与支持的数据库类型（向导初始化）
     */
    @GetMapping("status")
    public RestResult<Map<String, Object>> status() {
        return RestResultUtils.success(Map.of(
                "installMode", FastcmsInstallState.isInstallMode(),
                "databases", installService.getSupportedDatabases()
        ));
    }

    /**
     * 测试数据库连接
     */
    @PostMapping("test-connection")
    public RestResult<String> testConnection(@RequestBody InstallRequest request) {
        try {
            installService.testConnection(request);
            return RestResultUtils.success("连接成功");
        } catch (InstallException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 执行安装（或配置修复）
     */
    @PostMapping("execute")
    public RestResult<String> execute(@RequestBody InstallRequest request) {
        try {
            String message = installService.execute(request);
            scheduleAutoRestart();
            return RestResultUtils.success(message);
        } catch (InstallException e) {
            return RestResultUtils.failed(e.getMessage());
        }
    }

    /**
     * 安装（或配置修复）成功后安排自动重启：延迟 1.5s（确保响应已送达浏览器）触发重启信号。
     * requestRestart 幂等，重复调用无效
     */
    private void scheduleAutoRestart() {
        RESTART_TRIGGER.schedule(Fastcms::requestRestart, RESTART_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

}
