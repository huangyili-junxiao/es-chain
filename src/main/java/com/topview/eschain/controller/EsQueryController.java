package com.topview.eschain.controller;

import com.topview.eschain.config.EnvConfig;
import com.topview.eschain.dto.EsQueryRequest;
import com.topview.eschain.dto.EsQueryResponse;
import com.topview.eschain.dto.EsQueryResponse.ErrorDetail;
import com.topview.eschain.service.EsQueryExecutor;
import com.topview.eschain.service.IndexSchemaManager;
import com.topview.eschain.service.Text2DslService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.web.bind.annotation.RequestBody;
import okhttp3.Response;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/")
@RequiredArgsConstructor
public class EsQueryController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EsQueryController.class);

    private final Text2DslService text2DslService;
    private final EsQueryExecutor queryExecutor;
    private final IndexSchemaManager indexSchemaManager;
    private final EnvConfig envConfig;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询接口 - 标准流水线
     * POST /query
     */
    @PostMapping("/query")
    public EsQueryResponse query(@RequestBody EsQueryRequest request) {
        EsQueryResponse response = new EsQueryResponse();
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: 参数校验
            String question = request.getQuestion();
            if (question == null || question.trim().isEmpty()) {
                throw new IllegalArgumentException("Question cannot be empty");
            }
            log.info("收到查询请求: request_id={}, question={}", request.getRequestId(), question);

            // Step 2: 路由选择
            String indexName = text2DslService.selectIndex(question);
            log.info("智能路由选中索引: {}", indexName);

            // Step 3: DSL 生成（传入 maxResults 限制）
            int maxResults = request.getOptions() != null ? request.getOptions().getMaxResults() : 10;
            int timeoutMs = request.getOptions() != null ? request.getOptions().getTimeoutMs() : 10000;
            String dslString = text2DslService.generateDsl(indexName, question, maxResults);
            log.info("生成的 DSL: {}", dslString);

            // Step 4: 执行查询（传入用户指定的超时）
            Map<String, Object> queryResult = queryExecutor.executeQuery(indexName, dslString, timeoutMs);

            // Step 5: 组装响应（契约对齐：data 直接是列表，aggs/totalHits 进 metadata）
            Object rawList = queryResult.get("list");
            List<Map<String, Object>> dataList = List.of();
            if (rawList instanceof List<?> list) {
                dataList = list.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
            }

            Object rawAggs = queryResult.get("aggs");
            Map<String, Object> aggs = rawAggs instanceof Map<?, ?> m ?
                    m.entrySet().stream().collect(HashMap::new, (map, e) -> map.put(String.valueOf(e.getKey()), e.getValue()), HashMap::putAll)
                    : new HashMap<>();

            long totalHits = 0L;
            Object totalObj = queryResult.get("total");
            if (totalObj instanceof Number) {
                totalHits = ((Number) totalObj).longValue();
            } else {
                totalHits = dataList.size();
            }

            String summary = String.format(
                    "✨ 查询完成！在索引 [%s] 中为您找到了 %d 条相关数据。\uD83D\uDCCB 已生成统计分布，并为您截取了前 %d 条最具代表性的样本供参考。",
                    indexName, totalHits, dataList.size());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("generated_dsl", dslString);
            metadata.put("index", indexName);
            metadata.put("execution_time_ms", System.currentTimeMillis() - startTime);
            metadata.put("total_hits", totalHits);
            metadata.put("aggregations", aggs);

            response.setSuccess(true);
            response.setSource("es");
            response.setSummary(summary);
            response.setData(dataList);
            response.setMetadata(metadata);
            response.setError(null);

            log.info("查询成功: request_id={}, hits={}, time_ms={}",
                    request.getRequestId(), totalHits, metadata.get("execution_time_ms"));
            return response;

        } catch (Exception e) {
            log.error("查询失败: {}", e.getMessage(), e);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("execution_time_ms", System.currentTimeMillis() - startTime);

            String code = getErrorCode(e);
            ErrorDetail errorDetail = new ErrorDetail();
            errorDetail.setCode(code);
            errorDetail.setMessage(e.getMessage());
            errorDetail.setRetriable(isRetriable(code));

            response.setSuccess(false);
            response.setSource("es");
            response.setSummary("ES 查询失败: " + e.getMessage());
            response.setData(null);
            response.setMetadata(metadata);
            response.setError(errorDetail);

            return response;
        }
    }

    /**
     * 获取索引列表
     * GET /indices
     */
    @GetMapping("/indices")
    public Map<String, Object> getIndices() {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> indexList = indexSchemaManager.getIndexDetails();
        response.put("indices", indexList);
        return response;
    }

    /**
     * 获取索引 Mapping
     * GET /mapping/{index}
     */
    @GetMapping("/mapping/{index}")
    public Map<String, Object> getMapping(@PathVariable String index) {
        Map<String, Object> response = new HashMap<>();
        response.put("index", index);
        response.put("mappings", indexSchemaManager.getRawMapping(index));
        return response;
    }

    /**
     * 直接执行 DSL 查询（调试用）
     * POST /dsl
     */
    @PostMapping("/dsl")
    public Map<String, Object> executeDsl(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            String index = (String) request.get("index");
            Object dsl = request.get("dsl");
            if (index == null || dsl == null) {
                throw new IllegalArgumentException("Index and dsl are required");
            }

            String dslString = dsl instanceof Map ?
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(dsl) : dsl.toString();

            Map<String, Object> result = queryExecutor.executeQuery(index, dslString);

            // 组装 hits 结构
            Map<String, Object> hits = new HashMap<>();
            Object totalObj = result.get("total");
            long total = totalObj instanceof Number ? ((Number) totalObj).longValue() : 0L;
            hits.put("total", total);
            hits.put("hits", result.get("list"));

            response.put("success", true);
            response.put("took_ms", System.currentTimeMillis() - startTime);
            response.put("hits", hits);
            response.put("aggregations", result.get("aggs"));
            return response;

        } catch (Exception e) {
            log.error("DSL 执行失败: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return response;
        }
    }

    /**
     * DSL 验证（调用 ES 官方验证接口）
     * POST /validate
     */
    @PostMapping("/validate")
    public Map<String, Object> validateDsl(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        String index = (String) request.get("index");
        Object dsl = request.get("dsl");

        if (index == null || dsl == null) {
            response.put("valid", false);
            response.put("warnings", List.of("Missing index or dsl"));
            response.put("explanation", "Request must contain 'index' and 'dsl' fields");
            return response;
        }

        // Step 1: 转换为 JSON 字符串
        String dslString;
        try {
            dslString = dsl instanceof String ? (String) dsl : objectMapper.writeValueAsString(dsl);
        } catch (Exception e) {
            response.put("valid", false);
            response.put("warnings", List.of());
            response.put("explanation", "Invalid DSL format: " + e.getMessage());
            return response;
        }

        // Step 2: 调用 ES 验证接口
        try {
            return validateByElasticsearch(index, dslString);
        } catch (Exception e) {
            log.warn("ES 验证接口调用失败，降级为本地检查: {}", e.getMessage());
            // 容错：降级回本地基础语法检查
            return fallbackLocalValidation(dsl);
        }
    }

    /**
     * 调用 ES _validate/query 接口进行验证
     * 注意：ES 的 _validate/query 只支持 query 部分，需要剥离 size, aggs, sort 等顶级字段
     */
    private Map<String, Object> validateByElasticsearch(String index, String dslString) {
        Map<String, Object> response = new HashMap<>();

        // Step 1: 提取 query 部分（ES 只验证 query）
        String queryOnlyDsl;
        try {
            JsonNode root = objectMapper.readTree(dslString);
            JsonNode queryNode = root.path("query");

            if (queryNode.isMissingNode() || !queryNode.isObject()) {
                response.put("valid", false);
                response.put("warnings", List.of());
                response.put("explanation", "DSL must contain a valid 'query' field");
                return response;
            }

            // 构建只包含 query 的验证体
            java.util.Map<String, Object> queryOnly = new java.util.HashMap<>();
            queryOnly.put("query", objectMapper.convertValue(queryNode, java.util.Map.class));
            queryOnlyDsl = objectMapper.writeValueAsString(queryOnly);

            log.debug("提取 query 部分进行验证: {}", queryOnlyDsl);
        } catch (Exception e) {
            response.put("valid", false);
            response.put("warnings", List.of());
            response.put("explanation", "Failed to parse DSL: " + e.getMessage());
            return response;
        }

        // Step 2: 调用 ES 验证接口
        String url = envConfig.getEs().getFullUrl() + "/" + index + "/_validate/query?explain=true";
        log.info("调用 ES 验证接口: {}", url);

        try {
            okhttp3.RequestBody body = okhttp3.RequestBody.create(queryOnlyDsl, MediaType.parse("application/json"));
            Request esRequest = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response esResponse = client.newCall(esRequest).execute()) {
                if (!esResponse.isSuccessful()) {
                    response.put("valid", false);
                    response.put("warnings", List.of("ES returned HTTP " + esResponse.code()));
                    response.put("explanation", "ES validation request failed");
                    return response;
                }

                String responseBody = esResponse.body() != null ? esResponse.body().string() : null;
                if (responseBody == null) {
                    response.put("valid", false);
                    response.put("warnings", List.of());
                    response.put("explanation", "Empty response from ES");
                    return response;
                }

                JsonNode root = objectMapper.readTree(responseBody);
                boolean valid = root.path("valid").asBoolean(false);

                response.put("valid", valid);

                if (valid) {
                    response.put("warnings", List.of());
                    response.put("explanation", "DSL is valid according to ES");
                } else {
                    // 提取错误信息
                    JsonNode explanations = root.path("explanations");
                    java.util.List<String> errorList = new java.util.ArrayList<>();

                    if (explanations.isArray()) {
                        for (JsonNode exp : explanations) {
                            String error = exp.path("error").asText("");
                            if (!error.isEmpty()) {
                                errorList.add(error);
                            }
                        }
                    }

                    // 如果没有 explanations，尝试从根节点获取
                    if (errorList.isEmpty()) {
                        String rootError = root.path("error").asText("");
                        if (!rootError.isEmpty()) {
                            errorList.add(rootError);
                        }
                    }

                    response.put("warnings", errorList.isEmpty() ? List.of() : errorList);
                    response.put("explanation", errorList.isEmpty() ?
                            "DSL is invalid but no specific error was returned" :
                            String.join("; ", errorList));
                }

                return response;
            }

        } catch (IOException e) {
            throw new RuntimeException("ES validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 降级：本地基础语法检查（当 ES 不可用时）
     */
    private Map<String, Object> fallbackLocalValidation(Object dsl) {
        Map<String, Object> response = new HashMap<>();

        try {
            JsonNode node = dsl instanceof String ?
                    objectMapper.readTree((String) dsl) : objectMapper.valueToTree(dsl);

            // 基础检查：必须是对象，必须包含 query
            if (!node.isObject()) {
                response.put("valid", false);
                response.put("warnings", List.of());
                response.put("explanation", "DSL must be a JSON object");
                return response;
            }

            if (!node.has("query")) {
                response.put("valid", false);
                response.put("warnings", List.of());
                response.put("explanation", "DSL must contain 'query' field");
                return response;
            }

            response.put("valid", true);
            response.put("warnings", List.of("(ES unavailable - basic format check only)"));
            response.put("explanation", "Basic format check passed (ES validation skipped)");
            return response;

        } catch (Exception e) {
            response.put("valid", false);
            response.put("warnings", List.of());
            response.put("explanation", "Invalid DSL format: " + e.getMessage());
            return response;
        }
    }

    /**
     * 健康检查
     * GET /health
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();

        // 并行检查各依赖
        Map<String, Object> esHealth = checkEsHealth();
        Map<String, Object> llmHealth = checkLlmHealth();

        // 决定整体状态
        boolean esHealthy = "healthy".equals(esHealth.get("status"));
        boolean llmHealthy = "healthy".equals(llmHealth.get("status"));
        String overallStatus;

        if (esHealthy && llmHealthy) {
            overallStatus = "healthy";
        } else if (!esHealthy && !llmHealthy) {
            overallStatus = "unhealthy";
        } else {
            overallStatus = "degraded";
        }

        response.put("status", overallStatus);
        response.put("service", "es-chain");
        response.put("version", "1.0.0");

        Map<String, Object> dependencies = new HashMap<>();
        dependencies.put("elasticsearch", esHealth);
        dependencies.put("llm", llmHealth);
        response.put("dependencies", dependencies);

        return response;
    }

    /**
     * 检查 Elasticsearch 健康状态
     */
    private Map<String, Object> checkEsHealth() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            String url = envConfig.getEs().getFullUrl() + "/_cat/health";
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = client.newCall(request).execute()) {
                long latency = System.currentTimeMillis() - startTime;
                if (response.isSuccessful()) {
                    result.put("status", "healthy");
                    result.put("latency_ms", latency);
                } else {
                    result.put("status", "down");
                    result.put("latency_ms", latency);
                    result.put("error", "HTTP " + response.code());
                }
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.warn("ES 健康检查失败: {}", e.getMessage());
            result.put("status", "down");
            result.put("latency_ms", latency);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 检查 LLM 连接状态（TCP Ping + HTTP 检查）
     */
    private Map<String, Object> checkLlmHealth() {
        Map<String, Object> result = new HashMap<>();

        try {
            String baseUrl = envConfig.getLlm().getBaseUrl();
            // 解析 host 和 port
            String host = baseUrl.replace("https://", "")
                               .replace("http://", "")
                               .split("/")[0]
                               .split(":")[0];
            int port = baseUrl.contains("https://") ? 443 : 80;

            // TCP Ping 检测
            long startTime = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                long latency = System.currentTimeMillis() - startTime;

                // 尝试 HTTP HEAD 请求验证
                String apiUrl = baseUrl.replace("/v1", "") + "/models";
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .head()
                        .addHeader("Authorization", "Bearer test")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    // 只要能连接到 server 就认为是 healthy
                    result.put("status", "healthy");
                    result.put("latency_ms", latency);
                    result.put("model", envConfig.getLlm().getModel());
                }
            }
        } catch (Exception e) {
            log.warn("LLM 健康检查失败: {}", e.getMessage());
            result.put("status", "unreachable");
            result.put("latency_ms", -1);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 根据错误码判断是否可以重试
     */
    private boolean isRetriable(String code) {
        return switch (code) {
            case "TIMEOUT", "ES_ERROR", "LLM_ERROR" -> true;
            case "INDEX_NOT_FOUND", "INVALID_DSL", "INTERNAL_ERROR" -> false;
            default -> false;
        };
    }

    /**
     * 根据异常类型映射错误码
     */
    private String getErrorCode(Exception e) {
        String message = e.getMessage();
        if (message == null) return "INTERNAL_ERROR";

        if (message.contains("索引不存在") || message.contains("index not found")) {
            return "INDEX_NOT_FOUND";
        }
        if (message.contains("DSL") || message.contains("json")) {
            return "INVALID_DSL";
        }
        if (message.contains("超时") || message.contains("timeout")) {
            return "TIMEOUT";
        }
        if (message.contains("LLM") || message.contains("API")) {
            return "LLM_ERROR";
        }
        if (message.contains("ES") || message.contains("Elasticsearch")) {
            return "ES_ERROR";
        }
        return "INTERNAL_ERROR";
    }
}
