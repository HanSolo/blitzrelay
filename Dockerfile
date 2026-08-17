FROM gradle:9.7.0-jdk25 AS builder

WORKDIR /build
COPY settings.gradle build.gradle ./
COPY gradle ./gradle
# Download dependencies first — cached as a separate layer
RUN gradle dependencies -q

COPY src ./src
RUN gradle shadowJar -q

FROM azul/zulu-openjdk-alpine:25-jre-latest

WORKDIR /app
COPY --from=builder /build/build/libs/blitzrelay-*.jar app.jar

RUN addgroup -S blitzrelay && adduser -S blitzrelay -G blitzrelay
USER blitzrelay

ENTRYPOINT ["java", "-jar", "app.jar"]
