package com.rtbhouse.grpc.loadbalancer;

import com.google.protobuf.ByteString;
import io.grpc.lb.v1.Server;
import java.net.InetSocketAddress;
import java.util.List;

/** This class is a container for server info stored in DirectServerList. */
public class BackendServer {
  public static final int DEFAULT_WEIGHT = 100;

  private InetSocketAddress address;
  private List<String> services;
  private int weight;
  private long timestamp;

  public BackendServer(InetSocketAddress address, List<String> services) {
    this(address, services, 0);
  }

  public BackendServer(InetSocketAddress address, List<String> services, int weight) {
    this.address = address;
    this.services = services;
    this.weight = weight;
    this.timestamp = 0L;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BackendServer)) {
      return false;
    }

    BackendServer bs = (BackendServer) o;
    return bs.address.equals(address);
  }

  @Override
  public int hashCode() {
    return address.hashCode();
  }

  public Server toProto() {
    return Server.newBuilder()
        .setIpAddress(ByteString.copyFrom(address.getAddress().getAddress()))
        .setPort(address.getPort())
        .build();
  }

  public List<String> getServices() {
    return this.services;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  public long getTimestamp() {
    return this.timestamp;
  }

  public InetSocketAddress getAddress() {
    return this.address;
  }

  public int getWeight() {
    return this.weight;
  }
}
