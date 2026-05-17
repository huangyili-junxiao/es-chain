FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /app/target/es-chain-1.0-SNAPSHOT.jar app.jar

EXPOSE 8004

CMD ["java", "-jar", "app.jar"]
