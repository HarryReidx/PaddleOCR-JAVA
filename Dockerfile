FROM node:22-alpine3.21 AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.6-eclipse-temurin-17-alpine AS backend-build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY --from=frontend-build /workspace/frontend/dist/ backend/src/main/resources/static/
RUN mvn -f backend/pom.xml package -DskipTests

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre
WORKDIR /app
ENV SERVER_PORT=8083
ENV OCR_UPLOAD_DIR=/app/backend/uploads
RUN mkdir -p /app/backend/uploads
COPY --from=backend-build /workspace/backend/target/paddleocr-demo-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
