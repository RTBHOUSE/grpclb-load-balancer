package com.rtbhouse.grpc.loadbalancer;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GrpcServerBase {
  private static final Logger logger = LoggerFactory.getLogger(GrpcServerBase.class);
  protected int port;
  private Server server;
  private ArrayList<BindableService> services = new ArrayList<>();

  public GrpcServerBase(int port, BindableService... services) {
    this.port = port;
    Collections.addAll(this.services, services);
  }

  protected void start() throws IOException {
    ServerBuilder<?> serverBuilder = ServerBuilder.forPort(this.port);
    for (BindableService service : this.services) {
      serverBuilder.addService(service);
    }

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                  System.err.println("*** shutting down gRPC server since JVM is shutting down");
                  this.stop();
                  System.err.println("*** server shut down");
                }));

    server = serverBuilder.build().start();
    logger.info("Server started, listening on {}", port);
  }

  protected void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  public void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }
}
