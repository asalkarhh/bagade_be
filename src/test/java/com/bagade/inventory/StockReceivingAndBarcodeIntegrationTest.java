package com.bagade.inventory;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.ProductRepository;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.common.error.BarcodeConflictException;
import com.bagade.common.error.PoQuantityExceededException;
import com.bagade.common.error.StaleVersionException;
import com.bagade.common.idempotency.IdempotencyRecordRepository;
import com.bagade.inventory.dto.BatchLabelResponse;
import com.bagade.inventory.dto.CreateStockReceiptLineRequest;
import com.bagade.inventory.dto.CreateStockReceiptRequest;
import com.bagade.inventory.dto.RegisterBarcodeRequest;
import com.bagade.inventory.dto.StockReceiptResponse;
import com.bagade.inventory.entity.MovementType;
import com.bagade.inventory.entity.StockCondition;
import com.bagade.inventory.entity.StockMovement;
import com.bagade.inventory.repository.BarcodeMappingRepository;
import com.bagade.inventory.repository.BatchBalanceRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import com.bagade.inventory.repository.StockBatchRepository;
import com.bagade.inventory.repository.StockMovementRepository;
import com.bagade.inventory.repository.StockReceiptRepository;
import com.bagade.inventory.service.BarcodeService;
import com.bagade.inventory.service.BatchLabelService;
import com.bagade.inventory.service.StockReceiptService;
import com.bagade.purchasing.dto.CancelPurchaseOrderRemainderRequest;
import com.bagade.purchasing.entity.PurchaseOrder;
import com.bagade.purchasing.entity.PurchaseOrderStatus;
import com.bagade.purchasing.repository.PurchaseOrderCancellationRepository;
import com.bagade.purchasing.repository.PurchaseOrderLineRepository;
import com.bagade.purchasing.repository.PurchaseOrderRepository;
import com.bagade.purchasing.repository.VendorRepository;
import com.bagade.purchasing.service.PurchaseOrderService;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockReceivingAndBarcodeIntegrationTest {

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
    private PurchaseOrderLineRepository purchaseOrderLineRepository;

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
    private StockReceiptService stockReceiptService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private BarcodeService barcodeService;

    @Autowired
    private BatchLabelService batchLabelService;

    private UserAccount ownerUser;
    private UserAccount workerUser;

    @BeforeEach
    void setUp() {
        cleanAll();

        ownerUser = userAccountRepository.save(new UserAccount(
                "owner1",
                "Shop Owner",
                passwordEncoder.encode("OwnerPass123!"),
                UserRole.OWNER,
                true
        ));

        workerUser = userAccountRepository.save(new UserAccount(
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

    private UUID createCategory(SessionContext ctx, String name, UUID parentId) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        if (parentId != null) {
            body.put("parentId", parentId.toString());
        }
        MvcResult res = mockMvc.perform(post("/api/v1/categories")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
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

    private JsonNode createPo(SessionContext ctx, UUID vendorId, UUID skuId, int qty) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", skuId.toString(),
                                        "orderedQuantity", qty,
                                        "agreedUnitCost", "3200.00"
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Worker receives 8 then 4 of 12 silk sarees; PO closes, movements/batches/stock update on inventory & catalog")
    void partialAndFinalPurchaseOrderReceiptsUpdatePoAndStockBalances() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID sareesId = createCategory(worker, "Sarees", null);
        UUID silkSareesId = createCategory(worker, "Silk Sarees", sareesId);
        UUID productId = createProduct(worker, silkSareesId, "Kanchipuram Bridal Silk");
        UUID skuId = createSku(worker, productId, "SAR-KAN-01", null, "5500.00");

        // Verify newly created SKU automatically initialized sku_stock_state with stockVersion = 0
        assertThat(skuStockStateRepository.findById(skuId))
                .isPresent()
                .get()
                .satisfies(state -> assertThat(state.getStockVersion()).isZero());

        UUID vendorId = createVendor(worker, "Kanchipuram Looms");
        JsonNode po = createPo(worker, vendorId, skuId, 12);
        UUID poId = UUID.fromString(po.get("poId").asText());
        UUID poLineId = UUID.fromString(po.get("lines").get(0).get("poLineId").asText());

        // Receipt 1: 8 sarees with printedMrp and generateInternalTag=true
        MvcResult rcp1Res = mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rcp-saree-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "receivedDate", "2026-11-25",
                                "poId", poId.toString(),
                                "vendorInvoiceRef", "INV-2026-01",
                                "lines", List.of(Map.of(
                                        "skuId", skuId.toString(),
                                        "quantity", 8,
                                        "unitCost", "3200.00",
                                        "printedMrp", "5800.00",
                                        "poLineId", poLineId.toString(),
                                        "generateInternalTag", true
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptId").isNotEmpty())
                .andExpect(jsonPath("$.receiptNumber").value(org.hamcrest.Matchers.startsWith("RCP-")))
                .andExpect(jsonPath("$.postedAt").isNotEmpty())
                .andExpect(jsonPath("$.poStatus").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.poTotalOpenQuantity").value(4))
                .andExpect(jsonPath("$.poVersion").value(1))
                .andExpect(jsonPath("$.lines[0].batchId").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].quantity").value(8))
                .andExpect(jsonPath("$.lines[0].poLineOpenQuantity").value(4))
                .andExpect(jsonPath("$.lines[0].unitCost").value("3200.00"))
                .andExpect(jsonPath("$.lines[0].printedMrp").value("5800.00"))
                .andExpect(jsonPath("$.lines[0].internalBarcode").value(org.hamcrest.Matchers.startsWith("BGD")))
                .andReturn();

        JsonNode rcp1Json = objectMapper.readTree(rcp1Res.getResponse().getContentAsString());
        UUID receipt1Id = UUID.fromString(rcp1Json.get("receiptId").asText());
        UUID batch1Id = UUID.fromString(rcp1Json.get("lines").get(0).get("batchId").asText());
        String internalCode1 = rcp1Json.get("lines").get(0).get("internalBarcode").asText();

        // GET /api/v1/stock-receipts/{id} returns full header and lines
        mockMvc.perform(get("/api/v1/stock-receipts/{id}", receipt1Id).session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId").value(receipt1Id.toString()))
                .andExpect(jsonPath("$.vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.vendorName").value("Kanchipuram Looms"))
                .andExpect(jsonPath("$.poId").value(poId.toString()))
                .andExpect(jsonPath("$.poNumber").value(po.get("poNumber").asText()))
                .andExpect(jsonPath("$.vendorInvoiceRef").value("INV-2026-01"))
                .andExpect(jsonPath("$.receivedDate").value("2026-11-25"))
                .andExpect(jsonPath("$.lines[0].skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.lines[0].batchId").value(batch1Id.toString()))
                .andExpect(jsonPath("$.lines[0].quantity").value(8))
                .andExpect(jsonPath("$.lines[0].unitCost").value("3200.00"))
                .andExpect(jsonPath("$.lines[0].printedMrp").value("5800.00"))
                .andExpect(jsonPath("$.lines[0].internalBarcode").value(internalCode1));

        // Verify inventory and catalog endpoints show 8 sellable pieces and stockVersion = 1
        mockMvc.perform(get("/api/v1/inventory")
                        .param("categoryId", sareesId.toString())
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(8))
                .andExpect(jsonPath("$.items[0].nonSellableQuantity").value(0))
                .andExpect(jsonPath("$.items[0].stockVersion").value(1))
                .andExpect(jsonPath("$.items[0].batches.length()").value(1))
                .andExpect(jsonPath("$.items[0].batches[0].batchId").value(batch1Id.toString()))
                .andExpect(jsonPath("$.items[0].batches[0].internalBarcode").value(internalCode1));

        mockMvc.perform(get("/api/v1/skus")
                        .param("q", "SAR-KAN-01")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(8));

        // Receipt 2: remaining 4 sarees without printedMrp -> PO closes
        var line2Payload = new LinkedHashMap<String, Object>();
        line2Payload.put("skuId", skuId.toString());
        line2Payload.put("quantity", 4);
        line2Payload.put("unitCost", "3150.00");
        line2Payload.put("printedMrp", null);
        line2Payload.put("poLineId", poLineId.toString());

        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rcp-saree-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "receivedDate", "2026-11-26",
                                "poId", poId.toString(),
                                "lines", List.of(line2Payload)
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poStatus").value("CLOSED"))
                .andExpect(jsonPath("$.poTotalOpenQuantity").value(0))
                .andExpect(jsonPath("$.poVersion").value(2))
                .andExpect(jsonPath("$.lines[0].printedMrp").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].internalBarcode").value(nullValue()));

        // Verify BE03 PO detail reflects closed order with 12 received and 0 open
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", poId).session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.totalOpenQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].orderedQuantity").value(12))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(12))
                .andExpect(jsonPath("$.lines[0].cancelledQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].openQuantity").value(0));

        // Inventory & Catalog reflect 12 total sellable across 2 batches and stockVersion = 2
        mockMvc.perform(get("/api/v1/inventory")
                        .param("skuId", skuId.toString())
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(12))
                .andExpect(jsonPath("$.items[0].stockVersion").value(2))
                .andExpect(jsonPath("$.items[0].batches.length()").value(2));

        mockMvc.perform(get("/api/v1/skus")
                        .param("q", "SAR-KAN-01")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(12));

        // Verify append-only stock_movement entries
        List<StockMovement> batch1Movements = stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batch1Id);
        assertThat(batch1Movements).hasSize(1);
        assertThat(batch1Movements.get(0).getMovementType()).isEqualTo(MovementType.RECEIPT);
        assertThat(batch1Movements.get(0).getStockCondition()).isEqualTo(StockCondition.SELLABLE);
        assertThat(batch1Movements.get(0).getQuantityDelta()).isEqualTo(8);
    }

    @Test
    @DisplayName("Direct purchase succeeds without PO; mismatched vendor/SKU/PO line and over-receipt leave all state unchanged")
    void directPurchaseAndMismatchOverReceiptRollback() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");

        UUID catId = createCategory(owner, "Kurtis", null);
        UUID prodId = createProduct(owner, catId, "Cotton Kurti");
        UUID sku1Id = createSku(owner, prodId, "KUR-M", "M", "899.00");
        UUID sku2Id = createSku(owner, prodId, "KUR-L", "L", "899.00");

        UUID vendor1Id = createVendor(owner, "Vendor One");
        UUID vendor2Id = createVendor(owner, "Vendor Two");

        // Direct purchase without PO
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "direct-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendor1Id.toString(),
                                "receivedDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", sku1Id.toString(),
                                        "quantity", 5,
                                        "unitCost", "450.00"
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poId").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].quantity").value(5));

        JsonNode po1 = createPo(owner, vendor1Id, sku1Id, 6);
        UUID po1Id = UUID.fromString(po1.get("poId").asText());
        UUID po1LineId = UUID.fromString(po1.get("lines").get(0).get("poLineId").asText());

        JsonNode po2 = createPo(owner, vendor2Id, sku2Id, 6);
        UUID po2LineId = UUID.fromString(po2.get("lines").get(0).get("poLineId").asText());

        long receiptsBefore = stockReceiptRepository.count();
        long batchesBefore = stockBatchRepository.count();
        long movementsBefore = stockMovementRepository.count();

        // 1. Vendor mismatch against PO -> 409 PO_LINE_MISMATCH
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "bad-vendor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendor2Id.toString(),
                                "receivedDate", "2026-11-25",
                                "poId", po1Id.toString(),
                                "lines", List.of(Map.of(
                                        "skuId", sku1Id.toString(),
                                        "quantity", 2,
                                        "unitCost", "450.00",
                                        "poLineId", po1LineId.toString()
                                ))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_LINE_MISMATCH"));

        // 2. SKU mismatch against PO line -> 409 PO_LINE_MISMATCH
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "bad-sku")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendor1Id.toString(),
                                "receivedDate", "2026-11-25",
                                "poId", po1Id.toString(),
                                "lines", List.of(Map.of(
                                        "skuId", sku2Id.toString(),
                                        "quantity", 2,
                                        "unitCost", "450.00",
                                        "poLineId", po1LineId.toString()
                                ))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_LINE_MISMATCH"));

        // 3. PO line belonging to another order -> 409 PO_LINE_MISMATCH
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "bad-poline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendor1Id.toString(),
                                "receivedDate", "2026-11-25",
                                "poId", po1Id.toString(),
                                "lines", List.of(Map.of(
                                        "skuId", sku2Id.toString(),
                                        "quantity", 2,
                                        "unitCost", "450.00",
                                        "poLineId", po2LineId.toString()
                                ))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_LINE_MISMATCH"));

        // 4. Missing poLineId on linked receipt -> 409 PO_LINE_MISMATCH
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "missing-poline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendor1Id.toString(),
                                "receivedDate", "2026-11-25",
                                "poId", po1Id.toString(),
                                "lines", List.of(Map.of(
                                        "skuId", sku1Id.toString(),
                                        "quantity", 2,
                                        "unitCost", "450.00"
                                ))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_LINE_MISMATCH"));

        // 5. Direct receipt with poLineId -> 409 PO_LINE_MISMATCH
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "direct-with-poline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendor1Id.toString(),
                                "receivedDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", sku1Id.toString(),
                                        "quantity", 2,
                                        "unitCost", "450.00",
                                        "poLineId", po1LineId.toString()
                                ))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_LINE_MISMATCH"));

        // 6. Over-receipt split across two receipt lines referencing the same PO line (4 + 3 = 7 > 6) -> 409 PO_QUANTITY_EXCEEDED
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "over-receipt-split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendor1Id.toString(),
                                "receivedDate", "2026-11-25",
                                "poId", po1Id.toString(),
                                "lines", List.of(
                                        Map.of("skuId", sku1Id.toString(), "quantity", 4, "unitCost", "450.00", "poLineId", po1LineId.toString()),
                                        Map.of("skuId", sku1Id.toString(), "quantity", 3, "unitCost", "460.00", "poLineId", po1LineId.toString())
                                )
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_QUANTITY_EXCEEDED"));

        // Verify zero side effects from all failed attempts
        assertThat(stockReceiptRepository.count()).isEqualTo(receiptsBefore);
        assertThat(stockBatchRepository.count()).isEqualTo(batchesBefore);
        assertThat(stockMovementRepository.count()).isEqualTo(movementsBefore);
        assertThat(purchaseOrderLineRepository.findById(po1LineId).orElseThrow().getOpenQuantity()).isEqualTo(6);
        assertThat(skuStockStateRepository.findById(sku1Id).orElseThrow().getStockVersion()).isEqualTo(1L);
        assertThat(skuStockStateRepository.findById(sku2Id).orElseThrow().getStockVersion()).isZero();
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L); // Only "direct-1"
    }

    @Test
    @DisplayName("Concurrent receipts and receipt vs PO cancellation race never double-consume open PO quantities")
    void concurrentReceiptsAndCancellationRacePreservePoInvariants() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Sarees", null);
        UUID prodId = createProduct(owner, catId, "Cotton Saree");
        UUID skuId = createSku(owner, prodId, "SAR-COT-1", null, "1200.00");
        UUID vendorId = createVendor(owner, "Bengal Looms");

        // Race 1: Two concurrent receipts each requesting 7 on a PO for 10
        JsonNode po1 = createPo(owner, vendorId, skuId, 10);
        UUID po1Id = UUID.fromString(po1.get("poId").asText());
        UUID po1LineId = UUID.fromString(po1.get("lines").get(0).get("poLineId").asText());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready1 = new CountDownLatch(2);
        CountDownLatch start1 = new CountDownLatch(1);
        List<Future<String>> receiptFutures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            final String key = "race-rcp-" + i;
            receiptFutures.add(executor.submit((Callable<String>) () -> {
                ready1.countDown();
                start1.await(5, TimeUnit.SECONDS);
                try {
                    stockReceiptService.postReceipt(
                            new CreateStockReceiptRequest(
                                    vendorId,
                                    LocalDate.of(2026, 11, 25),
                                    po1Id,
                                    null,
                                    List.of(new CreateStockReceiptLineRequest(
                                            skuId, 7, "700.00", null, po1LineId, false
                                    ))
                            ),
                            key,
                            ownerUser.getId()
                    );
                    return "OK";
                } catch (PoQuantityExceededException ex) {
                    return "EXCEEDED";
                }
            }));
        }

        assertThat(ready1.await(5, TimeUnit.SECONDS)).isTrue();
        start1.countDown();

        List<String> outcomes1 = new ArrayList<>();
        for (Future<String> f : receiptFutures) {
            outcomes1.add(f.get(10, TimeUnit.SECONDS));
        }
        assertThat(outcomes1).containsExactlyInAnyOrder("OK", "EXCEEDED");
        var line1After = purchaseOrderLineRepository.findById(po1LineId).orElseThrow();
        assertThat(line1After.getReceivedQuantity()).isEqualTo(7);
        assertThat(line1After.getOpenQuantity()).isEqualTo(3);

        // Race 2: Receipt of 6 racing with BE03 remainder cancellation (expectedVersion=0) on a PO for 10
        JsonNode po2 = createPo(owner, vendorId, skuId, 10);
        UUID po2Id = UUID.fromString(po2.get("poId").asText());
        UUID po2LineId = UUID.fromString(po2.get("lines").get(0).get("poLineId").asText());

        CountDownLatch ready2 = new CountDownLatch(2);
        CountDownLatch start2 = new CountDownLatch(1);

        Future<String> rcpFuture = executor.submit(() -> {
            ready2.countDown();
            start2.await(5, TimeUnit.SECONDS);
            try {
                stockReceiptService.postReceipt(
                        new CreateStockReceiptRequest(
                                vendorId,
                                LocalDate.of(2026, 11, 25),
                                po2Id,
                                null,
                                List.of(new CreateStockReceiptLineRequest(
                                        skuId, 6, "700.00", null, po2LineId, false
                                ))
                        ),
                        "race-vs-cancel-rcp",
                        ownerUser.getId()
                );
                return "RCP_OK";
            } catch (PoQuantityExceededException ex) {
                return "RCP_EXCEEDED";
            }
        });

        Future<String> cancelFuture = executor.submit(() -> {
            ready2.countDown();
            start2.await(5, TimeUnit.SECONDS);
            try {
                purchaseOrderService.cancelRemainder(
                        po2Id,
                        "race-vs-cancel-rem",
                        new CancelPurchaseOrderRemainderRequest(0L, "Cancel race", null),
                        ownerUser.getId()
                );
                return "CANCEL_OK";
            } catch (StaleVersionException ex) {
                return "CANCEL_STALE";
            }
        });

        assertThat(ready2.await(5, TimeUnit.SECONDS)).isTrue();
        start2.countDown();

        String rcpOutcome = rcpFuture.get(10, TimeUnit.SECONDS);
        String cancelOutcome = cancelFuture.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        PurchaseOrder po2After = purchaseOrderRepository.findById(po2Id).orElseThrow();
        var line2After = purchaseOrderLineRepository.findById(po2LineId).orElseThrow();
        assertThat(line2After.getReceivedQuantity() + line2After.getCancelledQuantity()).isLessThanOrEqualTo(10);

        if ("RCP_OK".equals(rcpOutcome)) {
            assertThat(cancelOutcome).isEqualTo("CANCEL_STALE");
            assertThat(line2After.getReceivedQuantity()).isEqualTo(6);
            assertThat(line2After.getCancelledQuantity()).isZero();
            assertThat(po2After.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        } else {
            assertThat(rcpOutcome).isEqualTo("RCP_EXCEEDED");
            assertThat(cancelOutcome).isEqualTo("CANCEL_OK");
            assertThat(line2After.getReceivedQuantity()).isZero();
            assertThat(line2After.getCancelledQuantity()).isEqualTo(10);
            assertThat(po2After.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        }
    }

    @Test
    @DisplayName("Retrying a committed receipt returns the identical response; reusing key with different input returns 409")
    void receiptIdempotencyReplayAndConflict() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Shirts", null);
        UUID prodId = createProduct(worker, catId, "Linen Shirt");
        UUID skuId = createSku(worker, prodId, "SH-LIN-M", "M", "1499.00");
        UUID vendorId = createVendor(worker, "Linen Mills");

        String body = objectMapper.writeValueAsString(Map.of(
                "vendorId", vendorId.toString(),
                "receivedDate", "2026-11-25",
                "lines", List.of(Map.of(
                        "skuId", skuId.toString(),
                        "quantity", 10,
                        "unitCost", "800.00",
                        "generateInternalTag", true
                ))
        ));

        MvcResult first = mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rcp-idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult replay = mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rcp-idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(stockReceiptRepository.count()).isEqualTo(1);
        assertThat(stockBatchRepository.count()).isEqualTo(1);
        assertThat(stockMovementRepository.count()).isEqualTo(1);

        // Changed quantity under same Idempotency-Key -> 409 IDEMPOTENCY_CONFLICT
        String modifiedBody = objectMapper.writeValueAsString(Map.of(
                "vendorId", vendorId.toString(),
                "receivedDate", "2026-11-25",
                "lines", List.of(Map.of(
                        "skuId", skuId.toString(),
                        "quantity", 11,
                        "unitCost", "800.00"
                ))
        ));
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "rcp-idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifiedBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("Brand barcodes preserve leading zeros, enforce conflicts concurrently, and trigger batch selection on differing MRPs")
    void brandBarcodeRegistrationConflictsAndMrpBatchSelection() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID catId = createCategory(worker, "Innerwear", null);
        UUID prodId = createProduct(worker, catId, "Cotton Vest");
        UUID sku32 = createSku(worker, prodId, "VES-32", "32", "220.00");
        UUID sku34 = createSku(worker, prodId, "VES-34", "34", "240.00");
        UUID vendorId = createVendor(worker, "Comfort Mills");

        // Unmapped code returns 404 BARCODE_NOT_FOUND
        mockMvc.perform(get("/api/v1/barcodes/lookup")
                        .param("code", "00009999")
                        .session(worker.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BARCODE_NOT_FOUND"));

        // Register brand barcode with leading zeros -> 201
        mockMvc.perform(post("/api/v1/barcodes")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "00890123456",
                                "skuId", sku32.toString()
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("00890123456"))
                .andExpect(jsonPath("$.sourceType").value("BRAND"))
                .andExpect(jsonPath("$.skuId").value(sku32.toString()))
                .andExpect(jsonPath("$.batchId").value(nullValue()));

        // Re-registering same brand code and SKU returns 200 OK
        mockMvc.perform(post("/api/v1/barcodes")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "00890123456",
                                "skuId", sku32.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00890123456"));

        // Registering same brand code to another SKU returns 409 BARCODE_CONFLICT
        mockMvc.perform(post("/api/v1/barcodes")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "00890123456",
                                "skuId", sku34.toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BARCODE_CONFLICT"));

        // Receive batch 1 (MRP 220.00) with internal tag, and verify internal tag cannot be registered as brand barcode
        StockReceiptResponse rcp1 = stockReceiptService.postReceipt(
                new CreateStockReceiptRequest(
                        vendorId, LocalDate.of(2026, 11, 25), null, null,
                        List.of(new CreateStockReceiptLineRequest(sku32, 4, "120.00", "220.00", null, true))
                ),
                "brand-b1",
                workerUser.getId()
        );
        String internalCode = rcp1.lines().get(0).internalBarcode();

        mockMvc.perform(post("/api/v1/barcodes")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", internalCode,
                                "skuId", sku32.toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BARCODE_CONFLICT"));

        // Single MRP across sellable batches -> requiresBatchSelection = false
        mockMvc.perform(get("/api/v1/barcodes/lookup")
                        .param("code", "00890123456")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00890123456"))
                .andExpect(jsonPath("$.mappingType").value("BRAND"))
                .andExpect(jsonPath("$.skuId").value(sku32.toString()))
                .andExpect(jsonPath("$.name").value("Cotton Vest"))
                .andExpect(jsonPath("$.size").value("32"))
                .andExpect(jsonPath("$.defaultSellingPrice").value("220.00"))
                .andExpect(jsonPath("$.sellableQuantity").value(4))
                .andExpect(jsonPath("$.requiresBatchSelection").value(false));

        // Receive batch 2 with DIFFERENT printed MRP (250.00) -> lookup triggers requiresBatchSelection = true
        StockReceiptResponse rcp2 = stockReceiptService.postReceipt(
                new CreateStockReceiptRequest(
                        vendorId, LocalDate.of(2026, 11, 26), null, null,
                        List.of(new CreateStockReceiptLineRequest(sku32, 6, "135.00", "250.00", null, false))
                ),
                "brand-b2",
                workerUser.getId()
        );
        UUID batch1Id = rcp1.lines().get(0).batchId();
        UUID batch2Id = rcp2.lines().get(0).batchId();

        mockMvc.perform(get("/api/v1/barcodes/lookup")
                        .param("code", "00890123456")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00890123456"))
                .andExpect(jsonPath("$.sellableQuantity").value(10))
                .andExpect(jsonPath("$.batchId").value(nullValue()))
                .andExpect(jsonPath("$.printedMrp").value(nullValue()))
                .andExpect(jsonPath("$.requiresBatchSelection").value(true))
                .andExpect(jsonPath("$.eligibleBatches.length()").value(2))
                .andExpect(jsonPath("$.eligibleBatches[0].batchId").value(batch1Id.toString()))
                .andExpect(jsonPath("$.eligibleBatches[0].printedMrp").value("220.00"))
                .andExpect(jsonPath("$.eligibleBatches[0].sellableQuantity").value(4))
                .andExpect(jsonPath("$.eligibleBatches[1].batchId").value(batch2Id.toString()))
                .andExpect(jsonPath("$.eligibleBatches[1].printedMrp").value("250.00"))
                .andExpect(jsonPath("$.eligibleBatches[1].sellableQuantity").value(6));

        // Concurrent brand barcode registration for two different SKUs with the same new code -> 1 succeeds, 1 gets BarcodeConflictException
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<UUID> competingSkus = List.of(sku32, sku34);
        List<Future<String>> futures = new ArrayList<>();

        for (UUID targetSku : competingSkus) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    barcodeService.registerBrandBarcode(
                            new RegisterBarcodeRequest("0011223344", targetSku),
                            workerUser.getId()
                    );
                    return "OK";
                } catch (BarcodeConflictException ex) {
                    return "CONFLICT";
                }
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get(10, TimeUnit.SECONDS));
        }
        executor.shutdownNow();
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "CONFLICT");
    }

    @Test
    @DisplayName("Generating or reprinting internal batch labels preserves barcode, reflects defaultSellingPrice, and leaves stock unchanged")
    void internalLabelGenerationAndReprintPreserveCodeAndStock() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Kurtis", null);
        UUID prodId = createProduct(owner, catId, "Silk Kurti");
        UUID skuId = createSku(owner, prodId, "KUR-SLK-L", "L", "1499.00");
        UUID vendorId = createVendor(owner, "Jaipur Prints");

        StockReceiptResponse receipt = stockReceiptService.postReceipt(
                new CreateStockReceiptRequest(
                        vendorId, LocalDate.of(2026, 11, 25), null, null,
                        List.of(new CreateStockReceiptLineRequest(skuId, 3, "900.00", "1799.00", null, false))
                ),
                "label-rcp-1",
                ownerUser.getId()
        );
        UUID batchId = receipt.lines().get(0).batchId();

        // GET before generating returns 404 LABEL_NOT_FOUND
        mockMvc.perform(get("/api/v1/stock-batches/{id}/label", batchId).session(owner.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LABEL_NOT_FOUND"));

        // Concurrent POST label calls both return the exact same internal barcode
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BatchLabelResponse>> labelFutures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            labelFutures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return batchLabelService.generateOrGetLabel(batchId, ownerUser.getId());
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        BatchLabelResponse label1 = labelFutures.get(0).get(10, TimeUnit.SECONDS);
        BatchLabelResponse label2 = labelFutures.get(1).get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(label1).isEqualTo(label2);
        String generatedCode = label1.code();
        assertThat(generatedCode).startsWith("BGD");

        // Repeated POST via HTTP returns the same code and printable label fields
        mockMvc.perform(post("/api/v1/stock-batches/{id}/label", batchId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.code").value(generatedCode))
                .andExpect(jsonPath("$.shopName").value("Bagade Clothing"))
                .andExpect(jsonPath("$.productName").value("Silk Kurti"))
                .andExpect(jsonPath("$.size").value("L"))
                .andExpect(jsonPath("$.displayPrice").value("1499.00"))
                .andExpect(jsonPath("$.barcode").value(generatedCode))
                .andExpect(jsonPath("$.textCode").value(generatedCode));

        // Update SKU defaultSellingPrice; GET label reflects current defaultSellingPrice without altering stock
        mockMvc.perform(patch("/api/v1/skus/{id}", skuId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "defaultSellingPrice", "1599.00"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/stock-batches/{id}/label", batchId).session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayPrice").value("1599.00"))
                .andExpect(jsonPath("$.barcode").value(generatedCode))
                .andExpect(jsonPath("$.textCode").value(generatedCode));

        // Internal barcode lookup returns batchId, printedMrp, and batchSellableQuantity
        mockMvc.perform(get("/api/v1/barcodes/lookup")
                        .param("code", generatedCode)
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(generatedCode))
                .andExpect(jsonPath("$.mappingType").value("INTERNAL"))
                .andExpect(jsonPath("$.skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.printedMrp").value("1799.00"))
                .andExpect(jsonPath("$.batchSellableQuantity").value(3))
                .andExpect(jsonPath("$.sellableQuantity").value(3))
                .andExpect(jsonPath("$.requiresBatchSelection").value(false));

        // Verify stock balance, movements, and stockVersion were untouched by label operations
        assertThat(batchBalanceRepository.sumSellableByBatchId(batchId)).isEqualTo(3L);
        assertThat(stockMovementRepository.findByBatchIdOrderByPostedAtAsc(batchId)).hasSize(1);
        assertThat(skuStockStateRepository.findById(skuId).orElseThrow().getStockVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Owner and worker can access BE04 endpoints; unauthenticated and missing CSRF requests are rejected")
    void authAndCsrfEnforcementOnInventoryAndBarcodeEndpoints() throws Exception {
        // Unauthenticated requests -> 401 AUTH_REQUIRED
        mockMvc.perform(get("/api/v1/inventory")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/v1/stock-receipts/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/v1/barcodes/lookup").param("code", "123")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/v1/stock-batches/{id}/label", UUID.randomUUID())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        // Protected writes without CSRF -> 403 CSRF_ERROR
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        mockMvc.perform(post("/api/v1/stock-receipts")
                        .session(worker.session())
                        .header("Idempotency-Key", "no-csrf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", UUID.randomUUID().toString(),
                                "receivedDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", UUID.randomUUID().toString(),
                                        "quantity", 1,
                                        "unitCost", "100.00"
                                ))
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/barcodes")
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "00123",
                                "skuId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/stock-batches/{id}/label", UUID.randomUUID())
                        .session(worker.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        // Both Owner and Worker can access inventory
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        mockMvc.perform(get("/api/v1/inventory").session(owner.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inventory").session(worker.session())).andExpect(status().isOk());
    }
}
