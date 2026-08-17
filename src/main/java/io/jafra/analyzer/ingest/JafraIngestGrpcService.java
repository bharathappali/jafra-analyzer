package io.jafra.analyzer.ingest;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.jafra.ingest.v1.AckStatus;
import io.jafra.ingest.v1.JafraIngestServiceGrpc;
import io.jafra.ingest.v1.UploadAck;
import io.jafra.ingest.v1.UploadRequest;
import io.quarkus.grpc.GrpcService;

@GrpcService
public class JafraIngestGrpcService extends JafraIngestServiceGrpc.JafraIngestServiceImplBase {
    @Inject
    IngestRegistry registry;

    @Override
    public StreamObserver<UploadRequest> upload(StreamObserver<UploadAck> responseObserver) {
        IngestRegistry.StreamContext context;
        try {
            context = registry.openStream();
        } catch (IllegalStateException error) {
            responseObserver.onError(Status.RESOURCE_EXHAUSTED.withDescription(error.getMessage()).asRuntimeException());
            return unusedObserver();
        }
        AtomicBoolean closed = new AtomicBoolean();
        return new StreamObserver<>() {
            @Override
            public void onNext(UploadRequest request) {
                if (closed.get()) {
                    return;
                }
                UploadAck ack = registry.handle(context, request);
                if (ack != null) {
                    finish(ack);
                }
            }

            @Override
            public void onError(Throwable t) {
                closeOnce();
            }

            @Override
            public void onCompleted() {
                if (closed.get()) {
                    return;
                }
                if (!context.committed()) {
                    finish(UploadAck.newBuilder()
                            .setStatus(AckStatus.REJECTED)
                            .setMessage("client disconnect before commit")
                            .build());
                    return;
                }
                closeOnce();
            }

            private void finish(UploadAck ack) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                responseObserver.onNext(ack);
                responseObserver.onCompleted();
                registry.closeStream(context);
            }

            private void closeOnce() {
                if (closed.compareAndSet(false, true)) {
                    registry.closeStream(context);
                }
            }
        };
    }

    private static StreamObserver<UploadRequest> unusedObserver() {
        return new StreamObserver<>() {
            @Override
            public void onNext(UploadRequest value) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}
