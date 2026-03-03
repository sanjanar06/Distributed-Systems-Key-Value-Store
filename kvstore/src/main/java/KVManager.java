import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import grpc.*;

public class KVManager {
    public static void main(String[] args) throws Exception {
        int port = 3666;
        String[] servers = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--man_listen"))
                port = Integer.parseInt(args[++i].split(":")[1]);
            else if (args[i].equals("--servers"))
                servers = args[++i].split(",");
        }
        final String[] finalServers = servers;
        ServerBuilder.forPort(port).addService(new ManagerService(finalServers)).build().start().awaitTermination();
    }

    static class ManagerService extends ManagerGrpc.ManagerImplBase {
        private final String[] servers;

        public ManagerService(String[] s) {
            this.servers = s;
        }

        @Override
        public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
            responseObserver.onNext(RegisterResponse.newBuilder().setTotalServers(servers.length).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getPartitions(PartitionRequest request, StreamObserver<PartitionResponse> responseObserver) {
            PartitionResponse.Builder resp = PartitionResponse.newBuilder();
            for (int i = 0; i < servers.length; i++) {
                resp.addPartitions(PartitionResponse.PartitionInfo.newBuilder()
                        .setPartitionId(i).setServerAddress(servers[i]).build());
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        }
    }
}