package lab.minidb;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Точка входа. */
public final class Main {
    private Main() { }

    public static void main(String[] args) throws IOException {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        System.setErr(new PrintStream(System.err, true, "UTF-8"));
        int status;
        try { status = run(args); }
        catch (IOException | DbException e) {
            System.err.println("Ошибка: " + e.getMessage());
            status = 1;
        }
        if (status != 0) System.exit(status);
    }

    private static int run(String[] args) throws IOException {
        Path directory = Paths.get("data");
        Path script = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help")) { help(); return 0; }
            if ((args[i].equals("--data") || args[i].equals("--script")) && i + 1 < args.length) {
                if (args[i].equals("--data")) directory = Paths.get(args[++i]);
                else script = Paths.get(args[++i]);
            } else throw new DbException("Неизвестный или неполный аргумент: " + args[i]);
        }
        try (BufferedReader reader = script == null
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : Files.newBufferedReader(script, StandardCharsets.UTF_8);
             FileRepository repository = new FileRepository(directory)) {
            DatabaseEngine engine = new DatabaseEngine(repository);
            if (repository.recoveryMessage() != null) System.err.println(repository.recoveryMessage());
            boolean interactive = script == null;
            if (interactive) {
                System.out.println("MiniDB — автосалон. .help — справка, .exit — выход.");
                System.out.println("Данные: " + directory.toAbsolutePath().normalize());
            }
            SqlInput input = new SqlInput();
            while (true) {
                if (interactive) System.out.print(input.isPending() ? " ...> " : "sql> ");
                String line = reader.readLine();
                if (line == null) break;
                if (!input.isPending() && line.trim().startsWith(".")) {
                    switch (line.trim()) {
                        case ".exit": return 0;
                        case ".help": help(); break;
                        case ".tables": case ".schema": System.out.print(engine.schema()); break;
                        default:
                            System.err.println("Неизвестная служебная команда");
                            if (!interactive) return 1;
                    }
                    continue;
                }
                for (String sql : input.accept(line)) {
                    if (!execute(engine, sql) && !interactive) return 1;
                }
            }
            if (input.isPending() && !execute(engine, input.finish())) return 1;
        }
        return 0;
    }

    private static boolean execute(DatabaseEngine engine, String sql) throws IOException {
        try {
            TablePrinter.print(engine.execute(sql), System.out);
            return true;
        } catch (DbException e) {
            System.err.println("Ошибка: " + e.getMessage());
            return false;
        }
    }

    private static void help() {
        System.out.println("Запуск: java -cp build lab.minidb.Main [--data папка] [--script файл.sql]");
        System.out.println(".help  .tables  .schema  .exit");
        System.out.println("SQL завершается точкой с запятой. Поддерживаются INSERT, SELECT, UPDATE, DELETE,");
        System.out.println("CREATE TABLE, DROP TABLE, CREATE INDEX и EXPLAIN SELECT.");
        System.out.println("WHERE: одно сравнение (=, !=, <>, <, <=, >, >=), IS NULL или IS NOT NULL.");
        System.out.println("Пример: INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE);");
        System.out.println("Пример: SELECT brand, price FROM cars WHERE price > 20000;");
    }
}

/** Собирает многострочные команды до точки с запятой. */
final class SqlInput {
    private final StringBuilder pending = new StringBuilder();
    private boolean quoted;

    public List<String> accept(String line) {
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!quoted && c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') break;
            pending.append(c);
            if (c == '\'') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '\'') pending.append(line.charAt(++i));
                else quoted = !quoted;
            }
            if (!quoted && c == ';') {
                String command = pending.toString().trim();
                if (!command.equals(";")) commands.add(command);
                pending.setLength(0);
            }
        }
        if (pending.toString().trim().isEmpty()) pending.setLength(0);
        else pending.append('\n');
        return commands;
    }

    public boolean isPending() { return pending.length() > 0; }

    public String finish() {
        String command = pending.toString().trim();
        pending.setLength(0);
        quoted = false;
        return command;
    }
}

/** Печатает результат с выравниванием столбцов. */
final class TablePrinter {
    private TablePrinter() { }

    public static void print(QueryResult result, PrintStream out) {
        if (!result.columns.isEmpty()) {
            int[] widths = new int[result.columns.size()];
            List<List<String>> lines = new ArrayList<>();
            lines.add(result.columns);
            for (List<Object> row : result.rows) {
                List<String> line = new ArrayList<>();
                for (Object value : row) line.add(display(value));
                lines.add(line);
            }
            for (List<String> line : lines) {
                for (int i = 0; i < widths.length; i++) widths[i] = Math.max(widths[i], line.get(i).length());
            }
            String border = border(widths);
            out.println(border);
            for (int row = 0; row < lines.size(); row++) {
                StringBuilder line = new StringBuilder("|");
                for (int i = 0; i < widths.length; i++) {
                    String value = lines.get(row).get(i);
                    line.append(' ').append(value).append(repeat(' ', widths[i] - value.length())).append(" |");
                }
                out.println(line);
                if (row == 0) out.println(border);
            }
            out.println(border);
        }
        out.println(result.message);
    }

    private static String display(Object value) {
        if (value == null) return "NULL";
        return value.toString().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String border(int[] widths) {
        StringBuilder result = new StringBuilder("+");
        for (int width : widths) result.append(repeat('-', width + 2)).append('+');
        return result.toString();
    }

    private static String repeat(char c, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(c);
        return result.toString();
    }
}
