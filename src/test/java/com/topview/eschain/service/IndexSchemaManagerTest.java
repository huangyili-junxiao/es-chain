package com.topview.eschain.service;

import com.topview.eschain.config.EnvConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class IndexSchemaManagerTest {

    @Autowired
    private IndexSchemaManager schemaManager;

    @Test
    public void testScout() {
        // 这次查我们刚刚造的 "student_scores"
        String mapping = schemaManager.getSimplifiedMapping("student_scores");
        System.out.println(mapping);
    }
}
