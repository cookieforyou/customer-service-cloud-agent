# ============================================================
# cs-api 生产镜像（M0批5 部署形态，《13》§1/§6）
# 形态：fat jar 宿主侧构建（mvn -DskipTests package）后 docker build 打包——
# 2 核 ECS 不做镜像内 Maven 全量构建（姊妹项目同款定案）。
# 镜像钉版（坑#06 禁 latest）；AppCDS 不启用（坑#08 姊妹实证 SIGSEGV）。
# ============================================================
FROM eclipse-temurin:25-jre-noble

ARG JAR_FILE=cs-api/target/cs-api-1.0.0-SNAPSHOT.jar

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

# 优雅停机（《13》§6）：SIGTERM → Spring graceful → 在途轮排空（timeout 70s）
STOPSIGNAL SIGTERM

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8100/actuator/health | grep -q UP

EXPOSE 8100

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
