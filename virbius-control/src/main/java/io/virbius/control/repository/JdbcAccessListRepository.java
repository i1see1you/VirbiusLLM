package io.virbius.control.repository;

import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAccessListRepository implements AccessListRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccessListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> list(String tenantId, AccessListPolarity polarity, AccessListDimension dimension) {
        return jdbc.queryForList(
                """
                SELECT value FROM tb_access_list
                WHERE tenant_id = ? AND polarity = ? AND dimension = ?
                ORDER BY value
                """,
                String.class,
                tenantId,
                polarity.value(),
                dimension.value());
    }

    @Override
    public Map<String, List<String>> listAll(String tenantId) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (AccessListPolarity p : AccessListPolarity.values()) {
            for (AccessListDimension d : AccessListDimension.values()) {
                out.put(key(p, d), list(tenantId, p, d));
            }
        }
        return out;
    }

    @Override
    @Transactional
    public void replaceAll(
            String tenantId, AccessListPolarity polarity, AccessListDimension dimension, List<String> values) {
        jdbc.update(
                "DELETE FROM tb_access_list WHERE tenant_id = ? AND polarity = ? AND dimension = ?",
                tenantId,
                polarity.value(),
                dimension.value());
        for (String v : values) {
            add(tenantId, polarity, dimension, v);
        }
    }

    @Override
    public boolean add(String tenantId, AccessListPolarity polarity, AccessListDimension dimension, String value) {
        String normalized = normalize(dimension, value);
        if (normalized.isEmpty()) {
            return false;
        }
        try {
            int n = jdbc.update(
                    """
                    INSERT INTO tb_access_list (tenant_id, polarity, dimension, value)
                    VALUES (?, ?, ?, ?)
                    """,
                    tenantId,
                    polarity.value(),
                    dimension.value(),
                    normalized);
            return n > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean remove(String tenantId, AccessListPolarity polarity, AccessListDimension dimension, String value) {
        int n = jdbc.update(
                """
                DELETE FROM tb_access_list
                WHERE tenant_id = ? AND polarity = ? AND dimension = ? AND value = ?
                """,
                tenantId,
                polarity.value(),
                dimension.value(),
                normalize(dimension, value));
        return n > 0;
    }

    static String key(AccessListPolarity polarity, AccessListDimension dimension) {
        return polarity.value() + "." + dimension.value();
    }

    private static String normalize(AccessListDimension dimension, String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (dimension == AccessListDimension.IP_CIDR && v.contains("/")) {
            return v;
        }
        return v;
    }
}