package com.rtbhouse.grpc.loadbalancer;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/** Unit test for simple LoadBalancerServer. */
public class LoadBalancerServerTest extends TestCase {
  /**
   * Create the test case
   *
   * @param testName name of the test case
   */
  public LoadBalancerServerTest(String testName) {
    super(testName);
  }

  /** @return the suite of tests being tested */
  public static Test suite() {
    return new TestSuite(LoadBalancerServerTest.class);
  }

  /** Rigourous Test :-) */
  public void testApp() {
    assertTrue(true);
  }
}
