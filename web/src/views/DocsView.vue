<template>
  <div>
    <div class="row">
      <el-tag type="primary" effect="plain">Arthas 4.3.5 官方文档</el-tag>
      <el-input v-model="query" placeholder="搜索标题 / 命令 / 正文…" style="max-width: 380px; margin-left: auto"
                clearable @input="onSearch" />
    </div>

    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="5">
        <el-card shadow="never" header="分类">
          <div v-for="c in categories" :key="c.name" class="cat" :class="{ on: c.name === currentCat }"
               @click="selectCat(c.name)">
            <span>{{ c.name }}</span><span class="n">{{ c.count }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="7">
        <el-card shadow="never" :header="query ? `搜索结果（${hits.length}）` : currentCat">
          <div v-for="p in list" :key="p.slug" class="page" :class="{ on: p.path === current?.path }" @click="select(p)">
            <el-tag size="small" :type="riskMeta[p.risk]?.type ?? 'info'" effect="plain">
              {{ p.risk === "high" ? "高危" : p.risk === "mid" ? "中风险" : "只读" }}
            </el-tag>
            <span class="t">{{ p.title }}</span>
            <code class="s">{{ p.slug }}</code>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <div class="row">
              <b>{{ current?.title ?? "选择文档" }}</b>
              <code v-if="current" class="s" style="margin-left: 8px">{{ current.path }}</code>
              <el-button v-if="current" size="small" style="margin-left: auto" @click="openStandalone">新窗口打开</el-button>
            </div>
          </template>
          <iframe v-if="current" :src="iframeSrc" class="doc-frame" :key="current.path" title="文档正文" />
          <el-empty v-else description="在左侧选择文档查看正文" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { http, errMsg } from "@/api/http";
import { riskMeta } from "@/config/risk";

interface DocHit { slug: string; title: string; category: string; risk: string; path: string; snippet: string }

const all = ref<DocHit[]>([]);
const query = ref("");
const hits = ref<DocHit[]>([]);
const currentCat = ref("入门");
const current = ref<DocHit | null>(null);
const iframeSrc = ref("");

const categories = computed(() => {
  const m = new Map<string, number>();
  (query.value ? hits.value : all.value).forEach((p) => m.set(p.category, (m.get(p.category) ?? 0) + 1));
  return [...m.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => a.name.localeCompare(b.name));
});

const list = computed(() => {
  const src = query.value ? hits.value : all.value;
  return src.filter((p) => p.category === currentCat.value);
});

function selectCat(name: string) {
  currentCat.value = name;
}

function select(p: DocHit) {
  current.value = p;
  iframeSrc.value = `/docs/4.3.5/${p.path}`;
}

function openStandalone() {
  if (iframeSrc.value) window.open(iframeSrc.value, "_blank");
}

let timer: number | undefined;
function onSearch() {
  window.clearTimeout(timer);
  timer = window.setTimeout(async () => {
    const q = query.value.trim();
    if (!q) { currentCat.value = categories.value[0]?.name ?? "入门"; return; }
    try {
      const data = await http.get("/docs/search", { params: { q, limit: 50 } }) as unknown as { hits: DocHit[] };
      hits.value = data.hits ?? [];
      if (hits.value.length) currentCat.value = hits.value[0].category;
    } catch {
      hits.value = [];
    }
  }, 250);
}

/** 深链支持：/docs?page=doc/xxx.html（命令速查 / 全局搜索 / AI 参考文档跳入） */
const route = useRoute();
function applyDeepLink() {
  const p = String(route.query.page ?? "");
  if (!p || !all.value.length) return;
  const hit = all.value.find((x) => x.path === p);
  if (hit) {
    currentCat.value = hit.category;
    select(hit);
  }
}
watch(() => route.query.page, applyDeepLink);

onMounted(async () => {
  try {
    const data = await http.get("/docs/catalog") as unknown as { pages: DocHit[] };
    all.value = data.pages ?? [];
    currentCat.value = categories.value[0]?.name ?? "入门";
    applyDeepLink();
  } catch (e) {
    ElMessage.error("文档目录加载失败：" + errMsg(e));
  }
});
</script>

<style scoped>
.row { display: flex; align-items: center; }
.cat { display: flex; justify-content: space-between; padding: 6px 10px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.cat:hover { background: #f5f7fa; }
.cat.on { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.cat .n { color: #909399; font-size: 11px; }
.page { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.page:hover { background: #f5f7fa; }
.page.on { background: var(--el-color-primary-light-9); }
.page .t { flex: 1; }
.page .s { font-size: 11px; color: #909399; }
.doc-frame { width: 100%; height: 560px; border: 1px solid #e4e7ed; border-radius: 6px; }
</style>
