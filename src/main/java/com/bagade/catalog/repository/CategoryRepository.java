package com.bagade.catalog.repository;

import com.bagade.catalog.dto.CategoryResponse;
import com.bagade.catalog.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("""
            SELECT new com.bagade.catalog.dto.CategoryResponse(c.id, c.name, p.id)
            FROM Category c
            LEFT JOIN c.parent p
            """)
    Page<CategoryResponse> findAllCategories(Pageable pageable);

    @Query("""
            SELECT new com.bagade.catalog.dto.CategoryResponse(c.id, c.name, p.id)
            FROM Category c
            LEFT JOIN c.parent p
            WHERE p.id = :parentId
            """)
    Page<CategoryResponse> findByParentId(@Param("parentId") UUID parentId, Pageable pageable);

    @Query(value = """
            WITH RECURSIVE category_tree AS (
                SELECT id FROM category WHERE id = :rootCategoryId
                UNION ALL
                SELECT c.id FROM category c
                INNER JOIN category_tree ct ON c.parent_id = ct.id
            )
            SELECT id FROM category_tree
            """, nativeQuery = true)
    List<UUID> findDescendantCategoryIds(@Param("rootCategoryId") UUID rootCategoryId);
}
