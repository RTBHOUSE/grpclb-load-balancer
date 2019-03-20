package com.rtbhouse.grpc.loadbalancer.zookeeper;

import static com.rtbhouse.grpc.loadbalancer.Helper.getServerAddress;

import com.rtbhouse.grpc.loadbalancer.HealthCheckCapableGrpcServerBase;
import io.grpc.BindableService;
import io.grpc.health.v1.HealthGrpc.HealthImplBase;
import java.io.IOException;
import java.net.InetAddress;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperAwareBasicGrpcServer extends HealthCheckCapableGrpcServerBase {
  private static final Logger logger = LoggerFactory.getLogger(ZooKeeperAwareBasicGrpcServer.class);
  private final String zooNodePath;
  private final CuratorFramework curatorFramework;
  private final boolean usingNAT;

  private volatile ConnectionState connectionState;

  private ConnectionStateListener serverNodeRecreateListener =
      (client, newState) -> {
        connectionState = newState;
        if (connectionState.isConnected()) {
          if (!super.checkingHealth || isHealthy()) {
            registerServerNode();
          }
        }
      };

  public ZooKeeperAwareBasicGrpcServer(
      int port,
      String zooKeeperServerNodePath,
      CuratorFramework curatorFramework,
      boolean usingNAT,
      BindableService... services) {
    super(port, services);
    this.usingNAT = usingNAT;
    this.zooNodePath = zooKeeperServerNodePath;
    this.curatorFramework = curatorFramework;
  }

  public ZooKeeperAwareBasicGrpcServer(
      int port,
      String zooKeeperServerNodePath,
      CuratorFramework curatorFramework,
      boolean usingNAT,
      HealthImplBase healthService,
      BindableService... services)
      throws IOException {
    super(port, healthService, services);
    this.usingNAT = usingNAT;
    this.zooNodePath = zooKeeperServerNodePath;
    this.curatorFramework = curatorFramework;
  }

  @Override
  protected void start() throws IOException {
    super.start();

    registerInZooKeeper();
    curatorFramework.getConnectionStateListenable().addListener(serverNodeRecreateListener);
  }

  @Override
  protected void stop() {
    curatorFramework.getConnectionStateListenable().removeListener(serverNodeRecreateListener);
    deregisterServerNode();
    super.stop();
  }

  /** Clean-up is needed before registering new ephemeral node. */
  private void registerInZooKeeper() {
    deregisterServerNode();
    registerServerNode();
  }

  /** Ephemeral node is created so that ZooKeeper handles session expiry. */
  private void registerServerNode() {
    String serverNodePath = getServerNodePath();
    try {
      if (isConnectedToZookeeper()
          && curatorFramework.checkExists().forPath(serverNodePath) == null) {
        curatorFramework
            .create()
            .creatingParentsIfNeeded()
            .withMode(CreateMode.EPHEMERAL)
            .forPath(serverNodePath);
      }
    } catch (KeeperException.NodeExistsException e) {
      logger.debug("Node: {} already exists", serverNodePath);
    } catch (Exception e) {
      logger.error("Could not register server node in zookeeper", e);
      throw new RuntimeException("Could not register server node in zookeeper", e);
    }
  }

  private void deregisterServerNode() {
    String serverNodePath = getServerNodePath();
    try {
      if (isConnectedToZookeeper()
          && curatorFramework.checkExists().forPath(serverNodePath) != null) {
        curatorFramework.delete().forPath(serverNodePath);
      }
    } catch (KeeperException.NoNodeException e) {
      logger.debug("Node: {} does not exist", serverNodePath);
    } catch (Exception e) {
      logger.error("Could not deregister server node from zookeeper", e);
      throw new RuntimeException("Could not deregister server node from zookeeper", e);
    }
  }

  private boolean isConnectedToZookeeper() {
    return (CuratorFrameworkState.STARTED.equals(curatorFramework.getState())
        && (connectionState == null || connectionState.isConnected()));
  }

  private String getServerNodePath() {

    InetAddress address = getServerAddress(usingNAT);
    return (zooNodePath.charAt(0) == '/' ? zooNodePath : "/" + zooNodePath)
        + "/"
        + address.getHostAddress()
        + ":"
        + port;
  }

  @Override
  protected void healthInspectorTask() {
    if (isHealthy()) {
      registerServerNode();
    } else {
      deregisterServerNode();
    }
  }
}
