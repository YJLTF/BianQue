<template>
  <div class="md-wrap">
    <div ref="box" class="md-body" v-html="html" @click="onClick"></div>
    <span v-if="streaming" class="cursor">▍</span>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import MarkdownIt from "markdown-it";
import DOMPurify from "dompurify";
import hljs from "highlight.js/lib/common";
import "highlight.js/styles/github.css";

/** AI 回答的 Markdown 渲染：消毒（内容按不可信处理）、代码高亮 + 复制、表格/列表样式统一 */
const props = defineProps<{ content: string; streaming?: boolean }>();

const box = ref<HTMLElement>();

const md = new MarkdownIt({
  html: false, // 不放行内联 HTML（另有 DOMPurify 兜底）
  linkify: true,
  breaks: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang, ignoreIllegals: true }).value}</code></pre>`;
      } catch {
        /* 高亮失败回落默认转义渲染 */
      }
    }
    return "";
  },
});

// 外链新开页 + 去 opener（module 级 hook 只需注册一次）
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A") {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

const html = computed(() => DOMPurify.sanitize(md.render(props.content || "")));

// v-html 每次重渲染会清掉注入的代码条，流式期间随渲染重建（开销可忽略）
watch(html, () => nextTick(decorate));
onMounted(() => nextTick(decorate));

function decorate() {
  box.value?.querySelectorAll("pre").forEach((pre) => {
    if (pre.querySelector(".md-bar")) return;
    const code = pre.querySelector("code");
    const lang = Array.from(code?.classList ?? []).find((c) => c.startsWith("language-"))?.slice(9) ?? "";
    const bar = document.createElement("div");
    bar.className = "md-bar";
    const label = document.createElement("span");
    label.className = "md-lang";
    label.textContent = lang;
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "md-copy";
    btn.textContent = "复制";
    bar.append(label, btn);
    pre.appendChild(bar);
  });
}

function onClick(e: MouseEvent) {
  const t = (e.target as HTMLElement).closest?.(".md-copy") as HTMLElement | null;
  if (!t) return;
  const code = t.closest("pre")?.querySelector("code");
  navigator.clipboard.writeText(code?.textContent ?? "").then(() => {
    t.textContent = "已复制";
    window.setTimeout(() => (t.textContent = "复制"), 1200);
  });
}
</script>

<style scoped>
.md-wrap { min-width: 0; }
.cursor { display: inline-block; color: var(--el-color-primary); animation: md-blink 1s step-start infinite; }
@keyframes md-blink { 50% { opacity: 0; } }

.md-body { font-size: 13px; line-height: 1.65; word-break: break-word; }
.md-body :deep(p) { margin: 6px 0; }
.md-body :deep(p:first-child) { margin-top: 0; }
.md-body :deep(p:last-child) { margin-bottom: 0; }
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) { margin: 10px 0 6px; font-size: 14px; font-weight: 600; }
.md-body :deep(ul),
.md-body :deep(ol) { margin: 6px 0; padding-left: 20px; }
.md-body :deep(li) { margin: 2px 0; }
.md-body :deep(a) { color: var(--el-color-primary); }
.md-body :deep(blockquote) {
  margin: 6px 0; padding: 2px 10px; color: var(--el-text-color-secondary);
  border-left: 3px solid var(--el-border-color); background: #fafbfc; border-radius: 0 6px 6px 0;
}
.md-body :deep(code) {
  background: #f1f5f9; border-radius: 4px; padding: 1px 5px;
  font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}
.md-body :deep(pre) {
  position: relative; margin: 8px 0; padding: 10px 12px; overflow-x: auto;
  background: #f8fafc; border: 1px solid #eef1f6; border-radius: 8px;
}
.md-body :deep(pre code) { background: none; padding: 0; font-size: 12px; line-height: 1.5; }
.md-body :deep(table) { border-collapse: collapse; margin: 8px 0; max-width: 100%; display: block; overflow-x: auto; }
.md-body :deep(th),
.md-body :deep(td) { border: 1px solid #e4e9f2; padding: 4px 10px; font-size: 12.5px; text-align: left; }
.md-body :deep(th) { background: #f8fafc; font-weight: 600; }
.md-body :deep(.md-bar) {
  position: absolute; top: 4px; right: 6px; display: flex; align-items: center; gap: 6px;
}
.md-body :deep(.md-lang) { font-size: 10.5px; color: #94a3b8; }
.md-body :deep(.md-copy) {
  height: 20px; padding: 0 8px; font-size: 11px; color: #64748b;
  background: #fff; border: 1px solid #e2e8f0; border-radius: 4px; cursor: pointer;
}
.md-body :deep(.md-copy:hover) { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
</style>
