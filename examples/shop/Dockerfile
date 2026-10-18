# 샘플 쇼핑몰 이미지(03b §7.5). 호스트에 JDK가 없어도 compose가 이 안에서 Gradle로 빌드한다.
# 빌드 컨텍스트는 저장소 루트다(라이브러리 모듈을 함께 빌드한다).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew :examples-shop:bootJar --no-daemon --quiet

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/examples/shop/build/libs/shop.jar /app/shop.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/shop.jar"]
