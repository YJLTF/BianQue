<template>
  <el-container class="layout">
    <el-aside width="216px" class="aside">
      <div class="brand">
        <svg class="logo" viewBox="0 0 32 32" aria-hidden="true">
          <polyline points="2,18 9,18 12,8 16,26 20,12 23,18 30,18" fill="none"
                    stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <div class="brand-text">
          <span class="brand-name">扁鹊 BianQue</span>
          <span class="brand-sub">线上诊断平台</span>
        </div>
      </div>
      <el-menu router :default-active="route.path" class="menu">
        <el-menu-item v-for="item in nav" :key="item.path" :index="item.path">
          <el-icon class="mi"><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
          <span v-if="item.clan" class="clan">{{ item.clan }}</span>
        </el-menu-item>
      </el-menu>
      <div class="footer">
        <el-avatar :size="30" class="avatar">{{ auth.displayName.slice(0, 1) }}</el-avatar>
        <el-tooltip content="点击修改显示名" placement="top">
          <div class="who" style="cursor: pointer" @click="renameSelf">
            <span class="name">{{ auth.displayName }}</span>
            <span class="role">{{ auth.roleName }}</span>
          </div>
        </el-tooltip>
        <el-tooltip content="退出登录" placement="top">
          <el-button link class="logout" @click="logout">
            <el-icon :size="16"><SwitchButton /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </el-aside>
    <el-container>
      <el-header class="header" height="56px">
        <div class="crumbs">
          <span class="page-title">{{ route.meta.title }}</span>
          <span v-if="route.meta.clan" class="clan-chip">{{ route.meta.clan }}</span>
        </div>
        <div class="search" @click="searchOpen = true">
          <el-icon class="s-ico"><Search /></el-icon>
          <span class="s-text">搜索页面 / 实例 / 命令 / 文档</span>
          <span class="s-kbd">Ctrl K</span>
        </div>
        <el-tag type="success" effect="plain" round>生产环境 · 内网</el-tag>
        <el-tag type="info" effect="plain" round>v{{ APP_VERSION }} · Arthas 4.3.5</el-tag>
      </el-header>
      <GlobalSearch v-model="searchOpen" />
      <el-main class="main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { navItems } from "@/config/nav";
import { useAuthStore } from "@/stores/auth";

const APP_VERSION = __APP_VERSION__;
import { http, errMsg } from "@/api/http";
import GlobalSearch from "@/components/GlobalSearch.vue";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const searchOpen = ref(false);
const nav = navItems;

function onGlobalKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    searchOpen.value = true;
  }
}
onMounted(() => window.addEventListener("keydown", onGlobalKey));
onUnmounted(() => window.removeEventListener("keydown", onGlobalKey));

async function renameSelf() {
  try {
    const { value } = await ElMessageBox.prompt("设置新的显示名（1–32 个字符）", "修改显示名", {
      inputValue: auth.displayName,
      inputPattern: /^\S.{0,31}$/,
      inputErrorMessage: "显示名需 1–32 个字符",
      confirmButtonText: "保存",
      cancelButtonText: "取消",
    });
    const out = await http.put("/auth/profile", { displayName: value }) as unknown as { displayName: string };
    auth.setDisplayName(out.displayName);
    ElMessage.success("显示名已更新");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(errMsg(e));
  }
}

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
.aside {
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #101627 0%, #1a2238 100%);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.14);
}
.logo {
  width: 34px;
  height: 34px;
  padding: 5px;
  box-sizing: border-box;
  border-radius: 9px;
  color: #fff;
  background: linear-gradient(135deg, #4f46e5, #8b5cf6);
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.45);
  flex: none;
}
.brand-text { display: flex; flex-direction: column; min-width: 0; }
.brand-name { color: #f1f5f9; font-weight: 600; font-size: 15px; letter-spacing: 0.2px; }
.brand-sub { color: #7d8aa5; font-size: 11px; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.menu { border-right: none; background: transparent; flex: 1; padding: 8px; overflow-y: auto; }
.menu :deep(.el-menu-item) {
  color: #aab6cc;
  height: 42px;
  border-radius: 8px;
  margin-bottom: 2px;
  transition: background 0.15s, color 0.15s;
}
.menu :deep(.el-menu-item:hover) { background: rgba(148, 163, 184, 0.12); color: #e2e8f0; }
.menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, #4f46e5, #6d28d9);
  color: #fff;
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.4);
}
.mi { margin-right: 4px; }
.clan {
  margin-left: auto;
  font-size: 11px;
  color: #64748b;
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 18px;
}
.menu :deep(.el-menu-item.is-active) .clan { color: #ddd6fe; border-color: rgba(221, 214, 254, 0.5); }
.footer {
  margin: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(148, 163, 184, 0.1);
  display: flex;
  align-items: center;
  gap: 9px;
}
.avatar { background: linear-gradient(135deg, #0d9488, #4f46e5); color: #fff; font-weight: 600; flex: none; }
.who { display: flex; flex-direction: column; min-width: 0; flex: 1; }
.name { color: #e2e8f0; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.role { color: #7d8aa5; font-size: 11px; }
.logout { color: #7d8aa5; flex: none; }
.logout:hover { color: #f87171; }
.header {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border-bottom: 1px solid #eef1f6;
  padding: 0 20px;
}
.crumbs { display: flex; align-items: center; gap: 8px; margin-right: 4px; flex: none; }
.page-title { font-size: 16px; font-weight: 600; color: #0f172a; }
.clan-chip {
  font-size: 11px;
  color: #4f46e5;
  background: #eeedfd;
  border-radius: 999px;
  padding: 1px 8px;
}
.search {
  margin-right: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  max-width: 380px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid #e4e7ed;
  border-radius: 16px;
  color: #a8abb2;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
  user-select: none;
}
.search:hover { border-color: var(--el-color-primary-light-5); }
.s-ico { font-size: 14px; }
.s-text { flex: 1; white-space: nowrap; overflow: hidden; }
.s-kbd {
  font-size: 11px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 0 5px;
  line-height: 16px;
  background: #f5f7fa;
}
.main { background: #f4f6fb; padding: 16px 20px; overflow-y: auto; }
</style>
