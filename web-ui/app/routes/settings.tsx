import * as React from "react";

import { Link } from "react-router";
import {
  Brain,
  Database,
  FileText,
  Globe,
  Home,
  Puzzle,
  Sliders,
  UserCog,
  Wrench,
} from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { ScrollArea } from "~/components/ui/scroll-area";
import i18n from "~/i18n";

export function meta() {
  return [{ title: i18n.t("settings:hub.meta.title") }];
}

const SECTIONS: Array<{
  to: string;
  titleKey: string;
  descriptionKey: string;
  icon: React.ComponentType<{ className?: string }>;
}> = [
  {
    to: "/settings/search",
    titleKey: "hub.sections.search.title",
    descriptionKey: "hub.sections.search.description",
    icon: Globe,
  },
  {
    to: "/settings/providers",
    titleKey: "hub.sections.providers.title",
    descriptionKey: "hub.sections.providers.description",
    icon: Sliders,
  },
  {
    to: "/settings/assistants",
    titleKey: "hub.sections.assistants.title",
    descriptionKey: "hub.sections.assistants.description",
    icon: UserCog,
  },
  {
    to: "/settings/mcp",
    titleKey: "hub.sections.mcp.title",
    descriptionKey: "hub.sections.mcp.description",
    icon: Puzzle,
  },
  {
    to: "/settings/prompts",
    titleKey: "hub.sections.prompts.title",
    descriptionKey: "hub.sections.prompts.description",
    icon: FileText,
  },
  {
    to: "/settings/memory",
    titleKey: "hub.sections.memory.title",
    descriptionKey: "hub.sections.memory.description",
    icon: Brain,
  },
  {
    to: "/settings/backup",
    titleKey: "hub.sections.backup.title",
    descriptionKey: "hub.sections.backup.description",
    icon: Database,
  },
  {
    to: "/settings/advanced",
    titleKey: "hub.sections.advanced.title",
    descriptionKey: "hub.sections.advanced.description",
    icon: Wrench,
  },
];

export default function SettingsHubPage() {
  const { t } = useTranslation("settings");

  return (
    <div className="flex h-svh flex-col bg-background">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Button
          asChild
          variant="outline"
          size="icon-sm"
          title={t("hub.header.back")}
          aria-label={t("hub.header.back")}
        >
          <Link to="/">
            <Home className="size-4" />
          </Link>
        </Button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">{t("hub.header.title")}</div>
          <div className="truncate text-xs text-muted-foreground">{t("hub.header.description")}</div>
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
                        <div className="text-sm font-semibold">{t(section.titleKey)}</div>
                        <div className="mt-1 text-xs text-muted-foreground">
                          {t(section.descriptionKey)}
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
                {t("hub.tips.title")}
              </div>
              <ul className="mt-2 list-disc pl-5 text-xs text-muted-foreground">
                <li>{t("hub.tips.item_1")}</li>
                <li>{t("hub.tips.item_2")}</li>
                <li>{t("hub.tips.item_3")}</li>
              </ul>
            </div>
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}