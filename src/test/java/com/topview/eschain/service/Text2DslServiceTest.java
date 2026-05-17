package com.topview.eschain.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Text2DslServiceTest {

    @Autowired
    private Text2DslService text2DslService;

    @Test
    public void testFullFlow() {
        System.out.println("====== 🏁 开始全链路测试 🏁 ======");

        // 1. 定义测试场景
        String indexName = "student_scores";
        // 故意用一句复杂的自然语言，测试它的理解能力
        String userQuery = "帮我找一下考试日期在最近3天内，且Java架构这门课考了90分以上的学生";

        System.out.println("Step 1: 用户提问 -> " + userQuery);

        try {
            // 2. 调用翻译服务
            // 这一步会自动：查Mapping -> 拼Prompt -> 调LLM -> 洗数据
            String dsl = text2DslService.generateDsl(indexName, userQuery);

            System.out.println("Step 2: AI 生成 DSL 成功！结果如下：");
            System.out.println("--------------------------------------------------");
            System.out.println(dsl);
            System.out.println("--------------------------------------------------");

            // 3. (可选) 简单的肉眼验证
            if (dsl.contains("range") && dsl.contains("term") && dsl.contains("90")) {
                System.out.println("✅ 测试通过：生成的 DSL 包含关键查询条件！");
            } else {
                System.err.println("❌ 警告：生成的 DSL 好像差点意思，请检查日志。");
            }

        } catch (Exception e) {
            System.err.println("❌ 测试失败：");
            e.printStackTrace();
        }
    }
}