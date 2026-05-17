# ES-Chain 预期响应格式文档

## 说明
本文档描述 es-chain 服务各接口的预期响应格式，用于验证测试正确性。

---

## 1. 健康检查 `GET /health`

### 预期响应 (成功)
```json
{
  "status": "healthy",
  "service": "es-chain",
  "version": "1.0.0",
  "dependencies": {
    "elasticsearch": {
      "status": "healthy",
      "latency_ms": 15
    },
    "llm": {
      "status": "healthy",
      "latency_ms": 45,
      "model": "deepseek-v3.2"
    }
  }
}
```

### 关键字段验证
- `status`: "healthy" | "degraded" | "unhealthy"
- `dependencies.elasticsearch.status`: ES 健康状态
- `dependencies.llm.status`: LLM 服务状态

---

## 2. 获取索引列表 `GET /indices`

### 预期响应
```json
{
  "indices": [
    {
      "name": "factory_sensors",
      "docs_count": 10,
      "size_bytes": 15234,
      "created_at": "2026-04-08T10:00:00.000Z"
    },
    {
      "name": "app_logs",
      "docs_count": 10,
      "size_bytes": 8921,
      "created_at": "2026-04-08T10:00:00.000Z"
    },
    {
      "name": "user_orders",
      "docs_count": 10,
      "size_bytes": 12345,
      "created_at": "2026-04-08T10:00:00.000Z"
    }
  ]
}
```

---

## 3. 核心查询接口 `POST /query`

### 场景 3.1: A区的高温报警

**请求:**
```json
{
  "question": "A区的高温报警",
  "request_id": "test-factory-001"
}
```

**预期成功响应结构:**
```json
{
  "success": true,
  "source": "es",
  "summary": "✨ 查询完成！在索引 [factory_sensors] 中为您找到了 2 条相关数据...",
  "data": {
    "list": [
      {
        "sensor_id": "S-Zone_A-0001",
        "zone": "Zone_A",
        "status": "Critical",
        "alert_type": "Overheat",
        "temperature": 95.5,
        "message": "CRITICAL ALERT: Furnace temperature exceeded limit!",
        "timestamp": "2026-04-08T10:00:00Z"
      },
      {
        "sensor_id": "S-Zone_A-0002",
        "zone": "Zone_A",
        "status": "Critical",
        "alert_type": "Overheat",
        "temperature": 92.1,
        "message": "CRITICAL ALERT: Temperature too high, immediate action required!",
        "timestamp": "2026-04-08T10:05:00Z"
      }
    ],
    "aggs": {},
    "totalHits": 2
  },
  "metadata": {
    "generated_dsl": "{...生成的DSL字符串...}",
    "index": "factory_sensors",
    "execution_time_ms": 1234
  },
  "error": null
}
```

**关键验证点:**
- `success`: `true`
- `data.list`: 数组，包含 2 条记录
- `data.totalHits`: 2
- `data.list[*].zone`: 都是 "Zone_A"
- `data.list[*].status`: 都是 "Critical"
- `data.list[*].alert_type`: 都是 "Overheat"
- `metadata.index`: "factory_sensors"
- `metadata.generated_dsl`: 非空字符串，包含 query 语句

---

### 场景 3.2: 错误日志查询

**请求:**
```json
{
  "question": "最近有什么错误日志",
  "request_id": "test-logs-001"
}
```

**预期成功响应结构:**
```json
{
  "success": true,
  "source": "es",
  "summary": "✨ 查询完成！在索引 [app_logs] 中为您找到了 5 条相关数据...",
  "data": {
    "list": [
      { "level": "ERROR", "service": "order-service", "message": "Database connection timeout", ... },
      { "level": "ERROR", "service": "order-service", "message": "Failed to process payment", ... },
      { "level": "ERROR", "service": "user-service", "message": "Authentication failed for user", ... },
      { "level": "ERROR", "service": "inventory-service", "message": "Insufficient stock for product", ... },
      { "level": "ERROR", "service": "payment-service", "message": "Payment gateway unreachable", ... }
    ],
    "aggs": {},
    "totalHits": 5
  },
  "metadata": {
    "generated_dsl": "{...}",
    "index": "app_logs",
    "execution_time_ms": 890
  },
  "error": null
}
```

**关键验证点:**
- `data.list`: 包含 5 条 ERROR 级别日志
- `data.list[*].level`: 都是 "ERROR"
- `data.totalHits`: 5
- `metadata.index`: "app_logs"

---

### 场景 3.3: 北京地区已完成订单

