# ---- Stage 1: build the WAR ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Stage 2: run it on Tomcat 10.1 (Jakarta EE 10) ----
FROM tomcat:10.1-jre17-temurin
COPY --from=build /app/target/simple-web-app.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
CMD ["catalina.sh", "run"]