# 多阶段构建：阶段1 编译打包（maven 镜像），阶段2 只留 JRE（镜像小）
# ---------- 阶段一：build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 只先拷 pom 预热依赖，利用 Docker 层缓存：源码改了不用重新下载依赖
COPY pom.xml .
RUN mvn -q dependency:go-offline || true
COPY src ./src
# -Dmaven.test.skip=true：镜像构建不编译测试，测试交给 CI/本地
RUN mvn -q package -Dmaven.test.skip=true

# ---------- 阶段二：run ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# 数据库/Redis 地址等全部走环境变量（compose 注入）
ENTRYPOINT ["java", "-jar", "app.jar"]
