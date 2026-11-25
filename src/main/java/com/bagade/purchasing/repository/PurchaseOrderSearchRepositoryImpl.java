package com.bagade.purchasing.repository;

import com.bagade.purchasing.entity.PurchaseOrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class PurchaseOrderSearchRepositoryImpl implements PurchaseOrderSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<PurchaseOrderListRow> searchPurchaseOrders(
            UUID vendorId,
            PurchaseOrderStatus status,
            LocalDate from,
            LocalDate to,
            Pageable pageable) {

        List<String> predicates = new ArrayList<>();
        if (vendorId != null) {
            predicates.add("po.vendor.id = :vendorId");
        }
        if (status != null) {
            predicates.add("po.status = :status");
        }
        if (from != null) {
            predicates.add("po.orderDate >= :fromDate");
        }
        if (to != null) {
            predicates.add("po.orderDate <= :toDate");
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);

        String countJpql = "SELECT COUNT(po.id) FROM PurchaseOrder po" + whereClause;
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        bindParameters(countQuery, vendorId, status, from, to);
        long total = countQuery.getSingleResult();

        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        String selectJpql = """
                SELECT new com.bagade.purchasing.repository.PurchaseOrderListRow(
                    po.id,
                    po.poNumber,
                    po.vendor.id,
                    po.orderDate,
                    po.status,
                    COALESCE(SUM(pol.orderedQuantity - pol.receivedQuantity - pol.cancelledQuantity), 0L),
                    po.version
                )
                FROM PurchaseOrder po
                LEFT JOIN po.lines pol
                """ + whereClause + """
                 GROUP BY po.id, po.poNumber, po.vendor.id, po.orderDate, po.status, po.createdAt, po.version
                 ORDER BY po.orderDate DESC, po.createdAt DESC, po.id DESC
                """;

        TypedQuery<PurchaseOrderListRow> dataQuery = entityManager.createQuery(selectJpql, PurchaseOrderListRow.class);
        bindParameters(dataQuery, vendorId, status, from, to);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        return new PageImpl<>(dataQuery.getResultList(), pageable, total);
    }

    private void bindParameters(
            TypedQuery<?> query,
            UUID vendorId,
            PurchaseOrderStatus status,
            LocalDate from,
            LocalDate to) {
        if (vendorId != null) {
            query.setParameter("vendorId", vendorId);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (from != null) {
            query.setParameter("fromDate", from);
        }
        if (to != null) {
            query.setParameter("toDate", to);
        }
    }
}
