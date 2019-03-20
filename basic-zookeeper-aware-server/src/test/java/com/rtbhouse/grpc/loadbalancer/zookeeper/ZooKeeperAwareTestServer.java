package com.rtbhouse.grpc.loadbalancer.zookeeper;

import org.apache.curator.framework.CuratorFramework;

public class ZooKeeperAwareTestServer extends ZooKeeperAwareBasicGrpcServer {

  public ZooKeeperAwareTestServer(
      int port, String zooKeeperServerNodePath, CuratorFramework curatorFramework) {
    super(port, zooKeeperServerNodePath, curatorFramework, false);
  }
}
