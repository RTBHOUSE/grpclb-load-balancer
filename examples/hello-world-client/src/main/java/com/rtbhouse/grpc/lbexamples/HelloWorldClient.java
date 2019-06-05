package com.rtbhouse.grpc.lbexamples;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A simple client that requests a greeting from the HelloWorldServer. */
public class HelloWorldClient {
  private static final Logger logger = LoggerFactory.getLogger(HelloWorldClient.class);
  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;
  private HashMap<String, Integer> counts = new HashMap<>();

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HelloWorldClient(String host, int port) {
    this(
        ManagedChannelBuilder.forAddress(host, port)
            // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
            // needing certificates.
            .usePlaintext()
            .build());
  }

  /** Construct client for accessing HelloWorld server using the existing channel. */
  HelloWorldClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Say hello to server. */
  public void greet(String message) {
    logger.info("Will try to send: {} ...", message);
    HelloRequest request = HelloRequest.newBuilder().setMessage(message).build();
    HelloReply response;
    try {
      response = blockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.warn("RPC failed: {}", e.getStatus());
      return;
    }

    logger.info("Response: {}", response.getMessage());
    Integer count = counts.putIfAbsent(response.getAddr(), 0);
    if (count == null) count = 0;
    counts.put(response.getAddr(), count + 1);
  }

  public void printCounts() {
    for (HashMap.Entry<String, Integer> entry : counts.entrySet()) {
      logger.info("{} : {} responses", entry.getKey(), entry.getValue());
    }
  }

  /* Example usage:
    java -Dio.grpc.internal.DnsNameResolverProvider.enable_grpclb=true -jar \
    examples/hello-world-client/target/hello-world-client-1.0-shaded.jar \
    "hello.mimgrpc.me:2222" 100
  */

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      logger.error("Usage: host:port [requests_number]");
      System.exit(1);
    }

    String[] service = args[0].split(":");
    HelloWorldClient client = new HelloWorldClient(service[0], Integer.parseInt(service[1]));
    try {
      int times = 100;
      if (args.length == 1) {
        times = Integer.parseInt(args[0]);
      }
      for (int i = 0; i < times; i++) {
        client.greet("Hello!");
        Thread.sleep(300);
      }
    } finally {
      client.printCounts();
      client.shutdown();
    }
  }
}
