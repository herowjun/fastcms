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
package com.fastcms.ai.component;

/**
 * 组件槽位定义（数据 schema 的简化形式：一个组件的 data 里有哪些字段）
 *
 * @param name      槽位名（对应组件 FTL 中的 {@code comp.xxx}）
 * @param type      类型：string / number / media / list / boolean
 * @param required  是否必填
 * @param maxLength 文案长度建议（供 AI 生成时约束篇幅，可空）
 * @param desc      槽位说明（写给 AI）
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public record ComponentSlot(String name, String type, Boolean required, Integer maxLength, String desc) {

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

}
