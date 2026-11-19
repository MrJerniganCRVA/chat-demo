# Stage 1: compile the Java code with the full JDK
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY src ./src
RUN javac -d out src/*.java

# Stage 2: run it with the smaller Java runtime (no compiler needed)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/out ./out
COPY public ./public
ENV PORT=8080 \
    PUBLIC_DIR=/app/public \
    DATA_DIR=/app/data
EXPOSE 8080
CMD ["java", "-cp", "out", "ChatServer"]
