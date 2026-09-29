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
package com.fastcms.core.template;

import com.fastcms.common.exception.I18nFastcmsException;

/**
 * 卸载模板时备份目录已存在同名备份。
 *
 * <p>刻意做成独立异常而不是普通失败：Controller 需要把它翻译成区别于 500 的业务码，
 * 前端据此弹「是否覆盖」二次确认 —— 这是唯一需要用户再决策一次的分支。</p>
 *
 * @author wjun_java@163.com
 * @since 1.0.0
 */
public class TemplateBackupExistsException extends I18nFastcmsException {

    /**
     * 备份目录已存在同名备份的业务响应码（与 HTTP 409 Conflict 同义）。
     * 前端 request 拦截器只放行 code=200，其余走 reject，故这里给一个可判别的非 200 值。
     */
    public static final int CODE = 409;

    /**
     * 冲突的备份名（模板目录名）
     */
    private final String backupName;

    public TemplateBackupExistsException(final String i18nKey, final String backupName) {
        // 带上 backupName 作为 i18n 参数，消息模板用 %s 占位（本仓约定，见 CMS_TEMPLATE_PATH_IS_EXIST）
        super(i18nKey, backupName);
        this.backupName = backupName;
    }

    public String getBackupName() {
        return backupName;
    }

}
