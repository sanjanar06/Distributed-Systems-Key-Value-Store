import java.io.IOException;
import java.util.concurrent.ConcurrentSkipListMap;

import grpc.DeleteRequest;
import grpc.DeleteResponse;
import grpc.GetRequest;
import grpc.GetResponse;
import grpc.KVStoreGrpc;
import grpc.PutRequest;
import grpc.PutResponse;
import grpc.ScanRequest;
import grpc.ScanResponse;
import grpc.SwapRequest;
import grpc.SwapResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

public class KVServer {
    
    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 8080;
        
        if (args.length >= 1) {
            String arg = args[0];
            if (arg.contains(":")) {
                String[] parts = arg.split(":");
                port = Integer.parseInt(parts[1]);
            } else {
                port = Integer.parseInt(arg);
            }
        }
        
        Server server = ServerBuilder
                .forPort(port)                      
                .addService(new KVStoreService())   
                .build();                           
        
        server.start();
        System.err.println("Server started on port " + port);
        
        server.awaitTermination();
    }
    
    
    static class KVStoreService extends KVStoreGrpc.KVStoreImplBase {
        private final ConcurrentSkipListMap<String, String> store = new ConcurrentSkipListMap<>();
        
        @Override
        public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
            
            String key = request.getKey();
            
            boolean found = store.containsKey(key);
            
            store.put(key, request.getValue());
            
            responseObserver.onNext(
                PutResponse.newBuilder()
                    .setFound(found)
                    .build());
            responseObserver.onCompleted();
        }
        
        @Override
        public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
            String key = request.getKey();
            String value = store.get(key);
            
            if (value != null) {
                responseObserver.onNext(
                    GetResponse.newBuilder()
                        .setFound(true)
                        .setValue(value)
                        .build());
            } else {
                responseObserver.onNext(
                    GetResponse.newBuilder()
                        .setFound(false)
                        .setValue("")
                        .build());
            }
            
            responseObserver.onCompleted();
        }
        
        @Override
        public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
            String key = request.getKey();
            String removedValue = store.remove(key);
            
            boolean found = (removedValue != null);
            
            responseObserver.onNext(
                DeleteResponse.newBuilder()
                    .setFound(found)
                    .build());
            
            responseObserver.onCompleted();
        }
        
        @Override
        public void swap(SwapRequest request, StreamObserver<SwapResponse> responseObserver) {
            String key = request.getKey();
            String newValue = request.getValue();
            
            String oldValue = store.put(key, newValue);
            
            if (oldValue != null) {
                responseObserver.onNext(
                    SwapResponse.newBuilder()
                        .setFound(true)
                        .setOldValue(oldValue)
                        .build());
            } else {
                responseObserver.onNext(
                    SwapResponse.newBuilder()
                        .setFound(false)
                        .setOldValue("")
                        .build());
            }
            
            responseObserver.onCompleted();
        }
        
        @Override
        public void scan(ScanRequest request, StreamObserver<ScanResponse> responseObserver) {
            String startKey = request.getStartKey();
            String endKey = request.getEndKey();
            
            store.subMap(startKey, true, endKey, true)
                    .forEach((key, value) -> {
                        responseObserver.onNext(
                            ScanResponse.newBuilder()
                                .setKey(key)
                                .setValue(value)
                                .build());
                    });
            
            responseObserver.onCompleted();
        }
    }
}
