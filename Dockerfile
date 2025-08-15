# Use OpenJDK 21 as base image
FROM openjdk:21-jdk-slim

# Set working directory
WORKDIR /app


COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Make mvnw executable
RUN chmod +x mvnw

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw clean package -DskipTests

# Expose port 8080
EXPOSE 8080

# Set environment variables for OpenAI (can be overridden at runtime)
ENV OPENAI_API_KEY=""

# Run the application
ENTRYPOINT ["java", "-jar", "target/carbon-footprint-estimator-0.0.1-SNAPSHOT.jar"]