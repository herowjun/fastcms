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
package com.fastcms.ai.image;

/**
 * AI 生图请求（文生图/修图）
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
public class ImageGenRequest {

    /**
     * 任务类型：t2i-文生图 edit-修图
     */
    private String taskType;

    /**
     * 提示词
     */
    private String prompt;

    /**
     * 生成尺寸 宽*高（如 1664*928）
     */
    private String size;

    /**
     * 生成张数 1-4
     */
    private Integer num;

    /**
     * 修图原图附件ID（edit 时与 sourceTemplateId/sourceFilePath 二选一）
     */
    private Long sourceAttachmentId;

    /**
     * 修图源模板ID（模板 static 图片修图：与 sourceFilePath 成对传入，
     * 任务成功后结果仅存附件库供用户对比，用户确认应用时才回写该文件，
     * 回写前原图备份为 .bak）
     */
    private String sourceTemplateId;

    /**
     * 修图源模板内文件路径（与 sourceTemplateId 成对）
     */
    private String sourceFilePath;

    /**
     * 关联模板会话ID（模板 AI 调整中触发的生图才传）
     */
    private String sessionId;

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public Integer getNum() {
        return num;
    }

    public void setNum(Integer num) {
        this.num = num;
    }

    public Long getSourceAttachmentId() {
        return sourceAttachmentId;
    }

    public void setSourceAttachmentId(Long sourceAttachmentId) {
        this.sourceAttachmentId = sourceAttachmentId;
    }

    public String getSourceTemplateId() {
        return sourceTemplateId;
    }

    public void setSourceTemplateId(String sourceTemplateId) {
        this.sourceTemplateId = sourceTemplateId;
    }

    public String getSourceFilePath() {
        return sourceFilePath;
    }

    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
