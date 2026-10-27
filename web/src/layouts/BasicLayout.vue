<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">
        <span class="brand-name">扁鹊 BianQue</span>
        <span class="brand-sub">线上诊断平台 · Arthas 4.3.5</span>
      </div>
      <el-menu router :default-active="route.path" class="menu">
        <el-menu-item v-for="item in nav" :key="item.path" :index="item.path">
          <span>{{ item.title }}</span>
          <span v-if="item.clan" class="clan">{{ item.clan }}</span>
        </el-menu-item>
      </el-menu>
      <div class="footer">
        <el-avatar :size="26">{{ auth.displayName.slice(0, 1) }}</el-avatar>
        <span class="footer-text">{{ auth.displayName }} · {{ auth.roleName }}</span>
        <el-button link size="small" class="logout" @click="logout">退出</el-button>
      </div>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-input
          v-model="keyword"
          placeholder="搜索实例 / 模板 / 命令 / 文档…（Ctrl+K）"
          class="search"
          prefix-icon="Search"
        />
        <el-tag type="success" effect="plain">生产环境 · 内网</el-tag>
        <el-tag type="info" effect="plain">Agent 4.3.5 · 在线 87/93</el-tag>
      </el-header>
      <el-main class="main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { navItems } from "@/config/nav";
import { useAuthStore } from "@/stores/auth";
import { http } from "@/api/http";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const keyword = ref("");
const nav = navItems;

async function logout() {
  try {
    await http.post("/auth/logout");
  } catch {
    // token 已失效也照常清理本地态
  }
  auth.clear();
  router.push("/login");
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { display: flex; flex-direction: column; background: #1b2735; }
.brand { padding: 16px 14px; color: #fff; display: flex; flex-direction: column; }
.brand-name { font-weight: 600; font-size: 15px; }
.brand-sub { font-size: 11px; color: #8fa0b3; margin-top: 2px; }
.menu { border-right: none; background: transparent; flex: 1; }
.menu :deep(.el-menu-item) { color: #c8d2de; }
.menu :deep(.el-menu-item.is-active) { background: rgba(91, 145, 246, 0.28); color: #fff; }
.clan { margin-left: auto; font-size: 11px; color: #7e8ea1; }
.footer { padding: 12px; border-top: 1px solid rgba(255, 255, 255, 0.09); display: flex; align-items: center; gap: 8px; }
.footer-text { color: #c8d2de; font-size: 12px; }
.logout { margin-left: auto; color: #8fa0b3; }
.logout:hover { color: #fff; }
.header { display: flex; align-items: center; gap: 10px; background: #fff; border-bottom: 1px solid #e4e7ed; }
.search { max-width: 420px; margin-right: auto; }
.main { background: #f5f7fa; }
</style>
