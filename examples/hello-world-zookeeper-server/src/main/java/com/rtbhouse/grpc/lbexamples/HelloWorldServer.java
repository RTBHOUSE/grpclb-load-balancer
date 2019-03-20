package com.rtbhouse.grpc.lbexamples;

import com.rtbhouse.grpc.loadbalancer.zookeeper.ZooKeeperAwareBasicGrpcServer;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloWorldServer extends ZooKeeperAwareBasicGrpcServer {
  private CuratorFramework zkClient;

  public HelloWorldServer(int port, CuratorFramework curatorFramework) {
    super(port, "/servers", curatorFramework, true, new GreeterImpl());
    this.zkClient = curatorFramework;
  }

  public static void main(String[] args) throws InterruptedException, IOException {
    if (args.length != 2) {
      System.err.println("Usage: (...) zookeeper_address:zookeeper_port server_port");
      System.exit(1);
    }

    System.out.println("Starting server...");
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    CuratorFramework zkClient = CuratorFrameworkFactory.newClient(args[0], retryPolicy);
    zkClient.start();
    HelloWorldServer server = new HelloWorldServer(Integer.parseInt(args[1]), zkClient);
    server.start();
    server.blockUntilShutdown();
  }

  @Override
  protected void stop() {
    super.stop();
    zkClient.close();
  }

  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {
    private static final Logger logger = LoggerFactory.getLogger(GreeterImpl.class);

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      try {
        String myAddr = InetAddress.getLocalHost().toString();
        HelloReply reply =
            HelloReply.newBuilder()
                .setMessage("Hello " + req.getName() + " here is: " + myAddr)
                .setAddr(myAddr)
                .build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
      } catch (UnknownHostException e) {
        logger.error("The local host name could not be resolved into an address");
        responseObserver.onError(e);
      }
    }
  }
}
