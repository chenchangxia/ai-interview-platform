package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeVisionExtractionServiceTest {

    private MultimodalResumeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MultimodalResumeProperties();
        properties.setEnabled(true);
        properties.setProvider("dashscope");
        properties.setModel("qwen3-vl-flash");
    }

    @Test
    void shouldSendImageAndReturnExtractedText() {
        AtomicReference<String> provider = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>();
        ResumeVisionClient client = (providerId, modelName, mimeType, imageResource) -> {
            provider.set(providerId);
            model.set(modelName);
            assertThat(mimeType.toString()).isEqualTo("image/png");
            assertThat(imageResource.getFilename()).isEqualTo("resume.png");
            return "```text\n陈浩\nJava后端开发\n```";
        };
        ResumeVisionExtractionService service = new ResumeVisionExtractionService(properties, client);
        MockMultipartFile file = new MockMultipartFile(
            "file", "resume.png", "image/png", "fake-image".getBytes(StandardCharsets.UTF_8));

        String result = service.extractText(file, "image/png");

        assertThat(result).isEqualTo("陈浩\nJava后端开发");
        assertThat(provider.get()).isEqualTo("dashscope");
        assertThat(model.get()).isEqualTo("qwen3-vl-flash");
    }

    @Test
    void shouldRejectImageWhenMultimodalParsingIsDisabled() {
        properties.setEnabled(false);
        ResumeVisionExtractionService service = new ResumeVisionExtractionService(
            properties, (providerId, model, mimeType, resource) -> "unused");
        MockMultipartFile file = new MockMultipartFile(
            "file", "resume.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.extractText(file, "image/jpeg"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片简历解析未启用");
    }

    @Test
    void shouldRejectUnsupportedImageType() {
        ResumeVisionExtractionService service = new ResumeVisionExtractionService(
            properties, (providerId, model, mimeType, resource) -> "unused");
        MockMultipartFile file = new MockMultipartFile(
            "file", "resume.gif", "image/gif", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.extractText(file, "image/gif"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不支持的图片类型");
    }

    @Test
    void shouldRejectBlankVisionModelOutput() {
        ResumeVisionExtractionService service = new ResumeVisionExtractionService(
            properties, (providerId, model, mimeType, resource) -> "   ");
        MockMultipartFile file = new MockMultipartFile(
            "file", "resume.webp", "image/webp", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.extractText(file, "image/webp"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未能从图片中提取有效简历内容");
    }
}
