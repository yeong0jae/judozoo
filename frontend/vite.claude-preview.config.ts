// 임시 — 화면 확인용. 프런트만 로컬, API는 운영. 확인 끝나면 지운다
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { port: 5173, proxy: { "/api": { target: "https://judozoo.com", changeOrigin: true } } },
});
