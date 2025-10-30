FROM maven:3.9.11-amazoncorretto-25 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM amazoncorretto:25.0.0-alpine3.22
ENV TZ=America/Sao_Paulo
WORKDIR /app
COPY --from=build /app/target/moviemux.jar /app/moviemux.jar
EXPOSE 8080
ENTRYPOINT ["java","-Xms256M", "-Xmx1G", "-XX:+UseZGC", "-jar", "/app/moviemux.jar"]
