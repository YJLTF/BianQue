export interface NavItem {
  path: string;
  title: string;
  clan?: string;
  icon: string;
}

/** 左侧导航（四诊法信息架构，与产品设计方案 4.2 / 界面原型一致） */
export const navItems: NavItem[] = [
  { path: "/home", title: "工作台", clan: "望", icon: "Odometer" },
  { path: "/clinic", title: "诊断台", clan: "切", icon: "FirstAidKit" },
  { path: "/alert", title: "急诊取证", clan: "望", icon: "Bell" },
  { path: "/ai", title: "AI 问诊", clan: "问", icon: "ChatDotRound" },
  { path: "/atlas", title: "资产档案", clan: "望", icon: "Notebook" },
  { path: "/agents", title: "节点管理", clan: "切", icon: "Monitor" },
  { path: "/check", title: "巡检体检", clan: "治未病", icon: "CircleCheck" },
  { path: "/docs", title: "文档中心", clan: "知识", icon: "Reading" },
  { path: "/audit", title: "审计中心", icon: "Lock" },
  { path: "/admin", title: "管理设置", icon: "Setting" },
];
