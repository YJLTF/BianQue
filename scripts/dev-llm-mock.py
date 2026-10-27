#!/usr/bin/env python3
"""开发用假 OpenAI Chat Completions 端点（内网演示/E2E 用）。
用法: python scripts/dev-llm-mock.py [port]   # 默认 8901
行为: POST /v1/chat/completions → 回显诊断风格的固定回复（含 reasoning_content 思考过程）。
"""
import json
import sys
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


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
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

    def log_message(self, fmt, *args):
        print("[mock-llm]", fmt % args)


if __name__ == "__main__":
    print(f"fake OpenAI endpoint: http://127.0.0.1:{PORT}/v1/chat/completions")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
