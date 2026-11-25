package com.bagade.reporting;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import com.bagade.billing.repository.BillRepository;
import com.bagade.billing.repository.BillRevisionRepository;
import com.bagade.billing.repository.BillingEventRepository;
import com.bagade.billing.repository.CustomerRepository;
import com.bagade.billing.repository.PaymentEntryRepository;
import com.bagade.billing.repository.SaleAllocationRepository;
import com.bagade.billing.repository.SaleAllocationReversalRepository;
import com.bagade.billing.repository.SalesAdjustmentRepository;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.ProductRepository;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.common.idempotency.IdempotencyRecordRepository;
import com.bagade.expense.repository.ExpenseRepository;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import com.bagade.inventory.repository.StockBatchRepository;
import com.bagade.inventory.repository.StockCountRepository;
import com.bagade.inventory.repository.StockMovementRepository;
import com.bagade.inventory.repository.StockReceiptRepository;
import com.bagade.purchasing.repository.PurchaseOrderCancellationRepository;
import com.bagade.purchasing.repository.PurchaseOrderRepository;
import com.bagade.purchasing.repository.PurchaseTargetRepository;
import com.bagade.purchasing.repository.VendorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OwnerReportsAndPurchasePlanningIntegrationTest {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private VendorRepository vendorRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderCancellationRepository cancellationRepository;

    @Autowired
    private PurchaseTargetRepository purchaseTargetRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private BarcodeMappingRepository barcodeMappingRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private BatchBalanceRepository batchBalanceRepository;

    @Autowired
    private StockBatchRepository stockBatchRepository;

    @Autowired
    private StockReceiptRepository stockReceiptRepository;

    @Autowired
    private SkuStockStateRepository skuStockStateRepository;

    @Autowired
    private StockCountRepository stockCountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private BillRevisionRepository billRevisionRepository;

    @Autowired
    private BillingEventRepository billingEventRepository;

    @Autowired
    private SaleAllocationRepository saleAllocationRepository;

    @Autowired
    private SaleAllocationReversalRepository saleAllocationReversalRepository;

    @Autowired
    private SalesAdjustmentRepository salesAdjustmentRepository;

    @Autowired
    private PaymentEntryRepository paymentEntryRepository;

    @Autowired
    private ExpenseRepository expenseRepository;

    @BeforeEach
    void setUp() {
        cleanAll();

        userAccountRepository.save(new UserAccount(
                "owner1",
                "Shop Owner",
                passwordEncoder.encode("OwnerPass123!"),
                UserRole.OWNER,
                true
        ));
        userAccountRepository.save(new UserAccount(
                "worker1",
                "Counter Staff",
                passwordEncoder.encode("WorkerPass123!"),
                UserRole.WORKER,
                true
        ));
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    private void cleanAll() {
        idempotencyRecordRepository.deleteAll();
        barcodeMappingRepository.deleteAll();
        stockMovementRepository.deleteAll();
        paymentEntryRepository.deleteAll();
        saleAllocationReversalRepository.deleteAll();
        saleAllocationRepository.deleteAll();
        salesAdjustmentRepository.deleteAll();
        billingEventRepository.deleteAll();
        billRevisionRepository.deleteAll();
        billRepository.deleteAll();
        customerRepository.deleteAll();
        purchaseTargetRepository.deleteAll();
        batchBalanceRepository.deleteAll();
        stockBatchRepository.deleteAll();
        stockReceiptRepository.deleteAll();
        stockCountRepository.deleteAll();
        expenseRepository.deleteAll();
        cancellationRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        vendorRepository.deleteAll();
        skuStockStateRepository.deleteAll();
        skuRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userAccountRepository.deleteAll();
    }

    private record SessionContext(MockHttpSession session, String csrfToken) {}

    private SessionContext signIn(String username, String password) throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String preLoginToken = objectMapper.readTree(csrfResult.getResponse().getContentAsString())
                .get("csrfToken").asText();
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/session")
                        .session(session)
                        .header("X-CSRF-Token", preLoginToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession authSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        MvcResult postLoginCsrf = mockMvc.perform(get("/api/v1/auth/csrf").session(authSession))
                .andExpect(status().isOk())
                .andReturn();
        String freshToken = objectMapper.readTree(postLoginCsrf.getResponse().getContentAsString())
                .get("csrfToken").asText();

        return new SessionContext(authSession, freshToken);
    }

    private UUID createCategory(SessionContext ctx, String name, UUID parentId) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        if (parentId != null) {
            payload.put("parentId", parentId.toString());
        }
        MvcResult res = mockMvc.perform(post("/api/v1/categories")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("categoryId").asText());
    }

    private UUID createProduct(SessionContext ctx, UUID categoryId, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/products")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoryId", categoryId.toString(),
                                "name", name
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("productId").asText());
    }

    private UUID createSku(SessionContext ctx, UUID productId, String skuCode, String size, String price) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("skuCode", skuCode);
        payload.put("size", size);
        payload.put("defaultSellingPrice", price);

        MvcResult res = mockMvc.perform(post("/api/v1/products/{id}/skus", productId)
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("skuId").asText());
    }

    private UUID createVendor(SessionContext ctx, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/vendors")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("vendorId").asText());
    }

    private UUID createCustomer(SessionContext ctx, String name, String contact) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/customers")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "contact", contact))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("customerId").asText());
    }

    private JsonNode receiveDirectStock(
            SessionContext ctx,
            UUID vendorId,
            UUID skuId,
            LocalDate date,
            int quantity,
            String unitCost) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "receivedDate", date.toString(),
                                "lines", List.of(Map.of(
                                        "skuId", skuId.toString(),
                                        "quantity", quantity,
                                        "unitCost", unitCost,
                                        "generateInternalTag", false
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode createPo(SessionContext ctx, UUID vendorId, UUID skuId, int qty, LocalDate orderDate) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "orderDate", orderDate.toString(),
                                "lines", List.of(Map.of(
                                        "skuId", skuId.toString(),
                                        "orderedQuantity", qty
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode postBill(
            SessionContext ctx,
            List<Map<String, Object>> lines,
            String expectedTotal,
            UUID customerId,
            List<Map<String, Object>> payments) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("lines", lines);
        payload.put("expectedTotal", expectedTotal);
        if (customerId != null) {
            payload.put("customerId", customerId.toString());
        }
        payload.put("payments", payments);

        MvcResult res = mockMvc.perform(post("/api/v1/bills")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private String getLogicalLineId(SessionContext ctx, UUID billId, int index) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/bills/{id}", billId).session(ctx.session()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("lines").get(index).get("logicalLineId").asText();
    }

    private Instant ist(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(SHOP_ZONE).toInstant();
    }

    private void shiftBillIssueTimestamp(UUID billId, Instant instant) {
        Timestamp ts = Timestamp.from(instant);
        LocalDate shopDate = LocalDate.ofInstant(instant, SHOP_ZONE);
        jdbcTemplate.update("UPDATE bill SET issued_at = ?, issue_date = ? WHERE id = ?", ts, Date.valueOf(shopDate), billId);
        jdbcTemplate.update("UPDATE bill_revision SET created_at = ? WHERE bill_id = ? AND revision_number = 1", ts, billId);
        jdbcTemplate.update(
                "UPDATE billing_event SET occurred_at = ? WHERE bill_id = ? AND event_type = 'ISSUE'",
                ts, billId
        );
        jdbcTemplate.update(
                "UPDATE payment_entry SET posted_at = ? WHERE billing_event_id IN (SELECT id FROM billing_event WHERE bill_id = ? AND event_type = 'ISSUE')",
                ts, billId
        );
    }

    private void shiftBillEventTimestamp(UUID billId, String eventType, Instant instant) {
        Timestamp ts = Timestamp.from(instant);
        jdbcTemplate.update(
                "UPDATE billing_event SET occurred_at = ? WHERE bill_id = ? AND event_type = ?",
                ts, billId, eventType
        );
        if ("ISSUE".equals(eventType) || "EDIT".equals(eventType) || "EXCHANGE".equals(eventType)) {
            jdbcTemplate.update(
                    "UPDATE bill_revision SET created_at = ? WHERE id IN (SELECT bill_revision_id FROM billing_event WHERE bill_id = ? AND event_type = ?)",
                    ts, billId, eventType
            );
        }
        jdbcTemplate.update(
                "UPDATE sales_adjustment SET occurred_at = ? WHERE billing_event_id IN (SELECT id FROM billing_event WHERE bill_id = ? AND event_type = ?)",
                ts, billId, eventType
        );
        jdbcTemplate.update(
                "UPDATE payment_entry SET posted_at = ? WHERE billing_event_id IN (SELECT id FROM billing_event WHERE bill_id = ? AND event_type = ?)",
                ts, billId, eventType
        );
        jdbcTemplate.update(
                "UPDATE sale_allocation_reversal SET reversed_at = ? WHERE billing_event_id IN (SELECT id FROM billing_event WHERE bill_id = ? AND event_type = ?)",
                ts, billId, eventType
        );
    }

    private UUID postShopCount(SessionContext ctx, Map<UUID, Integer> quantities, Map<UUID, String> reasons) throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/v1/stock-counts")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scope", "SHOP"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID countId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).get("countId").asText());

        MvcResult detailRes = mockMvc.perform(get("/api/v1/stock-counts/{id}", countId).session(ctx.session()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailRes.getResponse().getContentAsString());
        long ver = detail.get("version").asLong();

        for (JsonNode line : detail.get("lines")) {
            UUID lineSkuId = UUID.fromString(line.get("skuId").asText());
            long observedVer = line.get("currentStockVersion").asLong();
            int qty = quantities.getOrDefault(lineSkuId, line.get("currentQuantity").asInt());
            var linePayload = new LinkedHashMap<String, Object>();
            linePayload.put("expectedVersion", ver);
            linePayload.put("countedQuantity", qty);
            linePayload.put("observedStockVersion", observedVer);
            if (reasons.containsKey(lineSkuId)) {
                linePayload.put("reason", reasons.get(lineSkuId));
            }
            MvcResult putRes = mockMvc.perform(put("/api/v1/stock-counts/{id}/lines/{skuId}", countId, lineSkuId)
                            .session(ctx.session())
                            .header("X-CSRF-Token", ctx.csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(linePayload)))
                    .andExpect(status().isOk())
                    .andReturn();
            ver = objectMapper.readTree(putRes.getResponse().getContentAsString()).get("countVersion").asLong();
        }

        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", ver))))
                .andExpect(status().isOk());

        return countId;
    }

    @Test
    @DisplayName("Enforces OWNER-only access, CSRF protection, and range/target validation while keeping WORKER operational routes working")
    void ownerOnlySecurityCsrfAndValidationEnforcedWhileWorkerOperationalAccessPreserved() throws Exception {
        MvcResult preCsrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String anonCsrf = objectMapper.readTree(preCsrf.getResponse().getContentAsString()).get("csrfToken").asText();
        MockHttpSession anonSession = (MockHttpSession) preCsrf.getRequest().getSession(false);

        UUID randomSku = UUID.randomUUID();

        // Unauthenticated requests -> 401 AUTH_REQUIRED
        mockMvc.perform(get("/api/v1/reports/sales").param("from", "2026-11-01").param("to", "2026-11-25"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", randomSku)
                        .session(anonSession)
                        .header("X-CSRF-Token", anonCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":10}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        SessionContext worker = signIn("worker1", "WorkerPass123!");
        SessionContext owner = signIn("owner1", "OwnerPass123!");

        // WORKER gets 403 ACCESS_DENIED on every BE08 endpoint (even with valid CSRF token on PUT)
        mockMvc.perform(get("/api/v1/reports/sales").param("from", "2026-11-01").param("to", "2026-11-25").session(worker.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/reports/vendors").param("from", "2026-11-01").param("to", "2026-11-25").session(worker.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/reports/stock-ageing").session(worker.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/reports/collections").param("from", "2026-11-01").param("to", "2026-11-25").session(worker.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/reports/stock-audits").param("from", "2026-11-01").param("to", "2026-11-25").session(worker.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/purchase-planning").session(worker.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", randomSku)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // WORKER still retains operational access
        mockMvc.perform(get("/api/v1/skus").session(worker.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expenses").session(worker.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/bills").session(worker.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/purchase-orders").session(worker.session())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"SHOP\"}"))
                .andExpect(status().isCreated());

        // OWNER without CSRF on PUT -> 403 CSRF_ERROR
        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", randomSku)
                        .session(owner.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        // Validation errors & 404 for OWNER
        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", randomSku)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", randomSku)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-11-26")
                        .param("to", "2026-11-25")
                        .session(owner.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-25")
                        .param("minPrice", "2000.00")
                        .param("maxPrice", "1000.00")
                        .session(owner.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("minPrice", "500.00")
                        .param("maxPrice", "100.00")
                        .session(owner.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Sales, collections, non-sellable returns, exchanges, cancellations, expenses, and IST date boundaries reconcile")
    void salesCollectionsExpensesExchangesCancellationsAndIstBoundaryAccuracy() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID sareesCat = createCategory(owner, "Sarees", null);
        UUID silkSareesCat = createCategory(owner, "Silk Sarees", sareesCat);
        UUID innerwearCat = createCategory(owner, "Innerwear", null);

        UUID silkProduct = createProduct(owner, silkSareesCat, "Banarasi Saree");
        UUID vestProduct = createProduct(owner, innerwearCat, "Cotton Vest");

        UUID sareeSku = createSku(owner, silkProduct, "SAR-RPT-1", null, "5000.00");
        UUID vest32Sku = createSku(owner, vestProduct, "VST-RPT-32", "32", "500.00");
        UUID vest34Sku = createSku(owner, vestProduct, "VST-RPT-34", "34", "600.00");

        UUID vendorId = createVendor(owner, "Heritage Mills");
        receiveDirectStock(owner, vendorId, sareeSku, LocalDate.of(2026, 11, 5), 5, "3000.00");
        receiveDirectStock(owner, vendorId, vest32Sku, LocalDate.of(2026, 11, 5), 6, "300.00");
        receiveDirectStock(owner, vendorId, vest34Sku, LocalDate.of(2026, 11, 5), 6, "360.00");

        UUID meeraId = createCustomer(worker, "Meera Joshi", "9876500001");
        UUID rajuId = createCustomer(worker, "Raju Nair", "9876500002");

        // Operating expenses: 1500.00 on Nov 12 (in range) + 500.00 on Nov 25 (out of Nov 10..20 range)
        mockMvc.perform(post("/api/v1/expenses")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expenseDate", "2026-11-12",
                                "category", "Electricity",
                                "amount", "1500.00",
                                "paymentMode", "CASH"
                        ))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/expenses")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expenseDate", "2026-11-25",
                                "category", "Tea",
                                "amount", "500.00",
                                "paymentMode", "CASH"
                        ))))
                .andExpect(status().isCreated());

        // Bill 1 (Nov 10): Meera buys 2 sarees (10000 - 1000 discount = 9000), pays CASH 4000, balanceDue 5000
        JsonNode bill1Res = postBill(
                worker,
                List.of(Map.of("skuId", sareeSku.toString(), "quantity", 2, "discountAmount", "1000.00")),
                "9000.00",
                meeraId,
                List.of(Map.of("mode", "CASH", "amount", "4000.00"))
        );
        UUID bill1Id = UUID.fromString(bill1Res.get("billId").asText());
        String bill1LineId = getLogicalLineId(worker, bill1Id, 0);
        shiftBillIssueTimestamp(bill1Id, ist(2026, 11, 10, 10, 0));

        // Bill 2 (Nov 10): Raju buys 2 VST-32 (1000), pays CASH 1000
        JsonNode bill2Res = postBill(
                worker,
                List.of(Map.of("skuId", vest32Sku.toString(), "quantity", 2)),
                "1000.00",
                rajuId,
                List.of(Map.of("mode", "CASH", "amount", "1000.00"))
        );
        UUID bill2Id = UUID.fromString(bill2Res.get("billId").asText());
        String bill2LineId = getLogicalLineId(worker, bill2Id, 0);
        shiftBillIssueTimestamp(bill2Id, ist(2026, 11, 10, 11, 30));

        // Bill 1 Return (Nov 15): 1 saree NON_SELLABLE -> -4500 netSales, balanceDue becomes 500, COGS remains 6000!
        mockMvc.perform(post("/api/v1/bills/{id}/returns", bill1Id)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rpt-b1-ret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Zari tear",
                                "returnedLines", List.of(Map.of(
                                        "lineId", bill1LineId,
                                        "quantity", 1,
                                        "disposition", "NON_SELLABLE"
                                )),
                                "settlement", List.of()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceDue").value("500.00"));
        shiftBillEventTimestamp(bill1Id, "RETURN", ist(2026, 11, 15, 14, 0));

        // Bill 2 Exchange (Nov 15): return 1 VST-32 SELLABLE (-500 sales, -300 cost) -> take 1 VST-34 (+600 sales, +360 cost), pay UPI 100
        mockMvc.perform(post("/api/v1/bills/{id}/exchanges", bill2Id)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rpt-b2-exch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Size swap",
                                "returnedLines", List.of(Map.of(
                                        "lineId", bill2LineId,
                                        "quantity", 1,
                                        "disposition", "SELLABLE"
                                )),
                                "replacementLines", List.of(Map.of(
                                        "skuId", vest34Sku.toString(),
                                        "quantity", 1
                                )),
                                "settlement", List.of(Map.of("direction", "PAYMENT", "mode", "UPI", "amount", "100.00", "reference", "U1"))
                        ))))
                .andExpect(status().isCreated());
        shiftBillEventTimestamp(bill2Id, "EXCHANGE", ist(2026, 11, 15, 16, 0));

        // Bill 3 (Nov 15): Meera buys 2 VST-32 (1000), pays UPI 400; then CANCELLED on Nov 18 refunding UPI 400
        JsonNode bill3Res = postBill(
                worker,
                List.of(Map.of("skuId", vest32Sku.toString(), "quantity", 2)),
                "1000.00",
                meeraId,
                List.of(Map.of("mode", "UPI", "amount", "400.00", "reference", "U3"))
        );
        UUID bill3Id = UUID.fromString(bill3Res.get("billId").asText());
        shiftBillIssueTimestamp(bill3Id, ist(2026, 11, 15, 17, 0));

        mockMvc.perform(post("/api/v1/bills/{id}/cancellations", bill3Id)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rpt-b3-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Cancelled order",
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "UPI", "amount", "400.00", "reference", "R3"))
                        ))))
                .andExpect(status().isOk());
        shiftBillEventTimestamp(bill3Id, "CANCEL", ist(2026, 11, 18, 12, 0));

        // Credit collection on Bill 1 (Nov 20): Meera pays remaining 500.00 via UPI
        MvcResult colRes = mockMvc.perform(post("/api/v1/bills/{id}/payments", bill1Id)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rpt-b1-collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "mode", "UPI",
                                "amount", "500.00",
                                "reference", "U-CR-1"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceDue").value("0.00"))
                .andReturn();
        UUID colPaymentId = UUID.fromString(objectMapper.readTree(colRes.getResponse().getContentAsString()).get("paymentId").asText());
        jdbcTemplate.update(
                "UPDATE payment_entry SET posted_at = ? WHERE id = ?",
                Timestamp.from(ist(2026, 11, 20, 18, 0)), colPaymentId
        );

        // Bill 4 (Nov 23 01:30 IST == Nov 22 20:00 UTC): crosses UTC midnight to verify shop-local Asia/Kolkata boundaries
        JsonNode bill4Res = postBill(
                worker,
                List.of(Map.of("skuId", vest34Sku.toString(), "quantity", 1)),
                "600.00",
                rajuId,
                List.of(Map.of("mode", "CASH", "amount", "600.00"))
        );
        UUID bill4Id = UUID.fromString(bill4Res.get("billId").asText());
        shiftBillIssueTimestamp(bill4Id, ist(2026, 11, 23, 1, 30));

        // 1. Whole-shop sales report for 2026-11-10..2026-11-20:
        //    SAR-RPT-1: netUnits=1, netSales=4500.00, cogs=6000.00 (2 sold - 0 for NON_SELLABLE return), nonSellableUnits=1, nonSellableCost=3000.00, nonSellableSalesValue=4500.00
        //    VST-RPT-32: netUnits=1 (2 - 1 exch + 2 - 2 cancel), netSales=500.00, cogs=300.00
        //    VST-RPT-34: netUnits=1, netSales=600.00, cogs=360.00
        //    Totals: netUnits=3, netSales=5600.00, cogs=6660.00, grossProfit=-1060.00, expenses=1500.00, estimatedProfit=-2560.00
        mockMvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-20")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netUnitsSold").value(3))
                .andExpect(jsonPath("$.netSales").value("5600.00"))
                .andExpect(jsonPath("$.cogs").value("6660.00"))
                .andExpect(jsonPath("$.grossProfit").value("-1060.00"))
                .andExpect(jsonPath("$.costComplete").value(true))
                .andExpect(jsonPath("$.nonSellableReturnUnits").value(1))
                .andExpect(jsonPath("$.nonSellableReturnCost").value("3000.00"))
                .andExpect(jsonPath("$.nonSellableReturnSalesValue").value("4500.00"))
                .andExpect(jsonPath("$.operatingExpenses").value("1500.00"))
                .andExpect(jsonPath("$.estimatedProfit").value("-2560.00"))
                .andExpect(jsonPath("$.total").value(3));

        // 2. Filtered sales report (parent category Sarees includes child Silk Sarees; operatingExpenses/estimatedProfit omitted)
        mockMvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-20")
                        .param("categoryId", sareesCat.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netUnitsSold").value(1))
                .andExpect(jsonPath("$.netSales").value("4500.00"))
                .andExpect(jsonPath("$.cogs").value("6000.00"))
                .andExpect(jsonPath("$.operatingExpenses").doesNotExist())
                .andExpect(jsonPath("$.estimatedProfit").doesNotExist())
                .andExpect(jsonPath("$.total").value(1));

        // 3. IST midnight boundary: 2026-11-22 excludes Bill 4 (01:30 IST Nov 23); 2026-11-23 includes Bill 4
        mockMvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-11-22")
                        .param("to", "2026-11-22")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netUnitsSold").value(0))
                .andExpect(jsonPath("$.netSales").value("0.00"))
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-11-23")
                        .param("to", "2026-11-23")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netUnitsSold").value(1))
                .andExpect(jsonPath("$.netSales").value("600.00"))
                .andExpect(jsonPath("$.total").value(1));

        // 4. Historical Collections report cutoff BEFORE Nov 20 collection (2026-11-10..2026-11-18):
        //    Cash collected: 4000 + 1000 = 5000.00; UPI collected: 100 + 400 = 500.00; totalCollections: 5500.00
        //    Refunds: UPI 400.00; netCollections: 5100.00
        //    CreditSales: Bill1 issued 9000 - paid 4000 - return 4500 + Bill2 0 + Bill3 600 - cancel 1000 + 400refund = 500.00
        //    Meera outstandingBalanceAsOfEnd as of Nov 18 = 500.00; shop outstanding = 500.00
        mockMvc.perform(get("/api/v1/reports/collections")
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-18")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashCollected").value("5000.00"))
                .andExpect(jsonPath("$.upiCollected").value("500.00"))
                .andExpect(jsonPath("$.totalCollections").value("5500.00"))
                .andExpect(jsonPath("$.upiRefunded").value("400.00"))
                .andExpect(jsonPath("$.totalRefunds").value("400.00"))
                .andExpect(jsonPath("$.netCollections").value("5100.00"))
                .andExpect(jsonPath("$.creditSales").value("500.00"))
                .andExpect(jsonPath("$.outstandingBalanceAsOfEnd").value("500.00"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].customerName").value("Meera Joshi"))
                .andExpect(jsonPath("$.items[0].outstandingBalanceAsOfEnd").value("500.00"));

        // 5. Collections report through Nov 20 (includes Nov 20 credit collection 500.00 UPI; net sales unaffected, Meera outstanding becomes 0.00)
        mockMvc.perform(get("/api/v1/reports/collections")
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-20")
                        .param("customerId", meeraId.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashCollected").value("4000.00"))
                .andExpect(jsonPath("$.upiCollected").value("900.00"))
                .andExpect(jsonPath("$.totalCollections").value("4900.00"))
                .andExpect(jsonPath("$.totalRefunds").value("400.00"))
                .andExpect(jsonPath("$.netCollections").value("4500.00"))
                .andExpect(jsonPath("$.outstandingBalanceAsOfEnd").value("0.00"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("Vendor performance separates FIFO vs direct batch attribution, excludes audit shortages from sales, and flags audit surplus")
    void vendorPerformanceFifoVsExplicitAttributionAuditShortagesAndSurplus() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID catId = createCategory(owner, "Kurtis", null);
        UUID kurtiProd = createProduct(owner, catId, "Cotton Kurti");
        UUID surplusProd = createProduct(owner, catId, "Surplus Dupatta");
        UUID kurtiSku = createSku(owner, kurtiProd, "KRT-VND-1", "M", "700.00");
        UUID surplusSku = createSku(owner, surplusProd, "DUP-SUR-1", null, "400.00");

        UUID alphaVendor = createVendor(owner, "Alpha Weaves");
        UUID betaVendor = createVendor(owner, "Beta Looms");

        UUID batchAlpha = UUID.fromString(receiveDirectStock(
                owner, alphaVendor, kurtiSku, LocalDate.of(2026, 11, 1), 4, "400.00"
        ).get("lines").get(0).get("batchId").asText());
        UUID batchBeta = UUID.fromString(receiveDirectStock(
                owner, betaVendor, kurtiSku, LocalDate.of(2026, 11, 5), 4, "420.00"
        ).get("lines").get(0).get("batchId").asText());

        // Sale 1 on Nov 10: 2 FIFO (from Alpha: 9 days) + 1 EXPLICIT_BATCH (from Beta: 5 days)
        JsonNode bill1 = postBill(
                worker,
                List.of(
                        Map.of("skuId", kurtiSku.toString(), "quantity", 2),
                        Map.of("skuId", kurtiSku.toString(), "quantity", 1, "batchId", batchBeta.toString())
                ),
                "2100.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "2100.00"))
        );
        UUID bill1Id = UUID.fromString(bill1.get("billId").asText());
        String fifoLineId = getLogicalLineId(worker, bill1Id, 0);
        shiftBillIssueTimestamp(bill1Id, ist(2026, 11, 10, 14, 0));

        // Return 1 FIFO unit (Alpha netSoldUnits becomes 1, revenue 700, cogs 400)
        mockMvc.perform(post("/api/v1/bills/{id}/returns", bill1Id)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "vnd-ret-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Customer returned 1 piece",
                                "returnedLines", List.of(Map.of(
                                        "lineId", fifoLineId,
                                        "quantity", 1,
                                        "disposition", "SELLABLE"
                                )),
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "700.00"))
                        ))))
                .andExpect(status().isCreated());
        shiftBillEventTimestamp(bill1Id, "RETURN", ist(2026, 11, 12, 12, 0));

        // Current sellable before audit: Alpha batch = 3, Beta batch = 3 (total kurti = 6), surplusSku = 0
        // Post SHOP stock count: kurti = 5 (-1 shortage removes 1 from Alpha FIFO -> Alpha remaining = 2), surplusSku = 2 (+2 AUDIT_SURPLUS!)
        UUID countId = postShopCount(
                worker,
                Map.of(kurtiSku, 5, surplusSku, 2),
                Map.of(kurtiSku, "Damaged piece lost", surplusSku, "Unrecorded bundle")
        );
        jdbcTemplate.update(
                "UPDATE stock_count SET posted_at = ? WHERE id = ?",
                Timestamp.from(ist(2026, 11, 15, 16, 0)), countId
        );
        // Set surplus batch received_date to 2026-11-15 so it is inside cohort filter and sold on 2026-11-16
        jdbcTemplate.update(
                "UPDATE stock_batch SET received_date = ? WHERE sku_id = ? AND batch_source = 'AUDIT_SURPLUS'",
                Date.valueOf(LocalDate.of(2026, 11, 15)), surplusSku
        );

        // Sell 1 unit from surplusSku on Nov 16 -> appears under Unknown Vendor (Audit Surplus) with unknown cost
        JsonNode surplusBill = postBill(
                worker,
                List.of(Map.of("skuId", surplusSku.toString(), "quantity", 1)),
                "400.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "400.00"))
        );
        UUID surplusBillId = UUID.fromString(surplusBill.get("billId").asText());
        shiftBillIssueTimestamp(surplusBillId, ist(2026, 11, 16, 11, 0));

        // Verify full vendor cohort report for batches received 2026-11-01..2026-11-20
        // Row 0: Alpha Weaves (received=4, direct=0, fifo=1, netSold=1, remaining=2, sellThrough=25.00, margin=300.00, days=9.00)
        // Row 1: Beta Looms (received=4, direct=1, fifo=0, netSold=1, remaining=3, sellThrough=25.00, margin=280.00, days=5.00)
        // Row 2: Unknown Vendor (Audit Surplus) (received=2, direct=0, fifo=1, netSold=1, remaining=1, costComplete=false, grossMargin=null)
        mockMvc.perform(get("/api/v1/reports/vendors")
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-20")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalReceivedUnits").value(10))
                .andExpect(jsonPath("$.totalDirectBatchSoldUnits").value(1))
                .andExpect(jsonPath("$.totalFifoSoldUnits").value(2))
                .andExpect(jsonPath("$.totalNetSoldUnits").value(3))
                .andExpect(jsonPath("$.totalRemainingSellableUnits").value(6))
                .andExpect(jsonPath("$.costComplete").value(false))
                .andExpect(jsonPath("$.grossMargin").doesNotExist())
                .andExpect(jsonPath("$.items[0].vendorName").value("Alpha Weaves"))
                .andExpect(jsonPath("$.items[0].fifoSoldUnits").value(1))
                .andExpect(jsonPath("$.items[0].directBatchSoldUnits").value(0))
                .andExpect(jsonPath("$.items[0].remainingSellableUnits").value(2))
                .andExpect(jsonPath("$.items[0].sellThroughPercentage").value("25.00"))
                .andExpect(jsonPath("$.items[0].grossMargin").value("300.00"))
                .andExpect(jsonPath("$.items[0].weightedDaysFromReceiptToSale").value("9.00"))
                .andExpect(jsonPath("$.items[1].vendorName").value("Beta Looms"))
                .andExpect(jsonPath("$.items[1].directBatchSoldUnits").value(1))
                .andExpect(jsonPath("$.items[1].fifoSoldUnits").value(0))
                .andExpect(jsonPath("$.items[1].remainingSellableUnits").value(3))
                .andExpect(jsonPath("$.items[1].grossMargin").value("280.00"))
                .andExpect(jsonPath("$.items[1].weightedDaysFromReceiptToSale").value("5.00"))
                .andExpect(jsonPath("$.items[2].batchSource").value("AUDIT_SURPLUS"))
                .andExpect(jsonPath("$.items[2].costComplete").value(false))
                .andExpect(jsonPath("$.items[2].grossMargin").doesNotExist());

        // Filter to Beta Looms only -> complete cost & margin returned
        mockMvc.perform(get("/api/v1/reports/vendors")
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-20")
                        .param("vendorId", betaVendor.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.costComplete").value(true))
                .andExpect(jsonPath("$.totalGrossMargin").value("280.00"))
                .andExpect(jsonPath("$.weightedDaysFromReceiptToSale").value("5.00"));
    }

    @Test
    @DisplayName("Stock ageing flags unknown-date/cost audit surplus alongside normal batches; stock audits include zero-adjustment posted counts and exclude drafts")
    void stockAgeingAndStockAuditReportsFlagUnknownSurplusAndKeepPaginationStable() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID catId = createCategory(owner, "Sarees", null);
        UUID prod1 = createProduct(owner, catId, "Mysore Silk");
        UUID prod2 = createProduct(owner, catId, "Found Cotton");
        UUID knownSku = createSku(owner, prod1, "MYS-AGE-1", null, "3500.00");
        UUID surplusSku = createSku(owner, prod2, "COT-AGE-2", null, "900.00");

        UUID vendorId = createVendor(owner, "Mysore Looms");
        LocalDate receivedDate = LocalDate.now(SHOP_ZONE).minusDays(10);
        receiveDirectStock(owner, vendorId, knownSku, receivedDate, 2, "1200.00");

        // Post SHOP count: knownSku verified at exact 2 (adjustment = 0), surplusSku found 3 (adjustment = +3, unknown cost/date)
        postShopCount(
                worker,
                Map.of(knownSku, 2, surplusSku, 3),
                Map.of(surplusSku, "Found extra bundle during count")
        );

        // Leave an extra DRAFT count unposted -> must NOT appear in /reports/stock-audits
        mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"SHOP\"}"))
                .andExpect(status().isCreated());

        // Ageing page 0 size 1 -> keeps whole-report totals across both batches
        mockMvc.perform(get("/api/v1/reports/stock-ageing")
                        .param("page", "0")
                        .param("size", "1")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalSellableUnits").value(5))
                .andExpect(jsonPath("$.costComplete").value(false))
                .andExpect(jsonPath("$.totalStockValue").doesNotExist())
                .andExpect(jsonPath("$.unknownReceiptDateUnits").value(3))
                .andExpect(jsonPath("$.unknownCostUnits").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].skuCode").value("MYS-AGE-1"))
                .andExpect(jsonPath("$.items[0].receiptDateKnown").value(true))
                .andExpect(jsonPath("$.items[0].daysOnHand").value(10))
                .andExpect(jsonPath("$.items[0].costKnown").value(true))
                .andExpect(jsonPath("$.items[0].stockValue").value("2400.00"));

        // Page 1 -> unknown date & cost audit surplus batch visible and explicitly flagged
        mockMvc.perform(get("/api/v1/reports/stock-ageing")
                        .param("page", "1")
                        .param("size", "1")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].skuCode").value("COT-AGE-2"))
                .andExpect(jsonPath("$.items[0].batchSource").value("AUDIT_SURPLUS"))
                .andExpect(jsonPath("$.items[0].receiptDate").doesNotExist())
                .andExpect(jsonPath("$.items[0].receiptDateKnown").value(false))
                .andExpect(jsonPath("$.items[0].daysOnHand").doesNotExist())
                .andExpect(jsonPath("$.items[0].unitCost").doesNotExist())
                .andExpect(jsonPath("$.items[0].costKnown").value(false))
                .andExpect(jsonPath("$.items[0].stockValue").doesNotExist())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(3));

        // Stock audits report includes BOTH adjustment=0 line and adjustment=+3 line, excludes draft
        LocalDate today = LocalDate.now(SHOP_ZONE);
        mockMvc.perform(get("/api/v1/reports/stock-audits")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPositiveAdjustment").value(3))
                .andExpect(jsonPath("$.totalNegativeAdjustment").value(0))
                .andExpect(jsonPath("$.netAdjustment").value(3))
                .andExpect(jsonPath("$.items[0].postedByUsername").value("worker1"))
                .andExpect(jsonPath("$.items[1].postedByDisplayName").value("Counter Staff"));

        mockMvc.perform(get("/api/v1/reports/stock-audits")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.toString())
                        .param("skuId", knownSku.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].expectedQuantity").value(2))
                .andExpect(jsonPath("$.items[0].countedQuantity").value(2))
                .andExpect(jsonPath("$.items[0].adjustment").value(0));
    }

    @Test
    @DisplayName("Purchase targets and planning track stock, open POs, partial receipts, cancellations, period fallback, and filters")
    void purchaseTargetUpsertAndPurchasePlanningSuggestionsAndLifecycle() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID sareesCat = createCategory(owner, "Sarees", null);
        UUID silkCat = createCategory(owner, "Silk Sarees", sareesCat);
        UUID innerwearCat = createCategory(owner, "Innerwear", null);

        UUID sareeProd = createProduct(owner, silkCat, "Kanjeevaram");
        UUID vestProd = createProduct(owner, innerwearCat, "Ribbed Vest");

        UUID mainSku = createSku(owner, sareeProd, "KAN-PLAN-1", null, "4500.00");
        UUID vest32Sku = createSku(owner, vestProd, "VST-PLAN-32", "32", "220.00");
        UUID vest34Sku = createSku(owner, vestProd, "VST-PLAN-34", "34", "240.00");

        UUID vendorA = createVendor(owner, "Vendor Alpha");
        UUID vendorB = createVendor(owner, "Vendor Beta");

        // Receive 20 of mainSku from Vendor A, 5 of vest32 from Vendor B; sell 2 mainSku today + 1 aged 45 days ago
        LocalDate today = LocalDate.now(SHOP_ZONE);
        receiveDirectStock(owner, vendorA, mainSku, today.minusDays(50), 21, "3000.00");
        receiveDirectStock(owner, vendorB, vest32Sku, today.minusDays(5), 5, "130.00");

        JsonNode recentBill = postBill(
                worker,
                List.of(Map.of("skuId", mainSku.toString(), "quantity", 2)),
                "9000.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "9000.00"))
        );
        assertThat(recentBill).isNotNull();

        JsonNode oldBill = postBill(
                worker,
                List.of(Map.of("skuId", mainSku.toString(), "quantity", 1)),
                "4500.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "4500.00"))
        );
        shiftBillIssueTimestamp(
                UUID.fromString(oldBill.get("billId").asText()),
                today.minusDays(45).atTime(12, 0).atZone(SHOP_ZONE).toInstant()
        );
        // mainSku now has 18 sellable, recentSales (30d) = 2

        // Verified zero-adjustment stock count sets lastVerifiedCountDate = today
        postShopCount(worker, Map.of(mainSku, 18, vest32Sku, 5, vest34Sku, 0), Map.of());

        // Set targets: default=30 & Diwali=50 on mainSku; explicit 0 on vest32Sku; leave vest34Sku without target
        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", mainSku)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetQuantity").value(30))
                .andExpect(jsonPath("$.planningPeriod").doesNotExist());

        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", mainSku)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":50,\"planningPeriod\":\"Diwali\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetQuantity").value(50))
                .andExpect(jsonPath("$.planningPeriod").value("Diwali"));

        mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", vest32Sku)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetQuantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetQuantity").value(0));

        // Concurrent idempotent upserts on same (skuId, planningPeriod) must never create duplicate rows
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<Integer>> upsertTasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            upsertTasks.add(() -> {
                startGate.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(put("/api/v1/purchase-targets/{skuId}", vest34Sku)
                                .session(owner.session())
                                .header("X-CSRF-Token", owner.csrfToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetQuantity\":12,\"planningPeriod\":\"Summer\"}"))
                        .andReturn().getResponse().getStatus();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> t : upsertTasks) {
            futures.add(pool.submit(t));
        }
        startGate.countDown();
        for (Future<Integer> f : futures) {
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(purchaseTargetRepository.findBySkuIdIn(List.of(vest34Sku))).hasSize(1);

        // Create open PO from Vendor B for 6 units of mainSku
        JsonNode po = createPo(worker, vendorB, mainSku, 6, today);
        UUID poId = UUID.fromString(po.get("poId").asText());
        UUID poLineId = UUID.fromString(po.get("lines").get(0).get("poLineId").asText());

        // Check default planning (target 30 - sellable 18 - openPo 6 = suggested 6), plus Vendor A filter still includes Vendor B's open PO!
        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("vendorId", vendorA.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuId").value(mainSku.toString()))
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(18))
                .andExpect(jsonPath("$.items[0].openPoQuantity").value(6))
                .andExpect(jsonPath("$.items[0].recentSales").value(2))
                .andExpect(jsonPath("$.items[0].lastVerifiedCountDate").value(today.toString()))
                .andExpect(jsonPath("$.items[0].targetQuantity").value(30))
                .andExpect(jsonPath("$.items[0].suggestedQuantity").value(6))
                .andExpect(jsonPath("$.items[0].pastVendors[0].vendorName").value("Vendor Alpha"));

        // Case-insensitive period match ("diwali" -> target 50, suggested 26), unknown period falls back to default 30
        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("categoryId", sareesCat.toString())
                        .param("planningPeriod", "diwali")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetQuantity").value(50))
                .andExpect(jsonPath("$.items[0].resolvedPlanningPeriod").value("Diwali"))
                .andExpect(jsonPath("$.items[0].suggestedQuantity").value(26));

        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("categoryId", sareesCat.toString())
                        .param("planningPeriod", "Pongal")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetQuantity").value(30))
                .andExpect(jsonPath("$.items[0].resolvedPlanningPeriod").doesNotExist())
                .andExpect(jsonPath("$.items[0].suggestedQuantity").value(6));

        // Receive 4 of 6 on PO -> sellable=22, openPo=2, suggested remains 6
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorB.toString(),
                                "receivedDate", today.toString(),
                                "poId", poId.toString(),
                                "lines", List.of(Map.of(
                                        "skuId", mainSku.toString(),
                                        "quantity", 4,
                                        "unitCost", "3100.00",
                                        "poLineId", poLineId.toString(),
                                        "generateInternalTag", false
                                ))
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("categoryId", sareesCat.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(22))
                .andExpect(jsonPath("$.items[0].openPoQuantity").value(2))
                .andExpect(jsonPath("$.items[0].suggestedQuantity").value(6));

        // Cancel remaining 2 open on PO -> openPo=0, suggested rises to 8
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", poId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "reason", "Remainder unavailable"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("categoryId", sareesCat.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(22))
                .andExpect(jsonPath("$.items[0].openPoQuantity").value(0))
                .andExpect(jsonPath("$.items[0].suggestedQuantity").value(8));

        // Zero target vs missing target & size/price filters + pageSize pagination
        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("categoryId", innerwearCat.toString())
                        .param("size", "32")
                        .param("minPrice", "200.00")
                        .param("maxPrice", "230.00")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuCode").value("VST-PLAN-32"))
                .andExpect(jsonPath("$.items[0].targetQuantity").value(0))
                .andExpect(jsonPath("$.items[0].suggestedQuantity").value(0));

        // Default query (without Summer period) leaves vest34Sku with null target & null suggested
        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("categoryId", innerwearCat.toString())
                        .param("size", "34")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].targetQuantity").doesNotExist())
                .andExpect(jsonPath("$.items[0].suggestedQuantity").doesNotExist());

        // Pagination using pageSize
        mockMvc.perform(get("/api/v1/purchase-planning")
                        .param("page", "0")
                        .param("pageSize", "2")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
    }
}
