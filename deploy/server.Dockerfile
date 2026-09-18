FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY bike-server/pom.xml pom.xml
RUN mvn -B dependency:go-offline
COPY bike-server/src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system bike && useradd --system --gid bike bike && mkdir -p /app/storage/production && chown -R bike:bike /app
WORKDIR /app
COPY --from=build /build/target/bike-server-0.1.0-SNAPSHOT.jar app.jar
USER bike
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
