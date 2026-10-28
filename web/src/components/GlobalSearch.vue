<template>
  <el-dialog v-model="open" width="600px" :show-close="false" class="gs-dialog" @opened="focusInput">
    <el-input ref="inputRef" v-model="query" size="large" clearable
              placeholder="搜索页面 / 实例 / 命令 / 文档…" @input="onQuery">
      <template #prefix><el-icon><Search /></el-icon></template>
    </el-input>

    <div class="results" @keydown="onKeydown">
      <template v-for="g in groups" :key="g.label">
        <div class="g-label">{{ g.label }}</div>
        <div v-for="it in g.items" :key="g.label + it.key" class="item" :class="{ on: it.key === flat[idx]?.key }"
             @click="pick(it)" @mouseenter="idx = flat.findIndex((f) => f.key === it.key)">
          <el-tag size="small" :type="it.tagType" effect="plain">{{ g.label }}</el-tag>
          <span class="t">{{ it.title }}</span>
          <span class="sub">{{ it.sub }}</span>
        </div>
      </template>
      <div v-if="!flat.length" class="empty">无匹配结果</div>
    </div>

    <div class="foot">↑↓ 选择 · Enter 打开 · Esc 关闭</div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { http } from "@/api/http";
import { getInstances, type InstanceVO } from "@/api/diag";
import { navItems } from "@/config/nav";

const open = defineModel<boolean>({ required: true });
const router = useRouter();
const query = ref("");
const idx = ref(0);
const inputRef = ref();
const instances = ref<InstanceVO[]>([]);

const commands = [
  { name: "thread -n 5", desc: "最忙的 5 个线程（CPU 飙高）" },
  { name: "memory", desc: "内存水位" },
  { name: "jvm", desc: "JVM 概览" },
  { name: "dashboard -n 1", desc: "实时面板" },
  { name: "watch 类 方法 '{params,returnObj}' -n 100", desc: "方法观测" },
  { name: "trace 类 方法 '#cost>100'", desc: "慢调用链路" },
  { name: "tt -t 类 方法", desc: "时间隧道（现场留痕）" },
  { name: "sc -d 类名", desc: "类加载信息（漏洞排查）" },
  { name: "profiler start", desc: "性能剖析（火焰图）" },
];
const templates = [
  { label: "慢接口排查", cmd: "trace com.bianque.order.OrderService placeOrder '#cost>100'" },
  { label: "CPU 飙高", cmd: "thread -n 5" },
  { label: "内存水位", cmd: "memory" },
  { label: "JVM 概览", cmd: "jvm" },
];

interface Item { key: string; title: string; sub: string; tagType: string; go: () => void }
const flat = ref<Item[]>([]);

const groups = computed(() => {
  const q = query.value.trim().toLowerCase();
  const out: { label: string; items: Item[] }[] = [];
  const push = (label: string, items: Item[]) => { if (items.length) out.push({ label, items }); };

  let key = 0;
  const mk = (title: string, sub: string, tagType: string, go: () => void): Item =>
    ({ key: String(key++), title, sub, tagType, go });

  push("页面", navItems.filter((n) => !q || n.title.toLowerCase().includes(q))
    .map((n) => mk(n.title, n.path, "primary", () => router.push(n.path))));

  if (q) {
    push("实例", instances.value.filter((i) =>
      i.instanceId.toLowerCase().includes(q) || i.appId.toLowerCase().includes(q))
      .slice(0, 5)
      .map((i) => mk(`${i.appId}/${i.instanceId}`, i.online ? "在线" : "离线", "success",
        () => router.push({ path: "/clinic", query: { instanceId: i.instanceId } }))));
    push("命令", commands.filter((c) =>
      c.name.toLowerCase().includes(q) || c.desc.toLowerCase().includes(q))
      .map((c) => mk(c.name, c.desc, "warning", () => router.push({ path: "/clinic", query: { cmd: c.name } }))));
    push("模板", templates.filter((t) => t.label.toLowerCase().includes(q))
      .map((t) => mk(t.label, t.cmd, "info", () => router.push({ path: "/clinic", query: { cmd: t.cmd } }))));
    push("文档", docsHits.value.map((d) => mk(d.title, d.slug, "danger", () => router.push({ path: "/docs", query: { page: d.path } }))));
  }
  return out;
});

interface DocHit { slug: string; title: string; path: string }
const docsHits = ref<DocHit[]>([]);
let debounce = 0;
function onQuery() {
  idx.value = 0;
  window.clearTimeout(debounce);
  const q = query.value.trim();
  if (!q) { docsHits.value = []; flat.value = groups.value.flatMap((g) => g.items); return; }
  debounce = window.setTimeout(async () => {
    try {
      const data = await http.get("/docs/search", { params: { q, limit: 6 } }) as unknown as { hits: DocHit[] };
      docsHits.value = data.hits ?? [];
    } catch {
      docsHits.value = [];
    }
    flat.value = groups.value.flatMap((g) => g.items);
  }, 200);
  flat.value = groups.value.flatMap((g) => g.items);
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === "ArrowDown") { idx.value = Math.min(idx.value + 1, flat.value.length - 1); e.preventDefault(); }
  else if (e.key === "ArrowUp") { idx.value = Math.max(idx.value - 1, 0); e.preventDefault(); }
  else if (e.key === "Enter" && flat.value[idx.value]) { pick(flat.value[idx.value]); }
}

function pick(it: Item) {
  open.value = false;
  query.value = "";
  docsHits.value = [];
  it.go();
}

function focusInput() {
  getInstances().then((list) => (instances.value = list)).catch(() => (instances.value = []));
  onQuery();
  setTimeout(() => inputRef.value?.focus?.(), 60);
}
</script>

<style scoped>
.results { max-height: 46vh; overflow-y: auto; margin-top: 10px; }
.g-label { font-size: 11px; color: var(--el-text-color-secondary); padding: 8px 4px 4px; }
.item { display: flex; align-items: center; gap: 10px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.item.on { background: var(--el-color-primary-light-9); }
.item .t { flex: none; max-width: 60%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item .sub { color: var(--el-text-color-secondary); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { text-align: center; color: var(--el-text-color-secondary); padding: 26px 0; font-size: 13px; }
.foot { border-top: 1px solid var(--el-border-color-lighter); margin-top: 8px; padding-top: 8px;
        font-size: 11px; color: var(--el-text-color-secondary); text-align: center; }
</style>
