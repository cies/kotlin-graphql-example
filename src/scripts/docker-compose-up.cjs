#!/usr/bin/env node
/**
 * Loads `.env.docker` (and optional `.env`) into the environment before `docker compose`,
 * so `${AUTH_SECRET}` / `${AUTH_URL}` in docker-compose.yml interpolate correctly.
 *
 * Compose only auto-loads a project file named `.env` for interpolation - not `.env.docker`.
 * The service `env_file` injects into the container but does NOT substitute `${VAR}` in YAML.
 *
 * Existing process.env wins (Dokploy / shell exports), so platform-injected secrets are kept.
 */
const { existsSync, readFileSync } = require("node:fs");
const { spawnSync } = require("node:child_process");

function loadDotenvFile(filePath) {
  if (!existsSync(filePath)) return;
  const text = readFileSync(filePath, "utf8");
  for (const line of text.split(/\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    let val = trimmed.slice(eq + 1).trim();
    if (
      (val.startsWith('"') && val.endsWith('"')) ||
      (val.startsWith("'") && val.endsWith("'"))
    ) {
      val = val.slice(1, -1);
    }
    if (key && (process.env[key] === undefined || process.env[key] === "")) {
      process.env[key] = val;
    }
  }
}

loadDotenvFile(".env.docker");
loadDotenvFile(".env");

const extraArgs = process.argv.slice(2);
const result = spawnSync(
  "docker",
  ["compose", "up", "-d", "--build", ...extraArgs],
  {
    stdio: "inherit",
    env: process.env,
    shell: process.platform === "win32",
  }
);
process.exit(result.status ?? 1);
