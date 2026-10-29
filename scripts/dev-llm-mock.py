#!/usr/bin/env python3
"""开发用假 OpenAI Chat Completions 端点（内网演示/E2E 用）。
用法: python scripts/dev-llm-mock.py [port]   # 默认 8901
行为: POST /v1/chat/completions
  - body.stream=false/缺省 → 一次性 JSON 回复（含 reasoning_content 思考过程）
  - body.stream=true       → SSE 流式：先思考增量、再回答增量、末帧 usage + [DONE]
"""
import json
import sys
import re
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8901

REPLY = """**结论**：old gen 水位 87% 且 Full GC 计数上升，属于堆压力偏高，未见明显泄漏斜率。

**证据**
1. heap used=40MB / old gen used=22MB，占比 87%（阈值 85%）；
2. 线程总数 412，其中 TIMED_WAITING 356，业务线程阻塞在连接池 borrow；
3. 无 stop-the-world 异常增长。

**处置建议**
1. 优先扩容下游连接池（maxTotal）并观察 borrow 耗时；
2. 只读验证命令：`memory`、`thread -n 5`、`dashboard -n 1`；
3. 若 30 分钟后 old gen 仍单调上升，再 dump 做对象占比分析（需审批）。

（本回复来自内网 mock 端点，用于演示 OpenAI 兼容协议接入。）"""

REASONING = "先看内存各分区水位 → 发现 old gen 超阈值；再看线程状态分布 → 大量等待连接池；二者结合判断为下游连接池打满导致的堆压力，暂无泄漏证据。"

SSE_TAIL = "\n\n"


TOOL_CALLS_SENT = {"n": 0}  # 会诊演示：tools 请求第一次回工具调用、第二次回结论


class Handler(BaseHTTPRequestHandler):
    def do_tools(self, body):
        """AI 会诊演示：第一轮回 run_diag_command(thread -n 5)，第二轮回最终结论"""
        user = ""
        for m in reversed(body.get("messages", [])):
            if m.get("role") == "user":
                user = m.get("content", "")
                break
        m = re.search(r"实例：([\w\-]+)", user)
        inst = m.group(1) if m else "pod-3"
        TOOL_CALLS_SENT["n"] += 1
        if TOOL_CALLS_SENT["n"] % 2 == 1:
            out = {"choices": [{"message": {"role": "assistant", "content": "", "tool_calls": [
                {"id": "call_1", "type": "function", "function": {
                    "name": "run_diag_command",
                    "arguments": json.dumps({"instanceId": inst, "command": "thread -n 5"})}}]}}]}
        else:
            out = {"choices": [{"message": {"role": "assistant", "content":
                "**结论**：已通过工具执行 `thread -n 5` 完成取样，目标实例线程状态正常（演示数据）。\n\n"
                "**证据**：工具 run_diag_command 返回的线程栈未见高 CPU 忙等线程；建议继续 memory / jvm 复核。\n\n"
                "**处置建议**：保持观察；如复发优先扩容连接池并开启 dashboard 实时面板。"}}]}
        data = json.dumps(out).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        if body.get("tools") and not body.get("stream"):
            return self.do_tools(body)
        if body.get("stream"):
            return self.do_stream(body)
        out = {
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": REPLY,
                    "reasoning_content": REASONING,
                }
            }],
            "usage": {"prompt_tokens": 1234, "completion_tokens": 289},
            "model": body.get("model", "mock-diag"),
        }
        data = json.dumps(out, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_stream(self, body):
        """SSE 流式：reasoning_content 先流出，再流 content，末帧带 usage + [DONE]。"""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def frame(delta):
            return "data: " + json.dumps({"choices": [{"delta": delta}]}, ensure_ascii=False) + SSE_TAIL

        for ch in REASONING:
            self.wfile.write(frame({"role": "assistant", "reasoning_content": ch}).encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.012)
        for ch in REPLY:
            self.wfile.write(frame({"content": ch}).encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.004)
        tail = {"choices": [{"delta": {}}],
                "usage": {"prompt_tokens": 1234, "completion_tokens": 289},
                "model": body.get("model", "mock-diag")}
        self.wfile.write(("data: " + json.dumps(tail, ensure_ascii=False) + SSE_TAIL).encode("utf-8"))
        self.wfile.write(b"data: [DONE]" + SSE_TAIL.encode("ascii"))
        self.wfile.flush()

    def log_message(self, fmt, *args):
        print("[mock-llm]", fmt % args)


if __name__ == "__main__":
    print(f"fake OpenAI endpoint: http://127.0.0.1:{PORT}/v1/chat/completions")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
