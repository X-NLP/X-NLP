FROM eclipse-temurin:25-jre-alpine
LABEL maintainer="X-NLP Team"

RUN addgroup -S xnlp && adduser -S xnlp -G xnlp

COPY xnlp-server/target/xnlp-server-*.jar /opt/xnlp/xnlp-server.jar

RUN mkdir -p /opt/xnlp/models && chown -R xnlp:xnlp /opt/xnlp

USER xnlp
WORKDIR /opt/xnlp

EXPOSE 8760

ENV JAVA_OPTS="-Xms512m -Xmx2g"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /opt/xnlp/xnlp-server.jar"]
