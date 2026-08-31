# Maven/JDK on the builder's native arch; copy bytecode into a TARGET JRE image.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY contracts ./contracts
COPY jafra-analyzer ./jafra-analyzer
WORKDIR /workspace/jafra-analyzer
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && mvn -q -DskipTests package \
    && rm -rf /root/.m2

FROM eclipse-temurin:21-jre
WORKDIR /work
COPY --from=build /workspace/jafra-analyzer/target/quarkus-app /work
USER 65532:65532
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/work/quarkus-run.jar"]
