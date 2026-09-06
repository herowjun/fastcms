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
        // 间距基准（Tailwind v4 默认 0.25rem）：安全网 calc(var(--spacing)*N) 依赖，
        // 防御性声明保证个别 pack 未带主题变量时刻度类仍可解析（与 Tailwind 默认同值）
        sb.append("  --spacing: 0.25rem;\n");
        sb.append("}\n");
        appendPrimaryUtilityFallback(sb);
        return sb.toString();
    }

    /**
     * 主色工具类兜底层：补齐 text/bg/border × 全色阶的基础、hover 变体与 important 变体类
     *
     * <p>pack.css 是组件包的预编译静态产物，与组件源码可能不同步——源码里用到的
     * 裸工具类（如 {@code text-primary-600}，典型场景：导航选中态）在预编译时
     * 若未出现就会缺失（只有 hover: 变体），导致该类无颜色规则、样式静默失效
     * （表现为选中态回退成继承色/黑色）。本层按同一 {@code --color-primary-*}
     * 变量补齐常用类：与 pack.css 已有类重复定义同值无害，缺失类即被兜底；
     * 亦覆盖 AI 微调补丁（filePatches）新引入的主色类。</p>
     *
     * <p>覆盖三种形态：基础类（{@code .text-primary-600}）、hover 变体
     * （{@code .hover\:text-primary-600:hover}，包 {@code @media (hover:hover)}
     * 与触屏设备口径一致）、important 前缀变体（{@code .\!text-primary-600}，
     * Tailwind v4 语法，AI 补丁高频使用——用于压制同元素上其他颜色类）。
     * 复杂渐变类（from-/to-/via-）依赖 Tailwind 运行时 @property，不做兜底。</p>
     *
     * <p>附带补齐 {@code .font-normal}（font-weight:400）：pack.css 预编译按需生成，
     * 组件源码未用过即缺失，而「选中态去加粗」是 AI 补丁高频需求。</p>
     */
    private static void appendPrimaryUtilityFallback(StringBuilder sb) {
        sb.append("\n/* 主色工具类兜底层：pack.css 预编译缺的常用主色类在此补齐（同变量同值，\n");
        sb.append(" * 与 pack.css 重复定义无害；AI 样式补丁新引入的主色类同样被覆盖） */\n");
        for (String prefix : List.of("text", "bg", "border")) {
            String cssProp = switch (prefix) {
                case "text" -> "color";
                case "bg" -> "background-color";
                default -> "border-color";
            };
            for (String shade : SHADES) {
                sb.append(".").append(prefix).append("-primary-").append(shade)
                        .append(" { ").append(cssProp)
                        .append(": var(--color-primary-").append(shade).append("); }\n");
            }
        }
        sb.append("@media (hover: hover) {\n");
        for (String prefix : List.of("text", "bg", "border")) {
            String cssProp = switch (prefix) {
                case "text" -> "color";
                case "bg" -> "background-color";
                default -> "border-color";
            };
            for (String shade : SHADES) {
                sb.append("  .hover\\:").append(prefix).append("-primary-").append(shade)
                        .append(":hover { ").append(cssProp)
                        .append(": var(--color-primary-").append(shade).append("); }\n");
            }
        }
        sb.append("}\n");
        // important 前缀变体（Tailwind v4 语法 !text-primary-600，CSS 选择器转义为 .\!text-primary-600）
        for (String prefix : List.of("text", "bg", "border")) {
            String cssProp = switch (prefix) {
                case "text" -> "color";
                case "bg" -> "background-color";
                default -> "border-color";
            };
            for (String shade : SHADES) {
                sb.append(".\\!").append(prefix).append("-primary-").append(shade)
                        .append(" { ").append(cssProp)
                        .append(": var(--color-primary-").append(shade).append(") !important; }\n");
            }
        }
        appendUtilitySafetyNet(sb);
    }

    /**
     * 通用工具类安全网：标准 Tailwind 刻度的间距/宽高/gap、字号、字重、圆角类兜底
     *
     * <p>与主色兜底同理的系统性补丁：pack.css 按需预编译，AI 样式补丁引入的
     * <b>任何</b>新数值类（典型：用户要求调高度/间距/字号/圆角，AI 输出
     * {@code py-4}、{@code mt-6}、{@code h-20}、{@code text-xl} 等）都可能
     * 未被编译而静默失效。本层按 Tailwind v4 同款公式
     * （{@code calc(var(--spacing) * N)}）补齐标准刻度：</p>
     * <ul>
     *     <li>间距/宽高：p/px/py/pt/pb/pl/pr、m/mx/my/mt/mb/ml/mr、gap、w/h
     *         × 刻度 0~12、14、16、20、24、28、32、40、48、56、64</li>
     *     <li>字号：text-xs ~ text-9xl（仅 font-size；pack 已有类自带行高，重复定义同值不冲突）</li>
     *     <li>字重：font-thin ~ font-black</li>
     *     <li>圆角：rounded-none/xs/sm/md/lg/xl/2xl/3xl/full（sm~3xl 走主题变量，随风格预设缩放）</li>
     * </ul>
     *
     * <p>全部类同时输出 {@code !} important 前缀变体（AI 补丁高频用法，
     * 用于压制同元素已有类）。仅覆盖标准刻度——任意值语法
     * （{@code py-[13px]}、{@code bg-[#ff0000]}）无法预生成，
     * 由提示词约束禁止。</p>
     */
    private static void appendUtilitySafetyNet(StringBuilder sb) {
        sb.append("\n/* 通用工具类安全网：标准 Tailwind 刻度兜底（pack.css 预编译缺的数值类在此补齐，\n");
        sb.append(" * 重复定义同值无害；AI 样式补丁新引入的标准刻度类同样被覆盖。\n");
        sb.append(" * 不含任意值语法（py-[13px] 等），该语法由提示词约束禁止） */\n");

        // 间距/宽高/gap × 标准刻度，calc(var(--spacing)*N) 与 Tailwind v4 产物同式
        String[][] sizeFamilies = {
                {"p", "padding"}, {"px", "padding-inline"}, {"py", "padding-block"},
                {"pt", "padding-top"}, {"pb", "padding-bottom"}, {"pl", "padding-left"}, {"pr", "padding-right"},
                {"m", "margin"}, {"mx", "margin-inline"}, {"my", "margin-block"},
                {"mt", "margin-top"}, {"mb", "margin-bottom"}, {"ml", "margin-left"}, {"mr", "margin-right"},
                {"gap", "gap"}, {"w", "width"}, {"h", "height"}
        };
        int[] steps = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 64};
        for (String[] fam : sizeFamilies) {
            for (int step : steps) {
                String val = "calc(var(--spacing) * " + step + ")";
                sb.append(".").append(fam[0]).append("-").append(step)
                        .append(" { ").append(fam[1]).append(": ").append(val).append("; }\n");
                sb.append(".\\!").append(fam[0]).append("-").append(step)
                        .append(" { ").append(fam[1]).append(": ").append(val).append(" !important; }\n");
            }
        }
        // 常用特殊值（pack 通常已有，重复定义同值无害）
        appendScaleRule(sb, "w-full", "width", "100%");
        appendScaleRule(sb, "w-screen", "width", "100vw");
        appendScaleRule(sb, "w-auto", "width", "auto");
        appendScaleRule(sb, "h-full", "height", "100%");
        appendScaleRule(sb, "h-screen", "height", "100vh");
        appendScaleRule(sb, "h-auto", "height", "auto");
        appendScaleRule(sb, "min-h-screen", "min-height", "100vh");

        // 字号（Tailwind v4 默认刻度；仅 font-size，行高由 pack 已有类或继承决定）
        String[][] fontSizes = {
                {"text-xs", ".75rem"}, {"text-sm", ".875rem"}, {"text-base", "1rem"},
                {"text-lg", "1.125rem"}, {"text-xl", "1.25rem"}, {"text-2xl", "1.5rem"},
                {"text-3xl", "1.875rem"}, {"text-4xl", "2.25rem"}, {"text-5xl", "3rem"},
                {"text-6xl", "3.75rem"}, {"text-7xl", "4.5rem"}, {"text-8xl", "6rem"}, {"text-9xl", "8rem"}
        };
        for (String[] fs : fontSizes) {
            appendScaleRule(sb, fs[0], "font-size", fs[1]);
        }

        // 字重
        String[][] fontWeights = {
                {"font-thin", "100"}, {"font-extralight", "200"}, {"font-light", "300"},
                {"font-normal", "400"}, {"font-medium", "500"}, {"font-semibold", "600"},
                {"font-bold", "700"}, {"font-extrabold", "800"}, {"font-black", "900"}
        };
        for (String[] fw : fontWeights) {
            appendScaleRule(sb, fw[0], "font-weight", fw[1]);
        }

        // 圆角（sm~3xl 走主题变量，随风格预设缩放）
        String[][] radii = {
                {"rounded-none", "0"}, {"rounded-xs", "0.125rem"}, {"rounded-sm", "var(--radius-sm)"},
                {"rounded-md", "var(--radius-md)"}, {"rounded-lg", "var(--radius-lg)"},
                {"rounded-xl", "var(--radius-xl)"}, {"rounded-2xl", "var(--radius-2xl)"},
                {"rounded-3xl", "var(--radius-3xl)"}, {"rounded-full", "9999px"}
        };
        for (String[] r : radii) {
            appendScaleRule(sb, r[0], "border-radius", r[1]);
        }
    }

    /**
     * 输出单条安全网规则（基础 + {@code !}important 前缀变体）
     */
    private static void appendScaleRule(StringBuilder sb, String cls, String prop, String value) {
        sb.append(".").append(cls).append(" { ").append(prop).append(": ").append(value).append("; }\n");
        sb.append(".\\!").append(cls).append(" { ").append(prop).append(": ").append(value).append(" !important; }\n");
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
