package com.topview.eschain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topview.eschain.config.EnvConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EsQueryExecutor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EsQueryExecutor.class);

    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;
    private final OkHttpClient client;

    public EsQueryExecutor(EnvConfig envConfig, ObjectMapper objectMapper, OkHttpClient client) {
        this.envConfig = envConfig;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    /**
     * 执行 ES 查询
     * @param indexName 索引名称
     * @param dslJson JSON 格式的查询 DSL
     * @return 查询结果，包含 list（文档列表）和 aggs（聚合结果）
     */
    public Map<String, Object> executeQuery(String indexName, String dslJson) {
        return executeQuery(indexName, dslJson, 0);
    }

    /**
     * 执行 ES 查询（带超时）
     * @param indexName 索引名称
     * @param dslJson JSON 格式的查询 DSL
     * @param timeoutMs 超时时间（毫秒），<=0 表示使用 ES 默认超时
     * @return 查询结果，包含 list（文档列表）和 aggs（聚合结果）
     */
    public Map<String, Object> executeQuery(String indexName, String dslJson, int timeoutMs) {
        // 构造请求 URL
        String url = envConfig.getEs().getFullUrl() + "/" + indexName + "/_search";
        if (timeoutMs > 0) {
            url += "?timeout=" + timeoutMs + "ms";
        }
        log.info("执行 ES 查询: {}", url);
        log.debug("DSL 内容: {}", dslJson);

        RequestBody body = RequestBody.create(dslJson, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            // 处理错误响应
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                String errorMsg = String.format("ES 查询失败，状态码: %d, URL: %s, 错误: %s",
                        response.code(), url, errorBody);
                log.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }

            String responseBody = response.body() != null ? response.body().string() : null;
            if (responseBody == null) {
                log.warn("ES 返回空响应，URL: {}", url);
                return buildEmptyResult();
            }

            log.debug("ES 响应: {}", responseBody);

            // 解析响应
            JsonNode root = objectMapper.readTree(responseBody);

            // 1. 提取 hits
            JsonNode hits = root.path("hits").path("hits");
            List<Map<String, Object>> docList = new ArrayList<>();
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    JsonNode source = hit.path("_source");
                    if (!source.isMissingNode()) {
                        docList.add(objectMapper.convertValue(source,
                                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class)));
                    }
                }
            }

            // 2. 提取 aggregations
            JsonNode aggsNode = root.path("aggregations");
            Map<String, Object> aggs = new HashMap<>();
            if (!aggsNode.isMissingNode() && aggsNode.isObject()) {
                aggsNode.fieldNames().forEachRemaining(aggName -> {
                    JsonNode aggValue = aggsNode.get(aggName);
                    aggs.put(aggName, extractAggregationValue(aggValue));
                });
            }

            // 3. 提取真实的 total hits (ES 7+ 结构: hits.total.value)
            long totalHits = extractTotalHits(root);

            log.info("查询返回 {} 条文档, {} 个聚合, 总数: {}", docList.size(), aggs.size(), totalHits);

            // 4. 构建返回结构
            Map<String, Object> result = new HashMap<>();
            result.put("list", docList);
            result.put("aggs", aggs);
            result.put("total", totalHits);
            return result;

        } catch (IOException e) {
            log.error("ES 查询异常，URL: {}, DSL: {}", url, dslJson, e);
            throw new RuntimeException("ES 查询失败: " + url, e);
        }
    }

    /**
     * 递归提取聚合值，支持桶聚合和指标聚合
     */
    private Object extractAggregationValue(JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }

        // 指标聚合：如 {"value": 95}
        if (node.has("value")) {
            JsonNode valueNode = node.get("value");
            return valueNode.isNull() ? null : valueNode.doubleValue();
        }

        // 桶聚合：如 {"buckets": [{"key": "a", "doc_count": 10}, ...]}
        if (node.has("buckets")) {
            List<Map<String, Object>> buckets = new ArrayList<>();
            JsonNode bucketsNode = node.get("buckets");
            if (bucketsNode.isArray()) {
                for (JsonNode bucket : bucketsNode) {
                    Map<String, Object> bucketMap = new HashMap<>();
                    // 提取 key（可能是字符串或数字）
                    if (bucket.has("key")) {
                        JsonNode keyNode = bucket.get("key");
                        if (keyNode.isTextual()) {
                            bucketMap.put("key", keyNode.asText());
                        } else if (keyNode.isNumber()) {
                            bucketMap.put("key", keyNode.isIntegralNumber() ? keyNode.longValue() : keyNode.doubleValue());
                        }
                    }
                    // 提取 doc_count
                    if (bucket.has("doc_count")) {
                        bucketMap.put("doc_count", bucket.get("doc_count").longValue());
                    }
                    // 递归处理嵌套聚合
                    for (String field : new String[]{"aggregations", "subaggregations"}) {
                        if (bucket.has(field)) {
                            JsonNode subAggs = bucket.get(field);
                            Map<String, Object> subAggMap = new HashMap<>();
                            subAggs.fieldNames().forEachRemaining(aggName -> {
                                subAggMap.put(aggName, extractAggregationValue(subAggs.get(aggName)));
                            });
                            if (!subAggMap.isEmpty()) {
                                bucketMap.put("aggs", subAggMap);
                            }
                        }
                    }
                    buckets.add(bucketMap);
                }
            }
            return buckets;
        }

        // 其他情况：转为 Map
        return objectMapper.convertValue(node,
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
    }

    private Map<String, Object> buildEmptyResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("list", new ArrayList<>());
        result.put("aggs", new HashMap<>());
        result.put("total", 0L);
        return result;
    }

    /**
     * 提取真实的文档总数 (ES 7+ 结构: hits.total.value)
     */
    private long extractTotalHits(JsonNode root) {
        try {
            JsonNode totalNode = root.path("hits").path("total");
            if (totalNode.isMissingNode()) {
                return 0L;
            }
            // ES 7+ 返回对象结构: {"value": 123, "relation": "eq"}
            if (totalNode.isObject() && totalNode.has("value")) {
                return totalNode.get("value").asLong();
            }
            // ES 6 及以下直接返回数字
            if (totalNode.isNumber()) {
                return totalNode.asLong();
            }
            return 0L;
        } catch (Exception e) {
            log.warn("提取 total hits 失败: {}", e.getMessage());
            return 0L;
        }
    }
}
