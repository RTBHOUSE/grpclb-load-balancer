package com.rtbhouse.grpc.lbexamples;

import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleHealthCheckGreeterService extends GreeterGrpc.GreeterImplBase {
  private static final Logger logger =
      LoggerFactory.getLogger(ExampleHealthCheckGreeterService.class);
  private final ExampleHealthService healthService;
  private final int port;

  ExampleHealthCheckGreeterService(ExampleHealthService healthService, int port) {
    this.healthService = healthService;
    this.port = port;
  }

  @Override
  public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
    try {
      String myAddr = InetAddress.getLocalHost().toString();
      String message;

      synchronized (healthService) {
        if (healthService.isRespondingServing()) {
          message = "Hello " + req.getMessage() + " here is: " + myAddr + ":" + port;
        } else {
          message =
              "This message to "
                  + req.getMessage()
                  + " from unhealthy "
                  + myAddr
                  + ":"
                  + port
                  + " should be discarded by loadbalancer";
        }
      }

      HelloReply reply = HelloReply.newBuilder().setMessage(message).setAddr(myAddr).build();

      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    } catch (UnknownHostException e) {
      logger.error("The local host name could not be resolved into an address");
      responseObserver.onError(e);
    }
  }
}
