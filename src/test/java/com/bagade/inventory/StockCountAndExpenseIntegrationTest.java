package com.bagade.inventory;

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
import com.bagade.inventory.entity.BatchSource;
import com.bagade.inventory.entity.MovementType;
import com.bagade.inventory.entity.StockBatch;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.entity.StockMovement;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import com.bagade.inventory.repository.StockBatchRepository;
import com.bagade.inventory.repository.StockCountRepository;
import com.bagade.inventory.repository.StockMovementRepository;
import com.bagade.inventory.repository.StockReceiptRepository;
import com.bagade.purchasing.repository.PurchaseOrderCancellationRepository;
import com.bagade.purchasing.repository.PurchaseOrderRepository;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockCountAndExpenseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private UUID receiveStock(
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
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString())
                .get("lines").get(0).get("batchId").asText());
    }

    private JsonNode getStockCount(SessionContext ctx, UUID countId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/stock-counts/{id}", countId).session(ctx.session()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode saveCountLine(
            SessionContext ctx,
            UUID countId,
            UUID skuId,
            long expectedVersion,
            Integer countedQty,
            long observedStockVersion,
            String reason) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("expectedVersion", expectedVersion);
        payload.put("countedQuantity", countedQty);
        payload.put("observedStockVersion", observedStockVersion);
        if (reason != null) {
            payload.put("reason", reason);
        }
        MvcResult res = mockMvc.perform(put("/api/v1/stock-counts/{id}/lines/{skuId}", countId, skuId)
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Category count uses subcategories stably, keeps sizes separate, rejects pending/missing-reason, and accepts explicit zero")
    void categoryScopeStableSkuListSizeSeparationAndValidation() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID sareesCat = createCategory(worker, "Sarees", null);
        UUID silkSareesCat = createCategory(worker, "Silk Sarees", sareesCat);
        UUID innerwearCat = createCategory(worker, "Innerwear", null);

        UUID silkSareeProd = createProduct(worker, silkSareesCat, "Banarasi Silk Saree");
        UUID vestProd = createProduct(worker, innerwearCat, "Cotton Vest");

        UUID silkSareeSku = createSku(worker, silkSareeProd, "SAR-BNR-1", null, "5500.00");
        UUID vest32Sku = createSku(worker, vestProd, "VST-32", "32", "220.00");
        UUID vest34Sku = createSku(worker, vestProd, "VST-34", "34", "240.00");

        UUID vendorId = createVendor(worker, "Loom Traders");
        receiveStock(worker, vendorId, silkSareeSku, LocalDate.of(2026, 11, 10), 4, "3600.00");
        receiveStock(worker, vendorId, vest32Sku, LocalDate.of(2026, 11, 10), 3, "130.00");
        receiveStock(worker, vendorId, vest34Sku, LocalDate.of(2026, 11, 10), 5, "140.00");

        // Scope validation: SHOP with categoryId or CATEGORY without categoryId
        mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scope", "SHOP",
                                "categoryId", sareesCat.toString()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scope", "CATEGORY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Start category count on parent Sarees -> captures Silk Sarees SKU, excludes Innerwear
        MvcResult catCountRes = mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scope", "CATEGORY",
                                "categoryId", sareesCat.toString()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.scopedSkus.length()").value(1))
                .andExpect(jsonPath("$.scopedSkus[0].skuId").value(silkSareeSku.toString()))
                .andReturn();
        UUID catCountId = UUID.fromString(objectMapper.readTree(catCountRes.getResponse().getContentAsString()).get("countId").asText());

        // Adding another Silk Saree SKU later does not mutate draft scope, and outside-scope save is rejected
        UUID lateSareeSku = createSku(worker, silkSareeProd, "SAR-BNR-LATE", "FREE", "6000.00");
        assertThat(getStockCount(worker, catCountId).get("lines")).hasSize(1);

        mockMvc.perform(put("/api/v1/stock-counts/{id}/lines/{skuId}", catCountId, lateSareeSku)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "countedQuantity", 0,
                                "observedStockVersion", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Start SHOP count -> includes sizes 32 and 34 separately
        MvcResult shopRes = mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scope", "SHOP"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopedSkus.length()").value(4))
                .andReturn();
        UUID shopCountId = UUID.fromString(objectMapper.readTree(shopRes.getResponse().getContentAsString()).get("countId").asText());

        // Initially pending (countedQuantity == null) -> cannot post
        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", shopCountId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "post-pending-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Count all lines, leaving VST-32 with explicit 0 WITHOUT reason -> post rejects missing reason
        JsonNode shopDetail = getStockCount(worker, shopCountId);
        long ver = shopDetail.get("version").asLong();
        for (JsonNode line : shopDetail.get("lines")) {
            UUID lineSkuId = UUID.fromString(line.get("skuId").asText());
            long currentVer = line.get("currentStockVersion").asLong();
            long currentQty = line.get("currentQuantity").asLong();
            int counted = lineSkuId.equals(vest32Sku) ? 0 : (int) currentQty;
            ver = saveCountLine(worker, shopCountId, lineSkuId, ver, counted, currentVer, null)
                    .get("countVersion").asLong();
        }

        // Stale count version on line PUT -> 409 STALE_VERSION
        mockMvc.perform(put("/api/v1/stock-counts/{id}/lines/{skuId}", shopCountId, vest32Sku)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "countedQuantity", 0,
                                "observedStockVersion", 1,
                                "reason", "Stale test"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        // Missing discrepancy reason on VST-32 (expected 3, counted 0) -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", shopCountId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "post-no-reason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", ver))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Provide reason for explicit zero count on VST-32 -> posts cleanly, VST-34 untouched at 5
        ver = saveCountLine(worker, shopCountId, vest32Sku, ver, 0, 1L, "Confirmed zero shelf stock")
                .get("countVersion").asLong();

        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", shopCountId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "post-explicit-zero-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", ver))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        mockMvc.perform(get("/api/v1/inventory").param("skuId", vest32Sku.toString()).session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(0));

        mockMvc.perform(get("/api/v1/inventory").param("skuId", vest34Sku.toString()).session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(5));
    }

    @Test
    @DisplayName("Shortage deducts oldest batches FIFO, surplus creates null-cost audit batch billable later, exact match creates no movement")
    void shortageFifoSurplusNullCostAuditBatchAndExactMatchPosting() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Kurtis", null);
        UUID pShort = createProduct(owner, catId, "Kurti Short");
        UUID pSurplus = createProduct(owner, catId, "Kurti Surplus");
        UUID pMatch = createProduct(owner, catId, "Kurti Match");

        UUID skuShort = createSku(owner, pShort, "KRT-SHRT", "M", "800.00");
        UUID skuSurplus = createSku(owner, pSurplus, "KRT-SURP", "L", "900.00");
        UUID skuMatch = createSku(owner, pMatch, "KRT-MTCH", "S", "700.00");

        UUID vendorId = createVendor(owner, "Jaipur Textiles");
        UUID shortBatch1 = receiveStock(owner, vendorId, skuShort, LocalDate.of(2026, 11, 5), 4, "450.00");
        UUID shortBatch2 = receiveStock(owner, vendorId, skuShort, LocalDate.of(2026, 11, 15), 6, "480.00");
        UUID matchBatch = receiveStock(owner, vendorId, skuMatch, LocalDate.of(2026, 11, 10), 5, "400.00");
        // Note: skuSurplus has 0 received stock prior to audit!

        MvcResult createRes = mockMvc.perform(post("/api/v1/stock-counts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scope", "CATEGORY",
                                "categoryId", catId.toString()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID countId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).get("countId").asText());

        // Record counts: skuShort expected 10 -> counted 8 (-2); skuSurplus expected 0 -> counted 3 (+3); skuMatch expected 5 -> counted 5 (0)
        long ver = 0;
        ver = saveCountLine(owner, countId, skuShort, ver, 8, 2L, "Two missing from rack").get("countVersion").asLong();
        ver = saveCountLine(owner, countId, skuSurplus, ver, 3, 0L, "Found three untagged pieces in backroom").get("countVersion").asLong();
        ver = saveCountLine(owner, countId, skuMatch, ver, 5, 1L, null).get("countVersion").asLong();

        // Still draft: no stock movements added
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(shortBatch1)).hasSize(1);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(matchBatch)).hasSize(1);

        // Post stock count
        MvcResult postRes = mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "post-audit-mix-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", ver))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.postedAt").isNotEmpty())
                .andExpect(jsonPath("$.adjustments.length()").value(3))
                .andReturn();

        JsonNode postJson = objectMapper.readTree(postRes.getResponse().getContentAsString());
        UUID surplusBatchId = null;
        for (JsonNode adj : postJson.get("adjustments")) {
            UUID adjSku = UUID.fromString(adj.get("skuId").asText());
            if (adjSku.equals(skuShort)) {
                assertThat(adj.get("quantityDelta").asInt()).isEqualTo(-2);
                assertThat(adj.get("allocationBasis").asText()).isEqualTo("FIFO_SHORTAGE");
            } else if (adjSku.equals(skuSurplus)) {
                assertThat(adj.get("quantityDelta").asInt()).isEqualTo(3);
                assertThat(adj.get("allocationBasis").asText()).isEqualTo("AUDIT_SURPLUS");
                surplusBatchId = UUID.fromString(adj.get("batchIds").get(0).asText());
            } else {
                assertThat(adj.get("quantityDelta").asInt()).isZero();
                assertThat(adj.get("batchIds")).isEmpty();
            }
        }

        // Shortage took 2 from oldest batch (shortBatch1: 4 -> 2; shortBatch2: 6 -> 6)
        assertThat(batchBalanceRepository.sumSellableByBatchId(shortBatch1)).isEqualTo(2L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(shortBatch2)).isEqualTo(6L);
        List<StockMovement> shortMoves = stockMovementRepository.findByBatchIdOrderByPostedAtAsc(shortBatch1);
        assertThat(shortMoves).hasSize(2);
        assertThat(shortMoves.get(1).getMovementType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(shortMoves.get(1).getQuantityDelta()).isEqualTo(-2);
        assertThat(shortMoves.get(1).getAllocationBasis()).isEqualTo("FIFO_SHORTAGE");
        assertThat(shortMoves.get(1).getSourceStockCountLine()).isNotNull();

        // Match SKU produced ZERO movements and kept stockVersion 1L
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(matchBatch)).hasSize(1);
        assertThat(skuStockStateRepository.findById(skuMatch).orElseThrow().getStockVersion()).isEqualTo(1L);

        // Surplus batch has BatchSource.AUDIT_SURPLUS, null receiptLine, null unitCost
        assertThat(surplusBatchId).isNotNull();
        StockBatch auditBatch = stockBatchRepository.findById(surplusBatchId).orElseThrow();
        assertThat(auditBatch.getBatchSource()).isEqualTo(BatchSource.AUDIT_SURPLUS);
        assertThat(auditBatch.getStockReceiptLine()).isNull();
        assertThat(auditBatch.getUnitCost()).isNull();
        assertThat(auditBatch.getSourceStockCountLine()).isNotNull();
        assertThat(batchBalanceRepository.sumSellableByBatchId(surplusBatchId)).isEqualTo(3L);

        // Selling from that unknown-cost audit surplus batch succeeds and preserves null capturedUnitCost!
        mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "sell-surplus-null-cost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuSurplus.toString(), "quantity", 1)),
                                "expectedTotal", "900.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "900.00"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations[0].batchId").value(surplusBatchId.toString()))
                .andExpect(jsonPath("$.allocations[0].capturedUnitCost").doesNotExist());

        // Posted count is immutable
        mockMvc.perform(put("/api/v1/stock-counts/{id}/lines/{skuId}", countId, skuMatch)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", ver + 1,
                                "countedQuantity", 4,
                                "observedStockVersion", 1,
                                "reason", "Edit after post"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_COUNT_POSTED"));
    }

    @Test
    @DisplayName("Sale after counting causes 409 STOCK_CHANGED with no partial adjustments, and recounting lets worker post")
    void saleAfterCountingCausesStockChangedWithoutPartialCommitAndAllowsRecount() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Apparel", null);
        UUID prodX = createProduct(worker, catId, "Item X");
        UUID prodY = createProduct(worker, catId, "Item Y");
        UUID skuX = createSku(worker, prodX, "ITM-X", null, "500.00");
        UUID skuY = createSku(worker, prodY, "ITM-Y", null, "600.00");
        UUID vendorId = createVendor(worker, "Vendor Co");

        UUID batchX = receiveStock(worker, vendorId, skuX, LocalDate.of(2026, 11, 10), 5, "300.00");
        UUID batchY = receiveStock(worker, vendorId, skuY, LocalDate.of(2026, 11, 10), 4, "350.00");

        MvcResult createRes = mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scope", "SHOP"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID countId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).get("countId").asText());

        long ver = saveCountLine(worker, countId, skuX, 0L, 5, 1L, null).get("countVersion").asLong();
        ver = saveCountLine(worker, countId, skuY, ver, 2, 1L, "2 damaged pieces missing").get("countVersion").asLong();

        // A sale happens on skuX after counting (skuX stock_version: 1 -> 2, sellable: 5 -> 4)
        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "sale-concurrent-audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuX.toString(), "quantity", 1)),
                                "expectedTotal", "500.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "500.00"))
                        ))))
                .andExpect(status().isCreated());

        // Detail shows stale observedStockVersion=1 vs currentStockVersion=2 on skuX
        JsonNode detail = getStockCount(worker, countId);
        for (JsonNode line : detail.get("lines")) {
            if (line.get("skuId").asText().equals(skuX.toString())) {
                assertThat(line.get("observedStockVersion").asLong()).isEqualTo(1L);
                assertThat(line.get("currentStockVersion").asLong()).isEqualTo(2L);
                assertThat(line.get("currentQuantity").asLong()).isEqualTo(4L);
            }
        }

        // Attempt to save skuX with stale observedStockVersion=1 -> 409 STOCK_CHANGED
        mockMvc.perform(put("/api/v1/stock-counts/{id}/lines/{skuId}", countId, skuX)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", ver,
                                "countedQuantity", 4,
                                "observedStockVersion", 1
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_CHANGED"))
                .andExpect(jsonPath("$.changedSkus[0].skuId").value(skuX.toString()))
                .andExpect(jsonPath("$.changedSkus[0].currentStockVersion").value(2));

        // Attempt to post -> 409 STOCK_CHANGED, NO partial adjustment on skuY, count remains DRAFT!
        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "post-after-sale-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", ver))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_CHANGED"))
                .andExpect(jsonPath("$.changedSkus.length()").value(1))
                .andExpect(jsonPath("$.changedSkus[0].skuId").value(skuX.toString()))
                .andExpect(jsonPath("$.changedSkus[0].observedStockVersion").value(1))
                .andExpect(jsonPath("$.changedSkus[0].currentStockVersion").value(2))
                .andExpect(jsonPath("$.changedSkus[0].currentSellableQuantity").value(4));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batchY)).isEqualTo(4L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchY)).hasSize(1);
        assertThat(getStockCount(worker, countId).get("status").asText()).isEqualTo("DRAFT");

        // Recount skuX with current stock version 2 and counted 4, then post cleanly
        ver = saveCountLine(worker, countId, skuX, ver, 4, 2L, null).get("countVersion").asLong();

        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "post-after-recount-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", ver))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batchX)).isEqualTo(4L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchY)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Idempotency replay, key conflict, and concurrent count postings prevent duplicate adjustments")
    void idempotencyReplayConflictAndConcurrentPosting() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(owner, "Shawls", null);
        UUID prodId = createProduct(owner, catId, "Wool Shawl");
        UUID skuId = createSku(owner, prodId, "SHW-1", null, "1500.00");
        UUID vendorId = createVendor(owner, "Kashmir Crafts");
        UUID batchId = receiveStock(owner, vendorId, skuId, LocalDate.of(2026, 11, 10), 6, "900.00");

        MvcResult createRes = mockMvc.perform(post("/api/v1/stock-counts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scope", "SHOP"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID countId = UUID.fromString(objectMapper.readTree(createRes.getResponse().getContentAsString()).get("countId").asText());

        long ver = saveCountLine(owner, countId, skuId, 0L, 4, 1L, "Two lost in display").get("countVersion").asLong();
        String postBody = objectMapper.writeValueAsString(Map.of("expectedVersion", ver));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String winnerKey;
        SessionContext winnerCtx;
        try {
            Callable<MvcResult> c1 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                                .session(owner.session())
                                .header("X-CSRF-Token", owner.csrfToken())
                                .header("Idempotency-Key", "conc-post-owner")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(postBody))
                        .andReturn();
            };
            Callable<MvcResult> c2 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                                .session(worker.session())
                                .header("X-CSRF-Token", worker.csrfToken())
                                .header("Idempotency-Key", "conc-post-worker")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(postBody))
                        .andReturn();
            };

            Future<MvcResult> f1 = pool.submit(c1);
            Future<MvcResult> f2 = pool.submit(c2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult r1 = f1.get(15, TimeUnit.SECONDS);
            MvcResult r2 = f2.get(15, TimeUnit.SECONDS);
            List<Integer> statuses = new ArrayList<>(List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus()));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);

            boolean ownerWon = r1.getResponse().getStatus() == 200;
            MvcResult loser = ownerWon ? r2 : r1;
            assertThat(objectMapper.readTree(loser.getResponse().getContentAsString()).get("code").asText())
                    .isEqualTo("STOCK_COUNT_POSTED");

            winnerKey = ownerWon ? "conc-post-owner" : "conc-post-worker";
            winnerCtx = ownerWon ? owner : worker;
        } finally {
            pool.shutdownNow();
        }

        // Only 1 adjustment movement recorded
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(4L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(2);

        // Retry with winner key replays 200 OK even though count is now POSTED with version+1
        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                        .session(winnerCtx.session())
                        .header("X-CSRF-Token", winnerCtx.csrfToken())
                        .header("Idempotency-Key", winnerKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.version").value(ver + 1));

        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(2);

        // Reusing key with different payload -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", countId)
                        .session(winnerCtx.session())
                        .header("X-CSRF-Token", winnerCtx.csrfToken())
                        .header("Idempotency-Key", winnerKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", ver + 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("Worker manages operating expenses cleanly without altering inventory; invalid and stale edits rejected")
    void operatingExpensesLifecycleFilteringStaleVersionAndNoInventoryEffect() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Accessories", null);
        UUID prodId = createProduct(worker, catId, "Dupatta");
        UUID skuId = createSku(worker, prodId, "DUP-EXP", null, "400.00");
        UUID vendorId = createVendor(worker, "Weave Craft");
        UUID batchId = receiveStock(worker, vendorId, skuId, LocalDate.of(2026, 11, 10), 4, "200.00");

        // Zero or negative expense amount rejected
        mockMvc.perform(post("/api/v1/expenses")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expenseDate", "2026-11-20",
                                "category", "Electricity",
                                "amount", "0.00",
                                "paymentMode", "CASH"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/expenses")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expenseDate", "2026-11-20",
                                "category", "Rent",
                                "amount", "-100.00",
                                "paymentMode", "CASH"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Create two expenses
        MvcResult rentRes = mockMvc.perform(post("/api/v1/expenses")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expenseDate", "2026-11-18",
                                "category", "  Rent  ",
                                "amount", "15000.00",
                                "paymentMode", "UPI",
                                "notes", "  November shop rent  "
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.category").value("Rent"))
                .andExpect(jsonPath("$.notes").value("November shop rent"))
                .andReturn();
        UUID rentId = UUID.fromString(objectMapper.readTree(rentRes.getResponse().getContentAsString()).get("expenseId").asText());

        mockMvc.perform(post("/api/v1/expenses")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expenseDate", "2026-11-24",
                                "category", "Electricity",
                                "amount", "2450.50",
                                "paymentMode", "CASH"
                        ))))
                .andExpect(status().isCreated());

        // Filter by inclusive date range & case-insensitive category
        mockMvc.perform(get("/api/v1/expenses")
                        .param("from", "2026-11-18")
                        .param("to", "2026-11-20")
                        .param("category", "rent")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].expenseId").value(rentId.toString()))
                .andExpect(jsonPath("$.items[0].amount").value("15000.00"));

        // Empty PATCH (no editable fields) -> 400 VALIDATION_ERROR
        mockMvc.perform(patch("/api/v1/expenses/{id}", rentId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Update amount and notes -> version 0 -> 1
        mockMvc.perform(patch("/api/v1/expenses/{id}", rentId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "amount", "15500.00",
                                "notes", "Updated with maintenance charge"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.amount").value("15500.00"))
                .andExpect(jsonPath("$.notes").value("Updated with maintenance charge"));

        // Stale version PATCH -> 409 STALE_VERSION
        mockMvc.perform(patch("/api/v1/expenses/{id}", rentId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "amount", "16000.00"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        // Inventory completely untouched by expenses, and receipts created 0 expenses
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(4L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(1);
        assertThat(skuStockStateRepository.findById(skuId).orElseThrow().getStockVersion()).isEqualTo(1L);
        assertThat(expenseRepository.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Enforces authentication and CSRF protection on stock counts and expenses while allowing OWNER and WORKER")
    void securityEnforcesAuthAndCsrfAndPermitsOwnerAndWorker() throws Exception {
        MvcResult preCsrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String anonCsrf = objectMapper.readTree(preCsrf.getResponse().getContentAsString()).get("csrfToken").asText();
        MockHttpSession anonSession = (MockHttpSession) preCsrf.getRequest().getSession(false);

        UUID fakeId = UUID.randomUUID();

        // Unauthenticated requests -> 401 AUTH_REQUIRED
        mockMvc.perform(get("/api/v1/stock-counts/{id}", fakeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/expenses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(post("/api/v1/stock-counts")
                        .session(anonSession)
                        .header("X-CSRF-Token", anonCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(post("/api/v1/expenses")
                        .session(anonSession)
                        .header("X-CSRF-Token", anonCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        SessionContext worker = signIn("worker1", "WorkerPass123!");
        SessionContext owner = signIn("owner1", "OwnerPass123!");

        // Missing CSRF token for authenticated worker -> 403 CSRF_ERROR
        mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(put("/api/v1/stock-counts/{id}/lines/{skuId}", fakeId, fakeId)
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/stock-counts/{id}/post", fakeId)
                        .session(worker.session())
                        .header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/expenses")
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(patch("/api/v1/expenses/{id}", fakeId)
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        // Both OWNER and WORKER can access stock-counts and expenses
        mockMvc.perform(post("/api/v1/stock-counts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("scope", "SHOP"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/expenses").session(owner.session()))
                .andExpect(status().isOk());
    }
}
