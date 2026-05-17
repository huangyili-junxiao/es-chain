package com.topview.eschain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topview.eschain.config.EnvConfig;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class IndexSchemaManager {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(IndexSchemaManager.class);

    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;
    private final OkHttpClient client;
    private final Map<String, String> schemaCache = new ConcurrentHashMap<>();

    // ========== 格式契约常量 ==========
    /**
     * WARNING: 此格式与 Text2DslService.enrichFieldsWithSamples() 共用！
     * 修改此处格式必须同步修改对应正则表达式。
     *
     * 格式: "- 字段名 (类型)"，例如: "- status (keyword)"
     */
    static final String FIELD_FORMAT = "- %s (%s)\n";

    /** 匹配单行字段格式的正则（用于 Text2DslService 解析） */
    static final String FIELD_LINE_REGEX = "^(- \\S+ \\([^)]+\\))";

    // 字段采样缓存：Key=indexName, Value=Map<字段名, 热门值列表>
    private final Map<String, Map<String, List<String>>> fieldSampleCache = new ConcurrentHashMap<>();
    // 缓存时间记录：Key=indexName, Value=缓存时间戳
    private final Map<String, Long> sampleCacheTimestamp = new ConcurrentHashMap<>();
    private static final long SAMPLE_CACHE_TTL_MS = 10 * 60 * 1000; // 10分钟

    public String getSimplifiedMapping(String indexName) {
        // 1. 缓存检查
        String cached = schemaCache.get(indexName);
        if (cached != null) {
            log.debug("返回缓存的 schema: {}", indexName);
            return cached;
        }

        // 2. 请求 ES
        String url = envConfig.getEs().getFullUrl() + "/" + indexName + "/_mapping";
        log.info("获取索引 schema: {}", url);

        try (Response response = client.newCall(new Request.Builder()
                .url(url)
                .get()
                .build()).execute()) {

            if (!response.isSuccessful()) {
                throw new RuntimeException("ES 请求失败，状态码: " + response.code() + ", URL: " + url);
            }

            String responseBody = response.body() != null ? response.body().string() : null;
            if (responseBody == null) {
                throw new RuntimeException("ES 响应为空，URL: " + url);
            }

            // 3. 数据清洗
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode indexNode = root.get(indexName);
            if (indexNode == null) {
                throw new RuntimeException("找不到索引: " + indexName + ", 响应: " + responseBody);
            }

            JsonNode properties = indexNode.path("mappings").path("properties");
            if (properties.isMissingNode()) {
                throw new RuntimeException("找不到 mappings.properties，响应: " + responseBody);
            }

            StringBuilder sb = new StringBuilder();
            // 递归处理所有字段，包括嵌套属性和 Multi-fields
            processProperties(properties, "", sb);

            String simplified = sb.toString();
            if (simplified.isEmpty()) {
                throw new RuntimeException("字段列表为空，索引: " + indexName);
            }

            log.info("\n================= [DEBUG] 索引 [{}] 的 Schema 菜单 =================\n{}\n==================================================================",
                    indexName, simplified);

            // 4. 存入缓存并返回
            schemaCache.put(indexName, simplified);
            log.debug("Schema 已缓存: {} ({} 字段)", indexName, sb.toString().split("\n").length);
            return simplified;

        } catch (IOException e) {
            throw new RuntimeException("获取索引 schema 失败: " + url, e);
        }
    }

    /**
     * 递归处理字段属性，支持嵌套对象和 Multi-fields 展平
     * @param properties JSON 节点
     * @param parentPath 父路径（用于嵌套字段）
     * @param sb 输出字符串构建器
     */
    private void processProperties(JsonNode properties, String parentPath, StringBuilder sb) {
        properties.fieldNames().forEachRemaining(fieldName -> {
            JsonNode fieldNode = properties.get(fieldName);
            String fullPath = parentPath.isEmpty() ? fieldName : parentPath + "." + fieldName;

            // 输出主字段类型
            String type = fieldNode.path("type").asText("object");
            sb.append(String.format(FIELD_FORMAT, fullPath, type));

            // 处理 Multi-fields（fields 属性）
            JsonNode fieldsNode = fieldNode.path("fields");
            if (!fieldsNode.isMissingNode()) {
                fieldsNode.fieldNames().forEachRemaining(subFieldName -> {
                    JsonNode subFieldNode = fieldsNode.get(subFieldName);
                    String subType = subFieldNode.path("type").asText("keyword");
                    String subFullPath = fullPath + "." + subFieldName;

                    // 如果主字段是 text 类型且子字段是 keyword 类型，添加警告提示
                    if ("text".equals(type) && "keyword".equals(subType)) {
                        subType = "keyword, 仅聚合/排序, 严禁用于搜索(Query)";
                    }

                    sb.append(String.format(FIELD_FORMAT, subFullPath, subType));
                });
            }

            // 递归处理嵌套对象的 properties
            JsonNode nestedProps = fieldNode.path("properties");
            if (!nestedProps.isMissingNode()) {
                processProperties(nestedProps, fullPath, sb);
            }
        });
    }

    /**
     * 获取所有非系统索引的名称列表
     * @return 业务索引名称列表
     */
    public List<String> getAllIndices() {
        String url = envConfig.getEs().getFullUrl() + "/_cat/indices?format=json&h=index";
        log.info("获取所有索引列表: {}", url);

        try (Response response = client.newCall(new Request.Builder()
                .url(url)
                .get()
                .build()).execute()) {

            if (!response.isSuccessful()) {
                log.error("获取索引列表失败，状态码: {}, URL: {}", response.code(), url);
                return new ArrayList<>();
            }

            String responseBody = response.body() != null ? response.body().string() : null;
            if (responseBody == null || responseBody.isEmpty()) {
                log.warn("获取索引列表返回空响应");
                return new ArrayList<>();
            }

            JsonNode root = objectMapper.readTree(responseBody);
            List<String> indices = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode node : root) {
                    String indexName = node.path("index").asText(null);
                    if (indexName != null && !indexName.startsWith(".")) {
                        indices.add(indexName);
                    }
                }
            }

            log.info("获取到 {} 个业务索引", indices.size());
            return indices;

        } catch (IOException e) {
            log.error("获取索引列表异常: {}", url, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取字段热门值采样（Top 10）
     * @param indexName 索引名称
     * @return Map<字段名, 热门值列表>
     */
    public Map<String, List<String>> getFieldSamples(String indexName) {
        // 1. 检查缓存是否有效
        if (isSampleCacheValid(indexName)) {
            log.debug("返回缓存的字段采样: {}", indexName);
            return fieldSampleCache.get(indexName);
        }

        log.info("开始获取字段采样: {}", indexName);

        // 2. 获取 Mapping，找出所有 keyword 类型字段
        String mapping = getSimplifiedMapping(indexName);
        Set<String> candidateFields = extractKeywordFields(mapping);
        log.debug("发现 {} 个 keyword 字段待筛选", candidateFields.size());

        // 3. 过滤掉高基数字段，得到枚举候选字段
        List<String> enumFields = new ArrayList<>();
        for (String field : candidateFields) {
            if (isEnumCandidate(field)) {
                enumFields.add(field);
            }
        }
        log.debug("筛选后剩余 {} 个枚举候选字段", enumFields.size());

        // 4. 如果没有候选字段，返回空
        if (enumFields.isEmpty()) {
            log.info("没有找到枚举候选字段");
            return new HashMap<>();
        }

        // 5. 批量聚合查询
        Map<String, List<String>> samples = fetchFieldSamples(indexName, enumFields);

        // 6. 更新缓存
        fieldSampleCache.put(indexName, samples);
        sampleCacheTimestamp.put(indexName, System.currentTimeMillis());

        log.info("字段采样完成，获取到 {} 个字段的采样数据", samples.size());
        return samples;
    }

    /**
     * 检查缓存是否有效（未过期）
     */
    private boolean isSampleCacheValid(String indexName) {
        Long timestamp = sampleCacheTimestamp.get(indexName);
        if (timestamp == null) {
            return false;
        }
        return System.currentTimeMillis() - timestamp < SAMPLE_CACHE_TTL_MS;
    }

    /**
     * 从简化的 Mapping 中提取 keyword 类型字段名
     */
    private Set<String> extractKeywordFields(String mapping) {
        Set<String> fields = new HashSet<>();
        String[] lines = mapping.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            // 格式: "- field_name (keyword)"
            int parenStart = line.lastIndexOf('(');
            int parenEnd = line.lastIndexOf(')');
            if (parenStart > 0 && parenEnd > parenStart) {
                String type = line.substring(parenStart + 1, parenEnd).trim();
                if ("keyword".equals(type)) {
                    String fieldName = line.substring(2, parenStart).trim();
                    fields.add(fieldName);
                }
            }
        }
        return fields;
    }

    /**
     * 判断字段是否为枚举候选（排除高基数字段）
     * @param fieldName 字段名
     * @return true=适合作为枚举提示，false=高基数字段，跳过
     */
    private boolean isEnumCandidate(String fieldName) {
        String lower = fieldName.toLowerCase();

        // 1. 排除 ID 类字段（以特定后缀结尾）
        if (lower.matches(".*(_|-)?(id|no|code)$")) {
            log.trace("跳过 ID 类字段: {}", fieldName);
            return false;
        }

        // 2. 排除时间相关字段
        if (lower.contains("time") || lower.contains("date") ||
            lower.contains("create") || lower.contains("update") ||
            lower.contains("timestamp")) {
            log.trace("跳过时间字段: {}", fieldName);
            return false;
        }

        // 3. 排除特定高基数字段
        String[] highCardinalityPatterns = {
            "uuid", "hash", "token", "trace", "session",
            "ip_", "_ip", "email", "url", "uri",
            "description", "content", "text", "body",
            "_at$"  // 以 _at 结尾的通常是时间戳
        };
        for (String pattern : highCardinalityPatterns) {
            if (lower.matches(".*" + pattern + ".*")) {
                log.trace("跳过高频基数字段: {} (匹配 {})", fieldName, pattern);
                return false;
            }
        }

        // 4. 字段名太长（>30字符）通常也不是枚举字段
        if (fieldName.length() > 30) {
            log.trace("跳过过长字段: {}", fieldName);
            return false;
        }

        return true;
    }

    /**
     * 批量执行字段聚合查询，获取热门值
     */
    private Map<String, List<String>> fetchFieldSamples(String indexName, List<String> fields) {
        Map<String, List<String>> samples = new HashMap<>();

        // 限制每次最多查询 5 个字段，避免请求体过大
        int batchSize = 5;
        for (int i = 0; i < fields.size(); i += batchSize) {
            int end = Math.min(i + batchSize, fields.size());
            List<String> batchFields = fields.subList(i, end);

            // 构建聚合查询 DSL
            Map<String, Object> aggs = new LinkedHashMap<>();
            for (String field : batchFields) {
                Map<String, Object> agg = new HashMap<>();
                agg.put("terms", Map.of("field", field, "size", 10));
                aggs.put("agg_" + field.replace(".", "_"), agg);
            }

            Map<String, Object> dsl = new HashMap<>();
            dsl.put("size", 0);
            dsl.put("aggs", aggs);

            String url = envConfig.getEs().getFullUrl() + "/" + indexName + "/_search";
            try {
                String jsonBody = objectMapper.writeValueAsString(dsl);
                RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.warn("字段聚合查询失败，状态码: {}, 字段: {}", response.code(), batchFields);
                        continue;
                    }

                    String responseBody = response.body() != null ? response.body().string() : null;
                    if (responseBody == null) continue;

                    JsonNode root = objectMapper.readTree(responseBody);
                    JsonNode aggsNode = root.path("aggregations");

                    for (String field : batchFields) {
                        String aggKey = "agg_" + field.replace(".", "_");
                        JsonNode aggNode = aggsNode.path(aggKey);
                        JsonNode buckets = aggNode.path("buckets");

                        List<String> values = new ArrayList<>();
                        if (buckets.isArray()) {
                            for (JsonNode bucket : buckets) {
                                String key = bucket.path("key").asText(null);
                                if (key != null && !key.isEmpty()) {
                                    values.add(key);
                                }
                            }
                        }

                        if (!values.isEmpty()) {
                            samples.put(field, values);
                            log.debug("字段 {} 采样: {}", field, values);
                        }
                    }
                }

            } catch (IOException e) {
                log.warn("字段聚合查询异常: {}, 字段: {}", e.getMessage(), batchFields);
            }
        }

        return samples;
    }

    /**
     * 获取所有业务索引的详细统计信息
     * @return 索引详细信息列表，每项包含 name, docs_count, size_bytes, created_at
     */
    public List<Map<String, Object>> getIndexDetails() {
        String url = envConfig.getEs().getFullUrl() +
                "/_cat/indices?format=json&h=index,docs.count,store.size,creation.date.string";
        log.info("获取索引详细信息: {}", url);

        List<Map<String, Object>> result = new ArrayList<>();

        try (Response response = client.newCall(new Request.Builder()
                .url(url)
                .get()
                .build()).execute()) {

            if (!response.isSuccessful()) {
                log.error("获取索引详情失败，状态码: {}, URL: {}", response.code(), url);
                return result;
            }

            String responseBody = response.body() != null ? response.body().string() : null;
            if (responseBody == null || responseBody.isEmpty()) {
                log.warn("获取索引详情返回空响应");
                return result;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String indexName = node.path("index").asText(null);
                    if (indexName == null || indexName.startsWith(".")) {
                        continue; // 跳过系统索引
                    }

                    Map<String, Object> item = new HashMap<>();
                    item.put("name", indexName);

                    // docs.count
                    String docsCount = node.path("docs.count").asText("0");
                    item.put("docs_count", Long.parseLong(docsCount));

                    // store.size - 尝试解析为字节数
                    String sizeStr = node.path("store.size").asText("0b");
                    item.put("size_bytes", parseSizeToBytes(sizeStr));

                    // creation.date.string
                    String createdAt = node.path("creation.date.string").asText(null);
                    item.put("created_at", createdAt);

                    result.add(item);
                }
            }

            log.info("获取到 {} 个索引的详细信息", result.size());
            return result;

        } catch (IOException e) {
            log.error("获取索引详情异常: {}", url, e);
            return result;
        }
    }

    /**
     * 获取索引的原始 Mapping（JSON 结构）
     * @param indexName 索引名称
     * @return 原始 Mapping 结构（包含 mappings 和 properties）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRawMapping(String indexName) {
        String url = envConfig.getEs().getFullUrl() + "/" + indexName + "/_mapping";
        log.info("获取原始 Mapping: {}", url);

        try (Response response = client.newCall(new Request.Builder()
                .url(url)
                .get()
                .build()).execute()) {

            if (!response.isSuccessful()) {
                throw new RuntimeException("获取 Mapping 失败，状态码: " + response.code() + ", URL: " + url);
            }

            String responseBody = response.body() != null ? response.body().string() : null;
            if (responseBody == null) {
                throw new RuntimeException("获取 Mapping 返回空响应: " + url);
            }

            // 解析为 Map 返回
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode indexNode = root.get(indexName);

            if (indexNode == null) {
                throw new RuntimeException("找不到索引: " + indexName);
            }

            // 返回整个 mappings 节点的内容
            JsonNode mappingsNode = indexNode.path("mappings");

            return objectMapper.convertValue(mappingsNode,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));

        } catch (IOException e) {
            throw new RuntimeException("获取 Mapping 异常: " + url, e);
        }
    }

    /**
     * 解析 ES 返回的存储大小字符串为字节数
     * 支持格式: "10kb", "5mb", "2gb", "1tb", "500b"
     */
    private long parseSizeToBytes(String sizeStr) {
        if (sizeStr == null || sizeStr.isEmpty() || "0".equals(sizeStr)) {
            return 0L;
        }

        try {
            String upper = sizeStr.toUpperCase().trim();
            double value = Double.parseDouble(upper.replaceAll("[A-Z]+$", ""));
            String unit = upper.replaceAll("^[0-9.]+", "");

            return switch (unit) {
                case "B" -> (long) value;
                case "KB" -> (long) (value * 1024);
                case "MB" -> (long) (value * 1024 * 1024);
                case "GB" -> (long) (value * 1024 * 1024 * 1024);
                case "TB" -> (long) (value * 1024 * 1024 * 1024 * 1024);
                default -> 0L;
            };
        } catch (Exception e) {
            log.warn("解析存储大小失败: {}", sizeStr);
            return 0L;
        }
    }
}
