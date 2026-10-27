import { createRouter, createWebHistory } from "vue-router";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      component: () => import("@/layouts/BasicLayout.vue"),
      redirect: "/home",
      children: [
        { path: "home", name: "home", component: () => import("@/views/HomeView.vue"), meta: { title: "工作台", clan: "望" } },
        { path: "clinic", name: "clinic", component: () => import("@/views/ClinicView.vue"), meta: { title: "诊断台", clan: "切" } },
        { path: "alert", name: "alert", component: () => import("@/views/AlertView.vue"), meta: { title: "急诊取证", clan: "望" } },
        { path: "ai", name: "ai", component: () => import("@/views/AiView.vue"), meta: { title: "AI 问诊", clan: "问" } },
        { path: "atlas", name: "atlas", component: () => import("@/views/AtlasView.vue"), meta: { title: "资产档案", clan: "望" } },
        { path: "check", name: "check", component: () => import("@/views/CheckView.vue"), meta: { title: "巡检体检", clan: "治未病" } },
        { path: "docs", name: "docs", component: () => import("@/views/DocsView.vue"), meta: { title: "文档中心", clan: "知识" } },
        { path: "audit", name: "audit", component: () => import("@/views/AuditView.vue"), meta: { title: "审计中心" } },
        { path: "admin", name: "admin", component: () => import("@/views/AdminView.vue"), meta: { title: "管理设置" } },
      ],
    },
  ],
});

export default router;
