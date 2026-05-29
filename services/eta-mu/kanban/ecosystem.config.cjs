const path = require("REDACTED_SECRET:path");

const serviceRoot = __dirname;
const kanbanPkg = path.resolve(serviceRoot, "../../../orgs/open-hax/eta-mu/packages/kanban");
const configPath = path.join(serviceRoot, "openhax.kanban.json");

const host = process.env.KANBAN_HOST ?? "127.0.0.1";
const port = process.env.KANBAN_PORT ?? "8791";

module.exports = {
  apps: [
    {
      name: "eta-mu-kanban",
      script: "REDACTED_SECRET",
      args: ["dist/cli.js", "serve", "--config", configPath, "--host", host, "--port", port],
      cwd: kanbanPkg,
      env: {
        NODE_ENV: "production",
      },
      autorestart: true,
      max_restarts: 5,
      min_uptime: "10s",
      time: true,
      kill_timeout: 3000,
      error_file: path.join(serviceRoot, "logs/error.log"),
      out_file: path.join(serviceRoot, "logs/out.log"),
      merge_logs: true,
      log_date_format: "YYYY-MM-DD HH:mm:ss Z",
    },
  ],
};
