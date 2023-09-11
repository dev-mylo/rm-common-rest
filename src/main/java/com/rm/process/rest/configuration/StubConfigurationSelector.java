package com.rm.process.rest.configuration;

import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

public class StubConfigurationSelector implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {

        return new String[]{
                HttpConfiguration.class.getName(),
                SwaggerConfiguration.class.getName()
        };

    }
}
