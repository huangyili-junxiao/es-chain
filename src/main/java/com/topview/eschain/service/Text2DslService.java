package com.topview.eschain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.topview.eschain.config.EnvConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class Text2DslService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(Text2DslService.class);

    private final EnvConfig envConfig;
    private final IndexSchemaManager schemaManager;
    private final ObjectMapper objectMapper;
    private final OkHttpClient client;

    public Text2DslService(EnvConfig envConfig,
                           IndexSchemaManager schemaManager,
                           ObjectMapper objectMapper,
                           OkHttpClient client) {
        this.envConfig = envConfig;
        this.schemaManager = schemaManager;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    /**
     * 调用 LLM API（OpenAI 兼容格式）
     */
    private String callLlmApi(String systemPrompt, String userMessage) {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", envConfig.getLlm().getModel());
        requestBody.put("temperature", 0);

        Map<String, Object> message1 = new HashMap<>();
        message1.put("role", "system");
        message1.put("content", systemPrompt);

        Map<String, Object> message2 = new HashMap<>();
        message2.put("role", "user");
        message2.put("content", userMessage);

        requestBody.put("messages", new Map[]{message1, message2});

        // 构建 URL，处理末尾斜杠
        String baseUrl = envConfig.getLlm().getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String url = baseUrl + "/chat/completions";

        log.info("调用 LLM API: {}", url);

        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + envConfig.getLlm().getApiKey())
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("LLM API 请求失败，状态码: " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : null;
                if (responseBody == null) {
                    throw new RuntimeException("LLM API 响应为空");
                }

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode contentNode = root.path("choices").get(0).path("message").path("content");

                if (contentNode.isMissingNode()) {
                    throw new RuntimeException("无法提取 LLM 返回内容，响应: " + responseBody);
                }

                String result = contentNode.asText();
                log.debug("LLM 返回内容长度: {} 字符", result.length());
                return result;
            }

        } catch (IOException e) {
            throw new RuntimeException("LLM API 调用失败: " + url, e);
        }
    }

    public String generateDsl(String indexName, String query) {
        return generateDsl(indexName, query, 50);
    }

    /**
     * 将自然语言查询转换为 ES DSL
     * @param indexName 索引名称
     * @param query 用户查询语句
     * @param maxResults 最大返回结果数（覆盖默认 size）
     * @return 纯 JSON 格式的 ES 查询 DSL
     */
    public String generateDsl(String indexName, String query, int maxResults) {
        // 1. 获取情报
        String mapping = schemaManager.getSimplifiedMapping(indexName);
        log.info("为索引 {} 生成 DSL，用户查询: {}，maxResults: {}", indexName, query, maxResults);

        // 2. 获取字段采样数据（用于动态 Schema 增强）
        Map<String, List<String>> fieldSamples = schemaManager.getFieldSamples(indexName);
        log.debug("获取到 {} 个字段的采样数据", fieldSamples.size());

        // 3. 构建增强后的字段描述（注入样本值）
        String enhancedFields = enrichFieldsWithSamples(mapping, fieldSamples);

        // size 上限策略：maxResults 可以是 0（仅聚合），但永远不能超过 100
        int targetSize = Math.min(Math.max(0, maxResults), 100);
        String sizeRule = maxResults == 0
                ? "- 当前请求要求仅返回统计（maxResults=0），**必须设置 size: 0**，但仍需生成 aggs 聚合。"
                : String.format("- 默认 size: %d（提供充足样本）。\n                - **硬限制 size: 100**（防止系统过载）。", targetSize);

        // 4. 构建 System Prompt
        String systemPrompt = String.format("""
                你是一个 Elasticsearch DSL 生成专家。

                ## 生成策略 (Samples + Statistics)

                【1. 数量控制 (Safety First)】
                %s
                - 规则：如果用户要求的数量超过 100（如“全部”、“1000条”），**强制设置 size 为 100**，严禁突破此底线。

                【2. 统计补偿 (Analysis Mode)】
                当用户意图是「分析」、「统计」、「分布」或「概览」时：
                - **必须**在 Query 同级生成 `aggs` 聚合部分。
                - **严禁设置 size: 0**：除非用户明确要求"仅统计数量"或"不要具体数据"，否则必须返回**代表性样本**（size 保持默认 %d 或 100）。
                - **样本与统计共存**：你的目标是让用户既看到宏观数据（Aggs），又能看到微观证据（Hits）。
                - 策略：
                  - 状态/枚举字段 -> Terms Aggregation
                  - 数值/时间字段 -> Stats / Date Histogram
                - **类型严格限制（关键）**：
                  - `terms` 聚合**只能**用于 Schema 中标记为 `(keyword)` 的字段。
                  - **严禁**对标记为 `(text)` 的字段进行聚合（如备注、内容、评论等），因为这会导致内存溢出报错。
                  - 如果找不到合适的 `keyword` 字段来统计，**宁可不生成 aggs，也不要强行统计 text 字段**。

                【2.5 多样性采样 (Diversity Mode)】
                当用户意图包含「分析」、「各类」、「不同」或「概览」时，为避免返回的样本千篇一律：
                - **尝试**在 DSL 顶层增加 `"collapse": { "field": "字段名" }` 参数。
                - **严格白名单检查**：
                  1. 请仔细检查下方的【当前索引结构】字段列表。
                  2. **类型单选制**：只有字段类型在下方列表中明确标记为 `(keyword)` 时，才**允许**考虑用于 collapse。
                  3. **直接复制列表中的字段名**，严禁修改或拼接。
                  4. **严禁强行折叠**：如果找不到符合条件的 keyword 字段进行折叠，**必须直接删除 collapse 参数**，不要生成无效或错误的折叠规则。**绝对禁止**使用 text 或 date 字段进行折叠。
                  5. **物理封杀 Text/Date**：严禁对标记为 `(text)`、`(date)` 或数值类型的字段进行折叠。哪怕它是你唯一认识的字段，只要不是 keyword，也**绝对禁止**生成 collapse 参数。
                  6. **字段名匹配**：禁止自行拼接 `.keyword` 后缀。必须直接从【当前索引结构】中复制那个标记为 `(keyword)` 的原名。

                【3. 必须包含的 DSL 结构 (Mandatory Structure)】
                为了防止大字段阻塞网络，你生成的 JSON **必须** 包含 `_source` 字段进行裁剪。请严格参考以下 JSON 骨架：
                {
                  "size": %d,
                  "_source": {
                    "excludes": ["*_base64", "binary", "embedding", "vector", "blob", "*_raw"]
                  },
                  "query": { ... },
                  "aggs": { ... }, // 根据策略 2 生成（可选）
                  "collapse": { ... } // 根据策略 2.5 生成（可选）
                }

                【4. 基础语法规则 (必须严格遵守)】
                - **字段名称铁律 (最高优先级)**：
                  - **照抄原则**：Schema 列表里写的是什么，你就必须用什么。
                    - Schema: `- lawyer_name (keyword)` -> DSL: `"field": "lawyer_name"` (❌严禁写 lawyer_name.keyword)
                    - Schema: `- title.keyword (keyword)` -> DSL: `"field": "title.keyword"` (✅允许)
                  - **聚合/折叠检查**：在使用 `aggs` 或 `collapse` 之前，必须在 Schema 列表中找到**完全一致**的字段名。如果找不到（例如你想用 .keyword 但列表中没有），**立刻放弃**该聚合/折叠操作，绝对不要自己造词。
                - **查询类型匹配原则 (Match vs Term)**：
                  - **搜索/查找/包含**（针对标题、内容、描述）：**必须**使用 `match` 查询 `text` 类型字段（如 `title`, `summary`）。**严禁**对这些字段使用 `term` 查询其 `.keyword` 子字段（除非用户明确要求精确匹配完整标题）。
                  - **筛选/状态/分类**（针对枚举值）：**必须**使用 `term` 查询 `keyword` 类型字段（如 `status`, `priority`）。
                - ID 查询规则：如果用户查询 "ID" 且 Mapping 中不存在名为 "id" 的业务字段，**必须**针对系统字段 "_id" 生成查询（使用 {"term": {"_id": "值"}} 或 {"ids": {"values": ["值"]}}）。
                - 时间范围 -> {"gte": "now-Xd/d", "lte": "now"}
                - 严禁包含 Markdown 代码块标记（如 ```json），只输出纯 JSON。
                - **Bool 结构铁律 (严禁嵌套)**：
                  - `must`, `must_not`, `should`, `filter` 必须作为 `bool` 对象的**直接子属性**（兄弟关系）。
                  - **绝对禁止**将它们放在数组内部！
                  - ❌ 错误（死刑）：`"must": [ { "must_not": { ... } } ]`
                  - ✅ 正确：`"bool": { "must": [...], "must_not": [...] }`
                  - **逻辑互斥修正**：如果既要“是 A 或 B”又要“不是 C”，请使用 `"bool": { "must": [{ "terms": { "status": ["A", "B"] } }], "must_not": [{ "term": { "status": "C" } }] }`。
                - **多值互斥原则 (AND vs OR)**：
                  - 当针对**同一个字段**筛选多个值时（例如 status 既可是 A 也可是 B），**严禁**使用多个 `must` 子句（逻辑 AND），因为一个字段不可能同时等于两个不同的值。
                  - **正确做法**：使用 `terms` 查询（逻辑 OR）。
                    - ❌ 错误：`must: [ {term: {status: A}}, {term: {status: B}} ]`
                    - ✅ 正确：`must: [ {terms: {status: [A, B]}} ]`
                - **展示意图分离 (View vs Filter)**：
                  - 如果用户说"看看xxx"、"显示xxx"、"xxx是什么"，这是在请求**返回该字段的内容**，而**不是**把该字段作为查询条件。
                  - **严禁**为此类意图生成查询子句（如 `term`, `match`, `exists`）。
                  - **正确做法**：确保该字段包含在 `_source` 的 `includes` 中（或者不被 excludes 排除），如果需要，可以在 `aggs` 中对相关实体进行聚合，但绝对不要在 `query` 中画蛇添足。

                【0. 核心反例 (Critical Negative Examples)】
                请仔细阅读以下错误示范，严禁重犯：
                1. **语法结构反例 (Syntax Error - 绝对禁止嵌套)**
                   ❌ 错误 (嵌套)："must": [ { "term": "A" }, { "must_not": { "term": "B" } } ]
                   ✅ 正确 (兄弟)："bool": { "must": [ { "term": "A" } ], "must_not": [ { "term": "B" } ] }

                2. **逻辑互斥反例 (Logical Error - 绝对禁止单字段多值 Must)**
                   ❌ 错误 (且关系)："must": [ { "term": { "status": "New" } }, { "term": { "status": "Processing" } } ] (一个工单不可能同时是 New 又是 Processing)
                   ✅ 正确 (或关系)："must": [ { "terms": { "status": ["New", "Processing"] } } ]

                【5. 语义理解与填坑逻辑 (Semantic Decomposition & Slot Filling)】
                请按照以下步骤处理复合查询词（如"A区未处理的严重高温报警"）：

                **Step 1: 拆解与匹配 (Decompose & Match)**
                将查询拆解为独立单元，并在 Schema 中寻找对应的"坑位"（字段）：
                - "A区" -> 命中 `zone` 字段 -> 生成 `term: { "zone.keyword": "Zone_A" }`
                - "高温" -> 命中 `alert_type` 字段 -> 生成 `term: { "alert_type.keyword": "Overheat" }`
                - "报警" -> **扫描 Schema 发现无对应字段** -> 🗑️ **直接丢弃** (视为无意义词，防止臆造 `type: alarm` 导致报错)。

                【Step 1.5: Text 兜底原则 (Text Fallback - 极其重要)】
                如果用户提到的某个专有名词（如 "VPN", "GPU"）在 keyword 字段的样本值中找不到匹配：
                - **严禁**猜测并生成 term 查询。
                - **严禁**使用 `must` 逻辑将该词拆分到多个 match 子句中（这会导致逻辑 AND 从而搜不到结果）。
                - **正确做法**：使用 `multi_match` 查询，将该词应用到所有标记为 `(text)` 的核心字段上（如 title, summary, content）。
                - **示例结构**：
                  "query": {
                    "multi_match": {
                      "query": "GPU",
                      "fields": ["title", "summary", "full_content_ocr"],
                      "type": "best_fields"
                    }
                  }
                **Step 2: 语义泛化与映射 (Semantic Expansion)**
                找到字段后，基于【样本值】列表进行值的逻辑推理（不要局限于一对一）：
                - **反向排除优先 (Exclusion First)**：对于“没xxx”、“非xxx”、“不xxx”这种否定意图，**优先检查**是否可以通过排除“正面状态”来精准实现。
                  - 💡 技巧：如果用户查“没修好”，而样本中明确有“Resolved”或“Closed”，应优先生成 `must_not: { "term": { "status": "Resolved" } }`。这比枚举正向状态更鲁棒。
                - **集合映射 (Set Mapping)**：若无法使用排除法，则必须选取样本中**所有**符合逻辑的值。
                  - 🚨 警告：严禁漏选。如果查“未完成”，必须同时包含 [New, Processing, Pending] 等所有中间态。

                **Step 3: 最终核对 (Final Verification)**
                - **宁缺毋滥**：只有当 Schema 中**明确存在**能承载该语义的字段时，才生成查询条件。
                - **严禁臆造**：绝对不允许臆造不存在的字段（如 `is_alarm`）。如果一个词找不到对应的字段，就忽略它。
                - **值必须存在**：生成的查询值必须完全来自提供的【样本值】或用户明确输入的关键词。
                - **空查询处理铁律**：如果用户仅表达分析概览意图且无过滤条件，严禁生成空对象 `"query": {}`，首选做法是在 DSL 中直接省略 query 字段。
                - **过滤优先原则**：如果用户问题包含具体筛选条件，必须生成精准的 query 子句，严禁因为有聚合就忽略查询。
                - **语法校验**：检查生成的 JSON，确保没有 `must_not` 出现在 `must` 或 `should` 的数组里。
                - **多字段搜索铁律**：当一个关键词（如 "GPU"）需要匹配多个可能的文本字段时，**必须**使用 `multi_match` 语法。**严禁**生成多个 match 子句并放入 must 数组中。

                ## 当前索引结构 (你的唯一菜单)
                索引名称: %s
                字段信息 (字段名必须从中选取，不可修改):
                %s

                请根据以上策略生成 ES 查询 DSL。
                """, sizeRule, targetSize, targetSize, indexName, enhancedFields);
        // 5. 执行调用
        String rawResult = callLlmApi(systemPrompt, query);
        log.debug("LLM 原始返回: {}", rawResult);

        // 6. 结果清洗 - 提取纯 JSON
        String cleanedResult = extractJson(rawResult);

        // 7. 自动纠错：修复字段名（如移除非法的 .keyword 后缀）
        String sanitizedResult = sanitizeDsl(cleanedResult, enhancedFields);

        // 8. 强制覆盖 size（防止 LLM 不听话）
        sanitizedResult = forceSize(sanitizedResult, targetSize);

        if (sanitizedResult == null || sanitizedResult.isBlank()) {
            throw new RuntimeException("DSL 生成失败，结果为空");
        }

        log.info("清洗后的 DSL 长度: {} 字符", sanitizedResult.length());
        return sanitizedResult;
    }

    /**
     * 强制覆盖 DSL 中的 size 字段
     */
    private String forceSize(String dsl, int size) {
        try {
            JsonNode root = objectMapper.readTree(dsl);
            if (root.isObject()) {
                ObjectNode obj = (ObjectNode) root;
                JsonNode current = obj.get("size");
                if (current == null || current.asInt() != size) {
                    obj.put("size", size);
                }
                return objectMapper.writeValueAsString(obj);
            }
        } catch (Exception e) {
            log.warn("强制覆盖 size 失败: {}", e.getMessage());
        }
        return dsl;
    }

    /**
     * 自动修复 DSL 中的非法字段名
     * 场景：LLM 错误地为 keyword 字段添加 .keyword 后缀（如 lawyer_name.keyword）
     * 场景：LLM 错误地对 text/date 字段使用聚合或折叠
     * 逻辑：
     * 1. 比对 Schema 白名单，修复非法字段名
     * 2. 检测 aggs/collapse 中的 field 是否为 text/date 类型，如果是则移除整个聚合项
     */
    private String sanitizeDsl(String dsl, String enhancedFields) {
        if (dsl == null || dsl.isEmpty()) {
            return dsl;
        }

        try {
            // 1. 构建字段白名单（字段名 -> 类型映射）
            Map<String, String> fieldTypeMap = extractFieldTypes(enhancedFields);
            if (fieldTypeMap.isEmpty()) {
                log.warn("字段白名单为空，跳过 DSL 纠错");
                return dsl;
            }

            // 2. 解析 DSL
            JsonNode root = objectMapper.readTree(dsl);
            boolean modified = fixFieldNamesAndFilter(root, fieldTypeMap);

            if (modified) {
                log.info("DSL 字段名纠错/非法聚合过滤完成");
                return objectMapper.writeValueAsString(root);
            }
            return dsl;

        } catch (IOException e) {
            log.warn("DSL 纠错失败，返回原始结果: {}", e.getMessage());
            return dsl;
        }
    }

    /**
     * 从 Schema 描述中提取合法字段名集合及其类型
     * 格式: "- fieldName (type)" 或 "- fieldName.keyword (type)"
     * @return Map<字段名, 类型>
     */
    private Map<String, String> extractFieldTypes(String enhancedFields) {
        Map<String, String> fieldTypeMap = new HashMap<>();
        // 匹配形如 "- fieldName (type)" 的行，捕获字段名和类型
        Pattern pattern = Pattern.compile("^- (\\S+)\\s+\\(([^)]+)\\)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(enhancedFields);

        while (matcher.find()) {
            String fieldName = matcher.group(1);
            String fieldType = matcher.group(2).toLowerCase();
            fieldTypeMap.put(fieldName, fieldType);
        }
        log.debug("从 Schema 中提取到 {} 个合法字段", fieldTypeMap.size());
        return fieldTypeMap;
    }

    /**
     * 递归修复 JSON 中的 field 字段名，并移除非法的 aggs/collapse
     * @param node 当前 JSON 节点
     * @param fieldTypeMap 字段名 -> 类型映射
     * @return 是否有修改
     */
    private boolean fixFieldNamesAndFilter(JsonNode node, Map<String, String> fieldTypeMap) {
        boolean modified = false;

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<String> fieldNames = objectNode.fieldNames();
            Set<String> toRemove = new HashSet<>();

            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                JsonNode valueNode = objectNode.get(key);

                // 检测 collapse 节点：使用预计算策略，先修复字段名再查类型
                if ("collapse".equals(key)) {
                    if (valueNode.isObject()) {
                        JsonNode fieldNode = valueNode.get("field");
                        if (fieldNode != null && fieldNode.isTextual()) {
                            String rawFieldName = fieldNode.asText();
                            // Step 1: 预计算修复后的字段名
                            String fixedFieldName = tryFixFieldName(rawFieldName, fieldTypeMap.keySet());
                            // Step 2: 使用修复后的名字查类型
                            String fieldType = fieldTypeMap.get(fixedFieldName);
                            // Step 3: 执行熔断检查
                            if (fieldType != null && (fieldType.contains("text") || fieldType.contains("date"))) {
                                log.warn("移除非法 collapse: 修复后字段 {} 是 {} 类型，不能用于折叠", fixedFieldName, fieldType);
                                toRemove.add(key);
                                modified = true;
                                continue;
                            }
                            // Step 4: 检查通过则更新为修复后的字段名
                            if (!fixedFieldName.equals(rawFieldName)) {
                                ((ObjectNode) valueNode).put("field", fixedFieldName);
                                log.debug("修复 collapse 字段名: {} -> {}", rawFieldName, fixedFieldName);
                                modified = true;
                            }
                        }
                    }
                }

                // 检测 aggs 中的非法聚合：使用预计算策略，先修复字段名再查类型
                if ("aggs".equals(key) && valueNode.isObject()) {
                    Iterator<String> aggNames = valueNode.fieldNames();
                    Set<String> toRemoveAggs = new HashSet<>();
                    Map<String, String> aggFieldFixes = new HashMap<>(); // 记录待修复的聚合字段名
                    while (aggNames.hasNext()) {
                        String aggName = aggNames.next();
                        JsonNode aggNode = valueNode.get(aggName);
                        if (aggNode.isObject()) {
                            // 增强字段提取：支持嵌套结构（如 terms.field, stats.field）
                            JsonNode fieldNode = aggNode.get("field");
                            if (fieldNode == null || fieldNode.isMissingNode()) {
                                fieldNode = aggNode.path("terms").path("field");
                            }
                            if (fieldNode == null || fieldNode.isMissingNode()) {
                                fieldNode = aggNode.path("stats").path("field");
                            }
                            if (fieldNode != null && fieldNode.isTextual()) {
                                String rawFieldName = fieldNode.asText();
                                // Step 1: 预计算修复后的字段名
                                String fixedFieldName = tryFixFieldName(rawFieldName, fieldTypeMap.keySet());
                                // Step 2: 使用修复后的名字查类型
                                String fieldType = fieldTypeMap.get(fixedFieldName);
                                // Step 3: 执行熔断检查
                                if (fieldType != null && fieldType.contains("text")) {
                                    log.warn("移除非法聚合 {}: 修复后字段 {} 是 text 类型，不能用于聚合", aggName, fixedFieldName);
                                    toRemoveAggs.add(aggName);
                                    modified = true;
                                    continue;
                                }
                                // Step 4: 记录需要修复的字段名（待统一应用）
                                if (!fixedFieldName.equals(rawFieldName)) {
                                    aggFieldFixes.put(aggName, fixedFieldName);
                                    modified = true;
                                }
                            }
                        }
                    }
                    // 统一删除 aggs 中的非法子聚合
                    if (!toRemoveAggs.isEmpty()) {
                        ObjectNode aggsNode = (ObjectNode) valueNode;
                        for (String aggName : toRemoveAggs) {
                            aggsNode.remove(aggName);
                        }
                    }
                    // 统一修复聚合中的字段名
                    if (!aggFieldFixes.isEmpty()) {
                        ObjectNode aggsNode = (ObjectNode) valueNode;
                        for (Map.Entry<String, String> entry : aggFieldFixes.entrySet()) {
                            String aggName = entry.getKey();
                            String fixedFieldName = entry.getValue();
                            JsonNode aggNode = aggsNode.get(aggName);
                            if (aggNode != null && aggNode.isObject()) {
                                // 尝试在顶层 field 修复
                                JsonNode fieldNode = aggNode.get("field");
                                if (fieldNode != null && fieldNode.isTextual()) {
                                    ((ObjectNode) aggNode).put("field", fixedFieldName);
                                    log.debug("修复聚合 {} 字段名: {}", aggName, fixedFieldName);
                                } else {
                                    // 尝试在 nested 层级修复（terms.field / stats.field）
                                    JsonNode termsNode = aggNode.get("terms");
                                    if (termsNode != null && termsNode.isObject()) {
                                        ((ObjectNode) termsNode).put("field", fixedFieldName);
                                        log.debug("修复聚合 {} 的 terms.field: {}", aggName, fixedFieldName);
                                    } else {
                                        JsonNode statsNode = aggNode.get("stats");
                                        if (statsNode != null && statsNode.isObject()) {
                                            ((ObjectNode) statsNode).put("field", fixedFieldName);
                                            log.debug("修复聚合 {} 的 stats.field: {}", aggName, fixedFieldName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 如果删除后 aggs 为空，则移除整个 aggs
                    if (valueNode.isObject() && !((ObjectNode) valueNode).fieldNames().hasNext()) {
                        toRemove.add(key);
                    }
                }

                // 检测空 query 对象：直接移除，不填充 match_all
                if ("query".equals(key) && valueNode.isObject() && valueNode.isEmpty()) {
                    log.warn("移除空 query 对象，避免无效查询");
                    toRemove.add(key);
                    modified = true;
                    continue;
                }

                // 处理 "field" 字段：修复字段名
                if ("field".equals(key) && valueNode.isTextual()) {
                    String fieldName = valueNode.asText();
                    String corrected = tryFixFieldName(fieldName, fieldTypeMap.keySet());
                    if (!corrected.equals(fieldName)) {
                        objectNode.put(key, corrected);
                        log.debug("修复字段名: {} -> {}", fieldName, corrected);
                        modified = true;
                    }
                }

                // 递归处理子节点
                if (fixFieldNamesAndFilter(valueNode, fieldTypeMap)) {
                    modified = true;
                }
            }

            // 移除标记的节点
            for (String key : toRemove) {
                objectNode.remove(key);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (fixFieldNamesAndFilter(item, fieldTypeMap)) {
                    modified = true;
                }
            }
        }

        return modified;
    }

    /**
     * 尝试修复单个字段名
     * 规则：如果 "xxx.keyword" 不在白名单中，但 "xxx" 在，则移除 .keyword 后缀
     */
    private String tryFixFieldName(String fieldName, Set<String> validFields) {
        // 如果字段名以 .keyword 结尾
        if (fieldName.endsWith(".keyword")) {
            String baseName = fieldName.substring(0, fieldName.length() - 8); // 去掉 ".keyword"
            // 如果原名不在白名单，但基础名在，则使用基础名
            if (!validFields.contains(fieldName) && validFields.contains(baseName)) {
                log.debug("字段纠错: {} 不在白名单，使用基础名 {}", fieldName, baseName);
                return baseName;
            }
        }
        return fieldName;
    }

    /**
     * 从 LLM 返回的文本中提取 JSON
     * 支持多种格式：代码块包裹、前缀文本、纯 JSON 等
     */
    private String extractJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }

        String trimmed = raw.trim();

        // 策略1：正则匹配 ```json ... ``` 或 ``` ... ```
        Pattern codeBlockPattern = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
        Matcher matcher = codeBlockPattern.matcher(trimmed);
        if (matcher.find()) {
            String json = matcher.group(1);
            return json.replaceAll("\\u00A0", " ").trim();
        }

        // 策略2：寻找第一个 '{' 和最后一个 '}'
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');

        if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
            String json = trimmed.substring(firstBrace, lastBrace + 1);
            return json.replaceAll("\\u00A0", " ").trim();
        }

        // 兜底：直接返回原始内容（可能已经是纯 JSON）
        return trimmed.replaceAll("\\u00A0", " ").trim();
    }

    /**
     * 将样本值注入到字段描述中
     * 原格式：- status (keyword)
     * 新格式：- status (keyword, 样本值: ["pending", "shipped", "closed"])
     *
     * @see IndexSchemaManager#FIELD_LINE_REGEX
     */
    private String enrichFieldsWithSamples(String mapping, Map<String, List<String>> samples) {
        // 使用 IndexSchemaManager 中定义的常量，确保格式契约一致
        Pattern fieldPattern = Pattern.compile(
                IndexSchemaManager.FIELD_LINE_REGEX,
                Pattern.MULTILINE);

        Matcher matcher = fieldPattern.matcher(mapping);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String fieldLine = matcher.group(1);
            // 提取字段名
            String fieldName = fieldLine.substring(2, fieldLine.indexOf(' ', 2));

            // 检查是否有样本值
            List<String> sampleValues = samples.get(fieldName);
            if (sampleValues != null && !sampleValues.isEmpty()) {
                // 追加样本值
                String enriched = fieldLine + String.format(" 样本值: %s", sampleValues);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(enriched));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(fieldLine));
            }
        }
        matcher.appendTail(sb);

        log.trace("增强后的字段描述:\n{}", sb);
        return sb.toString();
    }

    /**
     * 根据用户查询自动推断最匹配的索引名称
     * @param userQuery 用户查询语句
     * @return 推断出的索引名称
     */
    public String selectIndex(String userQuery) {
        // 1. 获取候选
        List<String> indices = schemaManager.getAllIndices();
        if (indices.isEmpty()) {
            throw new RuntimeException("ES 中没有业务索引");
        }

        // 2. 快速通道
        if (indices.size() == 1) {
            return indices.get(0);
        }

        // 3. AI 裁决
        String indicesStr = String.join(", ", indices);
        String systemPrompt = String.format("""
            你是一个 Elasticsearch 索引选择专家。
            候选索引列表：%s
            
            请分析用户问题，从列表中选出最匹配的一个索引名称。
            规则：
            1. 必须从候选列表中选择，严禁编造。
            2. 只返回索引名，不要任何标点或解释。
            """, indicesStr);

        String rawResult = callLlmApi(systemPrompt, userQuery);

        // 4. 清洗 (修正了正则，允许点号)
        String selected = rawResult.trim().replaceAll("[^a-zA-Z0-9_\\-\\.]", "");

        // 5. 【新增】安全校验：AI 选的索引真的在列表里吗？
        if (!indices.contains(selected)) {
            // 如果 AI 乱选，尝试用模糊匹配或默认选第一个，或者报错
            log.warn("AI 选择了不存在的索引: [{}], 候选列表: {}", selected, indices);
            // 容错策略：如果选的不在列表里，但包含在某个候选里（比如少了个后缀），也可以抢救一下
            // 这里简单处理：直接抛错，或者 fallback 到第一个
            throw new RuntimeException("无法匹配到有效索引，AI返回: " + selected);
        }

        log.info("智能路由锁定索引: {}", selected);
        return selected;
    }

    /**
     * 根据用户查询自动推断索引并生成 DSL
     * @param userQuery 用户查询语句
     * @return 纯 JSON 格式的 ES 查询 DSL
     */
    public String generateDsl(String userQuery) {
        String indexName = selectIndex(userQuery);
        return generateDsl(indexName, userQuery);
    }
}
