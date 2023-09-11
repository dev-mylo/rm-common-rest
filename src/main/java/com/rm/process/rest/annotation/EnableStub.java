package com.rm.process.rest.annotation;

import com.rm.process.rest.configuration.StubConfigurationSelector;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(StubConfigurationSelector.class)
public @interface EnableStub {
}
