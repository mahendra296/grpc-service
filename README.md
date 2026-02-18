# grpc-service

# gRPC with Java Spring Boot — Complete Guide

A comprehensive guide covering gRPC fundamentals, setup, CRUD operations, and inter-microservice communication patterns.

---

## Table of Contents

1. [What is gRPC?](#what-is-grpc)
2. [gRPC vs REST](#grpc-vs-rest)
3. [Protocol Buffers (Protobuf)](#protocol-buffers-protobuf)
4. [Why Field Numbers Exist in Proto Files](#why-field-numbers-exist-in-proto-files)
5. [Project Setup](#project-setup)
6. [Writing Proto Files](#writing-proto-files)
7. [Implementing gRPC Server](#implementing-grpc-server)
8. [Implementing gRPC Client](#implementing-grpc-client)
9. [Calling Other Microservices](#calling-other-microservices)
10. [Error Handling](#error-handling)
11. [Interceptors (Logging, Auth)](#interceptors)
12. [Best Practices](#best-practices)

---

## What is gRPC?

**gRPC** (Google Remote Procedure Call) is a high-performance, open-source RPC framework developed by Google. It allows services to communicate with each other as if calling a local function.

Key features:
- Uses **HTTP/2** for transport (multiplexing, bidirectional streaming)
- Uses **Protocol Buffers** as the interface definition language and serialization format
- Supports **4 communication patterns**: Unary, Server Streaming, Client Streaming, Bidirectional Streaming
- **Language agnostic** — generate client/server code in Java, Go, Python, Node.js, etc.

```
Client Service                        Server Service
     │                                      │
     │  ── gRPC Request (HTTP/2 + Protobuf) ──>  │
     │                                      │
     │  <── gRPC Response (Protobuf) ──────── │
```

---

## gRPC vs REST

| Feature          | gRPC                          | REST                        |
|------------------|-------------------------------|-----------------------------|
| Protocol         | HTTP/2                        | HTTP/1.1                    |
| Data Format      | Binary (Protobuf)             | Text (JSON/XML)             |
| Performance      | Faster (smaller payload)      | Slower (larger payload)     |
| Contract         | Strict `.proto` schema        | Optional (OpenAPI)          |
| Streaming        | Built-in (4 modes)            | Limited (SSE, WebSocket)    |
| Browser Support  | Limited (needs proxy)         | Native                      |
| Code Generation  | Auto-generated stubs          | Manual or via tools         |
| Best For         | Internal microservices        | Public APIs, browsers       |

---

## Protocol Buffers (Protobuf)

Protocol Buffers is Google's language-neutral, platform-neutral mechanism for serializing structured data.

### Basic Syntax

```proto
syntax = "proto3";

// Field types
message Example {
  int32   count      = 1;   // 32-bit integer
  int64   id         = 2;   // 64-bit integer
  float   price      = 3;   // 32-bit float
  double  amount     = 4;   // 64-bit float
  bool    active     = 5;   // boolean
  string  name       = 6;   // string
  bytes   data       = 7;   // raw bytes

  repeated string tags = 8; // list/array
}
```

> **Field numbers** (= 1, = 2, etc.) are used in the binary encoding — never change them once in production.

### Scalar Type Mapping (Proto → Java)

| Proto Type | Java Type   |
|------------|-------------|
| `double`   | `double`    |
| `float`    | `float`     |
| `int32`    | `int`       |
| `int64`    | `long`      |
| `bool`     | `boolean`   |
| `string`   | `String`    |
| `bytes`    | `ByteString`|

---

## Why Field Numbers Exist in Proto Files

When you write a proto message like this:

```proto
message User {
  int64  id         = 1;
  string username   = 2;
  string email      = 3;
}
```

The `= 1`, `= 2`, `= 3` are **field numbers**, not default values. They are the core of how protobuf works.

---

### Protobuf Uses Binary Encoding, Not Field Names

JSON serializes data using field **names**:

```json
{ "id": 1, "username": "john", "email": "john@example.com" }
```

Every field name is written out as a string in every single message. This is readable but wasteful.

Protobuf serializes data using field **numbers** instead of names. On the wire, the message above becomes something like:

```
field 1 (varint)  → 1
field 2 (string)  → "john"
field 3 (string)  → "john@example.com"
```

The field name `username` is **never transmitted**. Only the number `2` is. The receiving side uses the proto schema to map number `2` back to the field named `username`.

This is why protobuf payloads are so much smaller and faster to parse than JSON.

---

### How the Tag Is Encoded

Each field in the binary wire format is prefixed with a **tag** that encodes two things:

```
tag = (field_number << 3) | wire_type
```

| Wire Type | Meaning              | Used For                        |
|-----------|----------------------|---------------------------------|
| 0         | Varint               | int32, int64, bool, enum        |
| 1         | 64-bit               | double, fixed64                 |
| 2         | Length-delimited     | string, bytes, nested messages  |
| 5         | 32-bit               | float, fixed32                  |

For example, field number `2` with wire type `2` (string) produces tag `18` (`2 << 3 | 2`). The decoder sees `18` and knows: "this is field 2, and it's a length-delimited string — that's `username`."

---

### Why You Must Never Change a Field Number

Field numbers are the **only contract** between the sender and receiver. The field name can change freely — the number cannot.

```proto
// ✅ Safe — renaming a field does not affect the wire format
message User {
  int64  id          = 1;
  string user_name   = 2;  // renamed from 'username' — wire format unchanged
  string email       = 3;
}

// ❌ Dangerous — changing a field number breaks existing clients
message User {
  int64  id       = 1;
  string email    = 2;  // was 'username = 2' — clients now decode email as username!
  string username = 3;  // was 'email = 3' — clients now decode username as email!
}
```

If you change field numbers after data is in production:
- Old clients reading new messages will assign values to the wrong fields
- New clients reading old messages (e.g. from a database or queue) will silently misread the data
- No error is thrown — just silent data corruption

---

### What to Do When You Remove a Field

If you remove a field, **reserve** its number so it can never be accidentally reused:

```proto
message User {
  reserved 4;              // field number 4 is retired, block reuse
  reserved "phone";        // also block the name

  int64  id       = 1;
  string username = 2;
  string email    = 3;
  // field 4 (phone) was removed — reserved so it can never be reassigned
}
```

If a new field were assigned number `4` later, old clients that still have data with the old `phone` field would silently read the new field's data as a phone number.

---

### Valid Field Number Ranges

| Range             | Notes                                                     |
|-------------------|-----------------------------------------------------------|
| `1 – 15`          | Encoded in **1 byte** — use for the most frequent fields  |
| `16 – 2047`       | Encoded in **2 bytes**                                    |
| `19000 – 19999`   | Reserved by the protobuf library — do not use             |
| `2^29 - 1`        | Maximum allowed field number                              |

Put your most commonly used fields (like `id`, `name`) in the `1–15` range to save one byte per field per message at high volume.

---

### Summary

| Question                        | Answer                                                        |
|---------------------------------|---------------------------------------------------------------|
| What is a field number?         | A unique integer tag identifying each field in the wire format |
| Why not use field names?        | Names are verbose; numbers keep the binary payload tiny        |
| Can I rename a field?           | Yes — names don't affect the wire format                      |
| Can I change a field number?    | Never — it breaks backward compatibility silently             |
| What do I do with removed fields? | Reserve the number with `reserved`                          |
| Which numbers should I use first? | `1–15` for frequent fields (1-byte encoding)               |

---

## Project Setup

### Maven Dependencies

```xml
<properties>
    <grpc.version>1.58.0</grpc.version>
    <protobuf.version>3.24.3</protobuf.version>
</properties>

<dependencies>
    <!-- Spring Boot gRPC Starter (grpc-spring-boot-starter) -->
    <dependency>
        <groupId>net.devh</groupId>
        <artifactId>grpc-spring-boot-starter</artifactId>
        <version>2.15.0.RELEASE</version>
    </dependency>

    <!-- Protobuf Java -->
    <dependency>
        <groupId>com.google.protobuf</groupId>
        <artifactId>protobuf-java</artifactId>
        <version>${protobuf.version}</version>
    </dependency>

    <!-- gRPC stubs -->
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-stub</artifactId>
        <version>${grpc.version}</version>
    </dependency>

    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-protobuf</artifactId>
        <version>${grpc.version}</version>
    </dependency>
</dependencies>

<build>
    <extensions>
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>1.7.1</version>
        </extension>
    </extensions>
    <plugins>
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>
            <version>0.6.1</version>
            <configuration>
                <protocArtifact>com.google.protobuf:protoc:${protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
                <pluginId>grpc-java</pluginId>
                <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>compile-custom</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### application.yml (Server)

```yaml
grpc:
  server:
    port: 9090

spring:
  application:
    name: user-service
```

### application.yml (Client calling another service)

```yaml
grpc:
  server:
    port: 9091
  client:
    user-service:                    # logical name (matches @GrpcClient annotation)
      address: static://localhost:9090
      negotiation-type: plaintext    # use TLS in production

spring:
  application:
    name: ewallet-service
```

---

## Writing Proto Files

### user.proto

```proto
syntax = "proto3";

option java_multiple_files = true;
option java_package = "com.grpc.proto";
option java_outer_classname = "UserProto";

package user;

import "ewallet.proto"; // import other proto to use Account in response

message User {
  int64  id         = 1;
  string username   = 2;
  string email      = 3;
  string first_name = 4;
  string last_name  = 5;
  bool   active     = 6;
}

message CreateUserRequest {
  string username   = 1;
  string email      = 2;
  string first_name = 3;
  string last_name  = 4;
}

message GetUserRequest {
  int64 id = 1;
}

message UserResponse {
  bool   success = 1;
  string message = 2;
  User   user    = 3;
  repeated ewallet.Account accounts = 4; // enriched from ewallet-service
}

message ListUsersRequest {
  int32 page = 1;
  int32 size = 2;
}

message ListUsersResponse {
  bool          success     = 1;
  string        message     = 2;
  repeated User users       = 3;
  int32         total_count = 4;
}

message DeleteUserResponse {
  bool   success = 1;
  string message = 2;
}

service UserService {
  rpc CreateUser (CreateUserRequest) returns (UserResponse);
  rpc GetUser    (GetUserRequest)    returns (UserResponse);
  rpc UpdateUser (UpdateUserRequest) returns (UserResponse);
  rpc DeleteUser (DeleteUserRequest) returns (DeleteUserResponse);
  rpc ListUsers  (ListUsersRequest)  returns (ListUsersResponse);
}
```

### ewallet.proto

```proto
syntax = "proto3";

option java_multiple_files = true;
option java_package = "com.grpc.proto.ewallet";
option java_outer_classname = "EWalletProto";

package ewallet;

message Account {
  int64  id             = 1;
  int64  user_id        = 2;
  string account_number = 3;
  string account_type   = 4;
  double balance        = 5;
  string currency       = 6;
  bool   active         = 7;
}

message GetAccountsByUserIdRequest {
  int64 user_id = 1;
}

message GetAccountsByUserIdResponse {
  bool             success  = 1;
  string           message  = 2;
  repeated Account accounts = 3;
}

service EWalletService {
  rpc GetAccountsByUserId (GetAccountsByUserIdRequest) returns (GetAccountsByUserIdResponse);
}
```

---

## Implementing gRPC Server

### Service Implementation

```java
@GrpcService
@RequiredArgsConstructor
public class UserServiceImpl extends UserServiceGrpc.UserServiceImplBase {

    private final UserRepository userRepository;

    // ── CREATE ────────────────────────────────────────────────────────────────
    @Override
    public void createUser(CreateUserRequest request,
                           StreamObserver<UserResponse> responseObserver) {
        try {
            UserEntity entity = UserEntity.builder()
                    .username(request.getUsername())
                    .email(request.getEmail())
                    .firstName(request.getFirstName())
                    .lastName(request.getLastName())
                    .active(true)
                    .build();

            UserEntity saved = userRepository.save(entity);

            UserResponse response = UserResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("User created successfully")
                    .setUser(toProto(saved))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException()
            );
        }
    }

    // ── READ ──────────────────────────────────────────────────────────────────
    @Override
    public void getUser(GetUserRequest request,
                        StreamObserver<UserResponse> responseObserver) {
        userRepository.findById(request.getId())
                .ifPresentOrElse(
                    entity -> {
                        responseObserver.onNext(UserResponse.newBuilder()
                                .setSuccess(true)
                                .setMessage("User found")
                                .setUser(toProto(entity))
                                .build());
                        responseObserver.onCompleted();
                    },
                    () -> responseObserver.onError(
                        Status.NOT_FOUND
                            .withDescription("User not found with id: " + request.getId())
                            .asRuntimeException()
                    )
                );
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────
    @Override
    public void updateUser(UpdateUserRequest request,
                           StreamObserver<UserResponse> responseObserver) {
        userRepository.findById(request.getId())
                .ifPresentOrElse(
                    entity -> {
                        entity.setUsername(request.getUsername());
                        entity.setEmail(request.getEmail());
                        entity.setFirstName(request.getFirstName());
                        entity.setLastName(request.getLastName());
                        entity.setActive(request.getActive());

                        UserEntity updated = userRepository.save(entity);

                        responseObserver.onNext(UserResponse.newBuilder()
                                .setSuccess(true)
                                .setMessage("User updated successfully")
                                .setUser(toProto(updated))
                                .build());
                        responseObserver.onCompleted();
                    },
                    () -> responseObserver.onError(
                        Status.NOT_FOUND
                            .withDescription("User not found: " + request.getId())
                            .asRuntimeException()
                    )
                );
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    @Override
    public void deleteUser(DeleteUserRequest request,
                           StreamObserver<DeleteUserResponse> responseObserver) {
        if (!userRepository.existsById(request.getId())) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription("User not found").asRuntimeException()
            );
            return;
        }
        userRepository.deleteById(request.getId());
        responseObserver.onNext(DeleteUserResponse.newBuilder()
                .setSuccess(true)
                .setMessage("User deleted successfully")
                .build());
        responseObserver.onCompleted();
    }

    // ── LIST ──────────────────────────────────────────────────────────────────
    @Override
    public void listUsers(ListUsersRequest request,
                          StreamObserver<ListUsersResponse> responseObserver) {
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize());
        Page<UserEntity> page = userRepository.findAll(pageable);

        ListUsersResponse response = ListUsersResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Users fetched")
                .addAllUsers(page.getContent().stream().map(this::toProto).toList())
                .setTotalCount((int) page.getTotalElements())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // ── MAPPER ────────────────────────────────────────────────────────────────
    private User toProto(UserEntity e) {
        return User.newBuilder()
                .setId(e.getId())
                .setUsername(e.getUsername())
                .setEmail(e.getEmail())
                .setFirstName(e.getFirstName())
                .setLastName(e.getLastName())
                .setActive(e.isActive())
                .build();
    }
}
```

---

## Implementing gRPC Client

### Injecting a gRPC Stub

```java
@Service
public class UserGrpcClient {

    // @GrpcClient value must match the key in application.yml grpc.client.*
    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub userStub;

    public UserResponse getUser(long id) {
        GetUserRequest request = GetUserRequest.newBuilder()
                .setId(id)
                .build();
        return userStub.getUser(request);
    }
}
```

---

## Calling Other Microservices

This is the core pattern when one gRPC service needs data from another. Below are the **three main approaches**, each with trade-offs.

---

### Approach 1 — Blocking Stub (Synchronous)

The simplest approach. The thread blocks until the response is received. Good for straightforward request-response.

**Configuration**

```java
@Configuration
public class EWalletGrpcConfig {

    @Value("${ewallet.service.host:localhost}")
    private String host;

    @Value("${ewallet.service.port:9091}")
    private int port;

    @Bean
    public EWalletServiceGrpc.EWalletServiceBlockingStub eWalletBlockingStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()   // replace with .useTransportSecurity() for TLS
                .build();
        return EWalletServiceGrpc.newBlockingStub(channel);
    }
}
```

**Usage in UserServiceImpl**

```java
@GrpcService
@RequiredArgsConstructor
public class UserServiceImpl extends UserServiceGrpc.UserServiceImplBase {

    private final UserRepository userRepository;
    private final EWalletServiceGrpc.EWalletServiceBlockingStub eWalletStub;

    @Override
    public void getUser(GetUserRequest request,
                        StreamObserver<UserResponse> responseObserver) {
        // Step 1: Fetch user
        UserEntity entity = userRepository.findById(request.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Step 2: Call ewallet-service (blocking)
        GetAccountsByUserIdResponse accountsResp = eWalletStub
                .withDeadlineAfter(3, TimeUnit.SECONDS)   // always set a deadline
                .getAccountsByUserId(
                    GetAccountsByUserIdRequest.newBuilder()
                        .setUserId(request.getId())
                        .build()
                );

        // Step 3: Build enriched response
        UserResponse response = UserResponse.newBuilder()
                .setSuccess(true)
                .setMessage("User fetched successfully")
                .setUser(toProto(entity))
                .addAllAccounts(accountsResp.getAccountsList())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
```

**Pros:** Simple, easy to debug  
**Cons:** Thread blocked during the call; poor for high-concurrency scenarios

---

### Approach 2 — @GrpcClient Annotation (Spring Integration)

Spring's `grpc-spring-boot-starter` manages channel lifecycle and lets you inject stubs via annotation. This is the recommended approach for Spring Boot projects.

**application.yml**

```yaml
grpc:
  client:
    ewallet-service:                        # logical name used in @GrpcClient
      address: static://localhost:9091
      negotiation-type: plaintext
      enable-keep-alive: true
      keep-alive-without-calls: true
```

**Usage**

```java
@GrpcService
public class UserServiceImpl extends UserServiceGrpc.UserServiceImplBase {

    @GrpcClient("ewallet-service")          // matches grpc.client.ewallet-service in yml
    private EWalletServiceGrpc.EWalletServiceBlockingStub eWalletStub;

    @Autowired
    private UserRepository userRepository;

    @Override
    public void getUser(GetUserRequest request,
                        StreamObserver<UserResponse> responseObserver) {
        UserEntity entity = userRepository.findById(request.getId())
                .orElse(null);

        if (entity == null) {
            responseObserver.onError(
                Status.NOT_FOUND.withDescription("User not found").asRuntimeException()
            );
            return;
        }

        List<Account> accounts = List.of();
        try {
            GetAccountsByUserIdResponse accountsResp = eWalletStub
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getAccountsByUserId(
                        GetAccountsByUserIdRequest.newBuilder()
                            .setUserId(request.getId())
                            .build()
                    );
            accounts = accountsResp.getAccountsList();
        } catch (StatusRuntimeException e) {
            // Graceful degradation — return user without accounts
            // or rethrow if accounts are critical
        }

        responseObserver.onNext(UserResponse.newBuilder()
                .setSuccess(true)
                .setMessage("User fetched")
                .setUser(toProto(entity))
                .addAllAccounts(accounts)
                .build());
        responseObserver.onCompleted();
    }
}
```

**Pros:** Spring manages channel lifecycle, cleaner code, supports load balancing  
**Cons:** Requires `grpc-spring-boot-starter`

---

### Approach 3 — Async Stub (Non-Blocking)

Uses a `FutureStub` or `AsyncStub` so the calling thread is not blocked. Useful when you want to fire multiple calls in parallel.

**Parallel calls with FutureStub**

```java
@Configuration
public class EWalletGrpcConfig {

    @Bean
    public EWalletServiceGrpc.EWalletServiceFutureStub eWalletFutureStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9091)
                .usePlaintext()
                .build();
        return EWalletServiceGrpc.newFutureStub(channel);
    }
}
```

```java
@GrpcService
@RequiredArgsConstructor
public class UserServiceImpl extends UserServiceGrpc.UserServiceImplBase {

    private final UserRepository userRepository;
    private final EWalletServiceGrpc.EWalletServiceFutureStub eWalletFutureStub;

    @Override
    public void getUser(GetUserRequest request,
                        StreamObserver<UserResponse> responseObserver) {

        UserEntity entity = userRepository.findById(request.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Fire the ewallet call asynchronously
        ListenableFuture<GetAccountsByUserIdResponse> future = eWalletFutureStub
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .getAccountsByUserId(
                    GetAccountsByUserIdRequest.newBuilder()
                        .setUserId(request.getId())
                        .build()
                );

        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(GetAccountsByUserIdResponse result) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("User fetched")
                        .setUser(toProto(entity))
                        .addAllAccounts(result.getAccountsList())
                        .build());
                responseObserver.onCompleted();
            }

            @Override
            public void onFailure(Throwable t) {
                responseObserver.onError(
                    Status.INTERNAL.withDescription("Accounts fetch failed: " + t.getMessage())
                        .asRuntimeException()
                );
            }
        }, MoreExecutors.directExecutor());
    }
}
```

**Pros:** Non-blocking; great for parallel microservice calls  
**Cons:** More complex code and error handling

---

### Comparison of Approaches

| Approach              | Blocking | Spring Integration | Complexity | Best For                    |
|-----------------------|----------|--------------------|------------|-----------------------------|
| Blocking Stub (manual)| Yes      | No                 | Low        | Simple scripts, quick setup |
| @GrpcClient           | Yes      | Yes                | Low        | Standard Spring Boot apps   |
| Async / FutureStub    | No       | Optional           | High       | High throughput, parallel   |

---

### Service Discovery with Kubernetes or Consul

In production, you replace `static://localhost:9091` with a service discovery address.

**With Kubernetes DNS:**

```yaml
grpc:
  client:
    ewallet-service:
      address: dns:///ewallet-service.default.svc.cluster.local:9091
      negotiation-type: plaintext
```

**With Consul:**

```xml
<dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-client-spring-boot-starter</artifactId>
    <version>2.15.0.RELEASE</version>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-consul-discovery</artifactId>
</dependency>
```

```yaml
grpc:
  client:
    ewallet-service:
      address: discovery:///ewallet-service
      negotiation-type: plaintext
```

---

## Error Handling

gRPC uses `Status` codes (similar to HTTP status codes) to communicate errors.

### gRPC Status Code Reference

| Status Code        | Equivalent HTTP | Use Case                               |
|--------------------|-----------------|----------------------------------------|
| `OK`               | 200             | Success                                |
| `NOT_FOUND`        | 404             | Resource not found                     |
| `INVALID_ARGUMENT` | 400             | Bad input                              |
| `ALREADY_EXISTS`   | 409             | Duplicate resource                     |
| `PERMISSION_DENIED`| 403             | Authorization failure                  |
| `UNAUTHENTICATED`  | 401             | Authentication required                |
| `INTERNAL`         | 500             | Unexpected server error                |
| `UNAVAILABLE`      | 503             | Downstream service unavailable         |
| `DEADLINE_EXCEEDED`| 504             | Call took too long                     |

### Throwing Errors

```java
// Not found
throw Status.NOT_FOUND
    .withDescription("User not found with id: " + id)
    .asRuntimeException();

// Validation error
throw Status.INVALID_ARGUMENT
    .withDescription("Email cannot be empty")
    .asRuntimeException();

// Wrapping a Java exception
throw Status.INTERNAL
    .withDescription("Unexpected error")
    .withCause(e)
    .asRuntimeException();
```

### Global Exception Handler

```java
@GrpcAdvice
public class GlobalGrpcExceptionHandler {

    @GrpcExceptionHandler(EntityNotFoundException.class)
    public StatusRuntimeException handleEntityNotFound(EntityNotFoundException e) {
        return Status.NOT_FOUND
                .withDescription(e.getMessage())
                .asRuntimeException();
    }

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public StatusRuntimeException handleIllegalArgument(IllegalArgumentException e) {
        return Status.INVALID_ARGUMENT
                .withDescription(e.getMessage())
                .asRuntimeException();
    }

    @GrpcExceptionHandler(Exception.class)
    public StatusRuntimeException handleGeneral(Exception e) {
        return Status.INTERNAL
                .withDescription("Internal server error: " + e.getMessage())
                .asRuntimeException();
    }
}
```

> `@GrpcAdvice` and `@GrpcExceptionHandler` are provided by `grpc-spring-boot-starter`.

---

## Interceptors

Interceptors are the gRPC equivalent of HTTP filters/middleware. Use them for logging, authentication, tracing, etc.

### Server Interceptor (Logging Incoming Calls)

```java
@Component
@GrpcGlobalServerInterceptor          // applies to ALL gRPC services automatically
public class LoggingServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingServerInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        log.info("[gRPC IN]  method={}", method);

        long start = System.currentTimeMillis();

        ServerCall<ReqT, RespT> wrappedCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long elapsed = System.currentTimeMillis() - start;
                log.info("[gRPC OUT] method={} status={} duration={}ms",
                        method, status.getCode(), elapsed);
                super.close(status, trailers);
            }
        };

        return next.startCall(wrappedCall, headers);
    }
}
```

### Client Interceptor (Adding Auth Token to Outgoing Calls)

```java
@Component
public class AuthClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTH_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(AUTH_HEADER, "Bearer " + getToken());
                super.start(responseListener, headers);
            }
        };
    }

    private String getToken() {
        // Fetch from security context or config
        return "your-internal-service-token";
    }
}
```

**Registering client interceptor with @GrpcClient:**

```java
@GrpcClient(value = "ewallet-service", interceptorNames = "authClientInterceptor")
private EWalletServiceGrpc.EWalletServiceBlockingStub eWalletStub;
```

---

## Streaming (Advanced)

gRPC supports 3 additional communication patterns beyond basic unary RPC.

### Server Streaming — Server sends multiple responses

```proto
rpc ListTransactions (ListTransactionsRequest) returns (stream Transaction);
```

```java
@Override
public void listTransactions(ListTransactionsRequest request,
                              StreamObserver<Transaction> responseObserver) {
    transactionRepository.findByUserId(request.getUserId())
            .forEach(tx -> responseObserver.onNext(toProto(tx)));
    responseObserver.onCompleted();
}
```

### Client Streaming — Client sends multiple requests

```proto
rpc UploadTransactions (stream Transaction) returns (UploadResponse);
```

```java
@Override
public StreamObserver<Transaction> uploadTransactions(StreamObserver<UploadResponse> responseObserver) {
    List<Transaction> batch = new ArrayList<>();
    return new StreamObserver<>() {
        @Override public void onNext(Transaction tx)  { batch.add(tx); }
        @Override public void onError(Throwable t)    { /* handle */ }
        @Override public void onCompleted() {
            saveBatch(batch);
            responseObserver.onNext(UploadResponse.newBuilder().setCount(batch.size()).build());
            responseObserver.onCompleted();
        }
    };
}
```

### Bidirectional Streaming — Both sides stream

```proto
rpc Chat (stream ChatMessage) returns (stream ChatMessage);
```

---

## Best Practices

### 1. Always Set a Deadline on Client Calls

```java
stub.withDeadlineAfter(3, TimeUnit.SECONDS).getUser(request);
```

Without a deadline, a hung downstream service will block your thread forever.

### 2. Reuse ManagedChannel — Never Create Per-Request

```java
// ✅ Singleton channel — create once, inject everywhere
@Bean
public ManagedChannel eWalletChannel() {
    return ManagedChannelBuilder.forAddress("localhost", 9091).usePlaintext().build();
}

// ❌ Never do this
public void getUser(...) {
    ManagedChannel channel = ManagedChannelBuilder.forAddress(...).build(); // creates new connection each time!
}
```

### 3. Use Graceful Degradation for Non-Critical Downstream Calls

```java
try {
    accounts = eWalletStub.withDeadlineAfter(3, TimeUnit.SECONDS)
                          .getAccountsByUserId(request).getAccountsList();
} catch (StatusRuntimeException e) {
    log.warn("Could not fetch accounts for user {}: {}", userId, e.getStatus());
    accounts = List.of(); // return empty list, don't fail the entire request
}
```

### 4. Never Change Proto Field Numbers

```proto
// ❌ Changing field numbers breaks backward compatibility
message User {
  string username = 2; // was 1 — this BREAKS existing clients
}

// ✅ Add new fields with new numbers; mark old ones deprecated
message User {
  string username = 1;
  string nickname = 7; // new field — backward compatible
}
```

### 5. Use TLS in Production

```java
ManagedChannel channel = NettyChannelBuilder
        .forAddress(host, port)
        .useTransportSecurity()               // enables TLS
        .sslContext(GrpcSslContexts.forClient()
            .trustManager(new File("ca.pem"))
            .build())
        .build();
```

### 6. Proto File Organization for Multi-Service Projects

```
shared-proto/
  └── src/main/proto/
        ├── common.proto         # shared messages (Pagination, ErrorDetail, etc.)
        ├── user.proto
        └── ewallet.proto

user-service/
  └── pom.xml  (depends on shared-proto)

ewallet-service/
  └── pom.xml  (depends on shared-proto)
```

Publish `shared-proto` as a Maven artifact so both services always use the same compiled classes.

---

## Quick Reference

```
Proto file (.proto)
    └── mvn compile
          └── generates Java stubs in target/generated-sources/

Server: extend XxxServiceGrpc.XxxServiceImplBase
    + annotate with @GrpcService

Client: inject XxxServiceGrpc.XxxServiceBlockingStub
    + annotate with @GrpcClient("service-name")
    + configure address in application.yml under grpc.client.<service-name>

Error: throw Status.NOT_FOUND.withDescription("...").asRuntimeException()
Global handler: @GrpcAdvice + @GrpcExceptionHandler

Deadline: always use .withDeadlineAfter(N, TimeUnit.SECONDS)
Channel: singleton — create once, reuse everywhere
```