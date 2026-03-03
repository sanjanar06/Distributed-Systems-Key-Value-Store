import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import grpc.DeleteRequest;
import grpc.DeleteResponse;
import grpc.GetRequest;
import grpc.GetResponse;
import grpc.KVStoreGrpc;
import grpc.ManagerGrpc;
import grpc.PartitionRequest;
import grpc.PartitionResponse;
import grpc.PutRequest;
import grpc.PutResponse;
import grpc.ScanRequest;
import grpc.ScanResponse;
import grpc.SwapRequest;
import grpc.SwapResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

public class KVClient {
    private final Map<Integer, KVStoreGrpc.KVStoreBlockingStub> serverStubs = new HashMap<>();
    private final MessageDigest sha256;
    private int numServers;

    public KVClient(String managerAddr) throws NoSuchAlgorithmException {
        super();
        this.sha256 = MessageDigest.getInstance("SHA-256");
        initializeFromManager(managerAddr);
    }

    private void initializeFromManager(String managerAddr) {
        ManagedChannel managerChannel = ManagedChannelBuilder.forTarget(managerAddr)
                .usePlaintext()
                .build();
        ManagerGrpc.ManagerBlockingStub managerStub = ManagerGrpc.newBlockingStub(managerChannel);

        System.err.println("Client connecting to Manager at " + managerAddr + "...");

        while (true) {
            try {
                PartitionResponse response = managerStub.getPartitions(PartitionRequest.getDefaultInstance());
                this.numServers = response.getPartitionsCount();
                
                for (PartitionResponse.PartitionInfo info : response.getPartitionsList()) {
                    ManagedChannel channel = ManagedChannelBuilder.forTarget(info.getServerAddress())
                            .usePlaintext()
                            .build();
                    serverStubs.put(info.getPartitionId(), KVStoreGrpc.newBlockingStub(channel));
                }
                System.err.println("Discovered " + numServers + " servers via Manager.");
                break;
            } catch (StatusRuntimeException e) {
                System.err.println("Manager unavailable, retrying in 2 seconds...");
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException ignored) {}
            }
        }
    }


    private KVStoreGrpc.KVStoreBlockingStub getStubForKey(String key) {
        byte[] hash = sha256.digest(key.getBytes());
        int bucket = (ByteBuffer.wrap(hash).getInt() & Integer.MAX_VALUE) % numServers;
        return serverStubs.get(bucket);
    }

    public void put(String key, String value) {
        while (true) {
            try {
                PutResponse response = getStubForKey(key).put(PutRequest.newBuilder()
                        .setKey(key).setValue(value).build());
                System.out.println("PUT " + key + (response.getFound() ? " found" : " not_found"));
                return;
            } catch (StatusRuntimeException e) {
                handleError(e);
            }
        }
    }

    public void get(String key) {
        while (true) {
            try {
                GetResponse response = getStubForKey(key).get(GetRequest.newBuilder()
                        .setKey(key).build());
                System.out.println("GET " + key + " " + (response.getFound() ? response.getValue() : "null"));
                return;
            } catch (StatusRuntimeException e) {
                handleError(e);
            }
        }
    }

    public void delete(String key) {
        while (true) {
            try {
                DeleteResponse response = getStubForKey(key).delete(DeleteRequest.newBuilder()
                        .setKey(key).build());
                System.out.println("DELETE " + key + (response.getFound() ? " found" : " not_found"));
                return;
            } catch (StatusRuntimeException e) {
                handleError(e);
            }
        }
    }

    public void swap(String key, String value) {
        while (true) {
            try {
                SwapResponse response = getStubForKey(key).swap(SwapRequest.newBuilder()
                        .setKey(key).setValue(value).build());
                System.out.println("SWAP " + key + " " + (response.getFound() ? response.getOldValue() : "null"));
                return;
            } catch (StatusRuntimeException e) {
                handleError(e);
            }
        }
    }

    public void scan(String startKey, String endKey) {
        System.out.println("SCAN " + startKey + " " + endKey + " BEGIN");
        for (KVStoreGrpc.KVStoreBlockingStub stub : serverStubs.values()) {
            try {
                Iterator<ScanResponse> responses = stub.scan(ScanRequest.newBuilder()
                        .setStartKey(startKey).setEndKey(endKey).build());
                while (responses.hasNext()) {
                    ScanResponse r = responses.next();
                    System.out.println(r.getKey() + " " + r.getValue());
                }
            } catch (StatusRuntimeException e) {
                System.err.println("Warning: Scan partially failed on one partition: " + e.getStatus());
            }
        }
        System.out.println("SCAN END");
    }

    private void handleError(StatusRuntimeException e) {
        System.err.println("Server call failed (" + e.getStatus() + "), retrying in 1 second...");
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        String managerAddr = "localhost:3666";
        
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--manager_addr") && i + 1 < args.length) {
                managerAddr = args[++i];
            }
        }

        KVClient client = new KVClient(managerAddr);
        
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                String cmd = parts[0].toUpperCase();

                switch (cmd) {
                    case "PUT": if (parts.length >= 3) client.put(parts[1], parts[2]); break;
                    case "GET": if (parts.length >= 2) client.get(parts[1]); break;
                    case "DELETE": if (parts.length >= 2) client.delete(parts[1]); break;
                    case "SWAP": if (parts.length >= 3) client.swap(parts[1], parts[2]); break;
                    case "SCAN": if (parts.length >= 3) client.scan(parts[1], parts[2]); break;
                    case "STOP": return;
                }
                System.out.flush();
            }
        }
    }
}