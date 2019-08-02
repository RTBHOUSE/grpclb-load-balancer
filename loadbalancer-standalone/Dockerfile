FROM maven:3.6.0-jdk-11 AS builder

WORKDIR /sources
COPY . /sources

RUN mvn -T1C -f pom.xml clean install -Dmaven.test.skip -am -pl :loadbalancer-standalone

FROM openjdk:11.0.1

WORKDIR /root
COPY --from=builder /sources/loadbalancer-standalone/target/loadbalancer-standalone-1.0-shaded.jar .

HEALTHCHECK --interval=60s --timeout=10s --start-period=1m --retries=2 CMD /bin/true

ENTRYPOINT exec java $JVM_OPTS -jar loadbalancer-standalone-1.0-shaded.jar -port $LB_PORT -heartbeats-frequency 4000 -time-to-evict 5000
