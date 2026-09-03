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

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Token 引擎：主色 + 风格预设 → tokens.css（CSS 变量覆盖层）
 *
 * <p>配色质量的确定性保证——不让 AI 从零写色值，而是由本引擎用 HSL 数学生成完整色阶，
 * AI 只负责挑一个主色。生成物覆盖 Tailwind v4 的 {@code @theme} 变量
 * （{@code --color-primary-*} / {@code --font-sans} / {@code --radius-*}），
 * 在 pack.css 之后加载，全站换肤即时生效。</p>
 *
 * <p>色阶算法：保留主色色相 H 与饱和度 S，以 600 档为基准向两端推导明度；
 * 两端档位（50/900）轻微降饱和，避免极浅/极深处发灰。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
@Component
public class TokenEngine {

    /**
     * 默认主色（用户未指定时的兜底，中性蓝）
     */
    public static final String DEFAULT_PRIMARY_COLOR = "#2563eb";

    /**
     * 600 档（组件中 bg-primary-600 等使用的主档）明度归一区间：过深/过浅的主色都拉回该区间，
     * 保证推导出的色阶两端不越界
     */
    private static final double BASE_LIGHTNESS_MIN = 0.40;
    private static final double BASE_LIGHTNESS_MAX = 0.60;

    /**
     * 风格预设：字体栈 + 圆角系数（作用于 Tailwind 默认 radius 阶梯）
     */
    public record StylePreset(String fontSans, String fontMono, double radiusScale) {
    }

    private static final Map<String, StylePreset> PRESETS = Map.of(
            "minimal", new StylePreset(
                    "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
                    "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", 1.0),
            "corporate", new StylePreset(
                    "'Segoe UI', 'HarmonyOS Sans SC', 'PingFang SC', 'Microsoft YaHei', ui-sans-serif, system-ui, sans-serif",
                    "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", 0.7),
            "warm", new StylePreset(
                    "'Noto Sans SC', 'Source Han Sans SC', 'PingFang SC', 'Microsoft YaHei', ui-sans-serif, system-ui, sans-serif",
                    "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", 1.3),
            "bold", new StylePreset(
                    "ui-sans-serif, system-ui, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
                    "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", 0.5),
            "elegant", new StylePreset(
                    "'Noto Serif SC', 'Source Han Serif SC', Georgia, 'Times New Roman', serif",
                    "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", 0.9)
    );

    public static final String DEFAULT_PRESET = "minimal";

    private static final List<String> SHADES = List.of(
            "50", "100", "200", "300", "400", "500", "600", "700", "800", "900");

    /**
     * 各档位相对 600 档基准明度的偏移（顺序对应 SHADES）
     */
    private static final double[] LIGHTNESS_OFFSETS = {
            0.42, 0.35, 0.26, 0.16, 0.08, 0.04, 0.00, -0.08, -0.16, -0.23
    };

    public static List<String> presetNames() {
        return List.copyOf(PRESETS.keySet());
    }

    /**
     * 生成 tokens.css 内容
     *
     * @param primaryColor 主色（#RRGGBB），null 时用默认
     * @param stylePreset  风格预设名，null/非法时用 minimal
     */
    public String generateTokens(String primaryColor, String stylePreset) {
        int[] rgb = parseHex(primaryColor == null || primaryColor.isBlank()
                ? DEFAULT_PRIMARY_COLOR : primaryColor);
        if (rgb == null) {
            rgb = parseHex(DEFAULT_PRIMARY_COLOR);
        }
        double[] hsl = rgbToHsl(rgb[0], rgb[1], rgb[2]);
        StylePreset preset = PRESETS.getOrDefault(
                stylePreset == null ? DEFAULT_PRESET : stylePreset.toLowerCase(Locale.ROOT),
                PRESETS.get(DEFAULT_PRESET));

        // 600 档明度归一到 [0.40, 0.60]，保两端推导不越界
        double baseL = Math.clamp(hsl[2], BASE_LIGHTNESS_MIN, BASE_LIGHTNESS_MAX);

        StringBuilder sb = new StringBuilder();
        sb.append("/* 由 TokenEngine 生成的主题变量层（主色 + 风格预设）\n");
        sb.append(" * 加载顺序：pack.css（地基，含默认变量）之后，覆盖即换肤\n");
        sb.append(" * 主色: ").append(primaryColor == null ? DEFAULT_PRIMARY_COLOR : primaryColor);
        sb.append("  预设: ").append(stylePreset == null ? DEFAULT_PRESET : stylePreset).append(" */\n");
        sb.append(":root {\n");
        for (int i = 0; i < SHADES.size(); i++) {
            double l = Math.clamp(baseL + LIGHTNESS_OFFSETS[i], 0.05, 0.96);
            // 两端档位轻微降饱和，避免极浅/极深处发灰
            double s = hsl[1] * (i <= 1 || i >= 8 ? 0.75 : 1.0);
            int[] out = hslToRgb(hsl[0], s, l);
            sb.append("  --color-primary-").append(SHADES.get(i)).append(": ")
                    .append(toHex(out[0], out[1], out[2])).append(";\n");
        }
        sb.append("  --font-sans: ").append(preset.fontSans()).append(";\n");
        sb.append("  --font-mono: ").append(preset.fontMono()).append(";\n");
        // 圆角系数作用于 Tailwind 默认阶梯（保持档间比例）
        sb.append("  --radius-sm: ").append(round(0.25 * preset.radiusScale())).append("rem;\n");
        sb.append("  --radius-md: ").append(round(0.375 * preset.radiusScale())).append("rem;\n");
        sb.append("  --radius-lg: ").append(round(0.5 * preset.radiusScale())).append("rem;\n");
        sb.append("  --radius-xl: ").append(round(0.75 * preset.radiusScale())).append("rem;\n");
        sb.append("  --radius-2xl: ").append(round(1.0 * preset.radiusScale())).append("rem;\n");
        sb.append("  --radius-3xl: ").append(round(1.5 * preset.radiusScale())).append("rem;\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 是否合法的主色写法（#RRGGBB）
     */
    public static boolean isValidColor(String color) {
        return color != null && color.matches("^#[0-9a-fA-F]{6}$");
    }

    private static int[] parseHex(String hex) {
        if (!isValidColor(hex)) {
            return null;
        }
        return new int[]{
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)
        };
    }

    private static String toHex(int r, int g, int b) {
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /**
     * RGB → HSL（H∈[0,360)，S/L∈[0,1]）
     */
    private static double[] rgbToHsl(int r, int g, int b) {
        double rn = r / 255.0, gn = g / 255.0, bn = b / 255.0;
        double max = Math.max(rn, Math.max(gn, bn));
        double min = Math.min(rn, Math.min(gn, bn));
        double h, s, l = (max + min) / 2;
        if (max == min) {
            h = 0;
            s = 0;
        } else {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == rn) {
                h = (gn - bn) / d + (gn < bn ? 6 : 0);
            } else if (max == gn) {
                h = (bn - rn) / d + 2;
            } else {
                h = (rn - gn) / d + 4;
            }
            h *= 60;
        }
        return new double[]{h, s, l};
    }

    /**
     * HSL → RGB
     */
    private static int[] hslToRgb(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double hp = h / 60.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        double m = l - c / 2;
        return new int[]{
                (int) Math.round((r + m) * 255),
                (int) Math.round((g + m) * 255),
                (int) Math.round((b + m) * 255)
        };
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

}
