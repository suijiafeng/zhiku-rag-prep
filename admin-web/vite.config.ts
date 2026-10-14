import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev proxy keeps API calls same-origin, so the server needs no CORS for the admin console.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
});
