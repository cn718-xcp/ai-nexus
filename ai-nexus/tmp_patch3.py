import io

def patch(p, pairs):
    s = io.open(p, encoding='utf-8').read()
    for old, new in pairs:
        if old not in s:
            print('MISS in', p, ':', old[:70].replace(chr(10), ' | '))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# AppConfig：ModelRegistry + ModelInvoker Bean
patch('src/main/java/com/ainexus/config/AppConfig.java', [(
"""    @Bean(\"aiCallExecutor\")
    public Executor aiCallExecutor() {""",
"""    /** 模型注册表：统一 ollama/openai 协议适配（模型键缓存） */
    @Bean
    public com.ainexus.ai.model.ModelRegistry modelRegistry(
            org.springframework.ai.ollama.api.OllamaApi ollamaApi,
            @Value("${ainexus.models.openai.base-url:https://api.openai.com}") String openaiBaseUrl,
            @Value("${ainexus.models.openai.api-key:}") String openaiApiKey) {
        return new com.ainexus.ai.model.ModelRegistry(ollamaApi, openaiBaseUrl, openaiApiKey);
    }

    @Bean
    public com.ainexus.ai.model.ModelInvoker modelInvoker(com.ainexus.ai.model.ModelRegistry registry) {
        return new com.ainexus.ai.model.ModelInvoker(registry);
    }

    @Bean("aiCallExecutor")
    public Executor aiCallExecutor() {"""),
("""import org.springframework.boot.context.properties.EnableConfigurationProperties;""",
"""import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;""")])

# ChatService：多模型降级 + 灰度 + 上下文压缩 + 前缀缓存
patch('src/main/java/com/ainexus/service/ChatService.java', [(
"""    private final com.ainexus.common.metrics.BusinessMetrics metrics;
    private final com.ainexus.ai.tenant.TenantQuotaService tenantQuotaService;""",
"""    private final com.ainexus.common.metrics.BusinessMetrics metrics;
    private final com.ainexus.ai.tenant.TenantQuotaService tenantQuotaService;
    private final com.ainexus.ai.model.ModelInvoker modelInvoker;
    private final com.ainexus.ai.model.PromptCacheService promptCache;

    @org.springframework.beans.factory.annotation.Value("${ainexus.models.primary:ollama:qwen2.5:7b}")
    private String primaryModelKey;
    @org.springframework.beans.factory.annotation.Value("#{" + "${" + "ainexus.models.fallback:{}}}")
    private List<String> fallbackModelKeys;
    @org.springframework.beans.factory.annotation.Value("${ainexus.models.gray-key:}")
    private String grayModelKey;
    @org.springframework.beans.factory.annotation.Value("${ainexus.models.gray-ratio:0.0}")
    private double grayRatio;
    @org.springframework.beans.factory.annotation.Value("${ainexus.model.context-max-chars:4000}")
    private int contextMaxChars;"""),
("""        chatHistoryService.loadWindow(sid, userId, tenantId).forEach(h -> toMessage(h, messages));
        messages.add(new UserMessage(message));""",
"""        var history = chatHistoryService.loadWindow(sid, userId, tenantId);
        // 超长上下文智能压缩：超出字符预算从最早消息裁剪（与滑窗条数限制双重保障）
        com.ainexus.ai.memory.ContextWindowCompressor.compress(history, contextMaxChars)
                .forEach(h -> toMessage(h, messages));
        messages.add(new UserMessage(message));"""),
("""        // 单元素数组承载响应元数据（模型名 / token 用量），避免为 lambda 引入包装类
        ChatResponse[] holder = new ChatResponse[1];
        ModelCallResilienceTemplate.ResilienceResult<String> result = resilience.execute(
                ModelScene.CHAT.name(),
                () -> gate.call(() -> {
                    ChatResponse response = chatModel.call(new Prompt(messages));
                    holder[0] = response;
                    return response.getResult().getOutput().getText();
                }),
                "static-reply",
                () -> "抱歉，模型暂时不可用，请稍后再试。");""",
"""        // 单元素数组承载响应元数据（模型名 / token 用量），避免为 lambda 引入包装类
        ChatResponse[] holder = new ChatResponse[1];
        // 前缀缓存：相同提示词短 TTL 去重回放（默认关闭）
        String promptKey = cn.hutool.crypto.digest.DigestUtil.md5Hex(messages.toString());
        String cached = promptCache.getAndLock(promptKey);
        ModelCallResilienceTemplate.ResilienceResult<String> result;
        if (cached != null && !cached.isBlank()) {
            result = new ModelCallResilienceTemplate.ResilienceResult<>(cached, false, 0);
        } else {
            // 多模型统一适配：主模型失败（连接类异常）自动降级备用模型；灰度按比例分流
            result = resilience.execute(
                    ModelScene.CHAT.name(),
                    () -> gate.call(() -> {
                        String text = modelInvoker.invoke(primaryModelKey, fallbackModelKeys, grayModelKey, grayRatio,
                                cm -> {
                                    ChatResponse response = cm.call(new Prompt(messages));
                                    holder[0] = response;
                                    return response.getResult().getOutput().getText();
                                });
                        promptCache.write(promptKey, text);
                        return text;
                    }),
                    "static-reply",
                    () -> "抱歉，模型暂时不可用，请稍后再试。");
        }""")])
print('wired')
