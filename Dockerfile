# ---------- Build stage ----------
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY src ./src
COPY lib ./lib
RUN find src -name "*.java" > sources.txt \
 && mkdir -p build \
 && javac -encoding UTF-8 -cp "lib/postgresql-42.7.3.jar" -d build @sources.txt

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build ./build
COPY lib ./lib
COPY web ./web
RUN mkdir -p data
ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "java -cp build:lib/postgresql-42.7.3.jar com.parking.Main"]
