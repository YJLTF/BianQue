import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import * as ElementPlusIconsVue from "@element-plus/icons-vue";
import "element-plus/dist/index.css";
import "./styles/main.css";
import App from "./App.vue";
import router from "./router";

const app = createApp(App).use(createPinia()).use(router).use(ElementPlus);
// 全量注册图标（内网环境无字体 CDN，本地 SVG 图标组件按名引用）
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component);
}
app.mount("#app");
