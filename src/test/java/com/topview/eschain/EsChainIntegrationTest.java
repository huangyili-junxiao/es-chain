package com.topview.eschain;

import com.topview.eschain.service.EsQueryExecutor;
import com.topview.eschain.service.Text2DslService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

@SpringBootTest
public class EsChainIntegrationTest {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EsChainIntegrationTest.class);

    @Autowired
    private Text2DslService text2DslService;

    @Autowired
    private EsQueryExecutor queryExecutor;

    @Test
    public void testFromNaturalLanguageToData() {
        // === 1. 准备工作 ===
        String indexName = "student_scores";
        // 一个包含复杂逻辑的问题（时间 + 分数 + 科目）
        String userQuestion = "帮我查一下最近一周，Java架构这门课考了90分以上的学生有哪些？";

        log.info("📢 用户提问: {}", userQuestion);

        // === 2. 第一棒：翻译 (LLM) ===
        // 这一步会自动：查Schema -> 拼Prompt -> 调AI -> 清洗JSON
        String dsl = text2DslService.generateDsl(indexName, userQuestion);
        
        log.info("🤖 AI生成的 DSL:\n{}", dsl);

        // === 3. 第二棒：执行 (ES) ===
        // 这一步会自动：发HTTP请求 -> 解析hits -> 提取_source
        Map<String, Object> results = queryExecutor.executeQuery(indexName, dsl);

        // === 4. 见证奇迹 ===
        log.info("🔎 查询结果 (共 {} 条):", results.size());
        
        if (results.isEmpty()) {
            log.warn("❌ 没有查到数据，可能是时间范围不对？检查一下你的假数据时间。");
        } else {
            /*results.forEach(row -> {
                log.info("✅ 找到学生: 姓名={}, 科目={}, 分数={}", 
                    row.get("student_name"), 
                    row.get("subject"), 
                    row.get("score"));
            });*/
        }
        
        // 简单的断言
        assert dsl != null;
        assert results != null;
    }

    @Test
    public void testAutoRouting() {
        // 假设你 ES 里现在有两个索引：student_scores 和一个系统索引(会被过滤掉)

        String question = "查一下Java架构的最高分";

        // 注意：这里我们不传索引名了！让它自己猜！
        String dsl = text2DslService.generateDsl(question);

        System.out.println("====== 智能路由结果 ======");
        System.out.println(dsl);
    }
}
