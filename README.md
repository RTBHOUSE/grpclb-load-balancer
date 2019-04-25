# gRPC-LB load balancer
Server-side gRPC-LB implementation, LoadBalancerConnector for backend servers

### Overview

First, you should read [how load balancing works](https://github.com/grpc/grpc/blob/master/doc/load-balancing.md) for gRPC.

This project allows you to use grpclb-based load balancing - grpclb is a gRPC protocol defined [here](https://github.com/grpc/grpc-java/blob/master/grpclb/src/main/proto/grpc/lb/v1/load_balancer.proto). There is a client-side implementation of this protocol in all gRPC clients. But, in order to get this to work, you need a load balancer supporting server-side of the protocol. Here comes `loadbalancer-standalone` project.

It is a standalone server, serving two gRPC services: LoadBalancerService (aka grpclb) and SignupService [defined here](https://github.com/RTBHOUSE/grpclb-load-balancer/blob/6db1584360ba6ae9dc36a94e2cbe00492a90b695/common/src/main/proto/signup.proto). Load balancer needs to have an up-to-date list of backend servers, so SignupService is being used to register them in load balancer.

There is an implementation of SignupService provided in `basic-loadbalancer-aware-server` package. It is a `LoadBalancerConnector` class, that you can simply use in your backend server. You can control the communication of your server to the LB through `start()` and `stop()` methods. After invoking `start()`, server registers in the LB and starts sending heartbeats; calling `stop()` removes server from the LB policy immediately. If for some reason (e.g. network problems) LB won't be getting heartbeats from given server for some time (you can configure it), it also evicts the server from the list. You may also use `pause()` and `resume()` methods on `LoadBalancerConnector`. Those methods behave similar to `start()` and `stop()`, the only difference is that the gRPC channel is not being closed, so it is more efficient in case of frequent status changes.

If you are starting from scratch and don't have any specific server implementation, you can use `BasicLoadbalancerAwareGrpcServer` and have communication with LB done under the hood.

### Building
There is a parent-project in root directory, so you can simply build the whole project by `mvn install` there.

### Usage examples

Things with grpclb are a bit complicated when it comes to simple examples. You can't pass to the client (I mean, to ManagedChannelBuilder) loadbalancer address directly. You can only pass a domain name, and the gRPC library will resolve it and [look for SRV records there](https://github.com/grpc/proposal/blob/master/A5-grpclb-in-dns.md). This records indicate LB addresses. So, we registered a domain `hello.mimgrpc.me` and put there a proper record, which returns `127.0.0.1` as the LB address. In all examples here, we use this domain.

First, you may have a look on [this integration test](https://github.com/blazej24/grpc-load-balancer/blob/master/loadbalancer-standalone/src/test/java/com/rtbhouse/grpc/loadbalancer/standalone/LoadBalancerIntegrationTest.java). It shows how to use LoadBalancerConnector directly, and what is the "flow".

A fully working example, you will find in the `examples` folder. There are `hello-world-lbaware-server` and `hello-world-client` packages. How to run it (all commands assume being run at project's root directory):

1) You need to run loadbalancer first. After building the whole project, run for example (in root directory):
```sh
# heartbeats-frequency (shorter: hf) - how often backend servers
#   must send heartbeats to LB (to stay on the active servers list) (ms). 
#   LB will pass this information to servers.
# time-to-evict (shorter: evict) -  if LB won't receive heartbeat that long,
#   backend server is evicted from LB list (ms)
$ java -jar loadbalancer-standalone/target/loadbalancer-standalone-1.0-shaded.jar -port 9090 -heartbeats-frequency 3000 -time-to-evict 4000
```

2) Run a few backend servers, the example `HelloWorldLBServer` shows how to use `BasicLoadbalancerAwareGrpcServer`:
```sh
# example usage, for configuration possibilities during experiments use -help option 
$ java -jar examples/hello-world-lbaware-server/target/hello-world-lbaware-server-1.0-shaded.jar -p 2222 -lb "127.0.0.1:9090" -s "hello.mimgrpc.me:2222"

# if you are testing everything locally, on one machine, use
$ LOCAL=1 java -jar examples/hello-world-lbaware-server/target/hello-world-lbaware-server-1.0-shaded.jar -p 2222 -lb "127.0.0.1:9090" -s "hello.mimgrpc.me:2222"

# The server has to send its IP to the loadbalancer and by default, 
# it autodiscovers its public IP, but, if you don't have any, setting LOCAL=1
# causes that server uses 127.0.0.1 as its address.

# You can also use "dns_resolve" in -lb option, in order to have lb addresses resolved automatically, 
# from DNS, similarly to clients.
```

3) Finally, you can run clients:
```sh
# In args[0], you must specify host:port for the service you want to connect. You can also add number of requests being done in args[1], default is 100; after every request client sleeps for 300ms.
$ java -Dio.grpc.internal.DnsNameResolverProvider.enable_grpclb=true -jar examples/hello-world-client/target/hello-world-client-1.0-shaded.jar "hello.mimgrpc.me:2222" 100
```

#### Healthchecks
With `basic-loadbalancer-aware-server` you can use your custom healthcheck, provided as [Health service](https://github.com/grpc/grpc/blob/master/doc/health-checking.md) implementation. Server will run an InProcessServer with this service, call `Check` every 5s (can be configured) and inform LB about status changes (by `pause()` and `resume()` in `LBConnector`).
