package com.bianque.server.exec;

import com.bianque.server.service.ProfileService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastPathClientTest {

    /** 事故现场报文：profiler start 失败藏于 type=status statusCode=1（渲染器曾忽略 → 误报「回传内容为空」） */
    private static final String ASYNC_PROFILER_FAIL = """
            {"body":{"command":"profiler start","jobId":4,"jobStatus":"TERMINATED","results":[
            {"jobId":4,"message":"AsyncProfiler error: /tmp/ArthasJniLibrary13377894688965350507.tmp: failed to map segment from shared object",
             "statusCode":1,"type":"status"}],"timeExpired":false},"sessionId":"d8dc9f61","state":"SUCCEEDED"}""";

    @Test
    void status失败透传真实原因() {
        String err = FastPathClient.agentJsonError(ASYNC_PROFILER_FAIL);
        assertTrue(err.contains("AsyncProfiler error"), "应透传 arthas message 原文");
        assertTrue(err.contains("failed to map segment"));
        assertNull(FastPathClient.renderAgentJson(ASYNC_PROFILER_FAIL), "失败回传不应渲染为成功文本");
    }

    @Test
    void 空结果无错误() {
        assertNull(FastPathClient.agentJsonError("{\"body\":{\"results\":[]}}"));
        assertNull(FastPathClient.renderAgentJson("not-json"));
    }

    @Test
    void profiler结果渲染出产物路径行() {
        String body = """
                {"body":{"results":[
                {"action":"stop","executeResult":"OK\\n","jobId":4,"outputFile":"/app/arthas-output/20261030-033257.html","type":"profiler"},
                {"jobId":4,"statusCode":0,"type":"status"}]}}""";
        String text = FastPathClient.renderAgentJson(body);
        assertNull(FastPathClient.agentJsonError(body));
        assertEquals("OK\n/app/arthas-output/20261030-033257.html", text);
        assertEquals("/app/arthas-output/20261030-033257.html", ProfileService.findOutputPath(text));
    }

    @Test
    void cat结果去包装回传正文() {
        String body = "{\"body\":{\"results\":[{\"content\":\"<!DOCTYPE html>\\n<html>\",\"jobId\":9,\"type\":\"cat\"}]}}";
        assertEquals("<!DOCTYPE html>\n<html>", FastPathClient.renderAgentJson(body));
    }
}
