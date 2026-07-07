# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S payguard && adduser -S payguard -G payguard
COPY --from=build /build/target/*.jar app.jar
USER payguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
