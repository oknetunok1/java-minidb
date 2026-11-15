package lab.minidb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Парсер небольшого подмножества SQL. */
public final class SqlParser {
    private List<Lexer.Token> tokens;
    private int position;

    public SqlCommand parse(String sql) {
        tokens = Lexer.tokenize(sql);
        position = 0;
        SqlCommand command;
        if (take("SELECT")) command = select(false);
        else if (take("EXPLAIN")) { expect("SELECT"); command = select(true); }
        else if (take("INSERT")) command = insert();
        else if (take("UPDATE")) command = update();
        else if (take("DELETE")) { expect("FROM"); command = new SqlCommand.Delete(identifier(), where()); }
        else if (take("CREATE")) command = create();
        else if (take("DROP")) { expect("TABLE"); command = new SqlCommand.DropTable(identifier()); }
        else throw error("Ожидалась команда SELECT, INSERT, UPDATE, DELETE, CREATE, DROP или EXPLAIN");
        take(";");
        if (peek().kind != Lexer.Kind.END) throw error("Лишняя часть запроса");
        return command;
    }

    private SqlCommand select(boolean explain) {
        List<String> columns = null;
        if (!take("*")) columns = identifiers();
        expect("FROM");
        return new SqlCommand.Select(identifier(), columns, where(), explain);
    }

    private SqlCommand insert() {
        expect("INTO");
        String table = identifier();
        List<String> columns = null;
        if (take("(")) { columns = identifiers(); expect(")"); }
        expect("VALUES");
        List<List<SqlCommand.Literal>> rows = new ArrayList<>();
        do {
            expect("(");
            List<SqlCommand.Literal> values = new ArrayList<>();
            do { values.add(literal()); } while (take(","));
            expect(")");
            rows.add(values);
        } while (take(","));
        return new SqlCommand.Insert(table, columns, rows);
    }

    private SqlCommand update() {
        String table = identifier();
        expect("SET");
        Map<String, SqlCommand.Literal> assignments = new LinkedHashMap<>();
        do {
            String column = identifier();
            expect("=");
            if (assignments.put(column, literal()) != null) throw error("Повтор столбца " + column);
        } while (take(","));
        return new SqlCommand.Update(table, assignments, where());
    }

    private SqlCommand create() {
        if (take("INDEX")) {
            String name = identifier();
            expect("ON");
            String table = identifier();
            expect("(");
            String column = identifier();
            expect(")");
            return new SqlCommand.CreateIndex(name, table, column);
        }
        expect("TABLE");
        String table = identifier();
        expect("(");
        List<Column> columns = new ArrayList<>();
        do {
            String name = identifier();
            DataType type = DataType.parse(identifier());
            boolean primary = false;
            boolean notNull = false;
            String refTable = null;
            String refColumn = null;
            while (true) {
                if (take("PRIMARY")) {
                    expect("KEY");
                    if (primary) throw error("Повтор PRIMARY KEY");
                    primary = true;
                } else if (take("NOT")) {
                    expect("NULL");
                    if (notNull) throw error("Повтор NOT NULL");
                    notNull = true;
                } else if (take("REFERENCES")) {
                    if (refTable != null) throw error("Повтор REFERENCES");
                    refTable = identifier();
                    expect("("); refColumn = identifier(); expect(")");
                } else break;
            }
            columns.add(new Column(name, type, primary, notNull, refTable, refColumn));
        } while (take(","));
        expect(")");
        return new SqlCommand.CreateTable(table, columns);
    }

    private SqlCommand.Where where() {
        if (!take("WHERE")) return null;
        String column = identifier();
        if (take("IS")) {
            boolean not = take("NOT");
            expect("NULL");
            return new SqlCommand.Where(column, not ? "IS NOT NULL" : "IS NULL", new SqlCommand.Literal("NULL", false));
        }
        String operator = peek().text;
        if (peek().kind != Lexer.Kind.SYMBOL || !java.util.Arrays.asList("=", "!=", "<>", ">", "<", ">=", "<=").contains(operator)) {
            throw error("Ожидался оператор сравнения");
        }
        position++;
        return new SqlCommand.Where(column, operator, literal());
    }

    private SqlCommand.Literal literal() {
        Lexer.Token token = peek();
        if (token.kind == Lexer.Kind.STRING || token.kind == Lexer.Kind.NUMBER
                || (token.kind == Lexer.Kind.WORD && (token.text.equalsIgnoreCase("NULL")
                || token.text.equalsIgnoreCase("TRUE") || token.text.equalsIgnoreCase("FALSE")))) {
            position++;
            return new SqlCommand.Literal(token.text, token.kind == Lexer.Kind.STRING);
        }
        throw error("Ожидалось значение; строки и даты пишутся в одинарных кавычках");
    }

    private List<String> identifiers() {
        List<String> names = new ArrayList<>();
        do { names.add(identifier()); } while (take(","));
        return names;
    }

    private String identifier() {
        if (peek().kind != Lexer.Kind.WORD) throw error("Ожидалось имя");
        return tokens.get(position++).text.toLowerCase(Locale.ROOT);
    }

    private Lexer.Token peek() { return tokens.get(position); }

    private boolean take(String value) {
        if ((peek().kind == Lexer.Kind.WORD || peek().kind == Lexer.Kind.SYMBOL) && peek().text.equalsIgnoreCase(value)) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String value) {
        if (!take(value)) throw error("Ожидалось " + value);
    }

    private DbException error(String message) {
        return new DbException(message + ", позиция " + (peek().position + 1) + " (" + peek().text + ")");
    }
}

/** Разбивает запрос на слова, строки и знаки. */
final class Lexer {
    enum Kind { WORD, NUMBER, STRING, SYMBOL, END }

    static final class Token {
        final Kind kind;
        final String text;
        final int position;

        Token(Kind kind, String text, int position) {
            this.kind = kind;
            this.text = text;
            this.position = position;
        }
    }

    static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                continue;
            }
            int start = i;
            if (c == '\'') {
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                i++;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == '\'') {
                        if (i < sql.length() && sql.charAt(i) == '\'') { value.append('\''); i++; }
                        else { closed = true; break; }
                    } else value.append(next);
                }
                if (!closed) throw new DbException("Не закрыта строка, позиция " + (start + 1));
                tokens.add(new Token(Kind.STRING, value.toString(), start));
            } else if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                tokens.add(new Token(Kind.WORD, sql.substring(start, i), start));
            } else if (Character.isDigit(c) || c == '+' || c == '-' || c == '.') {
                i++;
                while (i < sql.length() && "0123456789.eE+-".indexOf(sql.charAt(i)) >= 0) i++;
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i), start));
            } else if ("(),;*=<>!".indexOf(c) >= 0) {
                i++;
                if (i < sql.length() && (("<>!".indexOf(c) >= 0 && sql.charAt(i) == '=')
                        || (c == '<' && sql.charAt(i) == '>'))) i++;
                tokens.add(new Token(Kind.SYMBOL, sql.substring(start, i), start));
            } else throw new DbException("Неожиданный символ '" + c + "', позиция " + (i + 1));
        }
        tokens.add(new Token(Kind.END, "конец запроса", sql.length()));
        return tokens;
    }
}
