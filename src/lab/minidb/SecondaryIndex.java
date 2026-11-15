package lab.minidb;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Дерево значений столбца со списками первичных ключей. */
public final class SecondaryIndex implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final int column;
    private final TreeMap<Object, LinkedHashSet<Object>> entries = new TreeMap<>(new ValueComparator());

    public SecondaryIndex(String name, int column, Table table) {
        this.name = name;
        this.column = column;
        for (Row row : table.rows.values()) {
            Object value = row.get(column);
            if (value != null) entries.computeIfAbsent(value, key -> new LinkedHashSet<>()).add(row.get(table.primaryKey));
        }
    }

    boolean supports(Condition condition) {
        return column == condition.column && condition.value != null
                && java.util.Arrays.asList("=", ">", ">=", "<", "<=").contains(condition.operator);
    }

    Collection<Object> find(Condition condition) {
        if (condition.operator.equals("=")) {
            Collection<Object> keys = entries.get(condition.value);
            return keys == null ? java.util.Collections.emptyList() : keys;
        }
        NavigableMap<Object, LinkedHashSet<Object>> range;
        switch (condition.operator) {
            case ">": range = entries.tailMap(condition.value, false); break;
            case ">=": range = entries.tailMap(condition.value, true); break;
            case "<": range = entries.headMap(condition.value, false); break;
            case "<=": range = entries.headMap(condition.value, true); break;
            default: throw new DbException("Индекс не поддерживает " + condition.operator);
        }
        List<Object> result = new ArrayList<>();
        for (Collection<Object> keys : range.values()) result.addAll(keys);
        return result;
    }

    private static final class ValueComparator implements Comparator<Object>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public int compare(Object left, Object right) {
            return DataType.compare(left, right);
        }
    }
}