**请求:**
```json
{
  "question": "北京地区的已完成订单",
  "request_id": "test-orders-001"
}
```

**预期成功响应结构:**
```json
{
  "success": true,
  "source": "es",
  "summary": "✨ 查询完成！在索引 [user_orders] 中为您找到了 3 条相关数据...",
  "data": {
    "list": [
      { "order_id": "ORD-2026-001", "region": "Beijing", "status": "completed", ... },
      { "order_id": "ORD-2026-007", "region": "Beijing", "status": "completed", ... },
      { "order_id": "ORD-2026-010", "region": "Beijing", "status": "completed", ... }
    ],
    "aggs": {},
    "totalHits": 3
  },
  "metadata": {
    "generated_dsl": "{...}",
    "index": "user_orders",
    "execution_time_ms": 756
  },
  "error": null
}
```

**关键验证点:**
- `data.list`: 包含 3 条记录
- `data.list[*].region`: 都是 "Beijing"
- `data.list[*].status`: 都是 "completed"
- `data.totalHits`: 3
- `metadata.index`: "user_orders"

---

### 场景 3.4: 空问题（参数校验错误）

**请求:**
```json
{
  "question": "",
  "request_id": "test-error-001"
}
```

**预期错误响应结构:**
```json
{
  "success": false,
  "source": "es",
  "summary": "ES 查询失败: Question cannot be empty",
  "data": null,
  "metadata": {
    "execution_time_ms": 5
  },
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "Question cannot be empty"
  }
}
```

**关键验证点:**
- `success`: `false`
- `data`: `null`
- `error.code`: 非空字符串
- `error.message`: 包含错误描述

---

## 4. 直接执行 DSL `POST /dsl`

### 预期响应
```json
{
  "success": true,
  "took_ms": 45,
  "hits": {
    "total": 2,
    "hits": [
      { "_source": { ... } },
      { "_source": { ... } }
    ]
  },
  "aggregations": null
}
```

---

## 5. DSL 验证 `POST /validate`

### 场景 5.1: 有效 DSL

**请求:**
```json
{
  "index": "factory_sensors",
  "dsl": {
    "query": {
      "term": { "zone": "Zone_A" }
    }
  }
}
```

**预期响应:**
```json
{
  "valid": true,
  "warnings": [],
  "explanation": "DSL is valid according to ES"
}
```

### 场景 5.2: 无效 DSL（缺少 query）

**请求:**
```json
{
  "index": "factory_sensors",
  "dsl": {
    "size": 10
  }
}
```

**预期响应:**
```json
{
  "valid": false,
  "warnings": [],
  "explanation": "DSL must contain a valid 'query' field"
}
```

---

## 6. 获取索引 Mapping `GET /mapping/{index}`

### 预期响应
```json
{
  "index": "factory_sensors",
  "mappings": {
    "properties": {
      "sensor_id": { "type": "keyword" },
      "zone": { "type": "keyword" },
      "status": { "type": "keyword" },
      ...
    }
  }
}
```

---

## 注意事项

### ⚠️ 当前已知问题（待修复）

1. **data 字段类型问题**
   - 当前返回: `data` 是 `DataPayload` 对象（包含 `list`, `aggs`, `totalHits`）
   - Orchestrator 期望: `data` 应该是 `List<Map<String, Object>>` 直接是列表
   - 这会导致下游处理异常，需要修复

2. **error 缺少 retriable 字段**
   - 当前返回: `error` 只有 `code` 和 `message`
   - Orchestrator 期望: `error` 需要包含 `retriable: boolean` 字段
   - 需要添加此字段

### 修复后预期变化

修复完成后，成功响应应该是:
```json
{
  "success": true,
  "source": "es",
  "summary": "查询完成",
  "data": [                          // ← 直接是列表
    {"sensor_id": "S-001", ...},
    {"sensor_id": "S-002", ...}
  ],
  "metadata": {
    "generated_dsl": "{...}",
    "index": "factory_sensors",
    "execution_time_ms": 1234,
    "total_hits": 2,                 // ← totalHits 移到 metadata
    "aggregations": {}               // ← aggs 移到 metadata
  },
  "error": null
}
```

错误响应应该是:
```json
{
  "success": false,
  "source": "es",
  "summary": "查询失败",
  "data": null,
  "metadata": {
    "execution_time_ms": 56
  },
  "error": {
    "code": "INDEX_NOT_FOUND",
    "message": "索引不存在",
    "retriable": false               // ← 新增字段
  }
}
```
