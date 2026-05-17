package com.topview.eschain; // 记得改成你的包名

import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Random;

public class DataGeneratorTest {

    @Test
    public void generateIoTSensorData() throws IOException {
        OkHttpClient client = new OkHttpClient();
        StringBuilder bulkBody = new StringBuilder();
        Random random = new Random();

        // 🏭 场景：智能工厂传感器
        String indexName = "factory_sensors";
        int totalDocs = 2000;
        int expectedTargets = 0;

        System.out.println("🔥 开始生成 IoT 数据...");

        for (int i = 1; i <= totalDocs; i++) {
            String status;
            String alertType; // 报警类型：Overheat(高温), Vibration(震动), Pressure(压力)
            String zone;      // 区域：Zone_A, Zone_B, Zone_C
            String sensorId;
            String message;

            // 🎯 核心逻辑：埋藏“宝藏”和“地雷”
            if (i % 100 == 0) {
                // ✅ 目标数据 (Target): Zone_A + Critical + Overheat
                // 总数应该有 2000 / 100 = 20 条
                zone = "Zone_A";
                status = "Critical";
                alertType = "Overheat";
                message = "CRITICAL ALERT: Furnace temperature exceeded limit!";
                expectedTargets++;
            } else if (i % 100 == 1) {
                // 💣 干扰项 A (地雷): 虽然是 Zone_A + Overheat，但是 Normal (已恢复)
                // 验证 AI 是否能过滤掉非 Critical 状态
                zone = "Zone_A";
                status = "Normal";
                alertType = "Overheat";
                message = "Temperature normalized - auto recovery.";
            } else if (i % 100 == 2) {
                // 💣 干扰项 B (地雷): 虽然是 Zone_A + Critical，但是 Vibration (不是高温)
                // 验证 AI 是否能通过关键词 "高温" 过滤掉其他类型
                zone = "Zone_A";
                status = "Critical";
                alertType = "Vibration";
                message = "CRITICAL ALERT: Motor vibration instability!";
            } else {
                // 🌊 背景噪音 (Noise)
                zone = random.nextBoolean() ? "Zone_B" : "Zone_C";
                status = random.nextBoolean() ? "Normal" : "Warning";
                alertType = "Pressure";
                message = "Regular telemetry log.";
            }

            sensorId = "S-" + zone + "-" + String.format("%04d", i);

            // 拼接 NDJSON
            bulkBody.append("{ \"index\" : { \"_index\" : \"" + indexName + "\" } }\n");
            // 注意：message 是 text 类型，其他字段建议设为 keyword
            bulkBody.append(String.format(
                "{ \"sensor_id\": \"%s\", \"zone\": \"%s\", \"status\": \"%s\", \"alert_type\": \"%s\", \"message\": \"%s\", \"timestamp\": \"2026-02-11T12:00:00Z\" }\n",
                sensorId, zone, status, alertType, message
            ));
        }

        System.out.println("📊 预期目标 (Zone_A + Critical + Overheat): " + expectedTargets + " 条");

        // 1. 先删旧索引 (如果存在)
        Request deleteRequest = new Request.Builder()
                .url("http://localhost:9200/" + indexName)
                .delete()
                .build();
        try (Response r = client.newCall(deleteRequest).execute()) {} // 忽略错误

        // 2. 创建索引 (为了确保字段类型准确，显式创建 Mapping 是个好习惯，不过这里依赖动态映射也能跑，只要 AI 够聪明)
        // 这里的动态映射：zone, status, alert_type 可能会被推断为 text+keyword。
        // 我们依靠你的 IndexSchemaManager 去读它。

        // 3. 写入数据
        RequestBody body = RequestBody.create(bulkBody.toString(), MediaType.parse("application/x-ndjson"));
        Request request = new Request.Builder()
                .url("http://localhost:9200/_bulk")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                System.out.println("✅ " + totalDocs + " 条 IoT 数据注入成功！索引名: " + indexName);
            } else {
                System.out.println("❌ 写入失败: " + response.body().string());
            }
        }
    }
}