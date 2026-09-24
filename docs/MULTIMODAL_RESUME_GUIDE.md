# 图片简历多模态解析

## 功能定位

该功能为简历上传链路增加真实的图文多模态调用：用户上传 PNG、JPEG 或 WEBP
简历图片后，后端通过 Spring AI `UserMessage.media(...)` 将“图片 + 文字提取指令”
直接发送给视觉模型。模型返回的简历文本继续复用原有 Redis Stream 异步评分链路。

这不同于语音面试中的 `ASR -> 文本 LLM -> TTS` 串联方式：视觉模型在一次请求中
同时接收文本指令和图片内容。

## 处理链路

```text
上传简历
  -> Apache Tika 检测真实 MIME 类型
  -> PNG/JPEG/WEBP: Spring AI + 视觉模型提取文字
  -> PDF/DOCX/TXT: 原有 Tika 文本解析
  -> RustFS 保存原文件
  -> PostgreSQL 保存简历及提取文本
  -> Redis Stream 异步执行简历评分
```

视觉调用使用独立的 ChatClient 缓存项，不挂载 Skills、会话记忆和 Tool Advisor，避免
无关上下文影响文字提取。系统提示词明确将图片内容视为数据，并要求忽略图片中的命令，
降低图片型 Prompt 注入对提取任务的影响。

## 启用方式

该功能默认关闭，避免未配置视觉模型时影响原有文档上传。启动前配置：

```dotenv
APP_AI_MULTIMODAL_RESUME_ENABLED=true
APP_AI_MULTIMODAL_RESUME_PROVIDER=dashscope
APP_AI_MULTIMODAL_RESUME_MODEL=qwen3-vl-flash
AI_BAILIAN_API_KEY=your-api-key
```

视觉模型和 Provider 均可替换，但模型必须支持 OpenAI 兼容的图像输入。

## 接口

继续使用原接口，无需单独维护图片上传端点：

```http
POST /api/resumes/upload
Content-Type: multipart/form-data
file: resume.png
```

成功响应新增：

```json
{
  "inputModality": "IMAGE"
}
```

普通文档对应 `DOCUMENT`。

## 一键冒烟验证

后端启动后，在PowerShell 7中运行：

```powershell
.\scripts\test-multimodal-resume.ps1 `
  -ImagePath "C:\path\to\resume.png"
```

脚本会依次验证后端健康状态、图片路由、视觉模型提取、数据库持久化，并显示提取文字
长度和前300个字符。若还要等待Redis Stream评分任务完成：

```powershell
.\scripts\test-multimodal-resume.ps1 `
  -ImagePath "C:\path\to\resume.png" `
  -WaitForAnalysis
```

执行脚本会真实调用配置的视觉模型，可能产生少量费用。

## 已验证内容

- Spring AI 2.0多模态API编译通过；
- PNG、JPEG、WEBP白名单及关闭状态校验；
- 图片资源、Provider和视觉模型参数传递；
- Markdown代码块形式模型输出的清理；
- 空模型输出的失败处理；
- 4项新增单元测试通过；
- 完整后端回归测试通过；
- 前端TypeScript与Vite生产构建通过。

以上验证未产生真实视觉模型费用。正式写入“真实识别效果”指标前，还需要使用配置的
视觉模型运行固定图片测试集。

## 当前边界

- 当前支持直接上传简历图片，不会自动把扫描版PDF渲染成图片；
- 不对模型识别结果承诺100%准确，低清晰度和复杂排版可能产生误识别；
- Prompt防护只降低风险，不能宣称完全抵御图片型间接注入；
- 视觉模型调用可能产生费用，且受供应商限流和文件大小限制。

## 简历表述（完成真实样本验证后使用）

> 扩展图片简历多模态解析链路，基于Spring AI将文本指令与PNG/JPEG/WEBP图片直接发送
> 至视觉模型，提取结果复用Redis Stream异步评分流程；通过MIME白名单、文件大小限制、
> 数据边界提示和模型输出校验处理异常输入。
