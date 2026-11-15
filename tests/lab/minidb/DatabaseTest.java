package lab.minidb;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;

/** Пять основных проверок. */
public class DatabaseTest {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        insertion();
        changes();
        typesAndReferences();
        persistence();
        index();
        System.out.println("Все 5 проверок прошли.");
    }

    private static void insertion() throws Exception {
        try (FileRepository storage = new FileRepository(temporaryDirectory())) {
            DatabaseEngine db = new DatabaseEngine(storage);
            db.execute("INSERT INTO cars VALUES(1,'Toyota',25000,TRUE),(2,'BMW',30000,FALSE)");
            equal(2, db.execute("SELECT * FROM cars").rows.size());
            equal(Arrays.asList(1, "Toyota", 25000.0, true), db.execute("SELECT * FROM cars WHERE id=1").rows.get(0));
            rejected(db, "INSERT INTO cars(id) VALUES(3),(1)");
            equal(2, db.execute("SELECT * FROM cars").rows.size());
        }
        System.out.println("OK: вставка, SELECT и уникальность ключа");
    }

    private static void changes() throws Exception {
        try (FileRepository storage = new FileRepository(temporaryDirectory())) {
            DatabaseEngine db = new DatabaseEngine(storage);
            db.execute("INSERT INTO cars VALUES(1,'Toyota',25000,TRUE),(2,'BMW',30000,FALSE)");
            db.execute("UPDATE cars SET price=27000 WHERE id=1");
            equal(27000.0, db.execute("SELECT price FROM cars WHERE id=1").rows.get(0).get(0));
            equal(30000.0, db.execute("SELECT price FROM cars WHERE id=2").rows.get(0).get(0));
            db.execute("DELETE FROM cars WHERE price>28000");
            equal(1, db.execute("SELECT * FROM cars").rows.size());
            equal(1, db.execute("SELECT id FROM cars").rows.get(0).get(0));
        }
        System.out.println("OK: обновление и удаление по условию");
    }

    private static void typesAndReferences() throws Exception {
        try (FileRepository storage = new FileRepository(temporaryDirectory())) {
            DatabaseEngine db = new DatabaseEngine(storage);
            db.execute("INSERT INTO cars VALUES(1,'Toyota',25000.5,TRUE)");
            equal(Arrays.asList(1, "Toyota", 25000.5, true), db.execute("SELECT * FROM cars").rows.get(0));
            db.execute("INSERT INTO sales VALUES(1,1,'Иван','2024-02-29')");
            equal(LocalDate.of(2024, 2, 29), db.execute("SELECT sold_on FROM sales").rows.get(0).get(0));
            rejected(db, "INSERT INTO sales VALUES(2,1,'Иван','2023-02-29')");
            rejected(db, "INSERT INTO sales VALUES(2,99,'Иван','2024-02-29')");
            rejected(db, "UPDATE cars SET price='дорого' WHERE id=1");
            rejected(db, "DELETE FROM cars WHERE id=1");
            equal(1, db.execute("SELECT * FROM sales").rows.size());
        }
        System.out.println("OK: типы данных и внешний ключ");
    }

    private static void persistence() throws Exception {
        Path directory = temporaryDirectory();
        try (FileRepository storage = new FileRepository(directory)) {
            DatabaseEngine db = new DatabaseEngine(storage);
            db.execute("INSERT INTO cars VALUES(1,'Toyota',25000,TRUE)");
            db.execute("CREATE INDEX idx_brand ON cars(brand)");
        }
        try (FileRepository storage = new FileRepository(directory)) {
            DatabaseEngine db = new DatabaseEngine(storage);
            QueryResult result = db.execute("SELECT * FROM cars WHERE brand='Toyota'");
            equal(Arrays.asList(1, "Toyota", 25000.0, true), result.rows.get(0));
            equal("INDEX idx_brand", result.plan);
        }
        System.out.println("OK: сохранение и загрузка данных и индекса");
    }

    private static void index() throws Exception {
        try (FileRepository storage = new FileRepository(temporaryDirectory())) {
            DatabaseEngine db = new DatabaseEngine(storage);
            db.execute("INSERT INTO cars VALUES(1,'Toyota',25000,TRUE),(2,'BMW',30000,FALSE),(3,'Toyota',28000,TRUE)");
            db.execute("CREATE INDEX idx_brand ON cars(brand)");
            SqlCommand query = new SqlParser().parse("SELECT * FROM cars WHERE brand='Toyota'");
            QueryResult scan = db.execute(query, false);
            QueryResult indexed = db.execute(query, true);
            equal(2, indexed.rows.size());
            equal(new HashSet<>(scan.rows), new HashSet<>(indexed.rows));
            equal("INDEX idx_brand", indexed.plan);
            equal(3, scan.examinedRows);
            equal(2, indexed.examinedRows);
        }
        System.out.println("OK: поиск с индексом и без него");
    }

    static Path temporaryDirectory() throws IOException {
        Files.createDirectories(Paths.get("build", "test-data"));
        return Files.createTempDirectory(Paths.get("build", "test-data"), "db-");
    }

    private static void rejected(DatabaseEngine db, String sql) throws IOException {
        try { db.execute(sql); }
        catch (DbException expected) { return; }
        throw new AssertionError("Ожидалась ошибка: " + sql);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Ожидалось " + expected + ", получено " + actual);
        }
    }
}
