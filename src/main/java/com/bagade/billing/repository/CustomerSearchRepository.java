package com.bagade.billing.repository;

import com.bagade.billing.dto.CustomerResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomerSearchRepository {

    Page<CustomerResponse> searchCustomers(String searchPattern, Pageable pageable);
}
