# Sample shop image. compose builds it with Gradle inside the image, so the host needs no JDK.
# The build context is the repository root (the library modules are built along with it).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew :examples-shop:bootJar --no-daemon --quiet

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/examples/shop/build/libs/shop.jar /app/shop.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/shop.jar"]
