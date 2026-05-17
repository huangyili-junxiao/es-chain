# ES Chain

基于 LLM 的自然语言转 Elasticsearch DSL 查询引擎。

## 项目简介

通过大语言模型将用户的自然语言查询转换为 Elasticsearch DSL 语句，执行后返回结构化结果。基于 Spring Boot 3.2 构建。

## 技术栈

- Java 17, Spring Boot 3.2
- Elasticsearch (Spring Data Elasticsearch)
- LLM 集成：支持 OpenRouter 及 OpenAI 兼容接口
- Docker 容器化部署

## 快速开始

```bash
# 复制并编辑环境配置
cp .env.example .env

# 构建
mvn clean package -DskipTests

# 运行
java -jar target/es-chain-1.0-SNAPSHOT.jar
```

## 配置说明

所有配置通过 `.env` 文件管理，参考 `.env.example` 填写。

| 变量 | 说明 |
|------|------|
| `ES_HOST` | Elasticsearch 地址 |
| `ES_PORT` | Elasticsearch 端口 |
| `LLM_API_KEY` | LLM API 密钥 |
| `LLM_BASE_URL` | LLM API 地址 |
| `LLM_MODEL` | 模型名称 |

## API

服务端口：`8004`
