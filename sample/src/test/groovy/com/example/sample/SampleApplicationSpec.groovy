package com.example.sample

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification

trait AppContextTest {
    int someInt = 42

    int method() {
        return someInt * 2
    }

    @Autowired
    ApplicationContext context
    // This trait can be used to share common setup or utilities for tests that require the application context.
}

interface SoemethingTest {
    default int add(int a, int b) {
        return a + b
    }
    // This interface can be used to define common test methods or properties for all specifications in this package.
    int getS()
}

@SpringBootTest
class SampleApplicationSpec extends Specification implements Trat, OthererName, AppContextTest, SoemethingTest {

    @Autowired
    ApplicationContext context

    def "abc loads"() {
        expect:
        true
        def x = new Bababxa()
        def z = add(5, 10)
        context.getApplicationName()
    }

    @Override
    int getS() {
        throw new UnsupportedOperationException("Method 'getS' is not implemented")
    }

}
