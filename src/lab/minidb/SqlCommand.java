package lab.minidb;

import java.util.List;
import java.util.Map;

/** Разобранные команды, ещё не привязанные к схеме. */
public interface SqlCommand {
    final class Literal {
        public final String text;
        public final boolean quoted;

        public Literal(String text, boolean quoted) {
            this.text = text;
            this.quoted = quoted;
        }

        public boolean isNull() {
            return !quoted && text.equalsIgnoreCase("NULL");
        }
    }

    final class Where {
        public final String column;
        public final String operator;
        public final Literal value;

        public Where(String column, String operator, Literal value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }
    }

    final class Select implements SqlCommand {
        public final String table;
        public final List<String> columns;
        public final Where where;
        public final boolean explain;

        public Select(String table, List<String> columns, Where where, boolean explain) {
            this.table = table;
            this.columns = columns;
            this.where = where;
            this.explain = explain;
        }
    }

    final class Insert implements SqlCommand {
        public final String table;
        public final List<String> columns;
        public final List<List<Literal>> rows;

        public Insert(String table, List<String> columns, List<List<Literal>> rows) {
            this.table = table;
            this.columns = columns;
            this.rows = rows;
        }
    }

    final class Update implements SqlCommand {
        public final String table;
        public final Map<String, Literal> assignments;
        public final Where where;

        public Update(String table, Map<String, Literal> assignments, Where where) {
            this.table = table;
            this.assignments = assignments;
            this.where = where;
        }
    }

    final class Delete implements SqlCommand {
        public final String table;
        public final Where where;

        public Delete(String table, Where where) {
            this.table = table;
            this.where = where;
        }
    }

    final class CreateTable implements SqlCommand {
        public final String table;
        public final List<Column> columns;

        public CreateTable(String table, List<Column> columns) {
            this.table = table;
            this.columns = columns;
        }
    }

    final class DropTable implements SqlCommand {
        public final String table;

        public DropTable(String table) {
            this.table = table;
        }
    }

    final class CreateIndex implements SqlCommand {
        public final String name;
        public final String table;
        public final String column;

        public CreateIndex(String name, String table, String column) {
            this.name = name;
            this.table = table;
            this.column = column;
        }
    }
}
