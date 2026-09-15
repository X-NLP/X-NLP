# Build the server image from source so `docker compose build` is reproducible.
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY pom.xml .
COPY xnlp-core/pom.xml xnlp-core/pom.xml
COPY xnlp-server/pom.xml xnlp-server/pom.xml
COPY xnlp-client/pom.xml xnlp-client/pom.xml
COPY xnlp-cli/pom.xml xnlp-cli/pom.xml
COPY xnlp-core/src xnlp-core/src
COPY xnlp-server/src xnlp-server/src
COPY xnlp-client/src xnlp-client/src
COPY xnlp-cli/src xnlp-cli/src

RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-noble
LABEL maintainer="X-NLP Team"

RUN groupadd --system xnlp && useradd --system --gid xnlp --home-dir /opt/xnlp xnlp \
    && mkdir -p /opt/xnlp/models /opt/xnlp/data \
    && chown -R xnlp:xnlp /opt/xnlp

COPY --from=build --chown=xnlp:xnlp /workspace/xnlp-server/target/xnlp-server-*.jar /opt/xnlp/xnlp-server.jar

USER xnlp
WORKDIR /opt/xnlp

EXPOSE 8760

ENV JAVA_OPTS="-Xms512m -Xmx2g --enable-native-access=ALL-UNNAMED"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/xnlp/xnlp-server.jar"]
