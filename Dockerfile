FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/bot/target/VanilaLiveBot.jar /app/VanilaLiveBot.jar
CMD ["java", "-jar", "/app/VanilaLiveBot.jar"]
