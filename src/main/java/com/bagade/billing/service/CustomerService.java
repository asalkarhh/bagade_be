package com.bagade.billing.service;

import com.bagade.billing.dto.CreateCustomerRequest;
import com.bagade.billing.dto.CustomerResponse;
import com.bagade.billing.entity.Customer;
import com.bagade.billing.repository.CustomerRepository;
import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.dto.PagedResponse;
import com.bagade.common.util.PaginationHelper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<CustomerResponse> listCustomers(String q, int page, int size) {
        Pageable pageable = PaginationHelper.createPageable(page, size, Sort.unsorted());
        Page<CustomerResponse> result = customerRepository.searchCustomers(CatalogValues.buildLikePattern(q), pageable);
        return PagedResponse.from(result);
    }

    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest request, UUID actorUserId) {
        String name = CatalogValues.normalizeRequiredText(request.name());
        String contact = CatalogValues.normalizeOptionalText(request.contact());
        Customer saved = customerRepository.save(new Customer(name, contact, actorUserId));
        return new CustomerResponse(saved.getId(), saved.getName(), saved.getContact());
    }
}
