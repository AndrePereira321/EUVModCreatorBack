package com.euvmodcreator;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Base for tests that send real HTTP requests: the whole app runs on a random port, with the real security filters
 * and PostgreSQL (schema {@code test}, see application-test.properties). Every subclass shares one started app.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
public abstract class IntegrationTest {

    // Generated once per run, and static, so every subclass sees the same value and can share the cached context.
    private static final String JWT_SECRET = randomBase64Key();

    @Autowired
    protected RestTestClient client;

    @Autowired
    private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> JWT_SECRET);
    }

    // The server commits each request in its own transaction, so a @Transactional test could not roll it back.
    // Instead every test starts from empty tables, found by query so new tables are covered without editing this.
    @BeforeEach
    void emptyTables() {
        List<String> tables = jdbcClient.sql("""
                        select quote_ident(tablename) from pg_tables
                        where schemaname = current_schema() and tablename <> 'flyway_schema_history'
                        """)
                .query(String.class)
                .list();

        if (!tables.isEmpty()) {
            jdbcClient.sql("truncate table " + String.join(", ", tables) + " cascade").update();
        }
    }

    private static String randomBase64Key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

}
