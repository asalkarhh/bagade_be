package com.bagade.billing;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import com.bagade.billing.entity.BillStatus;
import com.bagade.billing.entity.BillingEventType;
import com.bagade.billing.entity.PaymentDirection;
import com.bagade.billing.entity.PaymentEntry;
import com.bagade.billing.entity.SaleAllocation;
import com.bagade.billing.entity.SaleAllocationReversal;
import com.bagade.billing.entity.SalesAdjustment;
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
import com.bagade.inventory.entity.MovementType;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.entity.StockMovement;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import com.bagade.inventory.repository.StockBatchRepository;
import com.bagade.inventory.repository.StockMovementRepository;
import com.bagade.inventory.repository.StockReceiptRepository;
import com.bagade.purchasing.repository.PurchaseOrderCancellationRepository;
import com.bagade.purchasing.repository.PurchaseOrderRepository;
import com.bagade.purchasing.repository.VendorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

import java.math.BigDecimal;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillCorrectionIntegrationTest {

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

    private UUID createCategory(SessionContext ctx, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/categories")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
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
            int quantity,
            String unitCost,
            String printedMrp) throws Exception {
        var line = new LinkedHashMap<String, Object>();
        line.put("skuId", skuId.toString());
        line.put("quantity", quantity);
        line.put("unitCost", unitCost);
        if (printedMrp != null) {
            line.put("printedMrp", printedMrp);
        }
        line.put("generateInternalTag", false);

        MvcResult res = mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "receivedDate", LocalDate.of(2026, 11, 20).toString(),
                                "lines", List.of(line)
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString())
                .get("lines").get(0).get("batchId").asText());
    }

    private UUID createCustomer(SessionContext ctx, String name, String contact) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/customers")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "contact", contact
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("customerId").asText());
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

    private JsonNode getBillDetail(SessionContext ctx, UUID billId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/bills/{id}", billId).session(ctx.session()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Price-only revision retains stock allocations, preserves rev1 reprint snapshot, and handles qty reduction/addition")
    void priceOnlyEditAndQuantityEditsKeepReprintSnapshotsConsistent() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Sarees");
        UUID sareeProduct = createProduct(worker, catId, "Silk Saree");
        UUID blouseProduct = createProduct(worker, catId, "Designer Blouse");
        UUID sareeSku = createSku(worker, sareeProduct, "SAR-EDIT-1", null, "4500.00");
        UUID blouseSku = createSku(worker, blouseProduct, "BLS-EDIT-1", "36", "800.00");
        UUID vendorId = createVendor(worker, "Silk House");

        UUID sareeBatch = receiveStock(worker, vendorId, sareeSku, 4, "3000.00", null);
        UUID blouseBatch = receiveStock(worker, vendorId, blouseSku, 5, "500.00", null);

        JsonNode issued = postBill(
                worker,
                List.of(Map.of("skuId", sareeSku.toString(), "quantity", 2)),
                "9000.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "9000.00"))
        );
        UUID billId = UUID.fromString(issued.get("billId").asText());
        String logicalLineId = getBillDetail(worker, billId).get("lines").get(0).get("logicalLineId").asText();

        // Expected total mismatch -> 409 PRICE_CHANGED
        mockMvc.perform(post("/api/v1/bills/{id}/revisions", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "edit-price-mismatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Festival discount adjustment",
                                "lines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "skuId", sareeSku.toString(),
                                        "quantity", 2,
                                        "unitPriceOverride", "4250.00"
                                )),
                                "expectedTotal", "9000.00",
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "500.00"))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.currentTotals.total").value("8500.00"));

        // Overpayment without refund on walk-in -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/bills/{id}/revisions", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "edit-missing-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Agreed price correction",
                                "lines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "skuId", sareeSku.toString(),
                                        "quantity", 2,
                                        "unitPriceOverride", "4250.00"
                                )),
                                "expectedTotal", "8500.00",
                                "settlement", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Price-only edit with refund -> 200 OK, NO stock movement or reversal
        mockMvc.perform(post("/api/v1/bills/{id}/revisions", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "edit-price-only-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Agreed price correction",
                                "lines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "skuId", sareeSku.toString(),
                                        "quantity", 2,
                                        "unitPriceOverride", "4250.00"
                                )),
                                "expectedTotal", "8500.00",
                                "settlement", List.of(Map.of(
                                        "direction", "REFUND",
                                        "mode", "CASH",
                                        "amount", "500.00",
                                        "reference", "CASH-REFUND-1"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billId").value(billId.toString()))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.total").value("8500.00"))
                .andExpect(jsonPath("$.adjustmentAmount").value("-500.00"))
                .andExpect(jsonPath("$.balanceDue").value("0.00"));

        // Stock did not move and stockVersion stays 2L (1 receipt + 1 initial issue)
        assertThat(batchBalanceRepository.sumSellableByBatchId(sareeBatch)).isEqualTo(2L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(sareeBatch)).hasSize(2);
        assertThat(saleAllocationReversalRepository.findByBillIdOrderByReversedAtAsc(billId)).isEmpty();
        assertThat(skuStockStateRepository.findById(sareeSku).orElseThrow().getStockVersion()).isEqualTo(2L);

        // Historical reprint ?revision=1 retains 9000.00 total and 9000.00 paid without rev2 refund
        mockMvc.perform(get("/api/v1/bills/{id}/print", billId)
                        .param("revision", "1")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.total").value("9000.00"))
                .andExpect(jsonPath("$.paid").value("9000.00"))
                .andExpect(jsonPath("$.balanceDue").value("0.00"))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.corrections").isEmpty());

        // Current print reflects rev2 and corrections, including valid 80mm PDF
        mockMvc.perform(get("/api/v1/bills/{id}/print", billId).session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.total").value("8500.00"))
                .andExpect(jsonPath("$.paid").value("8500.00"))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.corrections[0].eventType").value("EDIT"))
                .andExpect(jsonPath("$.corrections[0].amountDelta").value("-500.00"));

        byte[] pdfBytes = mockMvc.perform(get("/api/v1/bills/{id}/print", billId)
                        .param("format", "pdf")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("CORRECTIONS:");
            assertThat(text).contains("Agreed price correction");
            assertThat(text).contains("8500.00");
        }

        // Second revision: reduce saree 2 -> 1 (reverses 1, credit 4250), add 2 blouses @ 800 (+1600), net = 5850, refund 2650
        mockMvc.perform(post("/api/v1/bills/{id}/revisions", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "edit-qty-and-add-line")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "reason", "Swapped one saree for two blouses",
                                "lines", List.of(
                                        Map.of("lineId", logicalLineId, "skuId", sareeSku.toString(), "quantity", 1),
                                        Map.of("skuId", blouseSku.toString(), "quantity", 2)
                                ),
                                "expectedTotal", "5850.00",
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "2650.00"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.total").value("5850.00"))
                .andExpect(jsonPath("$.adjustmentAmount").value("-2650.00"))
                .andExpect(jsonPath("$.balanceDue").value("0.00"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(sareeBatch)).isEqualTo(3L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(blouseBatch)).isEqualTo(3L);
        assertThat(skuStockStateRepository.findById(sareeSku).orElseThrow().getStockVersion()).isEqualTo(3L);
        assertThat(skuStockStateRepository.findById(blouseSku).orElseThrow().getStockVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Partial return restores original batch and rejects second return exceeding remaining returnable units")
    void partialReturnRestoresOriginalBatchAndEnforcesReturnableLimit() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Kurtis");
        UUID productId = createProduct(owner, catId, "Cotton Kurti");
        UUID skuId = createSku(owner, productId, "KRT-RET-1", "M", "1000.00");
        UUID vendorId = createVendor(owner, "Jaipur Handloom");

        UUID batch1 = receiveStock(owner, vendorId, skuId, 5, "600.00", null);

        JsonNode issued = postBill(
                owner,
                List.of(Map.of("skuId", skuId.toString(), "quantity", 3)),
                "3000.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "3000.00"))
        );
        UUID billId = UUID.fromString(issued.get("billId").asText());
        String logicalLineId = getBillDetail(owner, billId).get("lines").get(0).get("logicalLineId").asText();
        assertThat(batchBalanceRepository.sumSellableByBatchId(batch1)).isEqualTo(2L);

        // Return 2 sellable items with CASH refund 2000.00
        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "ret-partial-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Size did not fit",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "quantity", 2,
                                        "disposition", "SELLABLE"
                                )),
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "2000.00"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.returnId").isNotEmpty())
                .andExpect(jsonPath("$.billId").value(billId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.balanceDue").value("0.00"))
                .andExpect(jsonPath("$.stockEffects[0].batchId").value(batch1.toString()))
                .andExpect(jsonPath("$.stockEffects[0].stockCondition").value("SELLABLE"))
                .andExpect(jsonPath("$.stockEffects[0].movementType").value("RETURN"))
                .andExpect(jsonPath("$.stockEffects[0].quantityDelta").value(2))
                .andExpect(jsonPath("$.stockEffects[0].resultingBatchQuantity").value(4));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batch1)).isEqualTo(4L);

        JsonNode afterFirstReturn = getBillDetail(owner, billId);
        assertThat(afterFirstReturn.get("status").asText()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(afterFirstReturn.get("total").asText()).isEqualTo("1000.00");
        assertThat(afterFirstReturn.get("paid").asText()).isEqualTo("1000.00");
        assertThat(afterFirstReturn.get("lines").get(0).get("returnedQuantity").asInt()).isEqualTo(2);
        assertThat(afterFirstReturn.get("lines").get(0).get("returnableQuantity").asInt()).isEqualTo(1);

        // Original SALE movement preserved; compensating RETURN movement appended
        List<StockMovement> movements = stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batch1);
        assertThat(movements).hasSize(3);
        assertThat(movements.get(1).getMovementType()).isEqualTo(MovementType.SALE);
        assertThat(movements.get(1).getQuantityDelta()).isEqualTo(-3);
        assertThat(movements.get(2).getMovementType()).isEqualTo(MovementType.RETURN);
        assertThat(movements.get(2).getQuantityDelta()).isEqualTo(2);
        assertThat(movements.get(2).getSourceSaleAllocationReversal()).isNotNull();

        // Second return requesting 2 units (only 1 remaining) -> 409 RETURN_QUANTITY_EXCEEDED
        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "ret-exceed-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "reason", "Duplicate return attempt",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "quantity", 2,
                                        "disposition", "SELLABLE"
                                )),
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "2000.00"))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDED"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batch1)).isEqualTo(4L);

        // Return final 1 unit -> bill reaches RETURNED
        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "ret-final-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "reason", "Returning last unit",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "quantity", 1,
                                        "disposition", "SELLABLE"
                                )),
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "UPI", "amount", "1000.00"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        assertThat(getBillDetail(owner, billId).get("status").asText()).isEqualTo("RETURNED");
        assertThat(batchBalanceRepository.sumSellableByBatchId(batch1)).isEqualTo(5L);
    }

    @Test
    @DisplayName("NON_SELLABLE return tracks damaged stock separately without increasing sellable stock")
    void nonSellableReturnDoesNotIncreaseSellableStock() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Shirts");
        UUID productId = createProduct(worker, catId, "Formal Shirt");
        UUID skuId = createSku(worker, productId, "SHT-NS-1", "L", "1200.00");
        UUID vendorId = createVendor(worker, "Raymond Mill");
        UUID batchId = receiveStock(worker, vendorId, skuId, 4, "700.00", null);

        JsonNode issued = postBill(
                worker,
                List.of(Map.of("skuId", skuId.toString(), "quantity", 2)),
                "2400.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "2400.00"))
        );
        UUID billId = UUID.fromString(issued.get("billId").asText());
        String logicalLineId = getBillDetail(worker, billId).get("lines").get(0).get("logicalLineId").asText();
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(2L);

        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "ret-non-sellable-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Stitching defect noticed by customer",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "quantity", 1,
                                        "disposition", "NON_SELLABLE"
                                )),
                                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "1200.00"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockEffects[0].stockCondition").value("NON_SELLABLE"))
                .andExpect(jsonPath("$.stockEffects[0].quantityDelta").value(1))
                .andExpect(jsonPath("$.stockEffects[0].resultingBatchQuantity").value(1));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(2L);

        mockMvc.perform(get("/api/v1/inventory")
                        .param("skuId", skuId.toString())
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(2))
                .andExpect(jsonPath("$.items[0].nonSellableQuantity").value(1))
                .andExpect(jsonPath("$.items[0].batches[0].sellableQuantity").value(2))
                .andExpect(jsonPath("$.items[0].batches[0].nonSellableQuantity").value(1));

        List<SaleAllocationReversal> reversals = saleAllocationReversalRepository.findByBillIdOrderByReversedAtAsc(billId);
        assertThat(reversals).singleElement().satisfies(r -> {
            assertThat(r.getDisposition()).isEqualTo(StockCondition.NON_SELLABLE);
            assertThat(r.getCapturedUnitCost()).isEqualByComparingTo("700.00");
        });
    }

    @Test
    @DisplayName("Size 32 to 34 exchange updates both balances and price difference, rolling back atomically when replacement fails")
    void exchangeSize32To34AndRollbackWhenReplacementUnavailable() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Innerwear");
        UUID productId = createProduct(worker, catId, "Cotton Briefs");
        UUID sku32 = createSku(worker, productId, "BRF-32", "32", "500.00");
        UUID sku34 = createSku(worker, productId, "BRF-34", "34", "650.00");
        UUID vendorId = createVendor(worker, "Hosiery Works");

        UUID batch32 = receiveStock(worker, vendorId, sku32, 3, "300.00", null);
        UUID batch34 = receiveStock(worker, vendorId, sku34, 1, "380.00", null);

        JsonNode issued = postBill(
                worker,
                List.of(Map.of("skuId", sku32.toString(), "quantity", 1)),
                "500.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "500.00"))
        );
        UUID billId = UUID.fromString(issued.get("billId").asText());
        String logicalLine32 = getBillDetail(worker, billId).get("lines").get(0).get("logicalLineId").asText();
        assertThat(batchBalanceRepository.sumSellableByBatchId(batch32)).isEqualTo(2L);

        // Request 2 units of size 34 when only 1 exists -> 409 INSUFFICIENT_STOCK and full rollback
        mockMvc.perform(post("/api/v1/bills/{id}/exchanges", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "exch-fail-rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Size upgrade",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLine32,
                                        "quantity", 1,
                                        "disposition", "SELLABLE"
                                )),
                                "replacementLines", List.of(Map.of(
                                        "skuId", sku34.toString(),
                                        "quantity", 2
                                )),
                                "settlement", List.of(Map.of("direction", "PAYMENT", "mode", "CASH", "amount", "800.00"))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        // Verify zero side effects from failed exchange
        assertThat(batchBalanceRepository.sumSellableByBatchId(batch32)).isEqualTo(2L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(batch34)).isEqualTo(1L);
        assertThat(saleAllocationReversalRepository.findByBillIdOrderByReversedAtAsc(billId)).isEmpty();
        assertThat(paymentEntryRepository.findByBillIdOrderByPostedAtAscIdAsc(billId)).hasSize(1);
        assertThat(getBillDetail(worker, billId).get("version").asLong()).isZero();

        // Successful exchange: 1 size 32 returned -> 1 size 34 issued (+150.00 UPI payment)
        mockMvc.perform(post("/api/v1/bills/{id}/exchanges", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "exch-success-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Size 32 exchanged for 34",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLine32,
                                        "quantity", 1,
                                        "disposition", "SELLABLE"
                                )),
                                "replacementLines", List.of(Map.of(
                                        "skuId", sku34.toString(),
                                        "quantity", 1
                                )),
                                "settlement", List.of(Map.of(
                                        "direction", "PAYMENT",
                                        "mode", "UPI",
                                        "amount", "150.00",
                                        "reference", "UPI-EXCH-1"
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exchangeId").isNotEmpty())
                .andExpect(jsonPath("$.billId").value(billId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.priceDifference").value("150.00"))
                .andExpect(jsonPath("$.balanceDue").value("0.00"))
                .andExpect(jsonPath("$.stockEffects.length()").value(2));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batch32)).isEqualTo(3L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(batch34)).isZero();

        JsonNode detailAfterExchange = getBillDetail(worker, billId);
        assertThat(detailAfterExchange.get("status").asText()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(detailAfterExchange.get("total").asText()).isEqualTo("650.00");
        assertThat(detailAfterExchange.get("paid").asText()).isEqualTo("650.00");
        assertThat(detailAfterExchange.get("lines")).hasSize(2);
        assertThat(detailAfterExchange.get("lines").get(1).get("skuCode").asText()).isEqualTo("BRF-34");
        assertThat(detailAfterExchange.get("lines").get(1).get("returnableQuantity").asInt()).isEqualTo(1);

        // Revision 1 snapshot remains pure (only BRF-32, total 500.00, paid 500.00)
        mockMvc.perform(get("/api/v1/bills/{id}/print", billId)
                        .param("revision", "1")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("500.00"))
                .andExpect(jsonPath("$.paid").value("500.00"))
                .andExpect(jsonPath("$.lines.length()").value(1));
    }

    @Test
    @DisplayName("Cancelling a partially returned bill reverses only remaining units/amounts and rejects further actions")
    void cancellingPartiallyReturnedBillReversesOnlyRemainingEffects() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID customerId = createCustomer(owner, "Suman Patwardhan", "9822001122");
        UUID catId = createCategory(owner, "Tops");
        UUID productId = createProduct(owner, catId, "Linen Top");
        UUID skuId = createSku(owner, productId, "TOP-CXL-1", "S", "100.00");
        UUID vendorId = createVendor(owner, "Fab Studio");
        UUID batchId = receiveStock(owner, vendorId, skuId, 3, "60.00", null);

        JsonNode issued = postBill(
                owner,
                List.of(Map.of("skuId", skuId.toString(), "quantity", 3)),
                "300.00",
                customerId,
                List.of(Map.of("mode", "CASH", "amount", "180.00"))
        );
        UUID billId = UUID.fromString(issued.get("billId").asText());
        String logicalLineId = getBillDetail(owner, billId).get("lines").get(0).get("logicalLineId").asText();

        // Return 1 piece (100.00 credit, no refund since 180 paid <= 200 new total) -> 2 pieces & 200 total remain
        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "partial-before-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "One unit returned early",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "quantity", 1,
                                        "disposition", "SELLABLE"
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.balanceDue").value("20.00"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(1L);

        // Cancel without refunding collected 180.00 -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/bills/{id}/cancellations", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "cancel-no-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "reason", "Order cancelled",
                                "settlement", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Cancel with exact refund 180.00 -> 200 OK, reverses remaining 2 pieces
        String cancelBody = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 1,
                "reason", "Customer cancelled remaining pieces",
                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "180.00"))
        ));
        mockMvc.perform(post("/api/v1/bills/{id}/cancellations", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "cancel-exact-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billId").value(billId.toString()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.balanceDue").value("0.00"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(3L);
        List<StockMovement> movements = stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId);
        assertThat(movements).hasSize(4);
        assertThat(movements.get(2).getQuantityDelta()).isEqualTo(1);
        assertThat(movements.get(3).getMovementType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(movements.get(3).getQuantityDelta()).isEqualTo(2);

        List<SalesAdjustment> adjustments = salesAdjustmentRepository.findByBillIdOrderByOccurredAtAscIdAsc(billId);
        assertThat(adjustments).hasSize(2);
        assertThat(adjustments.get(0).getAdjustmentType()).isEqualTo(BillingEventType.RETURN);
        assertThat(adjustments.get(0).getAmountDelta()).isEqualByComparingTo("-100.00");
        assertThat(adjustments.get(1).getAdjustmentType()).isEqualTo(BillingEventType.CANCEL);
        assertThat(adjustments.get(1).getAmountDelta()).isEqualByComparingTo("-200.00");

        // Identical retry of cancel replays original 200 OK
        mockMvc.perform(post("/api/v1/bills/{id}/cancellations", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "cancel-exact-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(2));

        // Any new mutation with a fresh Idempotency-Key -> 409 BILL_CANCELLED
        mockMvc.perform(post("/api/v1/bills/{id}/payments", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "pay-after-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 2,
                                "mode", "CASH",
                                "amount", "10.00"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_CANCELLED"));

        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "return-after-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 2,
                                "reason", "Late return",
                                "returnedLines", List.of(Map.of(
                                        "lineId", logicalLineId,
                                        "quantity", 1,
                                        "disposition", "SELLABLE"
                                ))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_CANCELLED"));
    }

    @Test
    @DisplayName("Credit collection supports partial then full settlement, rejects over-collection, and moves no stock")
    void creditCollectionPartialThenFullRejectsOverpaymentAndMovesNoStock() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID customerId = createCustomer(worker, "Prakash Kale", "9011223344");
        UUID catId = createCategory(worker, "Suiting");
        UUID productId = createProduct(worker, catId, "Blazer");
        UUID skuId = createSku(worker, productId, "BLZ-CR-1", "40", "1000.00");
        UUID vendorId = createVendor(worker, "Raymond House");
        UUID batchId = receiveStock(worker, vendorId, skuId, 3, "650.00", null);

        JsonNode issued = postBill(
                worker,
                List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                "1000.00",
                customerId,
                List.of(Map.of("mode", "CASH", "amount", "200.00"))
        );
        UUID billId = UUID.fromString(issued.get("billId").asText());

        // Partial collection 300.00 -> balanceDue 500.00
        mockMvc.perform(post("/api/v1/bills/{id}/payments", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "credit-pay-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "mode", "UPI",
                                "amount", "300.00",
                                "reference", "UTR-300"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").isNotEmpty())
                .andExpect(jsonPath("$.billId").value(billId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.balanceDue").value("500.00"));

        // Over-collection 600.00 > 500.00 -> 409 PAYMENT_EXCEEDS_BALANCE
        mockMvc.perform(post("/api/v1/bills/{id}/payments", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "credit-over-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "mode", "CASH",
                                "amount", "600.00"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_BALANCE"));

        // Collect exact remaining 500.00 -> balanceDue 0.00
        mockMvc.perform(post("/api/v1/bills/{id}/payments", billId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "credit-pay-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 1,
                                "mode", "CASH",
                                "amount", "500.00"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.balanceDue").value("0.00"));

        // No inventory movement, no sales adjustment, no extra billing events
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(2L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(2);
        assertThat(skuStockStateRepository.findById(skuId).orElseThrow().getStockVersion()).isEqualTo(2L);
        assertThat(salesAdjustmentRepository.findByBillIdOrderByOccurredAtAscIdAsc(billId)).isEmpty();
        assertThat(billingEventRepository.findByBillIdWithRevisionOrderByOccurredAtAsc(billId))
                .singleElement()
                .satisfies(e -> assertThat(e.getEventType()).isEqualTo(BillingEventType.ISSUE));

        List<PaymentEntry> payments = paymentEntryRepository.findByBillIdOrderByPostedAtAscIdAsc(billId);
        assertThat(payments).hasSize(3);
        assertThat(payments).allSatisfy(p -> assertThat(p.getDirection()).isEqualTo(PaymentDirection.PAYMENT));

        mockMvc.perform(get("/api/v1/bills")
                        .param("customerId", customerId.toString())
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].paid").value("1000.00"))
                .andExpect(jsonPath("$.items[0].balanceDue").value("0.00"));
    }

    @Test
    @DisplayName("Concurrent returns prevent double reversal, stale version returns 409, and idempotency replays before version check")
    void concurrentReturnsStaleVersionAndIdempotencyReplayAndConflict() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(owner, "Bridal");
        UUID productId = createProduct(owner, catId, "Lehenga");
        UUID skuId = createSku(owner, productId, "LHG-1", null, "5000.00");
        UUID vendorId = createVendor(owner, "Designer Atelier");
        UUID batchId = receiveStock(owner, vendorId, skuId, 1, "3500.00", null);

        JsonNode issued = postBill(
                owner,
                List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                "5000.00",
                null,
                List.of(Map.of("mode", "CASH", "amount", "5000.00"))
        );
        UUID billId = UUID.fromString(issued.get("billId").asText());
        String logicalLineId = getBillDetail(owner, billId).get("lines").get(0).get("logicalLineId").asText();

        String retPayloadOwner = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 0,
                "reason", "Customer returned lehenga",
                "returnedLines", List.of(Map.of("lineId", logicalLineId, "quantity", 1, "disposition", "SELLABLE")),
                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "5000.00"))
        ));
        String retPayloadWorker = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 0,
                "reason", "Customer returned lehenga",
                "returnedLines", List.of(Map.of("lineId", logicalLineId, "quantity", 1, "disposition", "SELLABLE")),
                "settlement", List.of(Map.of("direction", "REFUND", "mode", "CASH", "amount", "5000.00"))
        ));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String winnerKey;
        String winnerPayload;
        SessionContext winnerCtx;
        try {
            Callable<MvcResult> c1 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                                .session(owner.session())
                                .header("X-CSRF-Token", owner.csrfToken())
                                .header("Idempotency-Key", "race-ret-owner")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(retPayloadOwner))
                        .andReturn();
            };
            Callable<MvcResult> c2 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                                .session(worker.session())
                                .header("X-CSRF-Token", worker.csrfToken())
                                .header("Idempotency-Key", "race-ret-worker")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(retPayloadWorker))
                        .andReturn();
            };

            Future<MvcResult> f1 = pool.submit(c1);
            Future<MvcResult> f2 = pool.submit(c2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult r1 = f1.get(15, TimeUnit.SECONDS);
            MvcResult r2 = f2.get(15, TimeUnit.SECONDS);

            List<Integer> statuses = new ArrayList<>(List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus()));
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);

            boolean ownerWon = r1.getResponse().getStatus() == 201;
            MvcResult loser = ownerWon ? r2 : r1;
            assertThat(objectMapper.readTree(loser.getResponse().getContentAsString()).get("code").asText())
                    .isEqualTo("STALE_VERSION");

            winnerKey = ownerWon ? "race-ret-owner" : "race-ret-worker";
            winnerPayload = ownerWon ? retPayloadOwner : retPayloadWorker;
            winnerCtx = ownerWon ? owner : worker;
        } finally {
            pool.shutdownNow();
        }

        // Only 1 reversal, 1 return movement, balance = 1
        assertThat(saleAllocationReversalRepository.findByBillIdOrderByReversedAtAsc(billId)).hasSize(1);
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(1L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(3);

        // Retrying the winner's committed request with expectedVersion=0 (now stale) replays original 201 cleanly!
        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(winnerCtx.session())
                        .header("X-CSRF-Token", winnerCtx.csrfToken())
                        .header("Idempotency-Key", winnerKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(winnerPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // Reusing winner's Idempotency-Key with modified payload -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(post("/api/v1/bills/{id}/returns", billId)
                        .session(winnerCtx.session())
                        .header("X-CSRF-Token", winnerCtx.csrfToken())
                        .header("Idempotency-Key", winnerKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Different reason",
                                "returnedLines", List.of(Map.of("lineId", logicalLineId, "quantity", 1, "disposition", "SELLABLE"))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(3);
    }

    @Test
    @DisplayName("Enforces authentication and CSRF protection on all BE06 endpoints while permitting OWNER and WORKER")
    void securityAllowsOwnerAndWorkerAndEnforcesAuthAndCsrf() throws Exception {
        UUID randomBillId = UUID.randomUUID();
        String emptyJson = "{}";

        MvcResult preCsrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String anonCsrf = objectMapper.readTree(preCsrf.getResponse().getContentAsString())
                .get("csrfToken").asText();
        MockHttpSession anonSession = (MockHttpSession) preCsrf.getRequest().getSession(false);

        for (String path : List.of("/revisions", "/returns", "/exchanges", "/cancellations", "/payments")) {
            mockMvc.perform(post("/api/v1/bills/{id}" + path, randomBillId)
                            .session(anonSession)
                            .header("X-CSRF-Token", anonCsrf)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emptyJson))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        }

        SessionContext worker = signIn("worker1", "WorkerPass123!");
        SessionContext owner = signIn("owner1", "OwnerPass123!");

        for (String path : List.of("/revisions", "/returns", "/exchanges", "/cancellations", "/payments")) {
            mockMvc.perform(post("/api/v1/bills/{id}" + path, randomBillId)
                            .session(worker.session())
                            .header("Idempotency-Key", "no-csrf")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emptyJson))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_ERROR"));
        }

        // Authenticated OWNER & WORKER with CSRF reach controller & domain handling (404 missing bill)
        mockMvc.perform(post("/api/v1/bills/{id}/cancellations", randomBillId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "owner-auth-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Test access"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/bills/{id}/payments", randomBillId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "worker-auth-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "mode", "CASH",
                                "amount", "10.00"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
