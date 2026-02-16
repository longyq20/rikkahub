import * as React from "react";

import { Link } from "react-router";
import { Home, Search } from "lucide-react";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Switch } from "~/components/ui/switch";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Search" }];
}

export default function SettingsSearchPage() {
  const settings = useSettingsStore((state) => state.settings);
  const [busy, setBusy] = React.useState(false);

  const enabled = settings?.enableWebSearch ?? false;
  const services = settings?.searchServices ?? [];
  const selectedIndex = settings?.searchServiceSelected ?? 0;

  const handleToggle = React.useCallback(async () => {
    if (!settings) return;

    setBusy(true);
    try {
      await api.post<{ status: string }>("settings/search/enabled", { enabled: !enabled });
      toast.success(!enabled ? "Web search enabled" : "Web search disabled");
    } catch (error) {
      console.error("settings/search/enabled failed", error);
      toast.error(error instanceof Error ? error.message : "Update failed");
    } finally {
      setBusy(false);
    }
  }, [enabled, settings]);

  const handleServiceChange = React.useCallback(
    async (value: string) => {
      if (!settings) return;
      const index = Number.parseInt(value, 10);
      if (Number.isNaN(index)) return;

      setBusy(true);
      try {
        await api.post<{ status: string }>("settings/search/service", { index });
        toast.success("Search service updated");
      } catch (error) {
        console.error("settings/search/service failed", error);
        toast.error(error instanceof Error ? error.message : "Update failed");
      } finally {
        setBusy(false);
      }
    },
    [settings],
  );

  return (
    <div className="flex h-svh flex-col bg-background">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Button asChild variant="outline" size="icon-sm" title="Back" aria-label="Back">
          <Link to="/settings">
            <Home className="size-4" />
          </Link>
        </Button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">Search</div>
          <div className="truncate text-xs text-muted-foreground">Web search and service selection</div>
        </div>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-3xl space-y-4 px-4 py-6">
            <div className="rounded-lg border p-4">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="text-sm font-semibold">Enable Web Search</div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    Controls whether search tools are offered to the model.
                  </div>
                </div>
                <Switch checked={enabled} disabled={busy || !settings} onCheckedChange={() => void handleToggle()} />
              </div>
            </div>

            <div className="rounded-lg border p-4">
              <div className="flex items-center gap-2 text-sm font-semibold">
                <Search className="size-4" />
                Search Service
              </div>
              <div className="mt-2 text-xs text-muted-foreground">
                Select which search service config is active.
              </div>

              <div className="mt-3">
                <Select value={String(selectedIndex)} onValueChange={(v) => void handleServiceChange(v)} disabled={busy || !settings}>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="Select a service" />
                  </SelectTrigger>
                  <SelectContent>
                    {services.length === 0 ? (
                      <SelectItem value="0" disabled>
                        No services
                      </SelectItem>
                    ) : (
                      services.map((svc, index) => (
                        <SelectItem key={`${svc.id}-${index}`} value={String(index)}>
                          {svc.id}
                        </SelectItem>
                      ))
                    )}
                  </SelectContent>
                </Select>
              </div>

              <div className="mt-2 text-xs text-muted-foreground">
                Current index: {selectedIndex}
              </div>
            </div>
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}