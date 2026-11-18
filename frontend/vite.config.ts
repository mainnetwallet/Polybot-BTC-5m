import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      // The Android app's connection test asks /healthz (outside /api). Without this it never reaches the
      // backend when the app is pointed at the dashboard port of a VPS (http://SERVER_IP:5173).
      "/healthz": {
        target: "http://127.0.0.1:8000",
        changeOrigin: false,
      },
      "/api": {
        target: "http://127.0.0.1:8000",
        // Keep the browser's own Host header. The API accepts a browser Origin only when it matches the Host it
        // sees, so the dashboard also works when opened by the server's IP (Linux VPS), not just on localhost.
        changeOrigin: false,
        ws: true,
      },
    },
  },
});
