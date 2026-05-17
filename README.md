# ES Chain

Natural language to Elasticsearch DSL query engine, powered by LLM.

## Overview

ES Chain translates natural language queries into Elasticsearch DSL, executing them against an ES cluster and returning structured results. Built with Spring Boot 3.2 and Elasticsearch.

## Tech Stack

- Java 17, Spring Boot 3.2
- Elasticsearch (Spring Data Elasticsearch)
- LLM integration via OpenRouter / OpenAI-compatible API
- Docker support

## Quick Start

```bash
# Copy and edit environment config
cp .env.example .env

# Build
mvn clean package -DskipTests

# Run
java -jar target/es-chain-1.0-SNAPSHOT.jar
```

## Configuration

All config via `.env` file, see `.env.example` for reference.

| Variable | Description |
|----------|-------------|
| `ES_HOST` | Elasticsearch host |
| `ES_PORT` | Elasticsearch port |
| `LLM_API_KEY` | LLM API key |
| `LLM_BASE_URL` | LLM API endpoint |
| `LLM_MODEL` | Model name |

## API

Service runs on port `8004`.
