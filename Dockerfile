FROM maven:3.8-openjdk-11

WORKDIR /app
COPY pom.xml .
COPY .mvn ./.mvn
COPY mvnw .
COPY src ./src

RUN ./mvnw clean compile

EXPOSE 8080

CMD ["./mvnw", "exec:java", "-Dexec.mainClass=com.example.UnleashComparisonApp"]