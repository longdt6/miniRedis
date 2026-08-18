# syntax=docker/dockerfile:1.6

###############################################################################
# Stage 1 — build a GraalVM native binary
###############################################################################
# GraalVM CE JDK 17 ships with the native-image component pre-installed and
# lives on a public registry, so Render can pull it without a quay.io login.
# JDK 17 is the highest version the public ghcr.io/graalvm/graalvm-ce images
# ship — Quarkus 3.15 supports JDK 17+ (we only use records/sealed types).
FROM ghcr.io/graalvm/graalvm-ce:java17-21.3.0 AS build

WORKDIR /build

# Cache dependencies first: copy only pom + wrapper, resolve, then copy source
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline

COPY src ./src

# JAVA_HOME is already set inside the graalvm-ce image. -Dnative activates
# the quarkus-maven-plugin native profile, which invokes the native-image
# compiler that ships in this image.
RUN ./mvnw -B -ntp package -Dnative -DskipTests

###############################################################################
# Stage 2 — copy the native binary onto a tiny runtime image
###############################################################################
FROM quay.io/quarkus/quarkus-micro-image:2.0

WORKDIR /work

# Quarkus produces a single static binary named <artifactId>-runner
COPY --from=build /build/target/*-runner /work/application

EXPOSE 8080

ENV MINIREDIS_DATA_FILE=/tmp/miniredis.json
ENV PORT=8080

# Micro-image uses /work as the working dir; bind on $PORT so Render can route
ENTRYPOINT ["./application", "-Dquarkus.http.port=${PORT}"]
