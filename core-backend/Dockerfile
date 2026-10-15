# STAGE 1: BUILD
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# STAGE 2: RUN
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y curl tini && rm -rf /var/lib/apt/lists/*
ENV TZ="Asia/Ho_Chi_Minh"
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
ENV SPRING_PROFILES_ACTIVE="prod"
RUN addgroup --system spring && adduser --system --ingroup spring spring
COPY --from=builder --chown=spring:spring /app/target/finance-api.jar app.jar
USER spring:spring

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["tini", "--", "java", "-XX:MaxRAMPercentage=75.0", "-XX:InitialRAMPercentage=50.0", "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}", "-jar", "app.jar"]