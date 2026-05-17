package com.topview.eschain.dto;

import lombok.Data;

@Data
public class EsQueryRequest {

    /**
     * 用户问题
     */
    private String question;

    /**
     * 请求追踪 ID
     */
    private String requestId;

    /**
     * 查询选项
     */
    private QueryOptions options;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public QueryOptions getOptions() {
        return options;
    }

    public void setOptions(QueryOptions options) {
        this.options = options;
    }

    @Data
    public static class QueryOptions {
        /**
         * 超时时间（毫秒）
         */
        private int timeoutMs = 10000;

        /**
         * 最大返回结果数
         */
        private int maxResults = 10;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
    }
}
