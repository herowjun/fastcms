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
package com.fastcms.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastcms.ai.image.DashScopeImageClient;
import com.fastcms.ai.image.ImageGenRequest;
import com.fastcms.ai.service.IAiImageTaskService;
import com.fastcms.ai.service.IAiModelConfigService;
import com.fastcms.ai.support.AiApiKeyCipher;
import com.fastcms.common.utils.DirUtils;
import com.fastcms.common.utils.FileUtils;
import com.fastcms.entity.AiImageTask;
import com.fastcms.entity.AiModelConfig;
import com.fastcms.entity.Attachment;
import com.fastcms.entity.AttachmentDirectory;
import com.fastcms.mapper.AiImageTaskMapper;
import com.fastcms.service.IAttachmentDirectoryService;
import com.fastcms.service.IAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.ObjectMapper;

/**
 * AI 生图任务服务实现
 *
 * <p>任务生命周期：submit 落库 pending → 异步线程执行（running → success/failed）。
 * 生图结果从 DashScope 下载后转存本地上传目录，并登记附件（归档到"AI 生成"目录）。</p>
 *
 * @author wjun_java@163.com
 * @since 0.3.0
 */
@Service
public class AiImageTaskServiceImpl extends ServiceImpl<AiImageTaskMapper, AiImageTask> implements IAiImageTaskService {

