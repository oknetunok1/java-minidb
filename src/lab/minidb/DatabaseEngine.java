package lab.minidb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Исполняет запросы и фиксирует изменения после проверки ограничений. */
public final class DatabaseEngine {
    private final DatabaseRepository repository;
    private final SqlParser parser = new SqlParser();
    private Database database;

    public DatabaseEngine(DatabaseRepository repository) throws IOException {
        this.repository = repository;
        database = repository.load();
        if (database == null) {
            database = new Database();
            addInitialTable("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, available BOOLEAN)");
            addInitialTable("CREATE TABLE sales (id INT PRIMARY KEY, car_id INT NOT NULL REFERENCES cars(id), buyer STRING NOT NULL, sold_on DATE NOT NULL)");
            database.validateReferences();
            repository.save(database);
        }
    }

    private void addInitialTable(String sql) {
        SqlCommand.CreateTable command = (SqlCommand.CreateTable) parser.parse(sql);
        database.tables.put(command.table, new Table(command.table, command.columns));
    }

    public synchronized QueryResult execute(String sql) throws IOException {
        return execute(parser.parse(sql), true);
    }

    /** Отключение индексов нужно для честного сравнения планов. */
    public synchronized QueryResult execute(SqlCommand command, boolean useIndexes) throws IOException {
        if (command instanceof SqlCommand.Select) return select((SqlCommand.Select) command, useIndexes);
        Database pending = database.copy();
        QueryResult result;
        if (command instanceof SqlCommand.Insert) result = insert(pending, (SqlCommand.Insert) command);
        else if (command instanceof SqlCommand.Update) result = update(pending, (SqlCommand.Update) command);
        else if (command instanceof SqlCommand.Delete) result = delete(pending, (SqlCommand.Delete) command);
        else if (command instanceof SqlCommand.CreateTable) {
            SqlCommand.CreateTable create = (SqlCommand.CreateTable) command;
            if (pending.tables.containsKey(create.table)) throw new DbException("Таблица уже существует: " + create.table);
            pending.tables.put(create.table, new Table(create.table, create.columns));
            result = QueryResult.changed("Создана таблица " + create.table);
        } else if (command instanceof SqlCommand.DropTable) {
            String name = ((SqlCommand.DropTable) command).table;
            pending.table(name);
            for (Table table : pending.tables.values()) {
                for (Column column : table.columns) {
                    if (!table.name.equals(name) && name.equals(column.referenceTable)) {
                        throw new DbException("На таблицу ссылается " + table.name + "." + column.name);
                    }
                }
            }
            pending.tables.remove(name);
            result = QueryResult.changed("Удалена таблица " + name);
        } else if (command instanceof SqlCommand.CreateIndex) {
            SqlCommand.CreateIndex create = (SqlCommand.CreateIndex) command;
            Table table = pending.table(create.table);
            int column = table.columnIndex(create.column);
            for (Table other : pending.tables.values()) {
                if (other.indexes.containsKey(create.name)) throw new DbException("Индекс уже существует: " + create.name);
            }
            for (SecondaryIndex index : table.indexes.values()) {
                if (index.column == column) throw new DbException("На столбце уже есть индекс " + index.name);
            }
            table.indexes.put(create.name, new SecondaryIndex(create.name, column, table));
            result = QueryResult.changed("Создан индекс " + create.name);
        } else throw new DbException("Неизвестная команда");
        pending.validateReferences();
        repository.save(pending);
        database = pending;
        return result;
    }

    private QueryResult insert(Database pending, SqlCommand.Insert command) {
        Table table = pending.table(command.table);
        List<Integer> positions = positions(table, command.columns);
        if (new HashSet<>(positions).size() != positions.size()) throw new DbException("Повтор столбца в INSERT");
        for (List<SqlCommand.Literal> literals : command.rows) {
            if (literals.size() != positions.size()) throw new DbException("Количество значений не совпадает с количеством столбцов");
            List<Object> values = new ArrayList<>(Collections.nCopies(table.columns.size(), null));
            for (int i = 0; i < positions.size(); i++) {
                int column = positions.get(i);
                values.set(column, table.columns.get(column).type.convert(literals.get(i)));
            }
            table.add(new Row(values));
        }
        table.rebuildIndexes();
        return QueryResult.changed("Добавлено записей: " + command.rows.size());
    }

    private QueryResult update(Database pending, SqlCommand.Update command) {
        Table table = pending.table(command.table);
        Condition condition = command.where == null ? null : new Condition(table, command.where);
        Map<Integer, Object> assignments = new LinkedHashMap<>();
        for (Map.Entry<String, SqlCommand.Literal> entry : command.assignments.entrySet()) {
            int column = table.columnIndex(entry.getKey());
            assignments.put(column, table.columns.get(column).type.convert(entry.getValue()));
        }
        List<Row> oldRows = new ArrayList<>(table.rows.values());
        table.rows.clear();
        int count = 0;
        for (Row row : oldRows) {
            if (condition == null || condition.matches(row)) {
                List<Object> values = new ArrayList<>(row.values());
                for (Map.Entry<Integer, Object> assignment : assignments.entrySet()) values.set(assignment.getKey(), assignment.getValue());
                table.add(new Row(values));
                count++;
            } else table.add(row);
        }
        table.rebuildIndexes();
        return QueryResult.changed("Обновлено записей: " + count);
    }

