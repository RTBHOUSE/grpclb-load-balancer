package com.rtbhouse.grpc.loadbalancer.zookeeper;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ZooKeeperAwareBasicGrpcServerTest {
  private TestingServer zookeeperServer;
  private CuratorFramework curatorFramework;
  private ZooKeeperAwareTestServer testServer;

  @Before
  public void beforeEachTest() throws Exception {
    zookeeperServer = new TestingServer();
    curatorFramework =
        CuratorFrameworkFactory.newClient(
            zookeeperServer.getConnectString(), new RetryNTimes(10, 50));
    curatorFramework.start();
    testServer = new ZooKeeperAwareTestServer(21811, "/test", curatorFramework);

    testServer.start();
  }

  @After
  public void afterEachTest() throws IOException {
    testServer.stop();
    curatorFramework.close();
    zookeeperServer.stop();
  }

  @Test
  public void shouldAppear() throws Exception {
    Assert.assertFalse(curatorFramework.getChildren().forPath("/test").isEmpty());
  }

  @Test
  public void shouldDisappearOnServerStop() throws Exception {
    // given
    Assert.assertFalse(curatorFramework.getChildren().forPath("/test").isEmpty());

    // when
    testServer.stop();
    testServer.blockUntilShutdown();

    // then
    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(() -> curatorFramework.getChildren().forPath("/test").isEmpty());
  }

  @Test
  public void shouldDissappearOnZkConnectionFailure() throws Exception {
    // given
    Assert.assertFalse(curatorFramework.getChildren().forPath("/test").isEmpty());

    // when
    curatorFramework.close();
    curatorFramework =
        CuratorFrameworkFactory.newClient(
            zookeeperServer.getConnectString(), 100, 100, new RetryNTimes(1, 1000));
    curatorFramework.start();

    // then
    Assert.assertTrue(curatorFramework.getChildren().forPath("/test").isEmpty());
  }

  @Test
  public void shouldReappearOnZkConnectionRestoration() throws Exception {
    // given
    Assert.assertFalse(curatorFramework.getChildren().forPath("/test").isEmpty());

    PathChildrenCache pathCache =
        new PathChildrenCache(
            curatorFramework, "/test", true, false, Executors.newSingleThreadExecutor());
    pathCache.start();

    AtomicBoolean reconnected = new AtomicBoolean(false);

    pathCache
        .getListenable()
        .addListener(
            (client, event) -> {
              if (event.getType().equals(PathChildrenCacheEvent.Type.CONNECTION_RECONNECTED)) {
                reconnected.set(true);
              }
            });

    // when
    curatorFramework
        .delete()
        .forPath("/test/" + InetAddress.getLocalHost().getHostAddress() + ":" + 21811);
    zookeeperServer.restart();

    // then
    await().atMost(10, TimeUnit.SECONDS).untilTrue(reconnected);
    pathCache.close();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(() -> !curatorFramework.getChildren().forPath("/test").isEmpty());
  }
}
