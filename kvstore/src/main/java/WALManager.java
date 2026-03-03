import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.rocksdb.RocksDBException;
import org.rocksdb.TransactionDB;
import org.rocksdb.WriteOptions;

import com.google.protobuf.Message;

import grpc.LogEntry;

public class WALManager implements AutoCloseable {
    private final FileChannel fileChannel;
    private final RandomAccessFile raf;

    public WALManager(String logFilePath) throws IOException {
        File logFile = new File(logFilePath);
        logFile.getParentFile().mkdirs();
        this.raf = new RandomAccessFile(logFile, "rw");
        this.fileChannel = raf.getChannel();
        fileChannel.position(fileChannel.size());
    }

    public synchronized void append(Message command) throws IOException {
        byte[] data = command.toByteArray();

        ByteBuffer buffer = ByteBuffer.allocate(4 + data.length);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        // Write to channel
        while (buffer.hasRemaining()) {
            fileChannel.write(buffer);
        }

        // Force the OS to push data to disk
        fileChannel.force(true);

    }

    public void replay(TransactionDB db, WriteOptions writeOptions) throws IOException, RocksDBException {
        fileChannel.position(0); // Start from the beginning of the log
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);

        while (fileChannel.read(lengthBuffer) == 4) {
            lengthBuffer.flip();
            int length = lengthBuffer.getInt();
            lengthBuffer.clear();

            ByteBuffer dataBuffer = ByteBuffer.allocate(length);
            int bytesRead = fileChannel.read(dataBuffer);

            // If the file ends before the full command is read, it's a partial write
            if (bytesRead < length) {
                System.err.println("Partial write detected at end of log. Discarding tail.");
                fileChannel.truncate(fileChannel.position() - 4 - bytesRead);
                break;
            }

            LogEntry entry = LogEntry.parseFrom(dataBuffer.array());

            // Replay the command to the state machine (RocksDB)
            if (entry.hasPut()) {
                db.put(writeOptions, entry.getPut().getKey().getBytes(), entry.getPut().getValue().getBytes());
            } else if (entry.hasDelete()) {
                db.delete(writeOptions, entry.getDelete().getKey().getBytes());
            } else if (entry.hasSwap()) {
                db.put(writeOptions, entry.getSwap().getKey().getBytes(), entry.getSwap().getValue().getBytes());
            }
        }
        // Set position back to end for future appends
        fileChannel.position(fileChannel.size());
    }

    @Override
    public void close() throws IOException {
        if (fileChannel != null)
            fileChannel.close();
        if (raf != null)
            raf.close();
    }
}