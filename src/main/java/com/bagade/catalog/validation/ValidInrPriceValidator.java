package com.bagade.catalog.validation;

import com.bagade.catalog.util.CatalogValues;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidInrPriceValidator implements ConstraintValidator<ValidInrPrice, String> {

    private boolean allowNull;

    @Override
    public void initialize(ValidInrPrice constraintAnnotation) {
        this.allowNull = constraintAnnotation.allowNull();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return allowNull;
        }
        return CatalogValues.isValidInrPriceString(value);
    }
}
