package com.bagade.purchasing;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import com.bagade.catalog.entity.Category;
import com.bagade.catalog.entity.Product;
import com.bagade.catalog.entity.Sku;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.ProductRepository;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.common.error.StaleVersionException;
import com.bagade.common.idempotency.IdempotencyRecordRepository;
import com.bagade.inventory.repository.SkuStockStateRepository;
import com.bagade.purchasing.dto.CancelPurchaseOrderRemainderRequest;
import com.bagade.purchasing.dto.PurchaseOrderResponse;
import com.bagade.purchasing.entity.PurchaseOrder;
import com.bagade.purchasing.entity.PurchaseOrderCancellation;
import com.bagade.purchasing.entity.PurchaseOrderLine;
import com.bagade.purchasing.entity.PurchaseOrderStatus;
import com.bagade.purchasing.entity.Vendor;
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
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseOrderIntegrationTest {

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
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private SkuStockStateRepository skuStockStateRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UserAccount ownerUser;
    private UserAccount workerUser;
    private Sku silkSareeSku;
    private Sku cottonKurtiSku;

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

        Category sarees = categoryRepository.save(new Category("Sarees", null));
        Category silkSarees = categoryRepository.save(new Category("Silk Sarees", sarees));
        Product bridalSilk = productRepository.save(new Product(silkSarees, "Bridal Silk Saree", "Bagade", "Zari"));
        silkSareeSku = skuRepository.save(new Sku(bridalSilk, "SAR-SLK-100", null, new BigDecimal("4500.00")));

        Category kurtis = categoryRepository.save(new Category("Kurtis", null));
        Product dailyKurti = productRepository.save(new Product(kurtis, "Cotton Kurti", "Fab", "Print"));
        cottonKurtiSku = skuRepository.save(new Sku(dailyKurti, "KUR-COT-M", "M", new BigDecimal("899.00")));
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    private void cleanAll() {
        idempotencyRecordRepository.deleteAll();
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

    private UUID createVendorViaApi(SessionContext ctx, String name, String contact) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        if (contact != null) {
            body.put("contact", contact);
        }
        MvcResult res = mockMvc.perform(post("/api/v1/vendors")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("vendorId").asText());
    }

    private JsonNode createPoViaApi(
            SessionContext ctx,
            UUID vendorId,
            LocalDate orderDate,
            List<Map<String, Object>> lines) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "orderDate", orderDate.toString(),
                                "lines", lines
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    /**
     * Simulates a BE04 stock receipt on a purchase order line for test verification without exposing a receipt API.
     */
    private void simulateLineReceiptFixture(UUID poId, UUID poLineId, int receivedQty) {
        transactionTemplate.executeWithoutResult(status -> {
            PurchaseOrder po = purchaseOrderRepository.findByIdForUpdate(poId).orElseThrow();
            List<PurchaseOrderLine> lines = purchaseOrderLineRepository.findByPurchaseOrderIdForUpdate(poId);
            PurchaseOrderLine target = lines.stream()
                    .filter(l -> l.getId().equals(poLineId))
                    .findFirst()
                    .orElseThrow();
            target.recordReceipt(receivedQty);
            long ordered = lines.stream().mapToLong(PurchaseOrderLine::getOrderedQuantity).sum();
            long received = lines.stream().mapToLong(PurchaseOrderLine::getReceivedQuantity).sum();
            long cancelled = lines.stream().mapToLong(PurchaseOrderLine::getCancelledQuantity).sum();
            po.setStatus(PurchaseOrder.deriveStatus(ordered, received, cancelled));
            po.touchUpdatedAt();
            purchaseOrderRepository.saveAndFlush(po);
        });
    }

    @Test
    @DisplayName("Worker creates a vendor and PO for 12 silk sarees; OPEN with 12 open, 0 received, 0 cancelled, and stock untouched")
    void workerCreatesVendorAndOpenPurchaseOrderWithoutAffectingStock() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        MvcResult vendorRes = mockMvc.perform(post("/api/v1/vendors")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "  Kanchipuram Silk Looms  ",
                                "contact", "  +91-9876543210  "
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vendorId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Kanchipuram Silk Looms"))
                .andExpect(jsonPath("$.contact").value("+91-9876543210"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        UUID vendorId = UUID.fromString(objectMapper.readTree(vendorRes.getResponse().getContentAsString()).get("vendorId").asText());

        // Create PO for 12 silk sarees with agreedUnitCost
        MvcResult poRes = mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", vendorId.toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", silkSareeSku.getId().toString(),
                                        "orderedQuantity", 12,
                                        "agreedUnitCost", "3200.5"
                                ))
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poId").isNotEmpty())
                .andExpect(jsonPath("$.poNumber").value(org.hamcrest.Matchers.startsWith("PO-")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.totalOpenQuantity").value(12))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].poLineId").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].skuId").value(silkSareeSku.getId().toString()))
                .andExpect(jsonPath("$.lines[0].orderedQuantity").value(12))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].cancelledQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].openQuantity").value(12))
                .andExpect(jsonPath("$.lines[0].agreedUnitCost").value("3200.50"))
                .andReturn();

        String poId = objectMapper.readTree(poRes.getResponse().getContentAsString()).get("poId").asText();

        // Detail endpoint returns full PO header and lines
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", poId).session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poId").value(poId))
                .andExpect(jsonPath("$.vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.orderDate").value("2026-11-25"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.totalOpenQuantity").value(12))
                .andExpect(jsonPath("$.lines[0].agreedUnitCost").value("3200.50"));

        // Creating a PO must not affect sellable stock
        mockMvc.perform(get("/api/v1/skus")
                        .param("q", "SAR-SLK-100")
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(0));

        // Unknown PO ID returns 404 RESOURCE_NOT_FOUND
        mockMvc.perform(get("/api/v1/purchase-orders/{id}", UUID.randomUUID()).session(worker.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Vendor search, active filtering, PO filters, totals, and newest-first pagination return expected results")
    void vendorAndPurchaseOrderFiltersTotalsAndPagination() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");

        UUID vendorA = createVendorViaApi(owner, "Arvind Textiles", "arvind@example.com");
        UUID vendorB = createVendorViaApi(owner, "Banares Weavers", "9900112233");
        Vendor inactiveVendor = vendorRepository.save(new Vendor("Dormant Vendor", "old@example.com", ownerUser.getId()));
        inactiveVendor.setActive(false);
        vendorRepository.save(inactiveVendor);

        // Vendor search by name and contact + active filter
        mockMvc.perform(get("/api/v1/vendors")
                        .param("q", "990011")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].vendorId").value(vendorB.toString()));

        mockMvc.perform(get("/api/v1/vendors")
                        .param("active", "true")
                        .param("page", "0")
                        .param("size", "1")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Arvind Textiles"));

        mockMvc.perform(get("/api/v1/vendors")
                        .param("active", "false")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Dormant Vendor"));

        // Create 3 POs across dates and vendors
        JsonNode po1 = createPoViaApi(owner, vendorA, LocalDate.of(2026, 11, 10), List.of(
                Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 5),
                Map.of("skuId", cottonKurtiSku.getId().toString(), "orderedQuantity", 7, "agreedUnitCost", "500.00")
        ));
        JsonNode po2 = createPoViaApi(owner, vendorB, LocalDate.of(2026, 11, 20), List.of(
                Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 4)
        ));
        JsonNode po3 = createPoViaApi(owner, vendorA, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", cottonKurtiSku.getId().toString(), "orderedQuantity", 10)
        ));

        // Partial receipt on po2 so status becomes PARTIALLY_RECEIVED with 1 open
        simulateLineReceiptFixture(
                UUID.fromString(po2.get("poId").asText()),
                UUID.fromString(po2.get("lines").get(0).get("poLineId").asText()),
                3
        );

        // Verify newest-first ordering and totalOpenQuantity
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("page", "0")
                        .param("size", "2")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].poId").value(po3.get("poId").asText()))
                .andExpect(jsonPath("$.items[0].totalOpenQuantity").value(10))
                .andExpect(jsonPath("$.items[1].poId").value(po2.get("poId").asText()))
                .andExpect(jsonPath("$.items[1].status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.items[1].totalOpenQuantity").value(1));

        // Page 1 shows oldest PO (po1) with combined open qty 5 + 7 = 12 and null agreedUnitCost preserved on line 0
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("page", "1")
                        .param("size", "2")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].poId").value(po1.get("poId").asText()))
                .andExpect(jsonPath("$.items[0].totalOpenQuantity").value(12));

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", po1.get("poId").asText()).session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].agreedUnitCost").value(nullValue()))
                .andExpect(jsonPath("$.lines[1].agreedUnitCost").value("500.00"));

        // Filter by vendorId + inclusive date range
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("vendorId", vendorA.toString())
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-25")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].poId").value(po3.get("poId").asText()))
                .andExpect(jsonPath("$.items[1].poId").value(po1.get("poId").asText()));

        // Filter by status = PARTIALLY_RECEIVED
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("status", "PARTIALLY_RECEIVED")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].poId").value(po2.get("poId").asText()));

        // Invalid date range from > to returns 400 VALIDATION_ERROR
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("from", "2026-11-25")
                        .param("to", "2026-11-10")
                        .session(owner.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    @DisplayName("Invalid quantities, missing references, and inactive vendors or SKUs are rejected without saving partial data")
    void invalidOrderInputsAndInactiveReferencesRejectedAtomically() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID activeVendorId = createVendorViaApi(worker, "Active Supplier", null);

        Vendor inactiveVendor = vendorRepository.save(new Vendor("Inactive Supplier", null, workerUser.getId()));
        inactiveVendor.setActive(false);
        vendorRepository.save(inactiveVendor);

        // 1. Ordered quantity <= 0 rejected
        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", activeVendorId.toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", silkSareeSku.getId().toString(),
                                        "orderedQuantity", 0
                                ))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        // 2. Empty lines array rejected
        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", activeVendorId.toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 3. Invalid agreedUnitCost precision rejected
        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", activeVendorId.toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", silkSareeSku.getId().toString(),
                                        "orderedQuantity", 5,
                                        "agreedUnitCost", "100.999"
                                ))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 4. Missing vendor returns 404
        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", UUID.randomUUID().toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", silkSareeSku.getId().toString(),
                                        "orderedQuantity", 5
                                ))
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 5. Inactive vendor rejected
        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", inactiveVendor.getId().toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(Map.of(
                                        "skuId", silkSareeSku.getId().toString(),
                                        "orderedQuantity", 5
                                ))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("vendorId"));

        // 6. Valid line 1 + missing SKU on line 2 leaves no partial PO or line saved
        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", activeVendorId.toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(
                                        Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 5),
                                        Map.of("skuId", UUID.randomUUID().toString(), "orderedQuantity", 3)
                                )
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(purchaseOrderRepository.count()).isZero();
        assertThat(purchaseOrderLineRepository.count()).isZero();

        // 7. Deactivate cottonKurtiSku via API, then valid line 1 + inactive SKU line 2 -> rejected with 0 partial data
        mockMvc.perform(patch("/api/v1/skus/{id}", cottonKurtiSku.getId())
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", cottonKurtiSku.getVersion(),
                                "active", false
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", activeVendorId.toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(
                                        Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 5),
                                        Map.of("skuId", cottonKurtiSku.getId().toString(), "orderedQuantity", 3)
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[1].skuId"));

        assertThat(purchaseOrderRepository.count()).isZero();
        assertThat(purchaseOrderLineRepository.count()).isZero();
    }

    @Test
    @DisplayName("Cancelling remainder preserves received units, bumps version even on line-only updates, sets status, and records audit")
    void cancelRemainderPreservesReceivedUnitsAndSetsCorrectStatusAndAudit() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID vendorId = createVendorViaApi(worker, "Silk House", null);

        // PO 1: unreceived order -> full cancel transitions OPEN -> CANCELLED
        JsonNode unreceivedPo = createPoViaApi(worker, vendorId, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 8)
        ));
        UUID unreceivedPoId = UUID.fromString(unreceivedPo.get("poId").asText());

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", unreceivedPoId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "cancel-unreceived-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Vendor out of stock"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.totalOpenQuantity").value(0))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lines[0].orderedQuantity").value(8))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].cancelledQuantity").value(8))
                .andExpect(jsonPath("$.lines[0].openQuantity").value(0));

        // Verify audit record persisted
        List<PurchaseOrderCancellation> audits = cancellationRepository.findByPurchaseOrderIdOrderByCancelledAtAsc(unreceivedPoId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getReason()).isEqualTo("Vendor out of stock");
        assertThat(audits.get(0).getCancelledBy()).isEqualTo(workerUser.getId());
        assertThat(audits.get(0).getCancelledAt()).isNotNull();

        // PO 2: 2 lines; simulate 6 received on line 1 (out of 10), 0 received on line 2 (out of 5)
        JsonNode partialPo = createPoViaApi(worker, vendorId, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 10),
                Map.of("skuId", cottonKurtiSku.getId().toString(), "orderedQuantity", 5)
        ));
        UUID partialPoId = UUID.fromString(partialPo.get("poId").asText());
        UUID line1Id = UUID.fromString(partialPo.get("lines").get(0).get("poLineId").asText());
        UUID line2Id = UUID.fromString(partialPo.get("lines").get(1).get("poLineId").asText());

        simulateLineReceiptFixture(partialPoId, line1Id, 6);
        long versionAfterReceipt = purchaseOrderRepository.findById(partialPoId).orElseThrow().getVersion();

        // Step A: Cancel ONLY line 2 -> PO status stays PARTIALLY_RECEIVED, and version MUST still increment
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", partialPoId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "cancel-line2-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", versionAfterReceipt,
                                "reason", "Line 2 discontinued",
                                "poLineIds", List.of(line2Id.toString())
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.totalOpenQuantity").value(4))
                .andExpect(jsonPath("$.version").value(versionAfterReceipt + 1))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(6))
                .andExpect(jsonPath("$.lines[0].cancelledQuantity").value(0))
                .andExpect(jsonPath("$.lines[0].openQuantity").value(4))
                .andExpect(jsonPath("$.lines[1].receivedQuantity").value(0))
                .andExpect(jsonPath("$.lines[1].cancelledQuantity").value(5))
                .andExpect(jsonPath("$.lines[1].openQuantity").value(0));

        // Step B: Cancel remaining open on the PO (poLineIds omitted) -> line 1 keeps received=6, cancelled=4, status -> CLOSED
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", partialPoId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "cancel-remaining-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", versionAfterReceipt + 1,
                                "reason", "Close remainder"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.totalOpenQuantity").value(0))
                .andExpect(jsonPath("$.version").value(versionAfterReceipt + 2))
                .andExpect(jsonPath("$.lines[0].orderedQuantity").value(10))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(6))
                .andExpect(jsonPath("$.lines[0].cancelledQuantity").value(4))
                .andExpect(jsonPath("$.lines[0].openQuantity").value(0));
    }

    @Test
    @DisplayName("Stale cancellation returns 409, idempotent retry replays committed result, and key conflict returns 409")
    void staleCancellationIdempotentReplayAndConflict() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID vendorId = createVendorViaApi(owner, "Saree Palace", null);

        JsonNode po1 = createPoViaApi(owner, vendorId, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 10)
        ));
        UUID po1Id = UUID.fromString(po1.get("poId").asText());

        JsonNode po2 = createPoViaApi(owner, vendorId, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", cottonKurtiSku.getId().toString(), "orderedQuantity", 6)
        ));
        UUID po2LineId = UUID.fromString(po2.get("lines").get(0).get("poLineId").asText());

        // Missing Idempotency-Key -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po1Id)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "No key"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"));

        // Explicitly empty poLineIds -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po1Id)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "empty-lines-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Empty list",
                                "poLineIds", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("poLineIds"));

        // Line ID belonging to another PO -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po1Id)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "foreign-line-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Foreign line",
                                "poLineIds", List.of(po2LineId.toString())
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("poLineIds"));

        // Valid cancellation commits with Idempotency-Key = "idem-1"
        String cancelBody = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 0,
                "reason", "Short supply"
        ));
        MvcResult firstResult = mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po1Id)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();

        // Identical retry with same Idempotency-Key returns exact original response without re-applying
        MvcResult replayResult = mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po1Id)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replayResult.getResponse().getContentAsString())
                .isEqualTo(firstResult.getResponse().getContentAsString());
        assertThat(cancellationRepository.findByPurchaseOrderIdOrderByCancelledAtAsc(po1Id)).hasSize(1);

        // Reusing same Idempotency-Key with different payload -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po1Id)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Different reason"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // Stale cancellation with new key & old expectedVersion -> 409 STALE_VERSION
        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po1Id)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .header("Idempotency-Key", "idem-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test
    @DisplayName("Concurrent cancel-remainder with distinct keys allows one and rejects stale; same key replays safely")
    void concurrentCancelRemainderLockingAndIdempotency() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID vendorId = createVendorViaApi(owner, "Concurrent Vendor", null);

        JsonNode po = createPoViaApi(owner, vendorId, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 10)
        ));
        UUID poId = UUID.fromString(po.get("poId").asText());

        // 1. Two concurrent threads with distinct keys racing on expectedVersion=0 -> 1 succeeds, 1 gets StaleVersionException
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            final String key = "race-distinct-" + i;
            results.add(executor.submit((Callable<String>) () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    purchaseOrderService.cancelRemainder(
                            poId,
                            key,
                            new CancelPurchaseOrderRemainderRequest(0L, "Race cancel", null),
                            ownerUser.getId()
                    );
                    return "OK";
                } catch (StaleVersionException ex) {
                    return "STALE";
                }
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get(10, TimeUnit.SECONDS));
        }
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "STALE");

        // 2. Two concurrent threads with the SAME Idempotency-Key racing on a second PO -> both get identical 200 response
        JsonNode po2 = createPoViaApi(owner, vendorId, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", cottonKurtiSku.getId().toString(), "orderedQuantity", 5)
        ));
        UUID po2Id = UUID.fromString(po2.get("poId").asText());

        CountDownLatch readySame = new CountDownLatch(2);
        CountDownLatch startSame = new CountDownLatch(1);
        List<Future<PurchaseOrderResponse>> sameKeyResults = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            sameKeyResults.add(executor.submit(() -> {
                readySame.countDown();
                startSame.await(5, TimeUnit.SECONDS);
                return purchaseOrderService.cancelRemainder(
                        po2Id,
                        "race-same-key",
                        new CancelPurchaseOrderRemainderRequest(0L, "Same key race", null),
                        ownerUser.getId()
                );
            }));
        }

        assertThat(readySame.await(5, TimeUnit.SECONDS)).isTrue();
        startSame.countDown();

        PurchaseOrderResponse resp1 = sameKeyResults.get(0).get(10, TimeUnit.SECONDS);
        PurchaseOrderResponse resp2 = sameKeyResults.get(1).get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(resp1).isEqualTo(resp2);
        assertThat(resp1.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(resp1.version()).isEqualTo(1L);
        assertThat(cancellationRepository.findByPurchaseOrderIdOrderByCancelledAtAsc(po2Id)).hasSize(1);
    }

    @Test
    @DisplayName("Owner and worker can use all purchasing endpoints; unauthenticated and missing CSRF requests are rejected")
    void authAndCsrfEnforcementOnPurchasingEndpoints() throws Exception {
        // Unauthenticated requests -> 401 AUTH_REQUIRED
        mockMvc.perform(get("/api/v1/vendors"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/purchase-orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/purchase-orders/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        // Authenticated worker without CSRF token -> 403 CSRF_ERROR
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        mockMvc.perform(post("/api/v1/vendors")
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "NoCsrf"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/purchase-orders")
                        .session(worker.session())
                        .header("X-CSRF-Token", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "vendorId", UUID.randomUUID().toString(),
                                "orderDate", "2026-11-25",
                                "lines", List.of(Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 1))
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", UUID.randomUUID())
                        .session(worker.session())
                        .header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "No CSRF"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        // Owner and Worker can both use vendor and purchase order endpoints
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID vendorId = createVendorViaApi(owner, "Owner Vendor", null);
        JsonNode po = createPoViaApi(owner, vendorId, LocalDate.of(2026, 11, 25), List.of(
                Map.of("skuId", silkSareeSku.getId().toString(), "orderedQuantity", 2)
        ));

        mockMvc.perform(get("/api/v1/vendors").session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/v1/purchase-orders").session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(post("/api/v1/purchase-orders/{id}/cancel-remainder", po.get("poId").asText())
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .header("Idempotency-Key", "worker-cancel-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "reason", "Worker cancel"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
