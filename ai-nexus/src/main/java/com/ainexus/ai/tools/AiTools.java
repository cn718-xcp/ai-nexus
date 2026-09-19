package com.ainexus.ai.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Function Calling 工具集（Spring AI 2.0 @Tool 注解式工具）。
 * 由 ToolCallbackProvider 注册到 ChatClient，模型按需自主调用。
 */
@Slf4j
@Component
public class AiTools {

    /** 内置演示数据：生产环境应替换为真实第三方 API 并做密钥管理 */
    private static final Map<String, Integer> MOCK_CITY_TEMPERATURE = Map.of(
            "北京", 18, "上海", 24, "深圳", 29, "杭州", 22);

    /**
     * 查询指定城市当前天气（演示用 Mock 数据源）。
     */
    @Tool(description = "查询指定城市的当前天气，返回天气状况与温度（摄氏度）")
    public String getWeather(@ToolParam(description = "城市名称，如：北京") String city) {
        log.info("[tool-call] getWeather city={}", city);
        int temp = MOCK_CITY_TEMPERATURE.getOrDefault(city, ThreadLocalRandom.current().nextInt(5, 30));
        return String.format("%s 当前晴，气温 %d°C", city, temp);
    }

    /**
     * 获取服务器当前时间。
     */
    @Tool(description = "获取服务器当前日期时间")
    public String getCurrentTime() {
        log.info("[tool-call] getCurrentTime");
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
