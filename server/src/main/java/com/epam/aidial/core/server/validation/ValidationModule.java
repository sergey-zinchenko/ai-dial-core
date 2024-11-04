package com.epam.aidial.core.server.validation;

import com.fasterxml.jackson.databind.module.SimpleModule;

public class ValidationModule extends SimpleModule {
    public ValidationModule() {
        super();
        setDeserializerModifier(new BeanDeserializerModifierWithValidation());
    }
}
