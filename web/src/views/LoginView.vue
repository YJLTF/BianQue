<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <span class="name">扁鹊 BianQue</span>
        <span class="sub">线上诊断平台 · Arthas 4.3.5</span>
      </div>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" size="large" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码" size="large" show-password @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" native-type="submit">
          登 录
        </el-button>
      </el-form>
      <el-divider>演示账号（点击填充）</el-divider>
      <div class="demo-accounts">
        <el-button v-for="a in demos" :key="a.u" size="small" plain @click="fill(a.u, a.p)">
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
  background: linear-gradient(135deg, #1b2735 0%, #2c3e50 60%, #3b5872 100%);
}
.login-card {
  width: 400px;
  background: #fff;
  border-radius: 10px;
  padding: 32px 36px 24px;
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.35);
}
.brand { display: flex; flex-direction: column; margin-bottom: 22px; }
.name { font-size: 22px; font-weight: 700; color: #1b2735; }
.sub { font-size: 12px; color: #8fa0b3; margin-top: 4px; }
.demo-accounts { display: flex; gap: 8px; justify-content: center; }
.tip { font-size: 11px; color: #a3adb8; text-align: center; margin-top: 14px; }
</style>
