FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew installDist -q --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/mmflights /app
VOLUME ["/app/data"]
ENV MMFLIGHTS_CONFIG=/app/config.yaml
ENTRYPOINT ["/app/bin/mmflights"]
CMD ["run"]
