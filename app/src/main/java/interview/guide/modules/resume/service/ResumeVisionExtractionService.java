package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 图片简历文字提取服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeVisionExtractionService {

    private final MultimodalResumeProperties properties;
    private final ResumeVisionClient visionClient;

    public boolean isImageContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        List<String> allowedTypes = properties.getAllowedTypes();
        return allowedTypes != null && allowedTypes.stream()
            .anyMatch(type -> type.equalsIgnoreCase(contentType));
    }

    public String extractText(MultipartFile file, String detectedContentType) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED,
                "图片简历解析未启用，请上传PDF、DOCX或TXT，或启用多模态视觉模型");
        }
        if (!isImageContentType(detectedContentType)) {
            throw new BusinessException(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED,
                "不支持的图片类型: " + detectedContentType);
        }
        if (file.getSize() > properties.getMaxFileSize()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片大小超过多模态解析限制");
        }
        if (properties.getProvider() == null || properties.getProvider().isBlank()
            || properties.getModel() == null || properties.getModel().isBlank()) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "图片简历解析缺少Provider或视觉模型配置");
        }

        try {
            byte[] imageBytes = file.getBytes();
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };
            MimeType mimeType = MimeTypeUtils.parseMimeType(detectedContentType);
            String extractedText = visionClient.extractResumeText(
                properties.getProvider().trim(),
                properties.getModel().trim(),
                mimeType,
                imageResource
            );
            String normalized = normalizeModelOutput(extractedText);
            if (normalized.isBlank()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED,
                    "视觉模型未能从图片中提取有效简历内容");
            }
            log.info("图片简历解析完成: filename={}, model={}, textLength={}",
                file.getOriginalFilename(), properties.getModel(), normalized.length());
            return normalized;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "读取简历图片失败", e);
        } catch (Exception e) {
            log.error("视觉模型解析简历图片失败: filename={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "视觉模型解析简历图片失败", e);
        }
    }

    private String normalizeModelOutput(String output) {
        if (output == null) {
            return "";
        }
        String normalized = output.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            int firstLineEnd = normalized.indexOf('\n');
            if (firstLineEnd >= 0) {
                normalized = normalized.substring(firstLineEnd + 1, normalized.length() - 3).trim();
            }
        }
        return normalized;
    }
}