    private static final Logger log = LoggerFactory.getLogger(AiImageTaskServiceImpl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 生图任务默认尺寸（16:9，适配站点横幅/首图）
     */
    private static final String DEFAULT_SIZE = "1664*928";

    /**
     * 生图专用线程池（有界：生图上游单任务 10~60 秒，并发过高只会排队超时）
     */
    private final ExecutorService taskExecutor = new ThreadPoolExecutor(
            1, 4, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "ai-image-task");
                t.setDaemon(true);
                return t;
            });

    /**
     * 下载结果图片的 HTTP 客户端（与 DashScopeImageClient 分开，无鉴权、只读 URL）
     */
    private final HttpClient downloadClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    private IAiModelConfigService modelConfigService;

    @Autowired
    private IAttachmentService attachmentService;

    @Autowired
    private IAttachmentDirectoryService attachmentDirectoryService;

    @Autowired
    private com.fastcms.core.template.TemplateService templateService;

    @Override
    public AiImageTask submit(ImageGenRequest request, Long userId) {
        String taskType = normalizeTaskType(request.getTaskType());
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new IllegalArgumentException("请输入提示词");
        }
        boolean templateSource = StringUtils.hasText(request.getSourceTemplateId())
                && StringUtils.hasText(request.getSourceFilePath());
        if (AiImageTask.TYPE_EDIT.equals(taskType) && request.getSourceAttachmentId() == null && !templateSource) {
            throw new IllegalArgumentException("修图任务必须指定原图附件或模板图片文件");
        }

        // 生图模型配置（scene=image）必须存在且已激活
        AiModelConfig config = modelConfigService.getActiveConfig(IAiModelConfigService.SCENE_IMAGE);
        if (config == null) {
            throw new IllegalArgumentException("未配置生图模型，请先在模型管理中添加「生图」场景配置并激活");
        }

        AiImageTask task = new AiImageTask();
        task.setSessionId(request.getSessionId());
        task.setUserId(userId);
        task.setTaskType(taskType);
        task.setModel(config.getModel());
        task.setPrompt(request.getPrompt());
        task.setSourceAttachmentId(request.getSourceAttachmentId());
        if (templateSource) {
            // 模板 static 图片修图：源与回写目标为同一文件，生成结果仅存附件库供用户对比，
            // 用户确认后调用 applyToTemplate 回写（回写前原图备份为 .bak）
            task.setTemplateId(request.getSourceTemplateId());
            task.setTemplateFilePath(request.getSourceFilePath());
        }
        task.setSize(StringUtils.hasText(request.getSize()) ? request.getSize() : DEFAULT_SIZE);
        task.setNum(request.getNum() == null ? 1 : Math.max(1, Math.min(4, request.getNum())));
        task.setStatus(AiImageTask.STATUS_PENDING);
        save(task);
        log.info("AI 生图任务已创建: id={}, type={}, model={}, userId={}", task.getId(), taskType, config.getModel(), userId);

        submitExecution(task.getId());
        return task;
    }

    @Override
    public AiImageTask getTaskDetail(Long id) {
        AiImageTask task = getById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        fillResults(task);
        return task;
    }

    @Override
    public AiImageTask retry(Long id) {
        AiImageTask task = getById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        if (!AiImageTask.STATUS_FAILED.equals(task.getStatus())) {
            throw new IllegalArgumentException("仅失败的任务可以重试");
        }
        // 重置为待执行并重新入队
        task.setStatus(AiImageTask.STATUS_PENDING);
        task.setError(null);
        task.setResultPaths(null);
        task.getResults().clear();
        updateById(task);
        submitExecution(id);
        log.info("AI 生图任务重试: id={}", id);
        return task;
    }

    @Override
    public AiImageTask applyToTemplate(Long id) {
        AiImageTask task = getById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        if (!AiImageTask.TYPE_EDIT.equals(task.getTaskType())
                || !StringUtils.hasText(task.getTemplateId())
                || !StringUtils.hasText(task.getTemplateFilePath())) {
            throw new IllegalArgumentException("仅模板图片修图任务支持应用");
        }
        if (!AiImageTask.STATUS_SUCCESS.equals(task.getStatus())) {
            throw new IllegalArgumentException("仅已成功的修图任务可以应用");
        }
        fillResults(task);
        if (task.getResults() == null || task.getResults().isEmpty()) {
            throw new IllegalArgumentException("任务无生成结果，无法应用");
        }
        writeBackToTemplate(task, task.getResults().get(0));
        return task;
    }

    @Override
    public Page<AiImageTask> pageTasks(Page<AiImageTask> page, Long userId, boolean isAdmin, String taskType, String status) {
        Page<AiImageTask> result = page(page, Wrappers.<AiImageTask>lambdaQuery()
                .eq(!isAdmin, AiImageTask::getUserId, userId)
                .eq(StringUtils.hasText(taskType), AiImageTask::getTaskType, taskType)
                .eq(StringUtils.hasText(status), AiImageTask::getStatus, status)
                .orderByDesc(AiImageTask::getId));
        result.getRecords().forEach(this::fillResults);
        return result;
    }

    /**
     * 提交异步执行；池满时直接标记失败（明确提示而非静默排队）
     */
    private void submitExecution(Long taskId) {
        try {
            taskExecutor.execute(() -> {
                try {
                    execute(taskId);
                } catch (Exception e) {
                    log.error("AI 生图任务执行异常: taskId={}", taskId, e);
                    markFailed(taskId, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("AI 生图任务被拒绝（线程池已满）: taskId={}", taskId);
            markFailed(taskId, "当前生图任务并发已达上限，请稍后重试");
        }
    }

    /**
     * 执行生图任务：pending → running → 调 DashScope → 下载转存附件库 → success/failed
     */
    private void execute(Long taskId) {
        AiImageTask task = getById(taskId);
        if (task == null || !AiImageTask.STATUS_PENDING.equals(task.getStatus())) {
            // 已被重试/取消的旧任务不再执行
            return;
        }
        // 乐观标记 running（并发重试时只允许一个执行）
        boolean marked = update(Wrappers.<AiImageTask>lambdaUpdate()
                .eq(AiImageTask::getId, taskId)
                .eq(AiImageTask::getStatus, AiImageTask.STATUS_PENDING)
                .set(AiImageTask::getStatus, AiImageTask.STATUS_RUNNING));
        if (!marked) {
            return;
        }

        try {
            // 每次执行前重新取配置（激活配置可能在任务排队期间被切换）
            AiModelConfig config = modelConfigService.getActiveConfig(IAiModelConfigService.SCENE_IMAGE);
            if (config == null) {
                throw new IllegalStateException("生图模型配置已被移除，请先在模型管理中配置「生图」场景并激活");
            }
            String apiKey = AiApiKeyCipher.decryptIfNeeded(config.getApiKey());
            if (!StringUtils.hasText(apiKey)) {
                throw new IllegalStateException("生图模型未配置 API Key，请在模型管理中补齐");
            }
            DashScopeImageClient client = new DashScopeImageClient(config.getBaseUrl(), apiKey);

            List<String> urls;
            if (AiImageTask.TYPE_EDIT.equals(task.getTaskType())) {
                byte[] sourceBytes;
                if (StringUtils.hasText(task.getTemplateId())) {
                    // 模板 static 图片修图：从模板目录读取原图
                    sourceBytes = readTemplateSourceImage(task.getTemplateId(), task.getTemplateFilePath());
                } else {
                    sourceBytes = readSourceImage(task.getSourceAttachmentId());
                }
                urls = client.edit(config.getModel(), task.getPrompt(), sourceBytes, "png", task.getSize(), task.getNum());
            } else {
                urls = client.generate(config.getModel(), task.getPrompt(), task.getSize(), task.getNum());
            }

            List<AiImageTask.TaskResult> results = new ArrayList<>(urls.size());
            for (String url : urls) {
                results.add(saveToAttachment(url, task));
            }

            // 模板 static 图片修图不在此处回写：结果仅存附件库，前端展示原图/生成图对比，
            // 用户确认满意后调用 applyToTemplate 回写模板文件（原图备份 .bak）

            task = getById(taskId);
            task.setStatus(AiImageTask.STATUS_SUCCESS);
            task.setResultPaths(MAPPER.writeValueAsString(results));
            task.setError(null);
            updateById(task);
            log.info("AI 生图任务成功: id={}, 生成 {} 张", taskId, results.size());
        } catch (Exception e) {
            log.error("AI 生图任务失败: id={}", taskId, e);
            markFailed(taskId, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 读取修图原图（附件库文件）
     */
    private byte[] readSourceImage(Long attachmentId) {
        Attachment attachment = attachmentService.getById(attachmentId);
        if (attachment == null) {
            throw new IllegalArgumentException("修图原图附件不存在: " + attachmentId);
        }
        File file = new File(DirUtils.getUploadDir(), attachment.getFilePath());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("修图原图文件不存在: " + attachment.getFilePath());
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("读取修图原图失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取修图原图（模板 static 目录文件）
     *
     * @param templateId 模板 ID
     * @param filePath   模板内相对路径（可能带模板目录前缀，与文件树返回格式一致）
     */
    private byte[] readTemplateSourceImage(String templateId, String filePath) {
        Path source = resolveTemplateFile(templateId, filePath);
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new IllegalStateException("读取模板图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * 修图结果回写模板文件（用户确认应用时调用）：原图备份为同目录 .bak
     * （存在则跳过，保留最早原图），结果图（附件库文件）覆盖原路径。
     * 回写失败抛异常，由调用方（applyToTemplate → Controller）转为失败响应。
     */
    private void writeBackToTemplate(AiImageTask task, AiImageTask.TaskResult result) {
        try {
            Path target = resolveTemplateFile(task.getTemplateId(), task.getTemplateFilePath());
            Path backup = target.resolveSibling(target.getFileName().toString() + ".bak");
            if (!Files.exists(backup)) {
                Files.copy(target, backup);
            }
            Attachment resultAttachment = attachmentService.getById(result.getAttachmentId());
            if (resultAttachment == null) {
                throw new IllegalStateException("修图结果附件不存在: " + result.getAttachmentId());
            }
            Files.copy(new File(DirUtils.getUploadDir(), resultAttachment.getFilePath()).toPath(), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("模板图片已回写: templateId={}, path={}", task.getTemplateId(), task.getTemplateFilePath());
        } catch (IOException e) {
            throw new IllegalStateException("修图结果回写模板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 定位模板内文件（含路径穿越防护）：文件树返回的 filePath 带模板目录名前缀时先截掉
     */
    private Path resolveTemplateFile(String templateId, String filePath) {
        com.fastcms.core.template.Template template = templateService.getTemplate(templateId);
        if (template == null || template.getTemplatePath() == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }
        String relPath = filePath;
        if (StringUtils.hasText(template.getPathName()) && relPath.startsWith(template.getPathName() + "/")) {
            relPath = relPath.substring(template.getPathName().length() + 1);
        }
        Path root = template.getTemplatePath();
        Path resolved = root.resolve(relPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法模板文件路径: " + filePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("模板图片文件不存在: " + filePath);
        }
        return resolved;
    }

    /**
     * 下载生成图片并转存附件库（归档到"AI 生成"目录）
     */
    private AiImageTask.TaskResult saveToAttachment(String url, AiImageTask task) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = downloadClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("下载生成图片失败 HTTP " + response.statusCode());
            }
            byte[] bytes = response.body();

            String newFilePath = FileUtils.newFileName("ai-image.png");
            File target = new File(DirUtils.getUploadDir(), newFilePath);
            if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) {
                throw new IllegalStateException("创建上传目录失败: " + target.getParentFile());
            }
            Files.write(target.toPath(), bytes);

            Attachment attachment = new Attachment();
            attachment.setFileName("AI生图-" + LocalDateTime.now().format(TS_FMT) + ".png");
            attachment.setFilePath(newFilePath.replace("\\", "/"));
            attachment.setFileType(Attachment.TYPE_IMAGE);
            AttachmentDirectory aiDir = attachmentDirectoryService.getOrCreateAiGeneratedDir();
            attachment.setDirectoryId(aiDir == null ? 0L : aiDir.getId());
            attachmentService.save(attachment);

            AiImageTask.TaskResult result = new AiImageTask.TaskResult();
            result.setFilePath(attachment.getFilePath());
            result.setAttachmentId(attachment.getId());
            result.setUrl(attachment.getPath());
            return result;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("保存生成图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * 标记任务失败（并发安全：直接 update 不依赖内存态）
     */
    private void markFailed(Long taskId, String error) {
        AiImageTask task = getById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(AiImageTask.STATUS_FAILED);
        task.setError(error);
        updateById(task);
    }

    /**
     * 解析 resultPaths JSON 到 results 视图（补全 url：附件库相对 filePath → 完整 URL）
     */
    private void fillResults(AiImageTask task) {
        if (!StringUtils.hasText(task.getResultPaths())) {
            return;
        }
        try {
            List<AiImageTask.TaskResult> results = MAPPER.readValue(task.getResultPaths(),
                    new tools.jackson.core.type.TypeReference<List<AiImageTask.TaskResult>>() {
                    });
            for (AiImageTask.TaskResult result : results) {
                if (!StringUtils.hasText(result.getUrl()) && result.getAttachmentId() != null) {
                    Attachment attachment = attachmentService.getById(result.getAttachmentId());
                    result.setUrl(attachment == null ? result.getFilePath() : attachment.getPath());
                }
            }
            task.setResults(results);
        } catch (Exception e) {
            log.warn("解析生图任务结果失败: taskId={}, error={}", task.getId(), e.getMessage());
        }
    }

    /**
     * 任务类型归一化
     */
    private static String normalizeTaskType(String taskType) {
        return AiImageTask.TYPE_EDIT.equals(taskType) ? AiImageTask.TYPE_EDIT : AiImageTask.TYPE_T2I;
    }

}
