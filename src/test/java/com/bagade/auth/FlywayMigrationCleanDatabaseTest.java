package com.bagade.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationCleanDatabaseTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway applies cleanly to a fresh PostgreSQL database")
    void flywayAppliesCleanlyToFreshDatabase() {
        flyway.clean();
        var migrateResult = flyway.migrate();

        assertThat(migrateResult.success).isTrue();
        assertThat(migrateResult.migrationsExecuted).isEqualTo(4);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name IN (" +
                        "'user_account', 'category', 'product', 'sku', " +
                        "'vendor', 'purchase_order', 'purchase_order_line', " +
                        "'purchase_order_cancellation', 'purchase_order_cancellation_line', 'idempotency_record', " +
                        "'stock_receipt', 'stock_receipt_line', 'stock_batch', 'batch_balance', " +
                        "'stock_movement', 'sku_stock_state', 'barcode_mapping'" +
                        ")",
                String.class
        );
        assertThat(tables).containsExactlyInAnyOrder(
                "user_account",
                "category",
                "product",
                "sku",
                "vendor",
                "purchase_order",
                "purchase_order_line",
                "purchase_order_cancellation",
                "purchase_order_cancellation_line",
                "idempotency_record",
                "stock_receipt",
                "stock_receipt_line",
                "stock_batch",
                "batch_balance",
                "stock_movement",
                "sku_stock_state",
                "barcode_mapping"
        );

        List<String> barcodeIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'barcode_mapping'",
                String.class
        );
        assertThat(barcodeIndexes).contains("uk_barcode_mapping_internal_batch", "uk_barcode_mapping_code");

        Integer seqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.sequences " +
                        "WHERE sequence_schema = 'public' AND sequence_name IN (" +
                        "'purchase_order_number_seq', 'stock_receipt_number_seq', 'internal_barcode_seq')",
                Integer.class
        );
        assertThat(seqCount).isEqualTo(3);
    }

    @Test
    @DisplayName("Flyway upgrades an existing BE03 database to BE04 and backfills sku_stock_state")
    void flywayUpgradesBe03DatabaseToBe04AndBackfillsStockState() {
        flyway.clean();

        // Apply V1..V3 (BE01..BE03)
        Flyway be03Flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("3")
                .cleanDisabled(false)
                .load();
        var v3Result = be03Flyway.migrate();
        assertThat(v3Result.success).isTrue();
        assertThat(v3Result.migrationsExecuted).isEqualTo(3);

        // Seed existing BE01..BE03 data
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO user_account (id, username, display_name, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "be03_owner", "Existing Owner", "$2a$10$dummyhashvalue", "OWNER", true
        );
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, name) VALUES (?, ?)", categoryId, "Sarees");
        jdbcTemplate.update("INSERT INTO product (id, category_id, name) VALUES (?, ?, ?)", productId, categoryId, "Silk Saree");
        jdbcTemplate.update(
                "INSERT INTO sku (id, product_id, sku_code, size, default_selling_price, active) VALUES (?, ?, ?, ?, ?, ?)",
                skuId, productId, "SAR-UPG-3", null, 3500.00, true
        );
        UUID vendorId = UUID.randomUUID();
        UUID poId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vendor (id, name, created_by) VALUES (?, ?, ?)", vendorId, "Silk Looms", userId);
        jdbcTemplate.update(
                "INSERT INTO purchase_order (id, po_number, vendor_id, order_date, status, created_by) VALUES (?, ?, ?, CURRENT_DATE, 'OPEN', ?)",
                poId, "PO-000001", vendorId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO purchase_order_line (purchase_order_id, sku_id, line_number, ordered_quantity) VALUES (?, ?, 0, 5)",
                poId, skuId
        );

        // Upgrade to latest (V4)
        var upgradeResult = flyway.migrate();
        assertThat(upgradeResult.success).isTrue();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(1);

        // Verify BE03 data preserved and sku_stock_state backfilled
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_order WHERE po_number = 'PO-000001'", Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_version FROM sku_stock_state WHERE sku_id = ?", Long.class, skuId
        )).isEqualTo(0L);

        // Leave test DB clean for subsequent tests
        flyway.clean();
        flyway.migrate();
    }
}
