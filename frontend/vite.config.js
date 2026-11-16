import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig(({ command, isPreview }) => {
  const configured = process.env.STUDIO_BASE_PATH?.trim();
  const base = configured
    ? `/${configured.replace(/^\/+|\/+$/g, "")}/`.replace(/^\/\/$/, "/")
    : command === "build" || isPreview
      ? "/art-studio/"
      : "/";
  if (!/^\/(?:[a-zA-Z0-9_-]+\/)*$/.test(base)) {
    throw new Error("STUDIO_BASE_PATH 必须是站内目录，例如 /art-studio/ 或 /");
  }
  const apiPath = `${base}api`;
  return {
    base,
    plugins: [vue()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        [apiPath]: {
          target: process.env.STUDIO_API_PROXY || "http://127.0.0.1:18080",
          changeOrigin: true,
          rewrite: (path) => path.slice(base.length - 1),
        },
      },
    },
    build: {
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks: { vue: ["vue", "vue-router"], element: ["element-plus"] },
        },
      },
    },
  };
});
