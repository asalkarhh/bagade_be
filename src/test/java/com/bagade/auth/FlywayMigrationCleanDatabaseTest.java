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
        assertThat(migrateResult.migrationsExecuted).isEqualTo(6);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name IN (" +
                        "'user_account', 'category', 'product', 'sku', " +
                        "'vendor', 'purchase_order', 'purchase_order_line', " +
                        "'purchase_order_cancellation', 'purchase_order_cancellation_line', 'idempotency_record', " +
                        "'stock_receipt', 'stock_receipt_line', 'stock_batch', 'batch_balance', " +
                        "'stock_movement', 'sku_stock_state', 'barcode_mapping', " +
                        "'customer', 'bill', 'bill_revision', 'bill_line', " +
                        "'billing_event', 'billing_event_line', 'sale_allocation', 'payment_entry', " +
                        "'sales_adjustment', 'sale_allocation_reversal'" +
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
                "barcode_mapping",
                "customer",
                "bill",
                "bill_revision",
                "bill_line",
                "billing_event",
                "billing_event_line",
                "sale_allocation",
                "payment_entry",
                "sales_adjustment",
                "sale_allocation_reversal"
        );

        List<String> barcodeIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'barcode_mapping'",
                String.class
        );
        assertThat(barcodeIndexes).contains("uk_barcode_mapping_internal_batch", "uk_barcode_mapping_code");

        Integer movementCols = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = 'stock_movement' " +
                        "AND column_name IN ('source_sale_allocation_id', 'source_sale_allocation_reversal_id')",
                Integer.class
        );
        assertThat(movementCols).isEqualTo(2);

        Integer seqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.sequences " +
                        "WHERE sequence_schema = 'public' AND sequence_name IN (" +
                        "'purchase_order_number_seq', 'stock_receipt_number_seq', 'internal_barcode_seq', 'bill_number_seq')",
                Integer.class
        );
        assertThat(seqCount).isEqualTo(4);
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

        // Upgrade to latest (V6)
        var upgradeResult = flyway.migrate();
        assertThat(upgradeResult.success).isTrue();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(3);

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

    @Test
    @DisplayName("Flyway upgrades an existing BE04 database to BE05 and preserves existing stock balances and movements")
    void flywayUpgradesBe04DatabaseToBe05AndPreservesStockData() {
        flyway.clean();

        // Apply V1..V4 (BE01..BE04)
        Flyway be04Flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("4")
                .cleanDisabled(false)
                .load();
        var v4Result = be04Flyway.migrate();
        assertThat(v4Result.success).isTrue();
        assertThat(v4Result.migrationsExecuted).isEqualTo(4);

        // Seed existing BE01..BE04 data
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO user_account (id, username, display_name, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "be04_worker", "Existing Worker", "$2a$10$dummyhashvalue", "WORKER", true
        );
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, name) VALUES (?, ?)", categoryId, "Innerwear");
        jdbcTemplate.update("INSERT INTO product (id, category_id, name) VALUES (?, ?, ?)", productId, categoryId, "Cotton Vest");
        jdbcTemplate.update(
                "INSERT INTO sku (id, product_id, sku_code, size, default_selling_price, active) VALUES (?, ?, ?, ?, ?, ?)",
                skuId, productId, "VST-UPG-34", "34", 250.00, true
        );
        jdbcTemplate.update(
                "INSERT INTO sku_stock_state (sku_id, stock_version) VALUES (?, ?)",
                skuId, 1L
        );
        UUID vendorId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID receiptLineId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vendor (id, name, created_by) VALUES (?, ?, ?)", vendorId, "Cotton Mills", userId);
        jdbcTemplate.update(
                "INSERT INTO stock_receipt (id, receipt_number, vendor_id, received_date, posted_by) VALUES (?, ?, ?, CURRENT_DATE, ?)",
                receiptId, "RCPT-000001", vendorId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO stock_receipt_line (id, stock_receipt_id, line_number, sku_id, quantity, unit_cost) VALUES (?, ?, 0, ?, 5, 160.00)",
                receiptLineId, receiptId, skuId
        );
        jdbcTemplate.update(
                "INSERT INTO stock_batch (id, sku_id, stock_receipt_line_id, received_date, unit_cost) VALUES (?, ?, ?, CURRENT_DATE, 160.00)",
                batchId, skuId, receiptLineId
        );
        jdbcTemplate.update(
                "INSERT INTO batch_balance (batch_id, stock_condition, quantity) VALUES (?, 'SELLABLE', 5)",
                batchId
        );
        jdbcTemplate.update(
                "INSERT INTO stock_movement (id, batch_id, stock_condition, movement_type, quantity_delta, source_receipt_line_id, posted_by) VALUES (?, ?, 'SELLABLE', 'RECEIPT', 5, ?, ?)",
                movementId, batchId, receiptLineId, userId
        );

        // Upgrade to latest (V6)
        var upgradeResult = flyway.migrate();
        assertThat(upgradeResult.success).isTrue();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(2);

        // Verify BE04 balances and movements preserved and BE05/BE06 schema created
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM batch_balance WHERE batch_id = ? AND stock_condition = 'SELLABLE'",
                Integer.class, batchId
        )).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement WHERE id = ? AND source_sale_allocation_id IS NULL",
                Integer.class, movementId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('customer', 'bill', 'bill_revision', 'bill_line', 'billing_event', 'billing_event_line', 'sale_allocation', 'payment_entry', 'sales_adjustment', 'sale_allocation_reversal')",
                Integer.class
        )).isEqualTo(10);

        // Leave test DB clean for subsequent tests
        flyway.clean();
        flyway.migrate();
    }

    @Test
    @DisplayName("Flyway upgrades an existing BE05 database to BE06 and backfills billing correction metadata")
    void flywayUpgradesBe05DatabaseToBe06AndBackfillsCorrectionSchema() {
        flyway.clean();

        // Apply V1..V5 (BE01..BE05)
        Flyway be05Flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("5")
                .cleanDisabled(false)
                .load();
        var v5Result = be05Flyway.migrate();
        assertThat(v5Result.success).isTrue();
        assertThat(v5Result.migrationsExecuted).isEqualTo(5);

        // Seed existing BE01..BE05 data
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO user_account (id, username, display_name, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "be05_worker", "Counter Staff", "$2a$10$dummyhashvalue", "WORKER", true
        );
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, name) VALUES (?, ?)", categoryId, "Innerwear");
        jdbcTemplate.update("INSERT INTO product (id, category_id, name) VALUES (?, ?, ?)", productId, categoryId, "Vest");
        jdbcTemplate.update(
                "INSERT INTO sku (id, product_id, sku_code, size, default_selling_price, active) VALUES (?, ?, ?, ?, ?, ?)",
                skuId, productId, "VST-BE05-1", "34", 250.00, true
        );
        jdbcTemplate.update("INSERT INTO sku_stock_state (sku_id, stock_version) VALUES (?, 1)", skuId);
        UUID vendorId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID receiptLineId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vendor (id, name, created_by) VALUES (?, ?, ?)", vendorId, "Mills", userId);
        jdbcTemplate.update(
                "INSERT INTO stock_receipt (id, receipt_number, vendor_id, received_date, posted_by) VALUES (?, 'RCP-000010', ?, CURRENT_DATE, ?)",
                receiptId, vendorId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO stock_receipt_line (id, stock_receipt_id, line_number, sku_id, quantity, unit_cost) VALUES (?, ?, 0, ?, 5, 150.00)",
                receiptLineId, receiptId, skuId
        );
        jdbcTemplate.update(
                "INSERT INTO stock_batch (id, sku_id, stock_receipt_line_id, received_date, unit_cost) VALUES (?, ?, ?, CURRENT_DATE, 150.00)",
                batchId, skuId, receiptLineId
        );
        jdbcTemplate.update("INSERT INTO batch_balance (batch_id, stock_condition, quantity) VALUES (?, 'SELLABLE', 3)", batchId);

        UUID customerId = UUID.randomUUID();
        UUID billId = UUID.randomUUID();
        UUID revId = UUID.randomUUID();
        UUID logicalLineId = UUID.randomUUID();
        UUID billLineId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO customer (id, name, contact, created_by) VALUES (?, 'Meera', '9876543210', ?)",
                customerId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO bill (id, bill_number, customer_id, issue_date, issued_by, status, current_revision_number, subtotal, discount_total, total, paid, balance_due) " +
                        "VALUES (?, 'BILL-000001', ?, CURRENT_DATE, ?, 'ISSUED', 1, 500.00, 0.00, 500.00, 200.00, 300.00)",
                billId, customerId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO bill_revision (id, bill_id, revision_number, subtotal, discount_total, total, created_by) VALUES (?, ?, 1, 500.00, 0.00, 500.00, ?)",
                revId, billId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO bill_line (id, bill_revision_id, logical_line_id, line_number, sku_id, sku_code_snapshot, product_name_snapshot, size_snapshot, quantity, returned_quantity, unit_selling_price, discount_amount, line_total) " +
                        "VALUES (?, ?, ?, 0, ?, 'VST-BE05-1', 'Vest', '34', 2, 0, 250.00, 0.00, 500.00)",
                billLineId, revId, logicalLineId, skuId
        );
        jdbcTemplate.update(
                "INSERT INTO sale_allocation (id, bill_line_id, batch_id, sku_id, quantity, captured_unit_cost, attribution_method) " +
                        "VALUES (?, ?, ?, ?, 2, 150.00, 'FIFO')",
                allocId, billLineId, batchId, skuId
        );
        jdbcTemplate.update(
                "INSERT INTO payment_entry (id, bill_id, mode, amount, posted_by) VALUES (?, ?, 'CASH', 200.00, ?)",
                paymentId, billId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO stock_movement (id, batch_id, stock_condition, movement_type, quantity_delta, source_sale_allocation_id, posted_by) " +
                        "VALUES (?, ?, 'SELLABLE', 'SALE', -2, ?, ?)",
                movementId, batchId, allocId, userId
        );

        // Upgrade to V6
        var upgradeResult = flyway.migrate();
        assertThat(upgradeResult.success).isTrue();
        assertThat(upgradeResult.migrationsExecuted).isEqualTo(1);

        // Verify BE06 backfill on existing BE05 records
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_snapshot FROM bill_revision WHERE id = ?", java.math.BigDecimal.class, revId
        )).isEqualByComparingTo("200.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance_due_snapshot FROM bill_revision WHERE id = ?", java.math.BigDecimal.class, revId
        )).isEqualByComparingTo("300.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT direction FROM payment_entry WHERE id = ?", String.class, paymentId
        )).isEqualTo("PAYMENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT bill_revision_id FROM payment_entry WHERE id = ?", UUID.class, paymentId
        )).isEqualTo(revId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT bill_id FROM sale_allocation WHERE id = ?", UUID.class, allocId
        )).isEqualTo(billId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT logical_line_id FROM sale_allocation WHERE id = ?", UUID.class, allocId
        )).isEqualTo(logicalLineId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reversed_quantity FROM sale_allocation WHERE id = ?", Integer.class, allocId
        )).isEqualTo(0);

        flyway.clean();
        flyway.migrate();
    }
}
