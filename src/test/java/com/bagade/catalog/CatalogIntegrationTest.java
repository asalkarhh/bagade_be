package com.bagade.catalog;

import com.bagade.auth.entity.UserAccount;
import com.bagade.auth.entity.UserRole;
import com.bagade.auth.repository.UserAccountRepository;
import com.bagade.catalog.dto.CreateSkuRequest;
import com.bagade.catalog.repository.CategoryRepository;
import com.bagade.catalog.repository.ProductRepository;
import com.bagade.catalog.repository.SkuRepository;
import com.bagade.catalog.service.SkuService;
import com.bagade.common.error.DuplicateSkuException;
import com.bagade.inventory.repository.SkuStockStateRepository;
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

import java.util.ArrayList;
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
class CatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private SkuService skuService;

    @Autowired
    private SkuStockStateRepository skuStockStateRepository;

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

        String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", password
        ));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/session")
                        .session(session)
                        .header("X-CSRF-Token", preLoginToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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
        String body = parentId == null
                ? objectMapper.writeValueAsString(Map.of("name", name))
                : objectMapper.writeValueAsString(Map.of("name", name, "parentId", parentId.toString()));

        MvcResult res = mockMvc.perform(post("/api/v1/categories")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").isNotEmpty())
                .andExpect(jsonPath("$.name").value(name.trim()))
                .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        return UUID.fromString(json.get("categoryId").asText());
    }

    private UUID createProduct(SessionContext ctx, UUID categoryId, String name, String brand, String design) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("categoryId", categoryId.toString());
        payload.put("name", name);
        if (brand != null) {
            payload.put("brand", brand);
        }
        if (design != null) {
            payload.put("design", design);
        }

        MvcResult res = mockMvc.perform(post("/api/v1/products")
                        .session(ctx.session())
                        .header("X-CSRF-Token", ctx.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").isNotEmpty())
                .andExpect(jsonPath("$.categoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$.name").value(name.trim()))
                .andReturn();

        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("productId").asText());
    }

    private JsonNode createSku(SessionContext ctx, UUID productId, String skuCode, String size, String price) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
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

        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Worker creates Sarees -> Silk Sarees, product, one unsized SKU; second unsized SKU returns 409")
    void workerCreatesUnsizedSareeSkuAndRejectsSecondUnsizedSku() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID sareesId = createCategory(worker, "Sarees", null);
        UUID silkSareesId = createCategory(worker, "Silk Sarees", sareesId);

        // Verify direct children filtering
        mockMvc.perform(get("/api/v1/categories")
                        .param("parentId", sareesId.toString())
                        .session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].categoryId").value(silkSareesId.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Silk Sarees"))
                .andExpect(jsonPath("$.items[0].parentId").value(sareesId.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.total").value(1));

        UUID productId = createProduct(worker, silkSareesId, "Kanchipuram Bridal Silk", "Bagade Heritage", "Zari Border");

        // First unsized SKU with blank size normalized to null
        var firstPayload = objectMapper.writeValueAsString(Map.of(
                "skuCode", "  SAR-KAN-001  ",
                "size", "   ",
                "defaultSellingPrice", "4500"
        ));

        mockMvc.perform(post("/api/v1/products/{id}/skus", productId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skuId").isNotEmpty())
                .andExpect(jsonPath("$.skuCode").value("SAR-KAN-001"))
                .andExpect(jsonPath("$.size").value(nullValue()))
                .andExpect(jsonPath("$.defaultSellingPrice").value("4500.00"))
                .andExpect(jsonPath("$.version").value(0));

        // Second unsized SKU (null size, distinct skuCode) must return 409 DUPLICATE_SKU
        var secondPayload = new java.util.LinkedHashMap<String, Object>();
        secondPayload.put("skuCode", "SAR-KAN-002");
        secondPayload.put("size", null);
        secondPayload.put("defaultSellingPrice", "4800.00");

        mockMvc.perform(post("/api/v1/products/{id}/skus", productId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Worker creates size 32 and 34 for innerwear product; duplicate SKU codes and sizes are rejected")
    void workerCreatesSizedSkusAndRejectsDuplicates() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        UUID innerwearId = createCategory(worker, "Innerwear", null);
        UUID productId = createProduct(worker, innerwearId, "Cotton Vest", "Classic", "Ribbed");

        // Size 32 (with surrounding spaces)
        JsonNode sku32 = createSku(worker, productId, "VES-32", " 32 ", "199.5");
        assertThat(sku32.get("size").asText()).isEqualTo("32");
        assertThat(sku32.get("defaultSellingPrice").asText()).isEqualTo("199.50");

        // Size 34
        JsonNode sku34 = createSku(worker, productId, "VES-34", "34", "210.00");
        assertThat(sku34.get("size").asText()).isEqualTo("34");

        // Duplicate size "32" with different SKU code -> 409 DUPLICATE_SKU
        mockMvc.perform(post("/api/v1/products/{id}/skus", productId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skuCode", "VES-32-ALT",
                                "size", "32",
                                "defaultSellingPrice", "199.50"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));

        // Duplicate SKU code (case-insensitive) with different size -> 409 DUPLICATE_SKU
        mockMvc.perform(post("/api/v1/products/{id}/skus", productId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skuCode", "ves-34",
                                "size", "36",
                                "defaultSellingPrice", "220.00"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test
    @DisplayName("Concurrent racing SKU creations enforce DB uniqueness and translate to DuplicateSkuException")
    void concurrentDuplicateSkuCreationIsRejected() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Kurtis", null);
        UUID productId = createProduct(owner, catId, "Anarkali Kurti", null, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Boolean> task = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                skuService.createSku(productId, new CreateSkuRequest("KUR-RACE-" + Thread.currentThread().threadId(), " ", "799.00"));
                return true;
            } catch (DuplicateSkuException ex) {
                return false;
            }
        };

        List<Future<Boolean>> futures = new ArrayList<>();
        futures.add(executor.submit(task));
        futures.add(executor.submit(task));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = 0;
        int duplicates = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successes++;
            } else {
                duplicates++;
            }
        }
        executor.shutdownNow();

        assertThat(successes).isEqualTo(1);
        assertThat(duplicates).isEqualTo(1);
    }

    @Test
    @DisplayName("Search, category hierarchy filtering, active filtering, and pagination return expected results")
    void searchCategoryActiveAndPaginationWork() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");

        UUID sareesId = createCategory(owner, "Sarees", null);
        UUID silkSareesId = createCategory(owner, "Silk Sarees", sareesId);
        UUID kanjivaramId = createCategory(owner, "Kanjivaram", silkSareesId);
        UUID innerwearId = createCategory(owner, "Innerwear", null);

        UUID cottonSareeProd = createProduct(owner, sareesId, "Bengal Cotton Saree", "Tant", null);
        UUID silkSareeProd = createProduct(owner, silkSareesId, "Banarasi Silk Saree", "Varanasi", null);
        UUID kanjivaramProd = createProduct(owner, kanjivaramId, "Gold Zari Kanjivaram", "Kanchi", null);
        UUID vestProd = createProduct(owner, innerwearId, "Mens Brief", "Comfort", null);

        createSku(owner, cottonSareeProd, "SAR-COT-1", null, "1200.00");
        JsonNode banarasiSku = createSku(owner, silkSareeProd, "SAR-SLK-1", null, "5500.00");
        createSku(owner, kanjivaramProd, "SAR-KNJ-1", null, "9500.00");
        createSku(owner, vestProd, "INN-BRF-32", "32", "250.00");
        createSku(owner, vestProd, "INN-BRF-34", "34", "260.00");

        // Category filter on parent "Sarees" must include Silk Sarees & Kanjivaram, excluding Innerwear
        mockMvc.perform(get("/api/v1/skus")
                        .param("categoryId", sareesId.toString())
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].productId").value(silkSareeProd.toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Banarasi Silk Saree"))
                .andExpect(jsonPath("$.items[0].sellableQuantity").value(0))
                .andExpect(jsonPath("$.items[1].productId").value(cottonSareeProd.toString()))
                .andExpect(jsonPath("$.items[1].productName").value("Bengal Cotton Saree"))
                .andExpect(jsonPath("$.items[2].productId").value(kanjivaramProd.toString()))
                .andExpect(jsonPath("$.items[2].productName").value("Gold Zari Kanjivaram"));

        // Search q by product name
        mockMvc.perform(get("/api/v1/skus")
                        .param("q", "kanjivaram")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuCode").value("SAR-KNJ-1"));

        // Search q by SKU code
        mockMvc.perform(get("/api/v1/skus")
                        .param("q", "sar-cot")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuCode").value("SAR-COT-1"));

        // Search q by size
        mockMvc.perform(get("/api/v1/skus")
                        .param("q", "34")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuCode").value("INN-BRF-34"));

        // Pagination (size = 2, page = 0 and page = 1) with stable ordering across all 5 active SKUs
        mockMvc.perform(get("/api/v1/skus")
                        .param("page", "0")
                        .param("size", "2")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].skuCode").value("SAR-SLK-1"))
                .andExpect(jsonPath("$.items[1].skuCode").value("SAR-COT-1"));

        mockMvc.perform(get("/api/v1/skus")
                        .param("page", "1")
                        .param("size", "2")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].skuCode").value("SAR-KNJ-1"))
                .andExpect(jsonPath("$.items[1].skuCode").value("INN-BRF-32"));

        // Deactivate Banarasi Silk Saree SKU
        UUID banarasiSkuId = UUID.fromString(banarasiSku.get("skuId").asText());
        mockMvc.perform(patch("/api/v1/skus/{id}", banarasiSkuId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "active", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1));

        // Default list excludes inactive SKU
        mockMvc.perform(get("/api/v1/skus").session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4));

        // Explicit active=false returns deactivated SKU
        mockMvc.perform(get("/api/v1/skus")
                        .param("active", "false")
                        .session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuId").value(banarasiSkuId.toString()))
                .andExpect(jsonPath("$.items[0].active").value(false))
                .andExpect(jsonPath("$.items[0].version").value(1));
    }

    @Test
    @DisplayName("Successful SKU update returns incremented version; stale update returns 409 STALE_VERSION")
    void skuOptimisticLockingUpdateAndStaleConflict() throws Exception {
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Shirts", null);
        UUID prodId = createProduct(owner, catId, "Linen Shirt", "Studio", "Plain");
        JsonNode sku = createSku(owner, prodId, "SH-LIN-L", "L", "1499.00");
        UUID skuId = UUID.fromString(sku.get("skuId").asText());

        // First update succeeds and returns version 1
        mockMvc.perform(patch("/api/v1/skus/{id}", skuId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "defaultSellingPrice", "1599.50"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.defaultSellingPrice").value("1599.50"))
                .andExpect(jsonPath("$.version").value(1));

        // Stale update with expectedVersion = 0 returns 409 STALE_VERSION
        mockMvc.perform(patch("/api/v1/skus/{id}", skuId)
                        .session(owner.session())
                        .header("X-CSRF-Token", owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "defaultSellingPrice", "1699.00"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Validation errors and 404 RESOURCE_NOT_FOUND follow standard error format")
    void validationAndNotFoundErrors() throws Exception {
        SessionContext worker = signIn("worker1", "WorkerPass123!");
        UUID missingId = UUID.randomUUID();

        // 404 on unknown parent category
        mockMvc.perform(post("/api/v1/categories")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Orphan",
                                "parentId", missingId.toString()
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // 404 on unknown category when creating product
        mockMvc.perform(post("/api/v1/products")
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoryId", missingId.toString(),
                                "name", "Orphan Product"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 404 on unknown product when creating SKU
        mockMvc.perform(post("/api/v1/products/{id}/skus", missingId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skuCode", "MISSING-1",
                                "defaultSellingPrice", "100.00"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 404 on unknown SKU when patching
        mockMvc.perform(patch("/api/v1/skus/{id}", missingId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "active", false
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 400 invalid price precision (3 decimal places) & blank skuCode
        UUID catId = createCategory(worker, "Dupattas", null);
        UUID prodId = createProduct(worker, catId, "Silk Dupatta", null, null);
        mockMvc.perform(post("/api/v1/products/{id}/skus", prodId)
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skuCode", "   ",
                                "defaultSellingPrice", "199.999"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));

        // 400 PATCH with neither defaultSellingPrice nor active
        JsonNode sku = createSku(worker, prodId, "DUP-01", null, "499.00");
        mockMvc.perform(patch("/api/v1/skus/{id}", sku.get("skuId").asText())
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        // 400 invalid pagination parameters
        mockMvc.perform(get("/api/v1/skus")
                        .param("page", "-1")
                        .param("size", "101")
                        .session(worker.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));

        mockMvc.perform(get("/api/v1/categories")
                        .param("size", "0")
                        .session(worker.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    @DisplayName("Unauthenticated requests return 401 and catalog writes without valid CSRF return 403")
    void authAndCsrfEnforcementOnCatalogEndpoints() throws Exception {
        // Unauthenticated GETs
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/skus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        // Authenticated worker writes without CSRF token -> 403 CSRF_ERROR
        SessionContext worker = signIn("worker1", "WorkerPass123!");

        mockMvc.perform(post("/api/v1/categories")
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "NoCsrf"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(post("/api/v1/products")
                        .session(worker.session())
                        .header("X-CSRF-Token", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoryId", UUID.randomUUID().toString(),
                                "name", "BadCsrf"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        mockMvc.perform(patch("/api/v1/skus/{id}", UUID.randomUUID())
                        .session(worker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "active", false
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_ERROR"));

        // Both owner and worker can list categories and update SKUs
        SessionContext owner = signIn("owner1", "OwnerPass123!");
        UUID catId = createCategory(owner, "Kids Wear", null);
        UUID prodId = createProduct(owner, catId, "Kids Tee", null, null);
        JsonNode createdSku = createSku(owner, prodId, "KID-TEE-S", "S", "399.00");

        mockMvc.perform(get("/api/v1/categories").session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/v1/categories").session(worker.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(patch("/api/v1/skus/{id}", createdSku.get("skuId").asText())
                        .session(worker.session())
                        .header("X-CSRF-Token", worker.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0,
                                "defaultSellingPrice", "425.00"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultSellingPrice").value("425.00"))
                .andExpect(jsonPath("$.version").value(1));
    }
}
