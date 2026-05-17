package com.topview.eschain.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EsQueryResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 数据源类型
     */
    private String source = "es";

    /**
     * 简要描述（静态字符串，如 "Query executed successfully, see data for results"）
     */
    private String summary;

    /**
     * 查询结果数据列表（与 Orchestrator 契约对齐）
     */
    private List<Map<String, Object>> data;

    /**
     * 元数据（包含 generated_dsl, index, execution_time_ms 等）
     */
    private Map<String, Object> metadata;

    /**
     * 错误信息
     */
    private ErrorDetail error;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<Map<String, Object>> getData() {
        return data;
    }

    public void setData(List<Map<String, Object>> data) {
        this.data = data;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public ErrorDetail getError() {
        return error;
    }

    public void setError(ErrorDetail error) {
        this.error = error;
    }

    @Data
    public static class ErrorDetail {
        /**
         * 错误码
         */
        private String code;

        /**
         * 错误信息
         */
        private String message;

        /**
         * 是否可重试
         */
        private Boolean retriable;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Boolean getRetriable() {
            return retriable;
        }

        public void setRetriable(Boolean retriable) {
            this.retriable = retriable;
        }
    }
}
