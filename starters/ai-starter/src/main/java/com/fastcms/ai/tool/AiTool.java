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
package com.fastcms.ai.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个方法为可被 AI 调用的工具
 *
 * <p>使用方式：</p>
 * <pre>
 * &#64;Component
 * public class ArticleTool {
 *
 *     &#64;AiTool(description = "根据文章 ID 查询文章详情")
 *     public Article getArticleById(Long id) {
 *         return articleService.getById(id);
 *     }
 *
 *     &#64;AiTool(description = "列出最新发布的 N 篇文章")
 *     public List&lt;Article&gt; listLatestArticles(int limit) {
 *         return articleService.listLatest(limit);
 *     }
 * }
 * </pre>
 *
 * <p>主工程和插件都可使用。插件中的 @AiTool 方法会被 {@code AiToolRegister}
 * 自动扫描并通过 {@link AiToolRegistry} 注册到 ChatClient。</p>
 *
 * <p><b>注意</b>：当前阶段（第一步骨架）只提供注解定义和 Registry 数据结构，
 * 真正与 Spring AI ToolCallback 桥接的代码在后续步骤实现。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiTool {

    /**
     * 工具名称，默认使用方法名
     */
    String name() default "";

    /**
     * 工具描述，会作为 prompt 提供给 AI 模型判断何时调用
     */
    String description() default "";

    /**
     * 是否直接返回结果给 AI（true）还是再让 AI 加工（false）
     * <p>默认 true，适合查询类工具；写操作工具可设为 false 让 AI 总结</p>
     */
    boolean returnDirect() default true;

}
