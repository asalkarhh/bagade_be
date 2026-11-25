package com.bagade.purchasing.repository;

import com.bagade.purchasing.dto.VendorResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VendorSearchRepository {

    Page<VendorResponse> searchVendors(String searchPattern, Boolean active, Pageable pageable);
}
