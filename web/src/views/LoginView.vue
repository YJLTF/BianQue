<template>
  <div class="login-page">
    <div class="aurora a1" aria-hidden="true" />
    <div class="aurora a2" aria-hidden="true" />
    <div class="login-card">
      <div class="brand">
        <svg class="logo" viewBox="0 0 32 32" aria-hidden="true">
          <polyline points="2,18 9,18 12,8 16,26 20,12 23,18 30,18" fill="none"
                    stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <div class="brand-text">
          <span class="name">扁鹊 BianQue</span>
          <span class="sub">线上诊断平台 · Arthas 4.3.5</span>
        </div>
      </div>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" size="large" @keyup.enter="submit">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码" size="large" show-password @keyup.enter="submit">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" native-type="submit">
          登 录
        </el-button>
      </el-form>
      <el-divider>演示账号（点击填充）</el-divider>
      <div class="demo-accounts">
        <el-button v-for="a in demos" :key="a.u" size="small" plain round @click="fill(a.u, a.p)">
          {{ a.label }}
        </el-button>
      </div>
      <p class="tip">登录即接受审计：所有诊断操作将记录操作人并进入防篡改哈希链。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import axios from "axios";
import { useAuthStore, type AuthUser } from "@/stores/auth";

const router = useRouter();
const auth = useAuthStore();
const username = ref("admin");
const password = ref("");
const loading = ref(false);

const demos = [
  { label: "admin · 管理员", u: "admin", p: "admin123" },
  { label: "ops · 运维", u: "ops", p: "ops123" },
  { label: "viewer · 只读", u: "viewer", p: "viewer123" },
];

function fill(u: string, p: string) {
  username.value = u;
  password.value = p;
}

async function submit() {
  if (!username.value || !password.value) {
    ElMessage.warning("请输入用户名与密码");
    return;
  }
  loading.value = true;
  try {
    const resp = await axios.post("/api/auth/login", {
      username: username.value,
      password: password.value,
    });
    const user = resp.data as AuthUser;
    auth.setLogin(user);
    ElMessage.success(`欢迎，${user.displayName}（${user.roleName}）`);
    router.push("/home");
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.response?.data?.error || "登录失败";
    ElMessage.error(msg);
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  background: #0f172a;
}
.aurora {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.55;
  pointer-events: none;
}
.a1 {
  width: 720px;
  height: 480px;
  right: -160px;
  top: -180px;
  background: radial-gradient(closest-side, #4f46e5, transparent);
}
.a2 {
  width: 640px;
  height: 440px;
  left: -180px;
  bottom: -200px;
  background: radial-gradient(closest-side, #0d9488, transparent);
}
.login-card {
  width: 400px;
  background: #fff;
  border-radius: 16px;
  padding: 32px 36px 24px;
  box-shadow: 0 24px 70px rgba(2, 6, 23, 0.55);
  position: relative;
}
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.logo {
  width: 42px;
  height: 42px;
  padding: 7px;
  box-sizing: border-box;
  border-radius: 11px;
  color: #fff;
  background: linear-gradient(135deg, #4f46e5, #8b5cf6);
  box-shadow: 0 4px 14px rgba(79, 70, 229, 0.4);
  flex: none;
}
.brand-text { display: flex; flex-direction: column; }
.name { font-size: 21px; font-weight: 700; color: #0f172a; letter-spacing: 0.2px; }
.sub { font-size: 12px; color: #64748b; margin-top: 3px; }
.submit { width: 100%; margin-top: 2px; }
.demo-accounts { display: flex; gap: 8px; justify-content: center; }
.tip { font-size: 11px; color: #94a3b8; text-align: center; margin-top: 14px; }
</style>
