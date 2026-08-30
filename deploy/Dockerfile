FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim tesseract-ocr-chi-sim-vert \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build /build/web-api/target/web-api-*.jar /app/app.jar
COPY --from=build /build/LICENSE /app/LICENSE
COPY --from=build /build/THIRD_PARTY_NOTICES.md /app/THIRD_PARTY_NOTICES.md
RUN mkdir -p /app/data && chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
ENV JAVA_OPTS="-Xms512m -Xmx2g -Djava.awt.headless=true"
ENV FORMAT_CONVERTER_OCR_ENABLED="true" \
    FORMAT_CONVERTER_OCR_LANGUAGES="chi_sim+eng" \
    SERVER_ADDRESS="0.0.0.0"
# Startup is fail-closed until the operator supplies
# a 32+ character FORMAT_CONVERTER_API_TOKEN without surrounding whitespace
# (or explicitly acknowledges an isolated outer
# network with FORMAT_CONVERTER_ALLOW_INSECURE_REMOTE=true).
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
