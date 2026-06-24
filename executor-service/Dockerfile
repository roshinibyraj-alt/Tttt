FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Cache Maven dependencies
COPY pom.xml ./
COPY polybot-core/pom.xml ./polybot-core/pom.xml
COPY executor-service/pom.xml ./executor-service/pom.xml
RUN mvn dependency:go-offline -pl executor-service -am -B -q 2>/dev/null || true

# Build
COPY polybot-core/ ./polybot-core/
COPY executor-service/ ./executor-service/
RUN mvn clean package -DskipTests -pl executor-service -am -B -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/executor-service/target/executor-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx256m", "-XX:+UseSerialGC", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar", "--spring.profiles.active=railway"]
