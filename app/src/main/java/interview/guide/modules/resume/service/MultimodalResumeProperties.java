package interview.guide.modules.resume.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片简历多模态解析配置。
 */
@Component
@ConfigurationProperties(prefix = "app.ai.multimodal.resume")
public class MultimodalResumeProperties {

    private boolean enabled;
    private String provider = "dashscope";
    private String model = "qwen3-vl-flash";
    private long maxFileSize = 10 * 1024 * 1024;
    private List<String> allowedTypes = new ArrayList<>(List.of(
        "image/png",
        "image/jpeg",
        "image/webp"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedTypes() {
        return allowedTypes;
    }

    public void setAllowedTypes(List<String> allowedTypes) {
        this.allowedTypes = allowedTypes;
    }
}
