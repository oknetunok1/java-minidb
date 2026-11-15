package lab.minidb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Сравнивает поиск по дереву и полный перебор на одной базе. */
public final class Benchmark {
    private static final int WARMUP = 200;
    private static final int ROUNDS = 7;
    private static final int QUERIES = 200;
    private static volatile long checksum;

    private Benchmark() { }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length == 0 ? "build/benchmark.csv" : args[0]);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        List<String> csv = new ArrayList<>();
        csv.add("rows,matched,scan_examined,index_examined,scan_us,index_us,speedup,index_build_ms");
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("CPU threads: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Warmup: " + WARMUP + ", rounds: " + ROUNDS + ", queries per round: " + QUERIES);
        for (int size : new int[]{10000, 50000, 100000}) {
            String row = measure(size);
            csv.add(row);
            System.out.println(row);
        }
        Files.write(output, csv, StandardCharsets.UTF_8);
        System.out.println("CSV: " + output.toAbsolutePath());
        System.out.println("Checksum: " + checksum);
    }

    private static String measure(int size) throws Exception {
        Path directory = DatabaseTest.temporaryDirectory();
        long buildTime;
        try (FileRepository repository = new FileRepository(directory)) {
            DatabaseEngine db = new DatabaseEngine(repository);
            StringBuilder sql = new StringBuilder("INSERT INTO cars VALUES ");
            for (int i = 0; i < size; i++) {
                if (i > 0) sql.append(',');
                sql.append('(').append(i).append(", 'Brand").append(i % 1000).append("', ").append(10000 + i).append(", TRUE)");
            }
            db.execute(sql.toString());
            long start = System.nanoTime();
            db.execute("CREATE INDEX idx_brand ON cars(brand)");
            buildTime = System.nanoTime() - start;
        }
        try (FileRepository repository = new FileRepository(directory)) {
            DatabaseEngine db = new DatabaseEngine(repository);
            List<SqlCommand> queries = new ArrayList<>();
            SqlParser parser = new SqlParser();
            for (int i = 0; i < 20; i++) {
                SqlCommand query = parser.parse("SELECT id, brand, price FROM cars WHERE brand='Brand" + (i * 47) + "'");
                QueryResult scan = db.execute(query, false);
                QueryResult indexed = db.execute(query, true);
                if (scan.rows.size() != indexed.rows.size() || !new HashSet<>(scan.rows).equals(new HashSet<>(indexed.rows))) {
                    throw new AssertionError("Результаты планов не совпадают");
                }
                if (!indexed.plan.equals("INDEX idx_brand")) throw new AssertionError("Индекс не восстановился");
                queries.add(query);
            }
            batch(db, queries, false, WARMUP);
            batch(db, queries, true, WARMUP);
            double[] scans = new double[ROUNDS];
            double[] indexes = new double[ROUNDS];
            for (int round = 0; round < ROUNDS; round++) {
                if (round % 2 == 0) {
                    scans[round] = batch(db, queries, false, QUERIES);
                    indexes[round] = batch(db, queries, true, QUERIES);
                } else {
                    indexes[round] = batch(db, queries, true, QUERIES);
                    scans[round] = batch(db, queries, false, QUERIES);
                }
            }
            Arrays.sort(scans);
            Arrays.sort(indexes);
            double scan = scans[ROUNDS / 2];
            double index = indexes[ROUNDS / 2];
            QueryResult scanResult = db.execute(queries.get(0), false);
            QueryResult indexResult = db.execute(queries.get(0), true);
            return String.format(Locale.ROOT, "%d,%d,%d,%d,%.3f,%.3f,%.2f,%.3f", size, indexResult.rows.size(),
                    scanResult.examinedRows, indexResult.examinedRows, scan, index, scan / index, buildTime / 1_000_000.0);
        }
    }

    private static double batch(DatabaseEngine db, List<SqlCommand> queries, boolean indexed, int count) throws IOException {
        long total = 0;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            QueryResult result = db.execute(queries.get(i % queries.size()), indexed);
            total += result.rows.size();
            for (List<Object> row : result.rows) total += (Integer) row.get(0);
        }
        long elapsed = System.nanoTime() - start;
        checksum += total;
        return elapsed / (count * 1000.0);
    }
}
