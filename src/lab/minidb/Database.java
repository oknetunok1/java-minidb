package lab.minidb;

import java.io.Serializable;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Состояние базы, сохраняемое одним снимком. */
public final class Database implements Serializable {
    private static final long serialVersionUID = 1L;
    final LinkedHashMap<String, Table> tables = new LinkedHashMap<>();

    Table table(String name) {
        Table table = tables.get(name);
        if (table == null) throw new DbException("Нет таблицы " + name);
        return table;
    }

    Database copy() {
        Database copy = new Database();
        for (Table table : tables.values()) copy.tables.put(table.name, table.copy());
        return copy;
    }

    void validateReferences() {
        for (Table table : tables.values()) {
            for (int i = 0; i < table.columns.size(); i++) {
                Column column = table.columns.get(i);
                if (column.referenceTable == null) continue;
                Table parent = table(column.referenceTable);
                int parentColumn = parent.columnIndex(column.referenceColumn);
                if (parentColumn != parent.primaryKey) throw new DbException("Внешний ключ должен ссылаться на PRIMARY KEY");
                if (column.type != parent.columns.get(parentColumn).type) throw new DbException("Типы внешнего и первичного ключей не совпадают");
                for (Row row : table.rows.values()) {
                    Object value = row.get(i);
                    if (value != null && !parent.rows.containsKey(value)) {
                        throw new DbException("Нарушен внешний ключ " + table.name + "." + column.name + ": " + value);
                    }
                }
            }
        }
    }
}

/** Схема, записи и индексы одной таблицы. */
final class Table implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    @SuppressWarnings("serial")
    public final List<Column> columns;
    public final int primaryKey;
    final LinkedHashMap<Object, Row> rows = new LinkedHashMap<>();
    final LinkedHashMap<String, SecondaryIndex> indexes = new LinkedHashMap<>();

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        Set<String> names = new HashSet<>();
        int key = -1;
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            if (!names.add(column.name)) throw new DbException("Повтор столбца " + column.name);
            if (column.primaryKey) {
                if (key >= 0) throw new DbException("Разрешён только один PRIMARY KEY");
                key = i;
            }
        }
        if (key < 0) throw new DbException("Таблице нужен PRIMARY KEY");
        primaryKey = key;
    }

    public int columnIndex(String column) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name.equals(column)) return i;
        throw new DbException("Нет столбца " + column + " в таблице " + name);
    }

    Table copy() {
        Table copy = new Table(name, columns);
        copy.rows.putAll(rows);
        copy.indexes.putAll(indexes);
        return copy;
    }

    void add(Row row) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).notNull && row.get(i) == null) throw new DbException("Столбец " + columns.get(i).name + " не допускает NULL");
        }
        Object key = row.get(primaryKey);
        if (rows.containsKey(key)) throw new DbException("Повтор первичного ключа: " + key);
        rows.put(key, row);
    }

    void rebuildIndexes() {
        for (SecondaryIndex index : new ArrayList<>(indexes.values())) {
            indexes.put(index.name, new SecondaryIndex(index.name, index.column, this));
        }
    }
}

/** Описание столбца, включая внешний ключ. */
final class Column implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final DataType type;
    public final boolean primaryKey;
    public final boolean notNull;
    public final String referenceTable;
    public final String referenceColumn;

    public Column(String name, DataType type, boolean primaryKey, boolean notNull,
                  String referenceTable, String referenceColumn) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.notNull = notNull || primaryKey;
        this.referenceTable = referenceTable;
        this.referenceColumn = referenceColumn;
    }
}

/** Неизменяемая запись таблицы. */
final class Row implements Serializable {
    private static final long serialVersionUID = 1L;
    @SuppressWarnings("serial")
    private final List<Object> values;

    public Row(List<Object> values) {
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public Object get(int column) {
        return values.get(column);
    }

    public List<Object> values() {
        return values;
    }
}

/** Тип столбца и преобразование SQL-литералов. */
enum DataType {
    INT, DOUBLE, STRING, DATE, BOOLEAN;

    public static DataType parse(String name) {
        switch (name.toUpperCase(Locale.ROOT)) {
            case "INT": case "INTEGER": return INT;
            case "DOUBLE": case "FLOAT": return DOUBLE;
            case "STRING": case "TEXT": case "VARCHAR": return STRING;
            case "DATE": return DATE;
            case "BOOLEAN": case "BOOL": return BOOLEAN;
            default: throw new DbException("Неизвестный тип: " + name);
        }
    }

    public Object convert(SqlCommand.Literal literal) {
        if (literal.isNull()) return null;
        String text = literal.text;
        try {
            switch (this) {
                case STRING:
                    if (literal.quoted) return text;
                    break;
                case DATE:
                    if (literal.quoted) return LocalDate.parse(text);
                    break;
                case INT:
                    if (!literal.quoted && text.matches("[+-]?\\d+")) return Integer.valueOf(text);
                    break;
                case DOUBLE:
                    if (!literal.quoted && text.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")) {
                        double value = Double.parseDouble(text);
                        if (Double.isFinite(value)) return value == 0.0 ? 0.0 : value;
                    }
                    break;
                case BOOLEAN:
                    if (!literal.quoted && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
                        return Boolean.valueOf(text);
                    }
                    break;
                default: break;
            }
        } catch (NumberFormatException | DateTimeParseException ignored) {
            // Сообщение ниже одинаково для всех неверных значений.
        }
        throw new DbException("Значение '" + text + "' не подходит для типа " + this);
    }

    @SuppressWarnings("unchecked")
    static int compare(Object left, Object right) {
        return ((Comparable<Object>) left).compareTo(right);
    }
}
