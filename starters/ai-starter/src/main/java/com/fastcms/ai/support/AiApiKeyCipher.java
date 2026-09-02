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
package com.fastcms.ai.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 模型配置 API Key 加解密工具（AES-256-GCM）
 *
 * <p>解决 api_key 在 ai_model_config 表中明文落库的问题：数据库泄漏时密钥即泄漏。
 * 加密值带 {@code {AES}} 前缀，明文旧数据（无前缀）读取时原样返回、再次保存时自动加密，
 * 平滑兼容历史数据。</p>
 *
 * <p>主密钥来源（优先级从高到低）：</p>
 * <ol>
 *     <li>配置项 {@code fastcms.ai.api-key-secret}（SHA-256 派生 256-bit 密钥，适合多实例部署共用同一密钥）</li>
 *     <li>密钥文件 {@code ~/fastcms/ai-api-key.secret}（首次使用自动生成 32 字节随机密钥并落盘）</li>
 * </ol>
 *
 * <p>注意：密钥文件一旦丢失，已加密的 API Key 无法解密（会在调用处抛出明确异常），
 * 需在模型管理中重新保存各配置的 API Key。</p>
 *
 * @author wjun_java@163.com
 * @since 0.2.0
 */
public final class AiApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(AiApiKeyCipher.class);

    /**
     * 加密值前缀标识（历史明文无前缀，读取时按明文处理）
     */
    private static final String PREFIX = "{AES}";

    private static final Object INIT_LOCK = new Object();

    private static volatile SecretKey key;

    private AiApiKeyCipher() {
    }

    /**
     * 用配置的主密钥初始化（由 FastcmsAiAutoConfiguration 启动时调用；未调用时首次使用自动按密钥文件初始化）
     */
    public static void init(String configuredSecret) {
        synchronized (INIT_LOCK) {
            key = loadOrCreateKey(configuredSecret);
        }
        log.info("AI API Key 加密密钥已初始化（来源：{}）",
                StringUtils.hasText(configuredSecret) ? "配置项 fastcms.ai.api-key-secret" : "密钥文件");
    }

    private static SecretKey ensureKey() {
        SecretKey k = key;
        if (k == null) {
            synchronized (INIT_LOCK) {
                if (key == null) {
                    key = loadOrCreateKey(null);
                }
                k = key;
            }
        }
        return k;
    }

    private static SecretKey loadOrCreateKey(String configuredSecret) {
        try {
            if (StringUtils.hasText(configuredSecret)) {
                byte[] derived = MessageDigest.getInstance("SHA-256")
                        .digest(configuredSecret.getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(derived, "AES");
            }
            Path keyFile = Paths.get(System.getProperty("user.home"), "fastcms", "ai-api-key.secret");
            if (Files.exists(keyFile)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                return new SecretKeySpec(raw, "AES");
            }
            // 首次使用：生成随机密钥并落盘（重启后可解密已存储的值）
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
            log.info("已生成 AI API Key 加密密钥文件: {}", keyFile);
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 AI API Key 加密密钥失败", e);
        }
    }

    /**
     * 是否为加密格式值
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 加密（空值或已加密值原样返回，幂等）
     */
    public static String encrypt(String plain) {
        if (!StringUtils.hasText(plain) || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, ensureKey(), new GCMParameterSpec(128, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AI API Key 加密失败", e);
        }
    }

    /**
     * 解密：非加密格式（历史明文数据）原样返回；解密失败抛出明确异常（密钥变更场景）
     */
    public static String decryptIfNeeded(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, ensureKey(), new GCMParameterSpec(128, all, 0, 12));
            byte[] plain = cipher.doFinal(all, 12, all.length - 12);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI API Key 解密失败（加密密钥可能已变更），请在模型管理中重新保存 API Key", e);
        }
    }

}
