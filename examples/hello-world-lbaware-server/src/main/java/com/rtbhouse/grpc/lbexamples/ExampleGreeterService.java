package com.rtbhouse.grpc.lbexamples;

import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleGreeterService extends GreeterGrpc.GreeterImplBase {
  private static final Logger logger = LoggerFactory.getLogger(ExampleGreeterService.class);
  private final int port;

  ExampleGreeterService(int port) {
    this.port = port;
  }

  @Override
  public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
    try {
      String myAddr = InetAddress.getLocalHost().toString();
      String message = "Hello " + req.getMessage() + " here is: " + myAddr + ":" + port;

      HelloReply reply = HelloReply.newBuilder().setMessage(message).setAddr(myAddr).build();

      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    } catch (UnknownHostException e) {
      logger.error("The local host name could not be resolved into an address");
      responseObserver.onError(e);
    }
  }
}
