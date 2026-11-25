package com.bagade.billing.repository;

import com.bagade.billing.entity.Bill;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillRepository extends JpaRepository<Bill, UUID>, BillSearchRepository {

    @Query(value = "SELECT nextval('bill_number_seq')", nativeQuery = true)
    long nextBillNumberSequence();

    @Query("SELECT b FROM Bill b LEFT JOIN FETCH b.customer WHERE b.id = :id")
    Optional<Bill> findByIdWithCustomer(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Bill b LEFT JOIN FETCH b.customer WHERE b.id = :id")
    Optional<Bill> findByIdForUpdate(@Param("id") UUID id);
}
