import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// En dev, l'API du cœur tourne sur 127.0.0.1:47811 (sans jeton de lancement).
// En desktop / LAN, l'UI buildée est servie par le cœur lui-même : pas de CORS.
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    proxy: {
      "/api": "http://127.0.0.1:47811",
    },
  },
});
