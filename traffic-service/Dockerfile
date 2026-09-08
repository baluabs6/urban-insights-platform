# Build context is the REPO ROOT (see docker-compose.yml build.context), because
# this module's pom.xml declares a <parent> pointing at the root pom.xml — the
# Maven reactor needs to see the whole multi-module tree to resolve it, even
# though this image only builds and ships the traffic-service module.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY traffic-service/pom.xml traffic-service/pom.xml
COPY complaint-service/pom.xml complaint-service/pom.xml
COPY ai-insight-service/pom.xml ai-insight-service/pom.xml
# Cache dependency resolution in its own layer before copying source.
RUN mvn -q -pl traffic-service -am dependency:go-offline -DskipTests || true
COPY traffic-service/src traffic-service/src
RUN mvn -q -pl traffic-service -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/traffic-service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
