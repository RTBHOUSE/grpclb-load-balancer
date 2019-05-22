FROM openjdk:11.0.1

COPY ./target/loadbalancer-standalone-1.0-SNAPSHOT-shaded.jar /root/loadbalancer-standalone-1.0-SNAPSHOT-shaded.jar

WORKDIR /root
HEALTHCHECK --interval=60s --timeout=10s --start-period=1m --retries=2 CMD /bin/true

ENTRYPOINT exec java $JVM_OPTS -jar loadbalancer-standalone-1.0-SNAPSHOT-shaded.jar -port $LB_PORT -heartbeats-frequency 3000 -time-to-evict 4000
