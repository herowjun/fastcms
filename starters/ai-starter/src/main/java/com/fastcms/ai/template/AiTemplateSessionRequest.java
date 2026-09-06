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
package com.fastcms.ai.template;

import java.io.Serializable;

/**
 * 创建 AI 模板生成会话请求
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public class AiTemplateSessionRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 模板目录名（英文，将作为 fastcms 模板的 pathName）
     */
    private String templateName;

    /**
     * 会话标题（可选）
     */
    private String title;

    /**
     * 用户需求描述（首条消息内容）
     */
    private String requirement;

    /**
     * 绑定的正式模板ID（可选）
     *
     * <p>传入时创建"调整型会话"：AI 输出直接写入该正式模板目录（写前自动备份），
     * templateName 从模板解析，requirement 可为空。</p>
     */
    private String templateId;

    /**
     * 是否适配移动端（可选，默认 true）
     *
     * <p>开启时生成响应式布局（多端断点 + 移动端汉堡菜单）；
     * 关闭时专注桌面端设计。null 视为 true（兼容旧客户端）。</p>
     */
    private Boolean mobileAdaptive;

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRequirement() {
        return requirement;
    }

    public void setRequirement(String requirement) {
        this.requirement = requirement;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public Boolean getMobileAdaptive() {
        return mobileAdaptive;
    }

    public void setMobileAdaptive(Boolean mobileAdaptive) {
        this.mobileAdaptive = mobileAdaptive;
    }

}
