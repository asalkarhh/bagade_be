package com.bagade.catalog.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ValidInrPriceValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidInrPrice {

    String message() default "Must be a valid non-negative INR decimal with at most 2 decimal places";

    boolean allowNull() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
