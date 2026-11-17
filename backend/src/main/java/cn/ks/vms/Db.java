package cn.ks.vms;

import java.util.List;
import java.util.Map;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
record Db(JdbcTemplate jdbc) {
    <T> List<T> rows(Class<T> type, String sql, Object... args) {
        var mapper = new DataClassRowMapper<T>(type) {
            @Override
            protected Object getColumnValue(ResultSet rs, int index, Class<?> requiredType) throws SQLException {
                if (requiredType == Instant.class) {
                    var value = rs.getTimestamp(index);
                    return value == null ? null : value.toInstant();
                }
                return super.getColumnValue(rs, index, requiredType);
            }
        };
        return jdbc.query(sql, mapper, bind(args));
    }

    <T> T one(Class<T> type, String sql, Object... args) {
        return rows(type, sql, args).stream().findFirst().orElse(null);
    }

    List<Map<String, Object>> maps(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, bind(args));
        rows.forEach(row -> row.replaceAll((key, value) ->
            value instanceof LocalDateTime date ? date.toInstant(ZoneOffset.UTC) : value));
        return rows;
    }

    int update(String sql, Object... args) {
        return jdbc.update(sql, bind(args));
    }

    Long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, bind(args));
    }

    long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        var values = bind(args);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private Object[] bind(Object[] arguments) {
        return Arrays.stream(arguments).map(value -> value instanceof Instant instant ? Timestamp.from(instant) : value).toArray();
    }
}
