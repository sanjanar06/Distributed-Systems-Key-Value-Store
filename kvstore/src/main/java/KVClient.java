import java.util.Iterator;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

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
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class KVClient {
    private final ManagedChannel channel;
    private final KVStoreGrpc.KVStoreBlockingStub blockingStub;
    
    public KVClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()  
                .build();
        
        this.blockingStub = KVStoreGrpc.newBlockingStub(channel);
    }
    
    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public void put(String key, String value) {
        PutRequest request = PutRequest.newBuilder()
                .setKey(key)
                .setValue(value)
                .build();
        
        PutResponse response = blockingStub.put(request);
        
        if (response.getFound()) {
            System.out.println("PUT " + key + " found");
        } else {
            System.out.println("PUT " + key + " not_found");
        }
    }

    public void get(String key) {
        GetRequest request = GetRequest.newBuilder()
                .setKey(key)
                .build();
        
        GetResponse response = blockingStub.get(request);
        
        if (response.getFound()) {
            System.out.println("GET " + key + " " + response.getValue());
        } else {
            System.out.println("GET " + key + " null");
        }
    }
    
    public void delete(String key) {
        DeleteRequest request = DeleteRequest.newBuilder()
                .setKey(key)
                .build();
        
        DeleteResponse response = blockingStub.delete(request);
        
        if (response.getFound()) {
            System.out.println("DELETE " + key + " found");
        } else {
            System.out.println("DELETE " + key + " not_found");
        }
    }
    
    public void swap(String key, String value) {
        SwapRequest request = SwapRequest.newBuilder()
                .setKey(key)
                .setValue(value)
                .build();
        
        SwapResponse response = blockingStub.swap(request);
        
        if (response.getFound()) {
            System.out.println("SWAP " + key + " " + response.getOldValue());
        } else {
            System.out.println("SWAP " + key + " null");
        }
    }
    
    public void scan(String startKey, String endKey) {
        ScanRequest request = ScanRequest.newBuilder()
                .setStartKey(startKey)
                .setEndKey(endKey)
                .build();
        
        Iterator<ScanResponse> responses = blockingStub.scan(request);
        
        System.out.println("SCAN " + startKey + " " + endKey + " BEGIN");
        
        while (responses.hasNext()) {
            ScanResponse response = responses.next();
            System.out.println(response.getKey() + " " + response.getValue());
        }
        
        System.out.println("SCAN END");
    }
    
    public static void main(String[] args) throws InterruptedException {
        String host = "localhost";
        int port = 8080;
        
        if (args.length >= 1) {
            String arg = args[0];
            if (arg.contains(":")) {
                String[] parts = arg.split(":");
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            }
        }
        
        KVClient client = new KVClient(host, port);
        
        try {
            try (Scanner scanner = new Scanner(System.in)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    
                    if (line.isEmpty()) {
                        continue;
                    }
                    
                    String[] parts = line.split("\\s+");
                    String command = parts[0].toUpperCase();
                    
                    switch (command) {
                        case "PUT" -> {
                            if (parts.length >= 3) {
                                client.put(parts[1], parts[2]);
                            }
                        }
                        case "GET" -> {
                            if (parts.length >= 2) {
                                client.get(parts[1]);
                            }
                        }
                        case "DELETE" -> {
                            if (parts.length >= 2) {
                                client.delete(parts[1]);
                            }
                        }
                        case "SWAP" -> {
                            if (parts.length >= 3) {
                                client.swap(parts[1], parts[2]);
                            }
                        }
                        case "SCAN" -> {
                            if (parts.length >= 3) {
                                client.scan(parts[1], parts[2]);
                            }
                        }
                        case "STOP" -> {
                            System.out.println("STOP");
                            return;
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            client.shutdown();
        }
    }
}
