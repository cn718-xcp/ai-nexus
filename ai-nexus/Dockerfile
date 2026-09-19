# syntax=docker/dockerfile:1
# =====================================================================
# AI Nexus 分层构建镜像
# 阶段1 builder：Maven 离线依赖层 + 源码编译层（依赖不变时命中缓存，秒级重建）
# 阶段2 runtime：JRE 17 精简运行镜像，非 root 用户运行，内置健康检查
# 构建：docker build -t ai-nexus:1.0 .
# =====================================================================

# ---------- 阶段1：构建层 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先仅复制 pom.xml：依赖清单不变时该层与下述依赖层直接命中 Docker 缓存，
# 源码变更无需重新下载全部依赖（分层优化的核心收益点）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 再复制源码执行编译打包（单测在 CI 独立执行，镜像构建跳过）
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- 阶段2：运行层 ----------
FROM eclipse-temurin:17-jre AS runtime

ARG APP_PORT=8080
ENV TZ=Asia/Shanghai \
    APP_PORT=${APP_PORT} \
    # 容器内存感知参数：按容器限额自动伸缩堆大小，G1 降低长尾延迟
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

# 健康检查需要 curl（temurin 基础镜像未内置）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    # 非 root 运行，收敛容器逃逸风险面
    && useradd -r -u 1001 appuser

WORKDIR /app
COPY --from=builder /build/target/ai-nexus.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE ${APP_PORT}

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:${APP_PORT}/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=$APP_PORT"]
