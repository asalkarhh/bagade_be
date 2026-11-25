package com.bagade.purchasing.service;

import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.util.PaginationHelper;
import com.bagade.purchasing.dto.CreateVendorRequest;
import com.bagade.purchasing.dto.VendorResponse;
import com.bagade.purchasing.entity.Vendor;
import com.bagade.purchasing.repository.VendorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class VendorService {

    private final VendorRepository vendorRepository;

    public VendorService(VendorRepository vendorRepository) {
        this.vendorRepository = vendorRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<VendorResponse> listVendors(String q, Boolean active, int page, int size) {
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        String searchPattern = CatalogValues.buildLikePattern(q);
        Page<VendorResponse> result = vendorRepository.searchVendors(searchPattern, active, pageable);
        return PagedResponse.from(result);
    }

    @Transactional
    public VendorResponse createVendor(CreateVendorRequest request, UUID actorUserId) {
        String normalizedName = CatalogValues.normalizeRequiredText(request.name());
        String normalizedContact = CatalogValues.normalizeOptionalText(request.contact());

        Vendor vendor = new Vendor(normalizedName, normalizedContact, actorUserId);
        Vendor saved = vendorRepository.save(vendor);

        return new VendorResponse(saved.getId(), saved.getName(), saved.getContact(), saved.isActive());
    }
}
