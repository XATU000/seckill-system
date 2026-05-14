FROM eclipse-temurin:17-jre

WORKDIR /app
COPY target/seckill-system-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