    private QueryResult delete(Database pending, SqlCommand.Delete command) {
        Table table = pending.table(command.table);
        Condition condition = command.where == null ? null : new Condition(table, command.where);
        int count = 0;
        Iterator<Row> rows = table.rows.values().iterator();
        while (rows.hasNext()) {
            Row row = rows.next();
            if (condition == null || condition.matches(row)) {
                rows.remove();
                count++;
            }
        }
        table.rebuildIndexes();
        return QueryResult.changed("Удалено записей: " + count);
    }

    private QueryResult select(SqlCommand.Select command, boolean useIndexes) {
        Table table = database.table(command.table);
        List<Integer> positions = positions(table, command.columns);
        List<String> names = new ArrayList<>();
        for (int position : positions) names.add(table.columns.get(position).name);
        Condition condition = command.where == null ? null : new Condition(table, command.where);
        Collection<Row> candidates = table.rows.values();
        String plan = "TABLE SCAN " + table.name;
        if (useIndexes && condition != null && condition.column == table.primaryKey
                && condition.operator.equals("=") && condition.value != null) {
            Row row = table.rows.get(condition.value);
            candidates = row == null ? Collections.emptyList() : Collections.singletonList(row);
            plan = "PRIMARY KEY " + table.name;
        } else if (useIndexes && condition != null) {
            for (SecondaryIndex index : table.indexes.values()) {
                if (index.supports(condition)) {
                    List<Row> found = new ArrayList<>();
                    for (Object key : index.find(condition)) found.add(table.rows.get(key));
                    candidates = found;
                    plan = "INDEX " + index.name;
                    break;
                }
            }
        }
        List<List<Object>> result = new ArrayList<>();
        int examined = 0;
        for (Row row : candidates) {
            examined++;
            if (condition == null || condition.matches(row)) {
                List<Object> values = new ArrayList<>();
                for (int position : positions) values.add(row.get(position));
                result.add(values);
            }
        }
        if (command.explain) {
            List<List<Object>> stats = Collections.singletonList(Arrays.<Object>asList(plan, examined, result.size()));
            return new QueryResult(Arrays.asList("plan", "examined", "matched"), stats, "План выполненного запроса", plan, examined);
        }
        return new QueryResult(names, result, "Строк: " + result.size(), plan, examined);
    }

    private List<Integer> positions(Table table, List<String> columns) {
        List<Integer> positions = new ArrayList<>();
        if (columns == null) {
            for (int i = 0; i < table.columns.size(); i++) positions.add(i);
        } else {
            for (String column : columns) positions.add(table.columnIndex(column));
        }
        return positions;
    }

    public synchronized String schema() {
        StringBuilder output = new StringBuilder();
        for (Table table : database.tables.values()) {
            output.append(table.name).append(" (записей: ").append(table.rows.size()).append(")\n");
            for (Column column : table.columns) {
                output.append("  ").append(column.name).append(' ').append(column.type);
                if (column.primaryKey) output.append(" PRIMARY KEY");
                else if (column.notNull) output.append(" NOT NULL");
                if (column.referenceTable != null) output.append(" REFERENCES ").append(column.referenceTable).append('(').append(column.referenceColumn).append(')');
                output.append('\n');
            }
            for (SecondaryIndex index : table.indexes.values()) output.append("  INDEX ").append(index.name).append(" ON ").append(table.columns.get(index.column).name).append('\n');
        }
        return output.toString();
    }
}

/** Данные для вывода и статистика выбранного плана. */
final class QueryResult {
    public final List<String> columns;
    public final List<List<Object>> rows;
    public final String message;
    public final String plan;
    public final int examinedRows;

    QueryResult(List<String> columns, List<List<Object>> rows, String message, String plan, int examinedRows) {
        this.columns = columns;
        this.rows = rows;
        this.message = message;
        this.plan = plan;
        this.examinedRows = examinedRows;
    }

    static QueryResult changed(String message) {
        return new QueryResult(Collections.emptyList(), Collections.emptyList(), message, "", 0);
    }
}

/** Условие с проверенным типом значения. */
final class Condition {
    final int column;
    final String operator;
    final Object value;

    Condition(Table table, SqlCommand.Where where) {
        column = table.columnIndex(where.column);
        operator = where.operator;
        value = table.columns.get(column).type.convert(where.value);
    }

    boolean matches(Row row) {
        Object actual = row.get(column);
        if (operator.equals("IS NULL")) return actual == null;
        if (operator.equals("IS NOT NULL")) return actual != null;
        if (actual == null || value == null) return false;
        int comparison = DataType.compare(actual, value);
        switch (operator) {
            case "=": return comparison == 0;
            case "!=": case "<>": return comparison != 0;
            case ">": return comparison > 0;
            case ">=": return comparison >= 0;
            case "<": return comparison < 0;
            case "<=": return comparison <= 0;
            default: throw new DbException("Неизвестное сравнение: " + operator);
        }
    }
}

/** Ошибка запроса или ограничения таблицы. */
class DbException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DbException(String message) {
        super(message);
    }
}
