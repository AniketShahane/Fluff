import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Tests run inside workerd (Miniflare) with real D1 and KV bindings from wrangler.jsonc.
export default defineConfig(async () => {
  const migrations = await readD1Migrations(path.join(__dirname, "migrations"));
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            OWNER_KEY: "test-owner-key-0123456789abcdef0123456789",
            SYNC_TOKEN: "test-sync-token-0123456789abcdef",
            TEST_MIGRATIONS: migrations,
          },
        },
      }),
    ],
    test: {
      include: ["test/**/*.test.ts"],
      setupFiles: ["./test/setup.ts"],
      fileParallelism: false,
      testTimeout: 30_000,
    },
  };
});
