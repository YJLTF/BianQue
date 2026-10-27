<template>
  <el-row :gutter="12">
    <el-col :span="14">
      <el-card shadow="never">
        <template #header><b>漏洞应急扫描</b>（批量 sc -d，串行分批）</template>
        <el-form label-width="90px">
          <el-form-item label="目标类">
            <el-input v-model="targetClass" placeholder="org.apache.logging.log4j.core.lookup.JndiLookup" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="scanning" @click="scan">开始扫描（在线实例）</el-button>
          </el-form-item>
        </el-form>
        <template v-if="rep">
          <el-row :gutter="12">
            <el-col :span="8"><el-statistic title="扫描实例" :value="rep.scanned" /></el-col>
            <el-col :span="8"><el-statistic title="命中实例" :value="rep.hits" /></el-col>
            <el-col :span="8">
              <el-statistic title="漏洞路径可达" :value="rep.vulnerable"
                            :value-style="{ color: rep.vulnerable > 0 ? 'var(--el-color-danger)' : '' }" />
            </el-col>
          </el-row>
          <el-table :data="rep.hitsDetail.filter((h) => h.classFound)" size="small" style="margin-top: 10px">
            <el-table-column prop="instanceId" label="实例" min-width="120" />
            <el-table-column prop="sourceJar" label="来源 jar" min-width="180" />
            <el-table-column prop="verdict" label="判定" width="140">
              <template #default="{ row }">
                <el-tag size="small" :type="row.verdict === '漏洞路径可达' ? 'danger' : (row.verdict === '已修复版本' ? 'success' : 'warning')" effect="plain">
                  {{ row.verdict }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="示例：Log4Shell 场景，10 分钟回答「多少实例真的在用受影响的类」" :image-size="60" />
      </el-card>
    </el-col>
    <el-col :span="10">
      <el-alert type="info" :closable="false"
                title="与 SBOM 互补：构建清单 ≠ 运行时现实；本扫描基于 JVM 内存真相（sc -d / classloader / jad 抽查比对）" />
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { http } from "@/api/http";

interface AtlasHit { instanceId: string; targetClass: string; classFound: boolean; sourceJar: string; classloader: string; verdict: string }
interface AtlasReport { targetClass: string; scanned: number; hits: number; vulnerable: number; costMs: number; hitsDetail: AtlasHit[] }

const targetClass = ref("org.apache.logging.log4j.core.lookup.JndiLookup");
const scanning = ref(false);
const rep = ref<AtlasReport | null>(null);

async function scan() {
  scanning.value = true;
  rep.value = await http.post("/atlas/scan", { targetClass: targetClass.value }) as unknown as AtlasReport;
  scanning.value = false;
}
</script>
