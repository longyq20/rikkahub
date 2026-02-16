import * as React from "react";

import { Link } from "react-router";
import { ChevronLeft, Download, Home, Save, Upload } from "lucide-react";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { useConfirm } from "~/components/confirm-dialog-provider";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";
import api, { appendWebAuthQuery } from "~/services/api";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Advanced" }];
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "";
  }
}

export default function SettingsPage() {
  const settings = useSettingsStore((state) => state.settings);
  const [draft, setDraft] = React.useState(() => safeStringify(settings ?? {}));
  const [busy, setBusy] = React.useState(false);
  const [importBusy, setImportBusy] = React.useState(false);
  const [importFile, setImportFile] = React.useState<File | null>(null);
  const confirm = useConfirm();

  React.useEffect(() => {
    // Keep editor in sync until the user starts editing.
    // If draft is empty, always refresh.
    if (draft.trim().length === 0) {
      setDraft(safeStringify(settings ?? {}));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings]);

  const handleReset = React.useCallback(() => {
    setDraft(safeStringify(settings ?? {}));
  }, [settings]);

  const handleApply = React.useCallback(async () => {
    let next: unknown;
    try {
      next = JSON.parse(draft);
    } catch (error) {
      toast.error("Invalid JSON");
      return;
    }

    if (!next || typeof next !== "object" || Array.isArray(next)) {
      toast.error("Settings payload must be a JSON object");
      return;
    }

    setBusy(true);
    try {
      await api.post<{ status: string }>("settings/replace", next);
      toast.success("Settings updated");
    } catch (error) {
      console.error("settings/replace failed", error);
      toast.error(error instanceof Error ? error.message : "Settings update failed");
    } finally {
      setBusy(false);
    }
  }, [draft]);

  const handleImport = React.useCallback(async () => {
    if (!importFile) {
      toast.error("Please select a .zip backup file");
      return;
    }

    const formData = new FormData();
    formData.append("file", importFile);

    setImportBusy(true);
    try {
      await api.postMultipart("migration/import", formData);
      toast.success("Import finished");
      setImportFile(null);
    } catch (error) {
      console.error("migration/import failed", error);
      toast.error(error instanceof Error ? error.message : "Import failed");
    } finally {
      setImportBusy(false);
    }
  }, [importFile]);

  return (
    <div className="flex h-svh flex-col bg-background">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Button asChild variant="outline" size="icon-sm" title="Back to settings" aria-label="Back to settings">
          <Link to="/settings">
            <ChevronLeft className="size-4" />
          </Link>
        </Button>
        <Button asChild variant="outline" size="icon-sm" title="Back to chats" aria-label="Back to chats">
          <Link to="/">
            <Home className="size-4" />
          </Link>
        </Button>

        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">Settings</div>
          <div className="truncate text-xs text-muted-foreground">
            Portable backend raw settings editor (advanced)
          </div>
        </div>

        <Button variant="outline" size="sm" onClick={handleReset} disabled={busy}>
          Reset
        </Button>
        <Button variant="default" size="sm" onClick={handleApply} disabled={busy}>
          <Save className={cn("size-4", busy && "opacity-60")} />
          Apply
        </Button>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-5xl space-y-6 px-4 py-6">
            <section className="space-y-2">
              <div className="text-sm font-semibold">settings.json</div>
              <Textarea
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                className="min-h-[60vh] font-mono text-xs"
                spellCheck={false}
              />
              <div className="text-xs text-muted-foreground">
                Editing this can break your instance. Keep a backup.
              </div>
            </section>

            <section className="grid gap-3 md:grid-cols-2">
              <div className="rounded-lg border p-4">
                <div className="mb-2 text-sm font-semibold">Backup Export</div>
                <div className="text-xs text-muted-foreground">
                  Download a portable backup zip (settings + db + uploads).
                </div>
                <div className="mt-3">
                  <Button asChild variant="secondary" size="sm">
                    <a href={appendWebAuthQuery("/api/migration/export")}>
                      <Download className="size-4" />
                      Download Export
                    </a>
                  </Button>
                </div>
              </div>

              <div className="rounded-lg border p-4">
                <div className="mb-2 text-sm font-semibold">Backup Import</div>
                <div className="text-xs text-muted-foreground">
                  Import an Android/Web backup zip. This may overwrite current data.
                </div>
                <div className="mt-3 flex flex-col gap-2">
                  <input
                    type="file"
                    accept=".zip,application/zip"
                    onChange={(e) => {
                      const file = e.target.files?.[0] ?? null;
                      setImportFile(file);
                    }}
                  />
                  <div className="flex items-center gap-2">
                    <Button
                      variant="destructive"
                      size="sm"
                      disabled={importBusy || !importFile}
                      onClick={() => {
                        void (async () => {
                        const confirmed = await confirm({
                          title: "Import backup?",
                          description: "Import will overwrite current data. Continue?",
                          confirmText: "Import",
                          cancelText: "Cancel",
                          destructive: true,
                        });
                        if (!confirmed) return;
                        void handleImport();
                      })();
                      }}
                    >
                      <Upload className={cn("size-4", importBusy && "opacity-60")} />
                      Import
                    </Button>
                    <div className="text-xs text-muted-foreground">
                      {importFile ? importFile.name : "No file selected"}
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}