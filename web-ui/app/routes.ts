import { type RouteConfig, index, route } from "@react-router/dev/routes";

export default [
  index("routes/home.tsx"),
  route("c/:id", "routes/c.$id.tsx"),
  route("settings", "routes/settings.tsx"),
  route("settings/search", "routes/settings.search.tsx"),
  route("settings/providers", "routes/settings.providers.tsx"),
  route("settings/assistants", "routes/settings.assistants.tsx"),
  route("settings/mcp", "routes/settings.mcp.tsx"),
  route("settings/prompts", "routes/settings.prompts.tsx"),
  route("settings/memory", "routes/settings.memory.tsx"),
  route("settings/backup", "routes/settings.backup.tsx"),
  route("settings/advanced", "routes/settings.advanced.tsx"),
] satisfies RouteConfig;
