package interview.guide.modules.resume.service;

import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;

/**
 * 视觉模型调用边界，便于隔离 Spring AI 调用与业务验证逻辑。
 */
public interface ResumeVisionClient {

    String extractResumeText(
        String providerId,
        String model,
        MimeType mimeType,
        Resource imageResource
    );
}
