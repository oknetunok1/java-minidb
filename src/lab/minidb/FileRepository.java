package lab.minidb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/** Два чередующихся снимка с контрольной суммой. */
public final class FileRepository implements DatabaseRepository {
    private static final int MAGIC = 0x4D444232;
    private static final int VERSION = 1;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private long generation;
    private int activeSlot = -1;
    private String recoveryMessage;

    public FileRepository(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("database.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            throw new IOException("База уже открыта другой программой", e);
        } catch (IOException e) {
            lockChannel.close();
            throw e;
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("База уже открыта другой программой");
        }
        lock = acquired;
    }

    @Override
    public Database load() throws IOException {
        Database latest = null;
        boolean damaged = false;
        generation = 0;
        activeSlot = -1;
        recoveryMessage = null;
        for (int slot = 0; slot < 2; slot++) {
            Path file = snapshot(slot);
            if (!Files.exists(file)) continue;
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Неизвестный формат снимка");
                long current = input.readLong();
                int size = input.readInt();
                long checksum = input.readLong();
                if (current < 1 || size < 0 || size != Files.size(file) - 28) throw new IOException("Повреждён заголовок");
                byte[] bytes = new byte[size];
                input.readFully(bytes);
                if (checksum(current, bytes) != checksum) throw new IOException("Не совпадает контрольная сумма");
                try (ObjectInputStream objects = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                    Database database = (Database) objects.readObject();
                    database.validateReferences();
                    if (current > generation) {
                        latest = database;
                        generation = current;
                        activeSlot = slot;
                    }
                }
            } catch (IOException | ClassNotFoundException | RuntimeException e) {
                damaged = true;
            }
        }
        if (latest == null && damaged) throw new IOException("Нет исправного снимка. Файлы базы оставлены без изменений");
        if (damaged) recoveryMessage = "Повреждённый снимок пропущен. Загружено поколение " + generation + "; последние изменения могли быть потеряны.";
        return latest;
    }

    @Override
    public void save(Database database) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream objects = new ObjectOutputStream(buffer)) {
            objects.writeObject(database);
        }
        byte[] bytes = buffer.toByteArray();
        long next = generation + 1;
        int slot = activeSlot == 0 ? 1 : 0;
        Path temp = directory.resolve("database.tmp");
        try (FileOutputStream file = new FileOutputStream(temp.toFile());
             DataOutputStream output = new DataOutputStream(file)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(next);
            output.writeInt(bytes.length);
            output.writeLong(checksum(next, bytes));
            output.write(bytes);
            output.flush();
            file.getFD().sync();
        }
        try {
            Files.move(temp, snapshot(slot), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, snapshot(slot), StandardCopyOption.REPLACE_EXISTING);
        }
        generation = next;
        activeSlot = slot;
    }

    public String recoveryMessage() {
        return recoveryMessage;
    }

    private Path snapshot(int slot) {
        return directory.resolve("database." + slot + ".bin");
    }

    private static long checksum(long generation, byte[] bytes) {
        CRC32 crc = new CRC32();
        for (int shift = 56; shift >= 0; shift -= 8) crc.update((int) (generation >>> shift) & 255);
        crc.update(bytes);
        return crc.getValue();
    }

    @Override
    public void close() throws IOException {
        try { lock.release(); } finally { lockChannel.close(); }
    }
}

/** Загрузка и сохранение целого состояния базы. */
interface DatabaseRepository extends AutoCloseable {
    Database load() throws IOException;
    void save(Database database) throws IOException;

    @Override
    void close() throws IOException;
}
