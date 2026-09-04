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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LegacyTemplateUpgrader 测试：模拟旧直写 HTML 模板目录（testpipe5 结构），
 * 验证确定性升级的内容提取、备份、渲染、清理与幂等保护。
 */
class LegacyTemplateUpgraderTest {

    private LegacyTemplateUpgrader upgrader;

    @TempDir
    Path tempDir;

    /**
     * 独立于模板目录的备份根（模拟 ~/fastcms/template-backups 数据目录）
     */
    private Path backupRoot;

    @BeforeEach
    void setUp() {
        ComponentRegistry registry = new ComponentRegistry(
                List.of(new BuiltinTailwindPackProvider()));
        backupRoot = tempDir.resolve("template-backups");
        upgrader = new LegacyTemplateUpgrader(
                new PageSpecValidator(registry), new PageSpecRenderer(registry, new TokenEngine()),
                backupRoot.toString());
        legacyDir = tempDir.resolve("testpipe5");
    }

    /**
     * 旧模板目录（模拟 testpipe5：_preview_data.json + _template.properties + html/css + 图片）
     */
    private Path legacyDir;

    private void createLegacyTemplate() throws IOException {
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("_preview_data.json"), """
                {
                 "menus": [ {"name": "首页", "type": "index"} ],
                 "articles": {"titles": ["旧文章标题"], "summaries": ["旧摘要"], "suffixes": [""]},
                 "seo": {"website_title": "FastCMS - 现代化内容管理系统", "website_sub_title": "致力于互联网内容应用开发"}
                }
                """);
        Files.writeString(legacyDir.resolve("_template.properties"), "template.name=testpipe5\n");
        Files.writeString(legacyDir.resolve("index.html"), "<html><body>旧首页</body></html>");
        Files.writeString(legacyDir.resolve("_layout.html"), "<html>旧布局</html>");
        Files.createDirectories(legacyDir.resolve("static/css"));
        Files.writeString(legacyDir.resolve("static/css/base.css"), "body{}");
        Files.write(legacyDir.resolve("logo.png"), new byte[]{1, 2, 3});
    }

    @Test
    void shouldRecognizeLegacyTemplate() throws IOException {
        // 空目录不是旧模板
        Files.createDirectories(legacyDir);
        assertFalse(upgrader.isLegacy(legacyDir));

        // 有 html 无 spec = 旧模板
        createLegacyTemplate();
        assertTrue(upgrader.isLegacy(legacyDir));

        // 有 _pagespec.json = 已组件化，不可再升级
        Files.writeString(legacyDir.resolve("_pagespec.json"), "{}");
        assertFalse(upgrader.isLegacy(legacyDir));
    }

    @Test
    void shouldUpgradeWithContentPreservedAndLegacyCleaned() throws IOException {
        createLegacyTemplate();

        LegacyTemplateUpgrader.UpgradeResult result = upgrader.upgrade(legacyDir, "testpipe5");

        // 内容资产提取自 _preview_data.json
        assertEquals("FastCMS - 现代化内容管理系统", result.siteName());

        // 渲染产物：spec + 四个页面 + tokens/pack + 属性
        assertTrue(Files.isRegularFile(legacyDir.resolve("_pagespec.json")));
        assertTrue(Files.isRegularFile(legacyDir.resolve("index.html")));
        assertTrue(Files.isRegularFile(legacyDir.resolve("article.html")));

        // 旧文本文件被清理（渲染产物之外的）
        assertFalse(Files.exists(legacyDir.resolve("static/css/base.css")));
        assertTrue(result.removedFiles().contains("static/css/base.css"));

        // _layout.html 被渲染产物覆盖为新的公共布局（旧布局内容只存在于备份）
        String layout = Files.readString(legacyDir.resolve("_layout.html"));
        assertTrue(layout.contains("<#macro page>"), "升级后应为组件版公共布局");
        assertFalse(layout.contains("旧布局"), "旧布局内容不应残留");
        assertTrue(Files.isRegularFile(legacyDir.resolve("_components/tw__navbar__sticky.ftl")),
                "升级后应产出共享组件源码");

        // 二进制资源保留
        assertTrue(Files.exists(legacyDir.resolve("logo.png")));

        // 旧预览数据回写保留（菜单/文章 mock，预览质量依赖）
        String previewData = Files.readString(legacyDir.resolve("_preview_data.json"));
        assertTrue(previewData.contains("旧文章标题"));
        assertTrue(previewData.contains("现代化内容管理系统"));

        // 备份目录存在且含旧布局（位于备份根目录下，不在模板目录同级）
        assertTrue(result.backupDir() != null && Files.isDirectory(result.backupDir()));
        assertTrue(Files.exists(result.backupDir().resolve("_layout.html")));
        assertTrue(result.backupDir().getParent().equals(backupRoot),
                "备份应在备份根目录下: " + result.backupDir());
        assertFalse(result.backupDir().getParent().equals(legacyDir.getParent()),
                "备份不应落在模板目录同级（避免污染源码资源目录）");

        // 升级后目录已组件化（幂等保护生效依据）
        assertFalse(upgrader.isLegacy(legacyDir));
    }

    @Test
    void shouldRejectReupgrade() throws IOException {
        createLegacyTemplate();
        upgrader.upgrade(legacyDir, "testpipe5");
        // 幂等保护：已组件化的目录再次升级直接拒绝
        try {
            upgrader.upgrade(legacyDir, "testpipe5");
            throw new AssertionError("应当抛出 IllegalArgumentException（已组件化的目录不可再升级）");
        } catch (IllegalArgumentException expected) {
            // 期望
        } catch (IOException unexpected) {
            throw new AssertionError("不应当抛出 IOException", unexpected);
        }
    }

}