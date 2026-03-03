import java.io.File;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.TransactionDB;
import org.rocksdb.TransactionDBOptions;
import org.rocksdb.WriteOptions;

import grpc.DeleteRequest;
import grpc.DeleteResponse;
import grpc.GetRequest;
import grpc.GetResponse;
import grpc.KVStoreGrpc;
import grpc.LogEntry;
import grpc.ManagerGrpc;
import grpc.PutRequest;
import grpc.PutResponse;
import grpc.RegisterRequest;
import grpc.ScanRequest;
import grpc.ScanResponse;
import grpc.SwapRequest;
import grpc.SwapResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

public class KVServer {

    private static TransactionDB db;
    private static int serverId;
    private static int totalServers;

    static {
        RocksDB.loadLibrary();
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String backerPath = null;
        String managerAddress = null;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--api_listen"))
                port = Integer.parseInt(args[++i].split(":")[1]);
            else if (args[i].equals("--backer_path"))
                backerPath = args[++i];
            else if (args[i].equals("--manager_addr"))
                managerAddress = args[++i];
            else if (args[i].equals("--server_id"))
                serverId = Integer.parseInt(args[++i]);
        }
        File backerDir = new File(backerPath);
        if (!backerDir.exists()) {
            backerDir.mkdirs();
        }
        try (Options options = new Options().setCreateIfMissing(true);
                TransactionDBOptions txnDbOptions = new TransactionDBOptions()) {

            // Open the database with pessimistic locking enabled
            db = TransactionDB.open(options, txnDbOptions, new File(backerPath, "db").getAbsolutePath());
            WALManager wal = new WALManager(new File(backerPath, "server.log").getAbsolutePath());
            WriteOptions wo = new WriteOptions().setDisableWAL(true);

            wal.replay(db, wo);

            totalServers = registerWithManager(managerAddress, serverId);

            Server server = ServerBuilder.forPort(port)
                    .addService(new KVStoreService(db, wal, wo))
                    .build();

            server.start();

            server.awaitTermination();
        } catch (RocksDBException e) {
            System.err.println("Error initializing RocksDB: " + e.getMessage());
        }

    }

    private static int registerWithManager(String addr, int id) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(addr).usePlaintext().build();
        ManagerGrpc.ManagerBlockingStub stub = ManagerGrpc.newBlockingStub(channel);
        while (true) {
            try {
                // Returns total servers in cluster so we can perform consistent hashing locally
                return stub.register(RegisterRequest.newBuilder().setServerId(id).build()).getTotalServers();
            } catch (StatusRuntimeException e) {
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (Exception ignored) {
                }
            }
        }
    }

    static class KVStoreService extends KVStoreGrpc.KVStoreImplBase {
        private final TransactionDB db;
        private final WALManager wal;
        private final WriteOptions writeOptions;

        public KVStoreService(TransactionDB db, WALManager wal, WriteOptions writeOptions) throws Exception {
            this.db = db;
            this.wal = wal;
            this.writeOptions = writeOptions;
        }

        private void checkHash(String key) {
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] hash = sha256.digest(key.getBytes());
                int bucket = (ByteBuffer.wrap(hash).getInt() & Integer.MAX_VALUE) % totalServers;

                if (bucket != serverId) {
                    throw Status.INVALID_ARGUMENT
                            .withDescription("Key " + key + " belongs to server " + bucket)
                            .asRuntimeException();
                }
            } catch (NoSuchAlgorithmException e) {
                throw Status.INTERNAL.withDescription("SHA-256 not found").asRuntimeException();
            }
        }

        @Override
        public synchronized void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
            System.out.println("RPC: PUT key=" + request.getKey() + " val=" + request.getValue());
            try {
                checkHash(request.getKey());
                LogEntry entry = LogEntry.newBuilder()
                        .setPut(request)
                        .build();
                wal.append(entry);

                byte[] key = request.getKey().getBytes();
                boolean found = db.get(key) != null;

                db.put(writeOptions, key, request.getValue().getBytes());
                responseObserver.onNext(
                        PutResponse.newBuilder()
                                .setFound(found)
                                .build());

            } catch (Exception e) {
                responseObserver.onError(e);
            }

            responseObserver.onCompleted();

        }

        @Override
        public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
            try {
                checkHash(request.getKey());
                byte[] value = db.get(request.getKey().getBytes());
                boolean found = (value != null);

                responseObserver.onNext(
                        GetResponse.newBuilder()
                                .setFound(found)
                                .setValue(found ? new String(value) : "")
                                .build());

            } catch (Exception e) {
                responseObserver.onError(e);
            }

            responseObserver.onCompleted();
        }

        @Override
        public synchronized void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
            try {
                checkHash(request.getKey());
                LogEntry entry = LogEntry.newBuilder().setDelete(request).build();
                wal.append(entry);

                byte[] key = request.getKey().getBytes();
                boolean found = db.get(key) != null;
                db.delete(writeOptions, key);

                responseObserver.onNext(DeleteResponse.newBuilder().setFound(found).build());
            } catch (Exception e) {
                responseObserver.onError(e);
            }
            responseObserver.onCompleted();
        }

        @Override
        public synchronized void swap(SwapRequest request, StreamObserver<SwapResponse> responseObserver) {
            try {
                checkHash(request.getKey());
                LogEntry entry = LogEntry.newBuilder()
                        .setSwap(request)
                        .build();
                wal.append(entry);

                byte[] key = request.getKey().getBytes();
                byte[] oldValue = db.get(key);

                db.put(writeOptions, key, request.getValue().getBytes());

                responseObserver.onNext(
                        SwapResponse.newBuilder()
                                .setFound(oldValue != null)
                                .setOldValue(oldValue != null ? new String(oldValue) : "")
                                .build());

            } catch (Exception e) {
                responseObserver.onError(e);
            }

            responseObserver.onCompleted();
        }

        @Override
        public void scan(ScanRequest request, StreamObserver<ScanResponse> responseObserver) {
            String startKey = request.getStartKey();
            String endKey = request.getEndKey();

            try (RocksIterator iterator = db.newIterator()) {
                for (iterator.seek(startKey.getBytes()); iterator.isValid(); iterator.next()) {
                    String currentKey = new String(iterator.key());
                    if (currentKey.compareTo(endKey) > 0) {
                        break;
                    }
                    responseObserver.onNext(
                            ScanResponse.newBuilder()
                                    .setKey(currentKey)
                                    .setValue(new String(iterator.value()))
                                    .build());
                }
            } catch (Exception e) {
                responseObserver.onError(e);
            }

            responseObserver.onCompleted();
        }
    }
}
