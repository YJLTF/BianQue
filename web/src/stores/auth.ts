import { defineStore } from "pinia";

export interface AuthUser {
  token: string;
  username: string;
  displayName: string;
  role: string;
  roleName: string;
  permissions: string[];
  expiresAt: string;
}

const KEY = "bianque.auth";

function restore(): AuthUser | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  } catch {
    return null;
  }
}

export const useAuthStore = defineStore("auth", {
  state: () => ({ user: restore() as AuthUser | null }),
  getters: {
    isLoggedIn: (s) => !!s.user?.token,
    displayName: (s) => s.user?.displayName ?? "",
    role: (s) => s.user?.role ?? "",
    roleName: (s) => s.user?.roleName ?? "",
    can: (s) => (code: string) => !!s.user?.permissions?.includes(code),
  },
  actions: {
    setLogin(u: AuthUser) {
      this.user = u;
      localStorage.setItem(KEY, JSON.stringify(u));
    },
    setDisplayName(name: string) {
      if (!this.user) return;
      this.user = { ...this.user, displayName: name };
      localStorage.setItem(KEY, JSON.stringify(this.user));
    },
    clear() {
      this.user = null;
      localStorage.removeItem(KEY);
    },
  },
});
