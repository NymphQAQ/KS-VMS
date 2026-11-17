FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/visitor-management-0.1.0.jar app.jar
USER 10001
ENTRYPOINT ["java","-jar","app.jar"]
