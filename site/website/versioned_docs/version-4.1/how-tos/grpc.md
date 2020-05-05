---
title: Call gRPC Service
description: How to call unary or bidirectional streaming gRPC service from a pipeline.
id: version-4.1-grpc
original_id: grpc
---

The [Stateless
Transforms](../api/stateless-transforms.md#mapusingservice) section
shows how to use external services to transform items in a Jet pipeline.
One way to expose a remote service is using [gRPC](https://grpc.io/)
&mdash; an open-source universal RPC framework available for many
platforms and languages.

The `hazelcast-jet-grpc` module makes it easy to perform calls to a gRPC
service. We support two kinds of [gRPC service
methods](https://grpc.io/docs/guides/concepts/):

- unary RPC
- bidirectional streaming RPC

Here we'll show you how to use these services in your pipeline.

## Dependencies

Add this dependency to your Java project:

<!--DOCUSAURUS_CODE_TABS-->

<!--Gradle-->

```groovy
compile 'com.hazelcast.jet:hazelcast-jet-grpc:4.1'
```

<!--Maven-->

```xml
<dependency>
  <groupId>com.hazelcast.jet</groupId>
  <artifactId>hazelcast-jet-grpc</artifactId>
  <version>4.1</version>
</dependency>
```

<!--END_DOCUSAURUS_CODE_TABS-->

The Hazelcast Jet cluster must also have this module on the classpath.
It is in the `opt` directory of the distribution package so you can just
move it to the `lib` directory and restart the cluster.

## Unary RPC

The classical request-response RPC pattern is what gRPC calls "unary
RPC". You send a single request message and get a single response
message back, just like a plain function call.

Let's use this protobuf definition as an example:

```proto
service ProductService {
  rpc ProductInfo (ProductInfoRequest)
      returns (ProductInfoReply) {}
}

message ProductInfoRequest {
  int32 id = 1;
}

message ProductInfoReply {
  string productName = 1;
}
```

To call this service, use `GrpcServices.unaryService()`:

```java
ServiceFactory<?, ? extends GrpcService<ProductInfoRequest, ProductInfoReply>>
productService = unaryService(
    () -> ManagedChannelBuilder.forAddress("localhost", PORT) .usePlaintext(),
    channel -> ProductServiceGrpc.newStub(channel)::productInfo
);
```

The first parameter is a factory function that returns a channel
builder. Modify the builder settings as required, as an example we
could have enabled TLS via
`io.grpc.ManagedChannelBuilder.useTransportSecurity()`.

The second parameter is a function which, given a gRPC network channel,
creates a client-side stub and returns a reference to its method that
invokes the service. The stub code is auto-generated by the protobuf
compiler.

Returning a method reference isn't a requirement, though, you can
also modify the stub, the input item or anything else you need. The
functional type you must comply with is as follows (wildcards omitted
for clarity):

```java
FunctionEx<ManagedChannel, BiConsumerEx<T, StreamObserver<R>>> callStubFn
```

Here, `BiConsumerEx<T, StreamObserver<R>>` corresponds to the signature
of the generated gRPC method: it is a function that takes an input item
and the gRPC result observer, and ensures that the observer eventually
receives the gRPC invocation result.

Now you can use the service factory in any of the`mapUsingService*Async`
methods:

```java
StreamStage<Trade> trades = ...
trades.mapUsingServiceAsync(productService,
(service, trade) -> {
    ProductInfoRequest request = ProductInfoRequest.newBuilder()
            .setId(trade.productId()).build();
    return service.call(request).thenApply(productReply ->
            tuple2(trade, productReply.getProductName()));
})
```

## Bidirectional Streaming RPC

Bidirectional streaming RPC is an extension of the classic RPC paradigm
to streaming. You make a single RPC call, within which you can send any
number of messages to the server, and it can send any number of messages
to you. Since we are using gRPC to transform Jet pipeline items, this
general protocol is constrained: the response stream must match
one-for-one with the request stream.

This constraint makes the streaming communication very similar to unary
RPC, but it eliminates some of the overheads. Sending messages within a
single established call is cheaper than creating a new call from
scratch. Our benchmarks show 1.5x to 3x improvement, depending on
various factors.

This is an example of a bidirectional streaming RPC definition:

```proto
service BrokerService {
  rpc BrokerInfo (stream BrokerInfoRequest)
      returns (stream BrokerInfoReply) {}
}

message BrokerInfoRequest {
  int32 id = 1;
}

message BrokerInfoReply {
  string brokerName = 1;
}
```

Note the `stream` keyword appearing both in the request and the response.

We can create the following service factory using
`GrpcServices.bidirectionalStreamingService()` method:

```java
ServiceFactory<?, ? extends GrpcService<BrokerInfoRequest, BrokerInfoReply>>
brokerService = bidirectionalStreamingService(
    () -> ManagedChannelBuilder.forAddress("localhost", PORT).usePlaintext(),
    channel -> BrokerServiceGrpc.newStub(channel)::brokerInfo
);
```

As with the unary service, the first parameter is a supplier returning
a channel builder.

The full type of the second parameter is as follows (with wildcards
omitted):

```java
FunctionEx<ManagedChannel, FunctionEx<StreamObserver<R>, StreamObserver<T>>> callStubFn
```

The service method is now a function with the signature

```java
StreamObserver<R> -> StreamObserver<T>
```

This may look a bit confusing since the parameter is an observer of the
output and the return type is an observer of the input, but it makes
sense if you pause to think about it. Jet provides its observer of the
output (same as in the unary RPC), and this function returns an object
where Jet will push the input items.

Now the service factory can be used in any of the `mapUsingService*`
methods, preferably the `mapUsingServiceAsync`.

```java
StreamStage<Tuple2<Trade, String>> tradeAndProducts = ...
tradeAndProducts.mapUsingServiceAsync(brokerService,
    (service, t) -> {
        BrokerInfoRequest request = BrokerInfoRequest
            .newBuilder().setId(t.f0().brokerId()).build();
        return service
            .call(request)
            .thenApply(brokerReply ->
                tuple3(t.f0(), t.f1(), brokerReply.getBrokerName()));
})
```

## Improve Throughput with Batching

If your gRPC service's throughput capacity is very high, and the gRPC
link is the bottleneck, you can significantly improve the throughput by
applying batching. For example, you can use a protobuf definition like
this one (note the `repeated` keyword):

```proto
service Greeter {
  rpc SayHelloListBidirectional (stream HelloRequestList)
      returns (stream HelloReplyList) {}
}
message HelloRequestList {
  repeated string name = 1;
}
message HelloReplyList {
  repeated string message = 1;
}
```

Create the service in a way similar to previous example:

```java
ServiceFactory<?, ? extends GrpcService<HelloRequestList, HelloReplyList>> bidiService =
bidirectionalStreamingService(
    () -> ManagedChannelBuilder.forAddress(host, port).usePlaintext(),
    channel -> GreeterGrpc.newStub(channel)::sayHelloListBidirectional
);
```

In the Jet pipeline, use the specialized `mapUsingServiceAsyncBatched`
transform:

```java
StreamStage<String> stage = ...
stage.mapUsingServiceAsyncBatched(bidiService,
    1024,
    (service, itemList) -> {
        CompletableFuture<HelloReplyList> future =
            service.call(HelloRequestList.newBuilder().addAllName(itemList).build());
        return future.thenApply(HelloReplyList::getMessageList);
    })
})
```

See the [grpc
example](https://github.com/hazelcast/hazelcast-jet/tree/master/examples/grpc)
module for a complete code example.