import * as React from "react";

import { Link } from "react-router";
import { Brain, Database, FileText, Globe, Home, Puzzle, Sliders, Wrench } from "lucide-react";

import { Button } from "~/components/ui/button";
import { ScrollArea } from "~/components/ui/scroll-area";

export function meta() {
  return [{ title: "Settings" }];
}

const SECTIONS: Array<{
  to: string;
  title: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
}> = [
  {
    to: "/settings/search",
    title: "Search",
    description: "Web search toggle and service selection.",
    icon: Globe,
  },
  {
    to: "/settings/providers",
    title: "Providers & Models",
    description: "Configure provider baseUrl/apiKey and model tool toggles.",
    icon: Sliders,
  },
  {
    to: "/settings/mcp",
    title: "MCP",
    description: "Manage MCP servers and tool enable/approval settings.",
    icon: Puzzle,
  },
  {
    to: "/settings/prompts",
    title: "Prompts",
    description: "Manage mode injections and lorebooks templates.",
    icon: FileText,
  },
  {
    to: "/settings/memory",
    title: "Memory",
    description: "Manage long-term memory records per assistant.",
    icon: Brain,
  },
  {
    to: "/settings/backup",
    title: "Backup",
    description: "Import/export a portable zip backup.",
    icon: Database,
  },
  {
    to: "/settings/advanced",
    title: "Advanced",
    description: "Raw settings.json editor (dangerous).",
    icon: Wrench,
  },
];

export default function SettingsHubPage() {
  return (
    <div className="flex h-svh flex-col bg-background">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Button asChild variant="outline" size="icon-sm" title="Back to chats" aria-label="Back">
          <Link to="/">
            <Home className="size-4" />
          </Link>
        </Button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">Settings</div>
          <div className="truncate text-xs text-muted-foreground">
            Configure portable backend and WebUI features
          </div>
        </div>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-4xl px-4 py-6">
            <div className="grid gap-3 sm:grid-cols-2">
              {SECTIONS.map((section) => {
                const Icon = section.icon;
                return (
                  <Button
                    key={section.to}
                    asChild
                    variant="outline"
                    className="h-auto items-start justify-start gap-3 p-4 text-left"
                  >
                    <Link to={section.to}>
                      <div className="mt-0.5 rounded-md border bg-muted/40 p-2">
                        <Icon className="size-4" />
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-semibold">{section.title}</div>
                        <div className="mt-1 text-xs text-muted-foreground">
                          {section.description}
                        </div>
                      </div>
                    </Link>
                  </Button>
                );
              })}
            </div>

            <div className="mt-6 rounded-lg border p-4">
              <div className="flex items-center gap-2 text-sm font-semibold">
                <Wrench className="size-4" />
                Tips
              </div>
              <ul className="mt-2 list-disc pl-5 text-xs text-muted-foreground">
                <li>Settings changes are applied via backend SSE; reload if UI looks stale.</li>
                <li>Avoid pasting secrets into screenshots or logs.</li>
                <li>Use Backup export before editing Advanced settings.</li>
              </ul>
            </div>
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}