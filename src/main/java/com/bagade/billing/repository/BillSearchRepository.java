package com.bagade.billing.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

public interface BillSearchRepository {

    Page<BillListRow> searchBills(
            String billNumberPattern,
            LocalDate from,
            LocalDate to,
            UUID customerId,
            Pageable pageable
    );
}
