package com.grpc.service;

import com.grpc.model.User;
import com.grpc.proto.*;
import com.grpc.repository.UserRepository;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final UserRepository userRepository;

    public UserGrpcService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void createUser(CreateUserRequest request, StreamObserver<UserResponse> responseObserver) {
        try {
            // Validate request
            if (request.getUsername().isBlank()) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Username is required")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            if (request.getEmail().isBlank()) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Email is required")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // Check for duplicate username
            if (userRepository.existsByUsername(request.getUsername())) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Username already exists")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // Check for duplicate email
            if (userRepository.existsByEmail(request.getEmail())) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("Email already exists")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // Create user
            User user = User.builder()
                    .username(request.getUsername())
                    .email(request.getEmail())
                    .firstName(request.getFirstName())
                    .lastName(request.getLastName())
                    .active(true)
                    .build();

            User savedUser = userRepository.save(user);

            responseObserver.onNext(UserResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("User created successfully")
                    .setUser(mapToProtoUser(savedUser))
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onNext(UserResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error creating user: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
        try {
            Optional<User> userOptional = userRepository.findById(request.getId());

            if (userOptional.isEmpty()) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("User not found with id: " + request.getId())
                        .build());
                responseObserver.onCompleted();
                return;
            }

            responseObserver.onNext(UserResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("User retrieved successfully")
                    .setUser(mapToProtoUser(userOptional.get()))
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onNext(UserResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error retrieving user: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void updateUser(UpdateUserRequest request, StreamObserver<UserResponse> responseObserver) {
        try {
            Optional<User> existingUserOptional = userRepository.findById(request.getId());

            if (existingUserOptional.isEmpty()) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("User not found with id: " + request.getId())
                        .build());
                responseObserver.onCompleted();
                return;
            }

            User existingUser = existingUserOptional.get();

            // Update fields
            if (!request.getUsername().isBlank()) {
                existingUser.setUsername(request.getUsername());
            }
            if (!request.getEmail().isBlank()) {
                existingUser.setEmail(request.getEmail());
            }
            if (!request.getFirstName().isBlank()) {
                existingUser.setFirstName(request.getFirstName());
            }
            if (!request.getLastName().isBlank()) {
                existingUser.setLastName(request.getLastName());
            }
            existingUser.setActive(request.getActive());

            User updatedUser = userRepository.save(existingUser);

            responseObserver.onNext(UserResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("User updated successfully")
                    .setUser(mapToProtoUser(updatedUser))
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onNext(UserResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error updating user: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void deleteUser(DeleteUserRequest request, StreamObserver<DeleteUserResponse> responseObserver) {
        try {
            if (!userRepository.existsById(request.getId())) {
                responseObserver.onNext(DeleteUserResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("User not found with id: " + request.getId())
                        .build());
                responseObserver.onCompleted();
                return;
            }

            userRepository.deleteById(request.getId());

            responseObserver.onNext(DeleteUserResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("User deleted successfully")
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onNext(DeleteUserResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error deleting user: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void listUsers(ListUsersRequest request, StreamObserver<ListUsersResponse> responseObserver) {
        try {
            int page = request.getPage() > 0 ? request.getPage() : 0;
            int size = request.getSize() > 0 ? request.getSize() : 10;

            List<User> users = userRepository.findAll(page, size);
            int totalCount = userRepository.count();

            ListUsersResponse.Builder responseBuilder = ListUsersResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Users retrieved successfully")
                    .setTotalCount(totalCount);

            for (User user : users) {
                responseBuilder.addUsers(mapToProtoUser(user));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onNext(ListUsersResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error retrieving users: " + e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    private com.grpc.proto.User mapToProtoUser(User user) {
        return com.grpc.proto.User.newBuilder()
                .setId(user.getId())
                .setUsername(user.getUsername())
                .setEmail(user.getEmail())
                .setFirstName(user.getFirstName() != null ? user.getFirstName() : "")
                .setLastName(user.getLastName() != null ? user.getLastName() : "")
                .setActive(user.isActive())
                .build();
    }
}
