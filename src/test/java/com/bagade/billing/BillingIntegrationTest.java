package com.bagade.billing;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import com.bagade.billing.entity.AllocationAttributionMethod;
import com.bagade.billing.entity.BillStatus;
import com.bagade.billing.entity.BillingEventType;
import com.bagade.billing.entity.PaymentEntry;
import com.bagade.billing.repository.BillRepository;
import com.bagade.billing.repository.BillRevisionRepository;
import com.bagade.billing.repository.BillingEventRepository;
import com.bagade.billing.repository.CustomerRepository;
import com.bagade.billing.repository.PaymentEntryRepository;
import com.bagade.billing.repository.SaleAllocationRepository;
import com.bagade.billing.repository.SaleAllocationReversalRepository;
import com.bagade.billing.repository.SalesAdjustmentRepository;
import com.bagade.catalog.entity.Product;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.ProductRepository;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.common.idempotency.IdempotencyRecordRepository;
import com.bagade.inventory.entity.MovementType;
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

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingIntegrationTest {

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

    private JsonNode receiveDirectStock(
            SessionContext ctx,
            UUID vendorId,
            UUID skuId,
            LocalDate receivedDate,
            int quantity,
            String unitCost,
            String printedMrp,
            boolean generateInternalTag) throws Exception {
        var line = new LinkedHashMap<String, Object>();
        line.put("skuId", skuId.toString());
        line.put("quantity", quantity);
        line.put("unitCost", unitCost);
        if (printedMrp != null) {
            line.put("printedMrp", printedMrp);
        }
        line.put("generateInternalTag", generateInternalTag);

        MvcResult res = mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "receivedDate", receivedDate.toString(),
                                "lines", List.of(line)
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private UUID createCustomer(SessionContext ctx, String name, String contact) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        if (contact != null) {
            payload.put("contact", contact);
        }
        MvcResult res = mockMvc.perform(post("/api/v1/customers")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("customerId").asText());
    }

    @Test
    @DisplayName("Customer search/create, barcode scan lookup, cart preview (no deduction), and mixed CASH+UPI billing")
    void customerCrudCartPreviewAndMixedPaymentBillingFlow() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID customerId = createCustomer(worker, "  Asha Deshmukh  ", "  9876543210  ");
        createCustomer(worker, "Ramesh Kulkarni", "   ");

        mockMvc.perform(get("/api/v1/customers").param("q", "deshmukh").session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Asha Deshmukh"))
                .andExpect(jsonPath("$.items[0].contact").value("9876543210"));

        UUID catId = createCategory(worker, "Apparel");
        UUID vestProductId = createProduct(worker, catId, "Cotton Vest");
        UUID sareeProductId = createProduct(worker, catId, "Kanjeevaram Saree");
        UUID vestSkuId = createSku(worker, vestProductId, "VST-34", "34", "250.00");
        UUID sareeSkuId = createSku(worker, sareeProductId, "SAR-KNJ-1", null, "4500.00");

        mockMvc.perform(post("/api/v1/barcodes")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "0089012345",
                                "skuId", vestSkuId.toString()
                        ))))
                .andExpect(status().isCreated());

        UUID vendorId = createVendor(worker, "Standard Suppliers");
        UUID vestBatchId = UUID.fromString(receiveDirectStock(
                worker, vendorId, vestSkuId, LocalDate.of(2026, 11, 18), 4, "150.00", null, false
        ).get("lines").get(0).get("batchId").asText());

        JsonNode sareeReceipt = receiveDirectStock(
                worker, vendorId, sareeSkuId, LocalDate.of(2026, 11, 20), 2, "3200.00", "4500.00", true
        );
        UUID sareeBatchId = UUID.fromString(sareeReceipt.get("lines").get(0).get("batchId").asText());
        String internalTag = sareeReceipt.get("lines").get(0).get("internalBarcode").asText();

        // Barcode lookup confirms branded resolves to SKU (null batchId) and internal resolves to specific batch
        mockMvc.perform(get("/api/v1/barcodes/lookup").param("code", "0089012345").session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value(vestSkuId.toString()))
                .andExpect(jsonPath("$.batchId").doesNotExist());

        mockMvc.perform(get("/api/v1/barcodes/lookup").param("code", internalTag).session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value(sareeSkuId.toString()))
                .andExpect(jsonPath("$.batchId").value(sareeBatchId.toString()));

        // Preview cart without touching stock or creating bills
        List<Map<String, Object>> cartLines = List.of(
                Map.of("skuId", vestSkuId.toString(), "quantity", 2),
                Map.of(
                        "skuId", sareeSkuId.toString(),
                        "quantity", 1,
                        "batchId", sareeBatchId.toString(),
                        "discountAmount", "500.00"
                )
        );

        mockMvc.perform(post("/api/v1/bills/preview")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", cartLines))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value("5000.00"))
                .andExpect(jsonPath("$.discountTotal").value("500.00"))
                .andExpect(jsonPath("$.total").value("4500.00"))
                .andExpect(jsonPath("$.canCheckout").value(true))
                .andExpect(jsonPath("$.lines[0].availableQuantity").value(4))
                .andExpect(jsonPath("$.lines[0].sufficientStock").value(true))
                .andExpect(jsonPath("$.lines[1].availableQuantity").value(2))
                .andExpect(jsonPath("$.lines[1].sufficientStock").value(true));

        assertThat(batchBalanceRepository.sumSellableByBatchId(vestBatchId)).isEqualTo(4L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(sareeBatchId)).isEqualTo(2L);
        assertThat(billRepository.count()).isZero();

        // Post bill with CASH + UPI split payment
        MvcResult postRes = mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "bill-flow-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", cartLines,
                                "expectedTotal", "4500.00",
                                "customerId", customerId.toString(),
                                "payments", List.of(
                                        Map.of("mode", "CASH", "amount", "2000.00"),
                                        Map.of("mode", "UPI", "amount", "2500.00", "reference", "UPI-REF-7788")
                                )
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billNumber").value(org.hamcrest.Matchers.matchesPattern("BILL-\\d{6}")))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.subtotal").value("5000.00"))
                .andExpect(jsonPath("$.discountTotal").value("500.00"))
                .andExpect(jsonPath("$.total").value("4500.00"))
                .andExpect(jsonPath("$.paid").value("4500.00"))
                .andExpect(jsonPath("$.balanceDue").value("0.00"))
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.allocations[0].skuId").value(sareeSkuId.toString()))
                .andExpect(jsonPath("$.allocations[0].attributionMethod").value("EXPLICIT_BATCH"))
                .andExpect(jsonPath("$.allocations[0].capturedUnitCost").value("3200.00"))
                .andExpect(jsonPath("$.allocations[1].skuId").value(vestSkuId.toString()))
                .andExpect(jsonPath("$.allocations[1].attributionMethod").value("FIFO"))
                .andExpect(jsonPath("$.allocations[1].capturedUnitCost").value("150.00"))
                .andReturn();

        UUID billId = UUID.fromString(objectMapper.readTree(postRes.getResponse().getContentAsString()).get("billId").asText());

        assertThat(batchBalanceRepository.sumSellableByBatchId(vestBatchId)).isEqualTo(2L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(sareeBatchId)).isEqualTo(1L);
        assertThat(skuStockStateRepository.findById(vestSkuId).orElseThrow().getStockVersion()).isEqualTo(2L);
        assertThat(skuStockStateRepository.findById(sareeSkuId).orElseThrow().getStockVersion()).isEqualTo(2L);

        List<StockMovement> vestMovements = stockMovementRepository.findByBatchIdOrderByPostedAtAsc(vestBatchId);
        assertThat(vestMovements).hasSize(2);
        StockMovement vestSale = vestMovements.get(1);
        assertThat(vestSale.getMovementType()).isEqualTo(MovementType.SALE);
        assertThat(vestSale.getQuantityDelta()).isEqualTo(-2);
        assertThat(vestSale.getSourceSaleAllocation()).isNotNull();

        List<StockMovement> sareeMovements = stockMovementRepository.findByBatchIdOrderByPostedAtAsc(sareeBatchId);
        assertThat(sareeMovements).hasSize(2);
        assertThat(sareeMovements.get(1).getMovementType()).isEqualTo(MovementType.SALE);
        assertThat(sareeMovements.get(1).getQuantityDelta()).isEqualTo(-1);

        assertThat(paymentEntryRepository.findByBillIdOrderByPostedAtAscIdAsc(billId)).hasSize(2);
        assertThat(billingEventRepository.findByBillIdWithRevisionOrderByOccurredAtAsc(billId))
                .singleElement()
                .satisfies(ev -> assertThat(ev.getEventType()).isEqualTo(BillingEventType.ISSUE));
    }

    @Test
    @DisplayName("FIFO allocates oldest batch first across two batches and records cost and SALE movements for each slice")
    void fifoAllocatesAcrossMultipleBatchesAndCapturesUnitCosts() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Shirts");
        UUID productId = createProduct(worker, catId, "Linen Shirt");
        UUID skuId = createSku(worker, productId, "SHT-L", "L", "900.00");
        UUID vendorId = createVendor(worker, "Linen Weavers");

        UUID batch1 = UUID.fromString(receiveDirectStock(
                worker, vendorId, skuId, LocalDate.of(2026, 11, 10), 5, "500.00", "900.00", false
        ).get("lines").get(0).get("batchId").asText());
        UUID batch2 = UUID.fromString(receiveDirectStock(
                worker, vendorId, skuId, LocalDate.of(2026, 11, 15), 4, "560.00", "900.00", false
        ).get("lines").get(0).get("batchId").asText());

        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "fifo-multi-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 6)),
                                "expectedTotal", "5400.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "5400.00"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.allocations[0].batchId").value(batch1.toString()))
                .andExpect(jsonPath("$.allocations[0].quantity").value(5))
                .andExpect(jsonPath("$.allocations[0].capturedUnitCost").value("500.00"))
                .andExpect(jsonPath("$.allocations[0].attributionMethod").value("FIFO"))
                .andExpect(jsonPath("$.allocations[1].batchId").value(batch2.toString()))
                .andExpect(jsonPath("$.allocations[1].quantity").value(1))
                .andExpect(jsonPath("$.allocations[1].capturedUnitCost").value("560.00"))
                .andExpect(jsonPath("$.allocations[1].attributionMethod").value("FIFO"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batch1)).isZero();
        assertThat(batchBalanceRepository.sumSellableByBatchId(batch2)).isEqualTo(3L);

        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batch1).get(1).getQuantityDelta()).isEqualTo(-5);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batch2).get(1).getQuantityDelta()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Enforces batch selection for differing MRPs, price change 409, insufficient stock 409, repeated lines, and invalid discount")
    void enforcesBatchSelectionPriceChangedInsufficientStockRepeatedLinesAndDiscountValidation() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Kurtis");
        UUID kurtiProductId = createProduct(owner, catId, "Cotton Kurti");
        UUID scarfProductId = createProduct(owner, catId, "Dupatta");
        UUID kurtiSkuId = createSku(owner, kurtiProductId, "KRT-M", "M", "1000.00");
        UUID scarfSkuId = createSku(owner, scarfProductId, "DUP-1", null, "400.00");
        UUID vendorId = createVendor(owner, "Jaipur Prints");

        UUID batchA = UUID.fromString(receiveDirectStock(
                owner, vendorId, kurtiSkuId, LocalDate.of(2026, 11, 10), 2, "600.00", "1000.00", false
        ).get("lines").get(0).get("batchId").asText());
        UUID batchB = UUID.fromString(receiveDirectStock(
                owner, vendorId, kurtiSkuId, LocalDate.of(2026, 11, 12), 2, "720.00", "1200.00", true
        ).get("lines").get(0).get("batchId").asText());
        UUID scarfBatch = UUID.fromString(receiveDirectStock(
                owner, vendorId, scarfSkuId, LocalDate.of(2026, 11, 12), 5, "220.00", null, false
        ).get("lines").get(0).get("batchId").asText());

        // Differing MRPs on kurti -> preview flags BATCH_SELECTION_REQUIRED with selectableBatches
        mockMvc.perform(post("/api/v1/bills/preview")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", kurtiSkuId.toString(), "quantity", 1))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCheckout").value(false))
                .andExpect(jsonPath("$.lines[0].requiresBatchSelection").value(true))
                .andExpect(jsonPath("$.lines[0].selectableBatches.length()").value(2));

        // Posting without batchId returns 409 BATCH_SELECTION_REQUIRED
        mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "mrp-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", kurtiSkuId.toString(), "quantity", 1)),
                                "expectedTotal", "1000.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "1000.00"))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.selectableBatches.length()").value(2));

        // Explicit batch B succeeds with override MRP 1200.00 and leaves batch A untouched
        mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "mrp-explicit-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of(
                                        "skuId", kurtiSkuId.toString(),
                                        "quantity", 1,
                                        "batchId", batchB.toString(),
                                        "unitPriceOverride", "1200.00"
                                )),
                                "expectedTotal", "1200.00",
                                "payments", List.of(Map.of("mode", "UPI", "amount", "1200.00"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations[0].batchId").value(batchB.toString()))
                .andExpect(jsonPath("$.allocations[0].attributionMethod").value("EXPLICIT_BATCH"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(batchA)).isEqualTo(2L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchB)).isEqualTo(1L);

        // Catalog price changes between preview and post -> 409 PRICE_CHANGED with currentTotals
        mockMvc.perform(patch("/api/v1/skus/{id}", scarfSkuId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "defaultSellingPrice", "450.00"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "price-changed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", scarfSkuId.toString(), "quantity", 2)),
                                "expectedTotal", "800.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "800.00"))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.currentTotals.subtotal").value("900.00"))
                .andExpect(jsonPath("$.currentTotals.total").value("900.00"));

        // Repeated lines consuming same batch: 5 available, 3 + 3 exceeds availability
        mockMvc.perform(post("/api/v1/bills/preview")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(
                                        Map.of("skuId", scarfSkuId.toString(), "quantity", 3),
                                        Map.of("skuId", scarfSkuId.toString(), "quantity", 3)
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCheckout").value(false))
                .andExpect(jsonPath("$.lines[0].availableQuantity").value(5))
                .andExpect(jsonPath("$.lines[0].sufficientStock").value(true))
                .andExpect(jsonPath("$.lines[1].availableQuantity").value(2))
                .andExpect(jsonPath("$.lines[1].sufficientStock").value(false));

        // Posting earlier sufficient line + later insufficient line rolls back completely
        long billsBeforeFail = billRepository.count();
        mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "insufficient-rollback-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(
                                        Map.of("skuId", kurtiSkuId.toString(), "quantity", 1, "batchId", batchA.toString()),
                                        Map.of("skuId", scarfSkuId.toString(), "quantity", 6)
                                ),
                                "expectedTotal", "3700.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "3700.00"))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(billRepository.count()).isEqualTo(billsBeforeFail);
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchA)).isEqualTo(2L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(scarfBatch)).isEqualTo(5L);

        // Repeated lines within available stock (2 + 2 out of 5) succeed and leave 1
        mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "repeated-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(
                                        Map.of("skuId", scarfSkuId.toString(), "quantity", 2),
                                        Map.of("skuId", scarfSkuId.toString(), "quantity", 2, "discountAmount", "100.00")
                                ),
                                "expectedTotal", "1700.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "1700.00"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value("1800.00"))
                .andExpect(jsonPath("$.discountTotal").value("100.00"))
                .andExpect(jsonPath("$.total").value("1700.00"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(scarfBatch)).isEqualTo(1L);

        // Discount exceeding line subtotal -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "bad-discount-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of(
                                        "skuId", scarfSkuId.toString(),
                                        "quantity", 1,
                                        "discountAmount", "500.00"
                                )),
                                "expectedTotal", "0.00",
                                "payments", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(batchBalanceRepository.sumSellableByBatchId(scarfBatch)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Concurrent billing race for the last piece lets only one bill succeed and never goes negative")
    void concurrentBillingRaceForLastPieceAllowsOnlyOneSale() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID catId = createCategory(owner, "Bridal");
        UUID productId = createProduct(owner, catId, "Banarasi Saree");
        UUID skuId = createSku(owner, productId, "BNR-GOLD", null, "7500.00");
        UUID vendorId = createVendor(owner, "Varanasi Silk");
        UUID batchId = UUID.fromString(receiveDirectStock(
                owner, vendorId, skuId, LocalDate.of(2026, 11, 20), 1, "5200.00", null, false
        ).get("lines").get(0).get("batchId").asText());

        String payload = objectMapper.writeValueAsString(Map.of(
                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                "expectedTotal", "7500.00",
                "payments", List.of(Map.of("mode", "CASH", "amount", "7500.00"))
        ));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<MvcResult> task1 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/bills")
                                .session(owner.session())
                                .header("X-CSRF-Token", owner.csrfToken())
                                .header("Idempotency-Key", "race-owner-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();
            };
            Callable<MvcResult> task2 = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/bills")
                                .session(worker.session())
                                .header("X-CSRF-Token", worker.csrfToken())
                                .header("Idempotency-Key", "race-worker-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();
            };

            Future<MvcResult> f1 = pool.submit(task1);
            Future<MvcResult> f2 = pool.submit(task2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult r1 = f1.get(15, TimeUnit.SECONDS);
            MvcResult r2 = f2.get(15, TimeUnit.SECONDS);

            List<Integer> statuses = new ArrayList<>(List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus()));
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);

            MvcResult loser = r1.getResponse().getStatus() == 409 ? r1 : r2;
            assertThat(objectMapper.readTree(loser.getResponse().getContentAsString()).get("code").asText())
                    .isEqualTo("INSUFFICIENT_STOCK");

            assertThat(billRepository.count()).isEqualTo(1L);
            assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isZero();
            assertThat(skuStockStateRepository.findById(skuId).orElseThrow().getStockVersion()).isEqualTo(2L);
            assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId))
                    .extracting(StockMovement::getQuantityDelta)
                    .containsExactly(1, -1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Idempotency replay returns original 201 once without double deduction; payload mismatch returns 409 conflict")
    void idempotencyReplayAndConflict() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Accessories");
        UUID productId = createProduct(worker, catId, "Silk Stole");
        UUID skuId = createSku(worker, productId, "STL-1", null, "650.00");
        UUID vendorId = createVendor(worker, "Stole House");
        UUID batchId = UUID.fromString(receiveDirectStock(
                worker, vendorId, skuId, LocalDate.of(2026, 11, 20), 5, "400.00", null, false
        ).get("lines").get(0).get("batchId").asText());

        String body = objectMapper.writeValueAsString(Map.of(
                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 2)),
                "expectedTotal", "1300.00",
                "payments", List.of(Map.of("mode", "CASH", "amount", "1300.00"))
        ));

        // Missing Idempotency-Key -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        MvcResult first = mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "idem-bill-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());

        MvcResult replay = mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "idem-bill-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());

        assertThat(replayJson.get("billId").asText()).isEqualTo(firstJson.get("billId").asText());
        assertThat(replayJson.get("billNumber").asText()).isEqualTo(firstJson.get("billNumber").asText());
        assertThat(billRepository.count()).isEqualTo(1L);
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(3L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(2);

        // Changed payload with same key -> 409 IDEMPOTENCY_CONFLICT
        String modifiedBody = objectMapper.writeValueAsString(Map.of(
                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                "expectedTotal", "650.00",
                "payments", List.of(Map.of("mode", "CASH", "amount", "650.00"))
        ));
        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "idem-bill-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifiedBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("Supports partial and full credit with customer while rejecting anonymous credit, missing customer, and overpayment")
    void partialPaymentCreditRulesAndOverpaymentValidation() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID customerId = createCustomer(worker, "Meena Joshi", "9988776655");

        UUID catId = createCategory(worker, "Suiting");
        UUID productId = createProduct(worker, catId, "Cotton Kurta");
        UUID skuId = createSku(worker, productId, "KRT-XL", "XL", "1000.00");
        UUID vendorId = createVendor(worker, "Surat Looms");
        UUID batchId = UUID.fromString(receiveDirectStock(
                worker, vendorId, skuId, LocalDate.of(2026, 11, 20), 5, "650.00", null, false
        ).get("lines").get(0).get("batchId").asText());

        // Balance due without customerId -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "anon-credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                                "expectedTotal", "1000.00",
                                "payments", List.of(Map.of("mode", "CASH", "amount", "400.00"))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Overpayment -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "overpay-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                                "expectedTotal", "1000.00",
                                "customerId", customerId.toString(),
                                "payments", List.of(Map.of("mode", "CASH", "amount", "1100.00"))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Unknown customerId -> 404 RESOURCE_NOT_FOUND
        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "missing-cust-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                                "expectedTotal", "1000.00",
                                "customerId", UUID.randomUUID().toString(),
                                "payments", List.of()
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // Partial payment -> paid 400.00, balanceDue 600.00
        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "partial-credit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                                "expectedTotal", "1000.00",
                                "customerId", customerId.toString(),
                                "payments", List.of(Map.of("mode", "UPI", "amount", "400.00", "reference", "UTR-PART"))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paid").value("400.00"))
                .andExpect(jsonPath("$.balanceDue").value("600.00"));

        // Full credit -> paid 0.00, balanceDue 1000.00
        MvcResult creditRes = mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "full-credit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of("skuId", skuId.toString(), "quantity", 1)),
                                "expectedTotal", "1000.00",
                                "customerId", customerId.toString(),
                                "payments", List.of()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paid").value("0.00"))
                .andExpect(jsonPath("$.balanceDue").value("1000.00"))
                .andReturn();

        UUID creditBillId = UUID.fromString(objectMapper.readTree(creditRes.getResponse().getContentAsString()).get("billId").asText());
        assertThat(paymentEntryRepository.findByBillIdOrderByPostedAtAscIdAsc(creditBillId)).isEmpty();
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("Bill search, detail, snapshot immutability after catalog edits, and read-only JSON/80mm PDF printing")
    void searchDetailSnapshotImmutabilityAnd80mmJsonPdfPrinting() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID customerId = createCustomer(owner, "Kavita Sawant", "9123456780");
        UUID catId = createCategory(owner, "Festive");
        UUID productId = createProduct(owner, catId, "Paithani Saree");
        UUID skuId = createSku(owner, productId, "PTH-ROYAL", null, "8500.00");
        UUID vendorId = createVendor(owner, "Yeola Heritage");
        UUID batchId = UUID.fromString(receiveDirectStock(
                owner, vendorId, skuId, LocalDate.of(2026, 11, 19), 3, "6100.00", null, false
        ).get("lines").get(0).get("batchId").asText());

        MvcResult billRes = mockMvc.perform(post("/api/v1/bills")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "print-snapshot-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of(
                                        "skuId", skuId.toString(),
                                        "quantity", 1,
                                        "discountAmount", "500.00"
                                )),
                                "expectedTotal", "8000.00",
                                "customerId", customerId.toString(),
                                "payments", List.of(Map.of("mode", "CASH", "amount", "5000.00"))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(billRes.getResponse().getContentAsString());
        UUID billId = UUID.fromString(created.get("billId").asText());
        String billNumber = created.get("billNumber").asText();
        LocalDate issueDate = LocalDate.parse(created.get("issueDate").asText());

        // Mutate product name and SKU selling price AFTER bill is issued
        Product product = productRepository.findById(productId).orElseThrow();
        product.setName("Renamed Paithani V2");
        productRepository.save(product);

        mockMvc.perform(patch("/api/v1/skus/{id}", skuId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "defaultSellingPrice", "9999.00"
                        ))))
                .andExpect(status().isOk());

        // Search bills by billNumber, inclusive from/to, and customerId
        mockMvc.perform(get("/api/v1/bills")
                        .param("billNumber", billNumber.substring(5))
                        .param("from", issueDate.toString())
                        .param("to", issueDate.toString())
                        .param("customerId", customerId.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].billId").value(billId.toString()))
                .andExpect(jsonPath("$.items[0].billNumber").value(billNumber))
                .andExpect(jsonPath("$.items[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.items[0].total").value("8000.00"))
                .andExpect(jsonPath("$.items[0].paid").value("5000.00"))
                .andExpect(jsonPath("$.items[0].balanceDue").value("3000.00"));

        // Invalid date range from > to -> 400 VALIDATION_ERROR
        mockMvc.perform(get("/api/v1/bills")
                        .param("from", "2026-11-30")
                        .param("to", "2026-11-01")
                        .session(owner.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Bill detail still shows original historical snapshot
        mockMvc.perform(get("/api/v1/bills/{id}", billId).session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billNumber").value(billNumber))
                .andExpect(jsonPath("$.currentRevisionNumber").value(1))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.customerName").value("Kavita Sawant"))
                .andExpect(jsonPath("$.lines[0].productName").value("Paithani Saree"))
                .andExpect(jsonPath("$.lines[0].unitSellingPrice").value("8500.00"))
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].returnableQuantity").value(1))
                .andExpect(jsonPath("$.payments[0].mode").value("CASH"))
                .andExpect(jsonPath("$.payments[0].amount").value("5000.00"))
                .andExpect(jsonPath("$.revisions[0].revisionNumber").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("ISSUE"));

        // Print JSON (default format and explicit revision=1) preserves snapshots
        mockMvc.perform(get("/api/v1/bills/{id}/print", billId)
                        .param("revision", "1")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shopName").value("Bagade Clothing"))
                .andExpect(jsonPath("$.receiptProfile").value("80mm"))
                .andExpect(jsonPath("$.billNumber").value(billNumber))
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value("Paithani Saree"))
                .andExpect(jsonPath("$.lines[0].unitSellingPrice").value("8500.00"))
                .andExpect(jsonPath("$.total").value("8000.00"))
                .andExpect(jsonPath("$.paid").value("5000.00"))
                .andExpect(jsonPath("$.balanceDue").value("3000.00"));

        // Print PDF renders valid 80mm thermal PDF with snapshot text
        MvcResult pdfResult = mockMvc.perform(get("/api/v1/bills/{id}/print", billId)
                        .param("format", "pdf")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();

        byte[] pdfBytes = pdfResult.getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isBetween(225f, 228f);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Bagade Clothing");
            assertThat(text).contains(billNumber);
            assertThat(text).contains("Paithani Saree");
            assertThat(text).contains("8500.00");
            assertThat(text).contains("8000.00");
            assertThat(text).contains("5000.00");
            assertThat(text).contains("3000.00");
            assertThat(text).doesNotContain("Renamed Paithani V2");
        }

        // Printing is read-only: stock and payment counts unchanged
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(2L);
        assertThat(paymentEntryRepository.findByBillIdOrderByPostedAtAscIdAsc(billId)).hasSize(1);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(2);

        // Print validation / 404 checks
        mockMvc.perform(get("/api/v1/bills/{id}/print", billId)
                        .param("format", "xml")
                        .session(owner.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/bills/{id}/print", billId)
                        .param("revision", "99")
                        .session(owner.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/bills/{id}", UUID.randomUUID()).session(owner.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Enforces authentication and CSRF protection while allowing both OWNER and WORKER access")
    void securityEnforcesAuthenticationAndCsrfForBillingEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/bills"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        SessionContext worker = signIn("worker1", "WorkerPass123!");
        SessionContext owner = signIn("owner1", "OwnerPass123!");

        mockMvc.perform(post("/api/v1/customers")
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "No CSRF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/bills/preview")
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/bills")
                        .session(worker.session())
                        .header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(get("/api/v1/bills").session(worker.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/bills").session(owner.session()))
                .andExpect(status().isOk());
    }
}
